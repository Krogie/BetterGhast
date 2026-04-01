package com.krogie.betterghast.tags

import com.krogie.betterghast.config.AppConfig
import com.krogie.betterghast.db.Guilds
import com.krogie.betterghast.db.TagUsageLogs
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
    val usages: Long,
    val creatorId: Long? = null,
    val requiredRoleId: Long? = null,
    val createdAt: Long = 0
) {
    val keywordList: List<String> by lazy { keywords.split(",").map { it.trim() } }

    val cachedContainer: Container? by lazy {
        if (style == TagStyle.Raw) return@lazy null

        val lastSep = content.lastIndexOf("\n\n")
        var text = content
        var mediaUrl: String? = null

        if (lastSep != -1) {
            val suffix = content.substring(lastSep + 2).trim()
            if (suffix.startsWith("http") && !suffix.any { it.isWhitespace() }) {
                text = content.take(lastSep)
                mediaUrl = suffix
            }
        }

        val components = mutableListOf<ContainerChildComponent>()

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

    fun find(guildId: Long, keyword: String): Tag? {
        return getGuildCache(guildId)[keyword.trim().lowercase()]
    }

    fun checkPermission(tag: Tag, memberRoleIds: List<Long>): Boolean {
        val requiredRole = tag.requiredRoleId ?: return true
        return requiredRole in memberRoleIds
    }

    fun autocomplete(guildId: Long, query: String): List<Command.Choice> {
        val distinctTags = getGuildCache(guildId).values.distinctBy { it.primary }
        val needle = query.trim().lowercase()
        return distinctTags.asSequence()
            .filter { it.primary.lowercase().contains(needle) || it.keywords.lowercase().contains(needle) }
            .take(25)
            .map { Command.Choice(it.keywords.take(100), it.primary) }
            .toList()
    }

    fun listTags(guildId: Long): List<Tag> {
        return getGuildCache(guildId).values.distinctBy { it.primary }
    }

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

    fun createOrUpdate(
        guildId: Long, rawKeywords: String, content: String, style: TagStyle,
        oldPrimary: String? = null, creatorId: Long? = null, requiredRoleId: Long? = null
    ): String {
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

        val existingTag = if (oldPrimary != null) map[oldPrimary.lowercase()] else map[primary.lowercase()]
        val currentUsages = existingTag?.usages ?: 0L
        val finalCreatorId = creatorId ?: existingTag?.creatorId
        val finalRequiredRoleId = requiredRoleId ?: existingTag?.requiredRoleId
        val finalCreatedAt = existingTag?.createdAt ?: System.currentTimeMillis()

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
                it[Tags.creatorId] = finalCreatorId
                it[Tags.requiredRoleId] = finalRequiredRoleId
                it[Tags.createdAt] = finalCreatedAt
            }
        }

        existingTag?.keywordList?.forEach { k -> map.remove(k.lowercase()) }

        val newTag = Tag(
            guildId = guildId, primary = primary, keywords = cleanKw,
            content = content, style = style, usages = currentUsages,
            creatorId = finalCreatorId, requiredRoleId = finalRequiredRoleId, createdAt = finalCreatedAt
        )

        newTag.keywordList.forEach { k -> map[k.lowercase()] = newTag }

        logger.info("Guild $guildId: Tag upserted '$primary' (old: '$oldPrimary')")
        return primary
    }

    fun setPermission(guildId: Long, primary: String, roleId: Long?): Boolean {
        val tag = find(guildId, primary) ?: return false
        transaction {
            Tags.update({ (Tags.guildId eq guildId) and (Tags.primaryKeyword eq primary) }) {
                it[requiredRoleId] = roleId
            }
        }
        val updated = tag.copy(requiredRoleId = roleId)
        val map = getGuildCache(guildId)
        updated.keywordList.forEach { k -> map[k.lowercase()] = updated }
        return true
    }

    fun delete(guildId: Long, primary: String): Boolean {
        val deleted = transaction {
            Tags.deleteWhere { (Tags.guildId eq guildId) and (Tags.primaryKeyword eq primary) } > 0
        }
        if (deleted) {
            val map = getGuildCache(guildId)
            val tag = map[primary.lowercase()]
            tag?.keywordList?.forEach { k -> map.remove(k.lowercase()) }
            logger.info("Guild $guildId: Tag deleted '$primary'")
        }
        return deleted
    }

    fun incrementUsage(guildId: Long, primary: String, userId: Long = 0, channelId: Long = 0) {
        scope.launch {
            try {
                transaction {
                    Tags.update({ (Tags.guildId eq guildId) and (Tags.primaryKeyword eq primary) }) {
                        with(SqlExpressionBuilder) { it.update(usages, usages + 1) }
                    }
                    if (userId != 0L) {
                        TagUsageLogs.insert {
                            it[TagUsageLogs.guildId] = guildId
                            it[tagPrimary] = primary
                            it[TagUsageLogs.userId] = userId
                            it[TagUsageLogs.channelId] = channelId
                            it[usedAt] = System.currentTimeMillis()
                        }
                    }
                }

                val map = getGuildCache(guildId)
                val tag = map[primary.lowercase()]
                if (tag != null) {
                    val newTag = tag.copy(usages = tag.usages + 1)
                    newTag.keywordList.forEach { k -> map[k.lowercase()] = newTag }
                }
            } catch (e: Exception) {
                logger.error("Failed to increment usage: ${e.message}")
            }
        }
    }

    // ── Analytics ──

    fun getTopUsers(guildId: Long, limit: Int = 10): List<Pair<Long, Long>> {
        return transaction {
            TagUsageLogs.select(TagUsageLogs.userId, TagUsageLogs.userId.count())
                .where { TagUsageLogs.guildId eq guildId }
                .groupBy(TagUsageLogs.userId)
                .orderBy(TagUsageLogs.userId.count() to SortOrder.DESC)
                .limit(limit)
                .map { it[TagUsageLogs.userId] to it[TagUsageLogs.userId.count()] }
        }
    }

    fun getUsageTrends(guildId: Long, days: Int = 7): Map<String, Long> {
        val since = System.currentTimeMillis() - (days * 86400000L)
        return transaction {
            TagUsageLogs.selectAll()
                .where { (TagUsageLogs.guildId eq guildId) and (TagUsageLogs.usedAt greaterEq since) }
                .groupBy {
                    val day = (it[TagUsageLogs.usedAt] - since) / 86400000L
                    "Day ${day + 1}"
                }
                .mapValues { it.value.size.toLong() }
        }
    }

    fun getUnusedTags(guildId: Long): List<Tag> {
        return listTags(guildId).filter { it.usages == 0L }
    }

    fun getTagInfo(guildId: Long, keyword: String): Tag? = find(guildId, keyword)

    fun exportTags(guildId: Long): List<Map<String, Any>> {
        return listTags(guildId).map { tag ->
            mapOf(
                "primary" to tag.primary, "keywords" to tag.keywords,
                "content" to tag.content, "style" to tag.style.name,
                "usages" to tag.usages
            )
        }
    }

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

    private fun rowToTag(row: ResultRow) = Tag(
        guildId = row[Tags.guildId],
        primary = row[Tags.primaryKeyword],
        keywords = row[Tags.keywords],
        content = row[Tags.content],
        style = runCatching { TagStyle.valueOf(row[Tags.style]) }.getOrDefault(TagStyle.Accent),
        usages = row[Tags.usages],
        creatorId = row[Tags.creatorId],
        requiredRoleId = row[Tags.requiredRoleId],
        createdAt = row[Tags.createdAt]
    )
}
