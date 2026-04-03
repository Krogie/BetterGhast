package com.krogie.betterghast.community

import com.krogie.betterghast.config.AppConfig
import com.krogie.betterghast.db.Guilds
import com.krogie.betterghast.db.LevelRoles
import com.krogie.betterghast.db.UserLevels
import com.krogie.betterghast.util.GuildSettingsService
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.sqrt

data class UserLevel(
    val guildId: Long,
    val userId: Long,
    val xp: Long,
    val level: Int,
    val totalMessages: Long
)

object LevelingService {

    private val logger = LoggerFactory.getLogger(LevelingService::class.java)
    private const val MODULE = "leveling"

    private var xpMin = 15
    private var xpMax = 25
    private var cooldownMs = 60_000L

    // Cooldown tracking: guildId_userId -> timestamp
    private val cooldowns = ConcurrentHashMap<String, Long>()

    fun init(config: AppConfig) {
        xpMin = config.levelingXpMin
        xpMax = config.levelingXpMax
        cooldownMs = config.levelingCooldown
    }

    fun isEnabled(guildId: Long): Boolean =
        GuildSettingsService.getBoolean(guildId, MODULE, "enabled", true)

    fun setEnabled(guildId: Long, enabled: Boolean) {
        ensureGuild(guildId)
        GuildSettingsService.set(guildId, MODULE, "enabled", enabled.toString())
    }

    fun getMultiplier(guildId: Long): Double {
        return GuildSettingsService.get(guildId, MODULE, "multiplier")?.toDoubleOrNull() ?: 1.0
    }

    fun setMultiplier(guildId: Long, multiplier: Double) {
        ensureGuild(guildId)
        GuildSettingsService.set(guildId, MODULE, "multiplier", multiplier.toString())
    }

    fun getAnnounceChannelId(guildId: Long): Long? =
        GuildSettingsService.getLong(guildId, MODULE, "announceChannelId").takeIf { it != 0L }

    fun setAnnounceChannelId(guildId: Long, channelId: Long) {
        ensureGuild(guildId)
        GuildSettingsService.set(guildId, MODULE, "announceChannelId", channelId.toString())
    }

    // ── Level formula ──

    fun xpForLevel(level: Int): Long = (level * level * 100L)

    fun levelFromXp(xp: Long): Int = floor(0.1 * sqrt(xp.toDouble())).toInt()

    fun xpToNextLevel(currentXp: Long): Long {
        val currentLevel = levelFromXp(currentXp)
        val nextLevelXp = xpForLevel(currentLevel + 1)
        return (nextLevelXp - currentXp).coerceAtLeast(0)
    }

    // ── XP gain ──

    /**
     * Attempt to award XP to a user. Returns (newLevel, isLevelUp) or null if on cooldown.
     */
    fun tryAwardXp(guildId: Long, userId: Long): Pair<Int, Boolean>? {
        val key = "${guildId}_${userId}"
        val now = System.currentTimeMillis()
        val last = cooldowns[key] ?: 0L

        if (now - last < cooldownMs) return null
        cooldowns[key] = now

        val multiplier = getMultiplier(guildId)
        val baseXp = (xpMin..xpMax).random().toLong()
        val xpGain = (baseXp * multiplier).toLong().coerceAtLeast(1)

        return transaction {
            com.krogie.betterghast.util.GuildUtil.ensureGuild(guildId)
            val existing = UserLevels.selectAll().where {
                (UserLevels.guildId eq guildId) and (UserLevels.userId eq userId)
            }.firstOrNull()

            val oldLevel: Int
            val newXp: Long

            if (existing == null) {
                oldLevel = 0
                newXp = xpGain
                UserLevels.insert {
                    it[UserLevels.guildId] = guildId
                    it[UserLevels.userId] = userId
                    it[UserLevels.xp] = xpGain
                    it[UserLevels.level] = levelFromXp(xpGain)
                    it[UserLevels.totalMessages] = 1
                    it[UserLevels.lastXpGain] = now
                }
            } else {
                oldLevel = existing[UserLevels.level]
                newXp = existing[UserLevels.xp] + xpGain
                val newLevel = levelFromXp(newXp)
                UserLevels.update({
                    (UserLevels.guildId eq guildId) and (UserLevels.userId eq userId)
                }) {
                    it[UserLevels.xp] = newXp
                    it[UserLevels.level] = newLevel
                    it[UserLevels.totalMessages] = existing[UserLevels.totalMessages] + 1
                    it[UserLevels.lastXpGain] = now
                }
            }

            val newLevel = levelFromXp(newXp)
            Pair(newLevel, newLevel > oldLevel)
        }
    }

    // ── Level roles ──

    fun setLevelRole(guildId: Long, level: Int, roleId: Long) {
        ensureGuild(guildId)
        transaction {
            LevelRoles.upsert {
                it[LevelRoles.guildId] = guildId
                it[LevelRoles.level] = level
                it[LevelRoles.roleId] = roleId
            }
        }
        logger.info("Guild $guildId: Level role set: level $level -> role $roleId")
    }

    fun removeLevelRole(guildId: Long, level: Int): Boolean {
        return transaction {
            LevelRoles.deleteWhere {
                (LevelRoles.guildId eq guildId) and (LevelRoles.level eq level)
            } > 0
        }
    }

    fun getLevelRoles(guildId: Long): Map<Int, Long> {
        return transaction {
            LevelRoles.selectAll().where { LevelRoles.guildId eq guildId }
                .associate { it[LevelRoles.level] to it[LevelRoles.roleId] }
        }
    }

    fun getRoleForLevel(guildId: Long, level: Int): Long? {
        return transaction {
            LevelRoles.selectAll().where {
                (LevelRoles.guildId eq guildId) and (LevelRoles.level eq level)
            }.firstOrNull()?.get(LevelRoles.roleId)
        }
    }

    // ── Queries ──

    fun getUserLevel(guildId: Long, userId: Long): UserLevel? {
        return transaction {
            UserLevels.selectAll().where {
                (UserLevels.guildId eq guildId) and (UserLevels.userId eq userId)
            }.firstOrNull()?.let { rowToUserLevel(it) }
        }
    }

    fun getLeaderboard(guildId: Long, page: Int = 0, pageSize: Int = 10): List<UserLevel> {
        return transaction {
            UserLevels.selectAll().where { UserLevels.guildId eq guildId }
                .orderBy(UserLevels.xp, SortOrder.DESC)
                .limit(pageSize).offset((page * pageSize).toLong())
                .map { rowToUserLevel(it) }
        }
    }

    fun getRank(guildId: Long, userId: Long): Int {
        return transaction {
            val userXp = UserLevels.selectAll().where {
                (UserLevels.guildId eq guildId) and (UserLevels.userId eq userId)
            }.firstOrNull()?.get(UserLevels.xp) ?: return@transaction -1

            UserLevels.selectAll().where {
                (UserLevels.guildId eq guildId) and (UserLevels.xp greater userXp)
            }.count().toInt() + 1
        }
    }

    private fun ensureGuild(guildId: Long) {
        transaction {
            if (Guilds.selectAll().where { Guilds.id eq guildId }.empty()) {
                Guilds.insertIgnore { it[Guilds.id] = guildId }
            }
        }
    }

    private fun rowToUserLevel(row: ResultRow) = UserLevel(
        guildId = row[UserLevels.guildId],
        userId = row[UserLevels.userId],
        xp = row[UserLevels.xp],
        level = row[UserLevels.level],
        totalMessages = row[UserLevels.totalMessages]
    )
}
