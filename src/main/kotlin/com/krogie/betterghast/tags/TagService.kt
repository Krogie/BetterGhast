package com.krogie.betterghast.tags

import com.krogie.betterghast.config.AppConfig
import com.krogie.betterghast.db.Guilds
import com.krogie.betterghast.db.Tags
import dev.minn.jda.ktx.interactions.components.MediaGallery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.container.ContainerChildComponent
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.interactions.commands.Command
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap

enum class TagStyle { Accent, NoAccent, Raw }

data class Tag(
    val guildId: Long,
    val primary: String,
    val keywords: String,
    val content: String,
    val style: TagStyle,
    val usages: Long
) {
    val keywordList: List<String> by lazy { keywords.split(",").map { it.trim() } }

    /**
     * Builds the container from the tag content, respecting custom formatting rules.
     */
    val cachedContainer: Container? by lazy {
        if (style == TagStyle.Raw) return@lazy null

        // Detect if content ends with a URL
        val lastSep = content.lastIndexOf("\n\n")
        var text = content
        var mediaUrl: String? = null

        if (lastSep != -1) {
            val suffix = content.substring(lastSep + 2).trim()
            // Validate URL
            if (suffix.startsWith("http") && !suffix.any { it.isWhitespace() }) {
                text = content.take(lastSep)
                mediaUrl = suffix
            }
        }

        val components = mutableListOf<ContainerChildComponent>()

        // Moving headers into separate text display
        if (text.startsWith("###") && text.contains("\n")) {
            val splitIndex = text.indexOf("\n")
            val header = text.take(splitIndex).trim()
            val body = text.substring(splitIndex + 1).trim()

            components.add(TextDisplay.of(header))

            if (body.isNotEmpty()) {
                components.add(TextDisplay.of(body))
            }
        } else {
            components.add(TextDisplay.of(text))
        }

        // Moving URLs into media galleries
        if (mediaUrl != null) {
            components.add(MediaGallery {
                item(mediaUrl)
            })
        }

        var container = Container.of(components)
        if (style == TagStyle.Accent) {
            container = container.withAccentColor(TagService.accentColor)
        }

        container
    }
}

object TagService {
    private val logger = LoggerFactory.getLogger(TagService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO)

    // Cache: GuildID -> { Keyword -> Tag }
    private val cache = ConcurrentHashMap<Long, ConcurrentHashMap<String, Tag>>()
    private val cooldowns = ConcurrentHashMap<String, Long>()

    private var cooldownMs = 2500L
    private var allowedGuilds = emptySet<Long>()
    var accentColor: Color = Color(0xB5C8B4)
        private set

    const val MAX_CONTENT_LEN = 2000
    const val MAX_KEYWORD_LEN = 100
    const val MAX_KEYWORDS_TOTAL_LEN = 200

    fun init(config: AppConfig) {
        this.cooldownMs = config.tagCooldown
        this.accentColor = Color(config.accentColor)
        this.allowedGuilds = config.allowedGuilds
    }

    fun isAllowedGuild(guildId: Long): Boolean = allowedGuilds.isEmpty() || guildId in allowedGuilds

    /**
     * Checks if a specific channel and tag combination is on cooldown, returning remaining time in ms.
     */
    fun checkCooldown(channelId: Long, primary: String): Long {
        val key = "${channelId}_$primary"
        val now = System.currentTimeMillis()
        val last = cooldowns[key] ?: 0L
        val diff = now - last

        if (diff < cooldownMs) {
            return cooldownMs - diff
        }

        cooldowns[key] = now
        return 0L
    }

    /**
     * Retrieves a tag object by keyword for a specific guild, if it exists.
     */
    fun find(guildId: Long, keyword: String): Tag? {
        return getGuildCache(guildId)[keyword.trim().lowercase()]
    }

