package com.krogie.betterghast.moderation

import com.krogie.betterghast.config.AppConfig
import com.krogie.betterghast.db.Warnings
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

data class Warning(
    val id: Long,
    val guildId: Long,
    val userId: Long,
    val moderatorId: Long,
    val reason: String,
    val issuedAt: Long,
    val expiresAt: Long?,
    val active: Boolean
)

object WarningService {

    private val logger = LoggerFactory.getLogger(WarningService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var decayDays = 30
    private var thresholds = mapOf<Int, String>()

    fun init(config: AppConfig) {
        decayDays = config.warnDecayDays
        thresholds = config.warnThresholds
    }

    fun warn(guildId: Long, userId: Long, moderatorId: Long, reason: String): Pair<Warning, Int> {
        val now = System.currentTimeMillis()
        val expiresAt = now + decayDays.toLong() * 86400000L

        val warning = transaction {
            com.krogie.betterghast.util.GuildUtil.ensureGuild(guildId)
            val id = Warnings.insert {
                it[Warnings.guildId] = guildId
                it[Warnings.userId] = userId
                it[Warnings.moderatorId] = moderatorId
                it[Warnings.reason] = reason
                it[Warnings.issuedAt] = now
                it[Warnings.expiresAt] = expiresAt
                it[Warnings.active] = true
            } get Warnings.id

            Warning(
                id = id,
                guildId = guildId,
                userId = userId,
                moderatorId = moderatorId,
                reason = reason,
                issuedAt = now,
                expiresAt = expiresAt,
                active = true
            )
        }

        val activeCount = getActiveCount(guildId, userId)
        logger.info("Guild $guildId: User $userId warned by $moderatorId (active: $activeCount)")
        return warning to activeCount
    }

    fun getWarnings(guildId: Long, userId: Long, activeOnly: Boolean = true): List<Warning> {
        return transaction {
            val query = Warnings.selectAll().where {
                (Warnings.guildId eq guildId) and (Warnings.userId eq userId)
            }

            if (activeOnly) {
                query.andWhere { Warnings.active eq true }
            }

            query.orderBy(Warnings.issuedAt, SortOrder.DESC)
                .map { rowToWarning(it) }
        }
    }

    fun clearWarning(guildId: Long, warningId: Long): Boolean {
        val cleared = transaction {
            Warnings.update({
                (Warnings.id eq warningId) and (Warnings.guildId eq guildId)
            }) {
                it[active] = false
            } > 0
        }
        if (cleared) {
            logger.info("Guild $guildId: Warning #$warningId cleared")
        }
        return cleared
    }

    fun getActiveCount(guildId: Long, userId: Long): Int {
        return transaction {
            Warnings.selectAll().where {
                (Warnings.guildId eq guildId) and
                (Warnings.userId eq userId) and
                (Warnings.active eq true)
            }.count().toInt()
        }
    }

    fun getEscalationAction(count: Int): String? {
        return thresholds[count]
    }

    fun startDecayJob() {
        scope.launch {
            while (isActive) {
                try {
                    val now = System.currentTimeMillis()
                    val expired = transaction {
                        Warnings.update({
                            (Warnings.active eq true) and
                            (Warnings.expiresAt.isNotNull()) and
                            (Warnings.expiresAt lessEq now)
                        }) {
                            it[active] = false
                        }
                    }
                    if (expired > 0) {
                        logger.info("Warning decay: $expired warning(s) marked inactive")
                    }
                } catch (e: Exception) {
                    logger.error("Warning decay job error", e)
                }

                delay(86400000L) // Run daily
            }
        }
        logger.info("Warning decay job started (decay: $decayDays days)")
    }

    private fun rowToWarning(row: ResultRow) = Warning(
        id = row[Warnings.id],
        guildId = row[Warnings.guildId],
        userId = row[Warnings.userId],
        moderatorId = row[Warnings.moderatorId],
        reason = row[Warnings.reason],
        issuedAt = row[Warnings.issuedAt],
        expiresAt = row[Warnings.expiresAt],
        active = row[Warnings.active]
    )
}
