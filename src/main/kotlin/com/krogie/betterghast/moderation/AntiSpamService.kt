package com.krogie.betterghast.moderation

import com.krogie.betterghast.config.AppConfig
import com.krogie.betterghast.util.GuildSettingsService
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object AntiSpamService {

    private val logger = LoggerFactory.getLogger(AntiSpamService::class.java)

    private var rateLimit = 5
    private var rateWindow = 5000L

    private val messageTimestamps = ConcurrentHashMap<Long, ArrayDeque<Long>>()
    private val messageHashes = ConcurrentHashMap<Long, ArrayDeque<String>>()

    private val linkRegex = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val inviteRegex = Regex("(discord\\.gg|discordapp\\.com/invite)/\\S+", RegexOption.IGNORE_CASE)

    private const val MODULE = "antispam"
    private const val MAX_HASH_HISTORY = 10

    fun init(config: AppConfig) {
        rateLimit = config.antiSpamRateLimit
        rateWindow = config.antiSpamRateWindow
    }

    fun isEnabled(guildId: Long): Boolean {
        return GuildSettingsService.getBoolean(guildId, MODULE, "enabled", default = false)
    }

    fun checkRateLimit(userId: Long): Boolean {
        val now = System.currentTimeMillis()
        val timestamps = messageTimestamps.computeIfAbsent(userId) { ArrayDeque() }

        synchronized(timestamps) {
            // Remove timestamps outside the window
            while (timestamps.isNotEmpty() && now - timestamps.first() > rateWindow) {
                timestamps.removeFirst()
            }
            timestamps.addLast(now)
            return timestamps.size > rateLimit
        }
    }

    fun checkDuplicate(userId: Long, content: String): Boolean {
        val hash = content.hashCode().toString()
        val hashes = messageHashes.computeIfAbsent(userId) { ArrayDeque() }

        synchronized(hashes) {
            val isDuplicate = hashes.count { it == hash } >= 3
            hashes.addLast(hash)
            while (hashes.size > MAX_HASH_HISTORY) {
                hashes.removeFirst()
            }
            return isDuplicate
        }
    }

    fun checkLink(guildId: Long, content: String): Boolean {
        val linkFilterEnabled = GuildSettingsService.getBoolean(guildId, MODULE, "linkFilter", default = false)
        if (!linkFilterEnabled) return false

        val matches = linkRegex.findAll(content)
        for (match in matches) {
            if (!isWhitelisted(guildId, match.value)) {
                return true
            }
        }
        return false
    }

    fun checkInvite(guildId: Long, content: String): Boolean {
        val inviteFilterEnabled = GuildSettingsService.getBoolean(guildId, MODULE, "inviteFilter", default = false)
        if (!inviteFilterEnabled) return false

        val matches = inviteRegex.findAll(content)
        for (match in matches) {
            if (!isWhitelisted(guildId, match.value)) {
                return true
            }
        }
        return false
    }

    fun isWhitelisted(guildId: Long, link: String): Boolean {
        val whitelist = GuildSettingsService.get(guildId, MODULE, "whitelist") ?: return false
        val entries = whitelist.split(",").map { it.trim().lowercase() }
        val lowerLink = link.lowercase()
        return entries.any { entry -> entry.isNotEmpty() && lowerLink.contains(entry) }
    }
}