    /**
     * Generates autocomplete choices for a search query, matching against both primary keys and aliases.
     */
    fun autocomplete(guildId: Long, query: String): List<Command.Choice> {
        val distinctTags = getGuildCache(guildId).values.distinctBy { it.primary }
        val needle = query.trim().lowercase()
        return distinctTags.asSequence()
            .filter { it.primary.lowercase().contains(needle) || it.keywords.lowercase().contains(needle) }
            .take(25)
            .map { Command.Choice(it.keywords.take(100), it.primary) }
            .toList()
    }

    /**
     * Retrieves all unique tag objects associated with a specific guild.
     */
    fun listTags(guildId: Long): List<Tag> {
        return getGuildCache(guildId).values.distinctBy { it.primary }
    }

    /**
     * Searches tags by keyword or content for a specific guild.
     */
    fun searchTags(guildId: Long, query: String): List<Tag> {
        val needle = query.trim().lowercase()
        if (needle.isBlank()) return emptyList()
        return getGuildCache(guildId).values
            .distinctBy { it.primary }
            .filter {
                it.primary.lowercase().contains(needle) ||
                it.keywords.lowercase().contains(needle) ||
                it.content.lowercase().contains(needle)
            }
            .sortedByDescending { it.usages }
    }

    /**
     * Checks if any of the provided new keywords conflict with existing tags in the guild.
     */
    fun findConflicts(guildId: Long, newKeywords: String, ignorePrimary: String? = null): Map<String, String> {
        val map = getGuildCache(guildId)
        val inputList = newKeywords.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val conflicts = mutableMapOf<String, String>()

        for (kw in inputList) {
            val owner = map[kw]
            if (owner != null && (ignorePrimary == null || owner.primary != ignorePrimary)) {
                conflicts[kw] = owner.primary
            }
        }
        return conflicts
    }

    /**
     * Creates or updates a tag in the database and invalidates the cache.
     */
    fun createOrUpdate(guildId: Long, rawKeywords: String, content: String, style: TagStyle, oldPrimary: String? = null): String {
        if (!isAllowedGuild(guildId)) throw IllegalArgumentException("This guild is not authorized to use BetterGhast.")
        if (content.length > MAX_CONTENT_LEN) throw IllegalArgumentException("Content too long (${content.length} > $MAX_CONTENT_LEN)")

        val cleanKw = rawKeywords.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_KEYWORD_LEN }
            .distinct()
            .joinToString(", ")

        if (cleanKw.isBlank()) throw IllegalArgumentException("No valid keywords provided.")
        if (cleanKw.length > MAX_KEYWORDS_TOTAL_LEN) throw IllegalArgumentException("Total keywords length exceeds limit ($MAX_KEYWORDS_TOTAL_LEN).")

        val primary = cleanKw.split(",").first().trim()
        val map = getGuildCache(guildId)

        // Retrieve usages from existing cache to preserve count during update
        val existingTag = if (oldPrimary != null) map[oldPrimary.lowercase()] else map[primary.lowercase()]
        val currentUsages = existingTag?.usages ?: 0L

        transaction {
            if (Guilds.selectAll().where { Guilds.id eq guildId }.empty()) {
                Guilds.insertIgnore { it[id] = guildId }
            }

            if (oldPrimary != null && oldPrimary != primary) {
                Tags.deleteWhere { (Tags.guildId eq guildId) and (Tags.primaryKeyword eq oldPrimary) }
            }

            Tags.upsert {
                it[Tags.guildId] = guildId
                it[Tags.primaryKeyword] = primary
                it[Tags.keywords] = cleanKw
                it[Tags.content] = content
                it[Tags.style] = style.name
            }
        }

        existingTag?.keywordList?.forEach { k ->
            map.remove(k.lowercase())
        }

        val newTag = Tag(
            guildId = guildId,
            primary = primary,
            keywords = cleanKw,
            content = content,
            style = style,
            usages = currentUsages
        )

        newTag.keywordList.forEach { k ->
            map[k.lowercase()] = newTag
        }

