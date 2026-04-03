package com.krogie.betterghast.moderation

import com.krogie.betterghast.db.AutoResponses
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

data class CompiledTrigger(
    val id: Long,
    val pattern: String,
    val regex: Regex?,
    val tagPrimary: String,
    val channelId: Long?,
    val cooldownMs: Long,
    val enabled: Boolean
)

object AutoResponseService {

    private val logger = LoggerFactory.getLogger(AutoResponseService::class.java)

    private val cache = ConcurrentHashMap<Long, List<CompiledTrigger>>()
    private val cooldowns = ConcurrentHashMap<String, Long>()

    fun init() {
        transaction {
            val rows = AutoResponses.selectAll()
            val grouped = mutableMapOf<Long, MutableList<CompiledTrigger>>()

            for (row in rows) {
                val guildId = row[AutoResponses.guildId]
                val trigger = rowToTrigger(row)
                grouped.getOrPut(guildId) { mutableListOf() }.add(trigger)
            }

            for ((guildId, triggers) in grouped) {
                cache[guildId] = triggers
            }
        }
        logger.info("AutoResponse cache loaded with ${cache.size} guild(s)")
    }

    fun addTrigger(
        guildId: Long,
        pattern: String,
        isRegex: Boolean,
        tagPrimary: String,
        channelId: Long?,
        cooldownMs: Long = 30000L
    ): Long {
        val id = transaction {
            com.krogie.betterghast.util.GuildUtil.ensureGuild(guildId)
            AutoResponses.insert {
                it[AutoResponses.guildId] = guildId
                it[AutoResponses.pattern] = pattern
                it[AutoResponses.isRegex] = isRegex
                it[AutoResponses.tagPrimary] = tagPrimary
                it[AutoResponses.channelId] = channelId
                it[AutoResponses.cooldownMs] = cooldownMs
                it[AutoResponses.enabled] = true
            } get AutoResponses.id
        }
        invalidateCache(guildId)
        logger.info("Guild $guildId: Added auto-response trigger #$id for tag '$tagPrimary'")
        return id
    }

    fun removeTrigger(guildId: Long, id: Long): Boolean {
        val deleted = transaction {
            AutoResponses.deleteWhere {
                (AutoResponses.id eq id) and (AutoResponses.guildId eq guildId)
            } > 0
        }
        if (deleted) {
            invalidateCache(guildId)
            logger.info("Guild $guildId: Removed auto-response trigger #$id")
        }
        return deleted
    }

    fun toggleTrigger(guildId: Long, id: Long): Boolean {
        val newState = transaction {
            val row = AutoResponses.selectAll().where {
                (AutoResponses.id eq id) and (AutoResponses.guildId eq guildId)
            }.firstOrNull() ?: return@transaction null

            val current = row[AutoResponses.enabled]
            AutoResponses.update({ (AutoResponses.id eq id) and (AutoResponses.guildId eq guildId) }) {
                it[enabled] = !current
            }
            !current
        } ?: return false

        invalidateCache(guildId)
        logger.info("Guild $guildId: Toggled auto-response trigger #$id to $newState")
        return newState
    }

    fun listTriggers(guildId: Long): List<CompiledTrigger> {
        return cache[guildId] ?: emptyList()
    }

    fun checkMessage(guildId: Long, channelId: Long, content: String): CompiledTrigger? {
        val triggers = cache[guildId] ?: return null
        val now = System.currentTimeMillis()
        val lowerContent = content.lowercase()

        for (trigger in triggers) {
            if (!trigger.enabled) continue
            if (trigger.channelId != null && trigger.channelId != channelId) continue

            val matches = if (trigger.regex != null) {
                trigger.regex.containsMatchIn(content)
            } else {
                lowerContent.contains(trigger.pattern.lowercase())
            }

            if (!matches) continue

            val cooldownKey = "${guildId}_${trigger.id}"
            val lastUsed = cooldowns[cooldownKey] ?: 0L
            if (now - lastUsed < trigger.cooldownMs) continue

            cooldowns[cooldownKey] = now
            return trigger
        }

        return null
    }

    fun invalidateCache(guildId: Long) {
        val triggers = transaction {
            AutoResponses.selectAll().where { AutoResponses.guildId eq guildId }
                .map { rowToTrigger(it) }
        }
        cache[guildId] = triggers
    }

    private fun rowToTrigger(row: ResultRow): CompiledTrigger {
        val isRegex = row[AutoResponses.isRegex]
        val pattern = row[AutoResponses.pattern]
        val compiled = if (isRegex) {
            runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
        } else null

        return CompiledTrigger(
            id = row[AutoResponses.id],
            pattern = pattern,
            regex = compiled,
            tagPrimary = row[AutoResponses.tagPrimary],
            channelId = row[AutoResponses.channelId],
            cooldownMs = row[AutoResponses.cooldownMs],
            enabled = row[AutoResponses.enabled]
        )
    }
}