        logger.info("Guild $guildId: Tag upserted '$primary' (old: '$oldPrimary')")
        return primary
    }

    /**
     * Deletes a tag from the database and invalidates the cache.
     */
    fun delete(guildId: Long, primary: String): Boolean {
        val deleted = transaction {
            Tags.deleteWhere { (Tags.guildId eq guildId) and (Tags.primaryKeyword eq primary) } > 0
        }
        if (deleted) {
            val map = getGuildCache(guildId)
            val tag = map[primary.lowercase()]

            tag?.keywordList?.forEach { k ->
                map.remove(k.lowercase())
            }

            logger.info("Guild $guildId: Tag deleted '$primary'")
        }
        return deleted
    }

    /**
     * Asynchronously increments the usage counter for a specific tag.
     */
    fun incrementUsage(guildId: Long, primary: String) {
        scope.launch {
            try {
                transaction {
                    Tags.update({ (Tags.guildId eq guildId) and (Tags.primaryKeyword eq primary) }) {
                        with(SqlExpressionBuilder) { it.update(usages, usages + 1) }
                    }
                }

                val map = getGuildCache(guildId)
                val tag = map[primary.lowercase()]

                if (tag != null) {
                    val newTag = tag.copy(usages = tag.usages + 1)
                    newTag.keywordList.forEach { k ->
                        map[k.lowercase()] = newTag
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to increment usage: ${e.message}")
            }
        }
    }

    /**
     * Returns detailed info about a specific tag including all aliases and usage.
     */
    fun getTagInfo(guildId: Long, keyword: String): Tag? {
        return find(guildId, keyword)
    }

    /**
     * Exports all tags of a guild as a JSON-compatible list of maps.
     */
    fun exportTags(guildId: Long): List<Map<String, Any>> {
        return listTags(guildId).map { tag ->
            mapOf(
                "primary" to tag.primary,
                "keywords" to tag.keywords,
                "content" to tag.content,
                "style" to tag.style.name,
                "usages" to tag.usages
            )
        }
    }

    /**
     * Imports tags from a list of maps, skipping conflicts.
     * Returns the count of successfully imported tags.
     */
    fun importTags(guildId: Long, tags: List<Map<String, String>>): Int {
        if (!isAllowedGuild(guildId)) return 0
        var imported = 0
        for (tagData in tags) {
            val keywords = tagData["keywords"] ?: continue
            val content = tagData["content"] ?: continue
            val style = runCatching { TagStyle.valueOf(tagData["style"] ?: "Accent") }.getOrDefault(TagStyle.Accent)

            val conflicts = findConflicts(guildId, keywords)
            if (conflicts.isNotEmpty()) continue

            try {
                createOrUpdate(guildId, keywords, content, style)
                imported++
            } catch (e: Exception) {
                logger.warn("Skipped import of tag '$keywords': ${e.message}")
            }
        }
        return imported
    }

    /**
     * Fetches or constructs the keyword-to-tag map for a guild from the database.
     */
    private fun getGuildCache(guildId: Long): ConcurrentHashMap<String, Tag> {
        cache[guildId]?.let { return it }
        val newMap = ConcurrentHashMap<String, Tag>()
        transaction {
            val rows = Tags.selectAll().where { Tags.guildId eq guildId }
            for (row in rows) {
                val tag = rowToTag(row)
                for (k in tag.keywordList) {
                    newMap[k.lowercase()] = tag
                }
            }
        }
        return cache.computeIfAbsent(guildId) { newMap }
    }

    /**
     * Maps a database result row to a Tag domain object.
     */
    private fun rowToTag(row: ResultRow) = Tag(
        guildId = row[Tags.guildId],
        primary = row[Tags.primaryKeyword],
        keywords = row[Tags.keywords],
        content = row[Tags.content],
        style = runCatching { TagStyle.valueOf(row[Tags.style]) }.getOrDefault(TagStyle.Accent),
        usages = row[Tags.usages]
    )
}