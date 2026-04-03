package com.krogie.betterghast.community

import com.krogie.betterghast.db.Guilds
import com.krogie.betterghast.db.Tickets
import com.krogie.betterghast.util.GuildSettingsService
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

data class TicketData(
    val id: Long,
    val guildId: Long,
    val channelId: Long,
    val userId: Long,
    val claimedBy: Long?,
    val category: String,
    val status: String,
    val createdAt: Long,
    val closedAt: Long?
)

object TicketService {

    private val logger = LoggerFactory.getLogger(TicketService::class.java)
    private const val MODULE = "tickets"
    const val MAX_OPEN_TICKETS = 50

    // ── Settings helpers ──

    fun getSupportRoleId(guildId: Long): Long? =
        GuildSettingsService.getLong(guildId, MODULE, "supportRoleId").takeIf { it != 0L }

    fun setSupportRoleId(guildId: Long, roleId: Long) {
        ensureGuild(guildId)
        GuildSettingsService.set(guildId, MODULE, "supportRoleId", roleId.toString())
    }

    fun getLogChannelId(guildId: Long): Long? =
        GuildSettingsService.getLong(guildId, MODULE, "logChannelId").takeIf { it != 0L }

    fun setLogChannelId(guildId: Long, channelId: Long) {
        ensureGuild(guildId)
        GuildSettingsService.set(guildId, MODULE, "logChannelId", channelId.toString())
    }

    fun getPanelChannelId(guildId: Long): Long? =
        GuildSettingsService.getLong(guildId, MODULE, "panelChannelId").takeIf { it != 0L }

    fun setPanelChannelId(guildId: Long, channelId: Long) {
        ensureGuild(guildId)
        GuildSettingsService.set(guildId, MODULE, "panelChannelId", channelId.toString())
    }

    fun getCategory(guildId: Long): Long? =
        GuildSettingsService.getLong(guildId, MODULE, "categoryId").takeIf { it != 0L }

    fun setCategory(guildId: Long, categoryId: Long) {
        ensureGuild(guildId)
        GuildSettingsService.set(guildId, MODULE, "categoryId", categoryId.toString())
    }

    fun isEnabled(guildId: Long): Boolean =
        GuildSettingsService.getBoolean(guildId, MODULE, "enabled", false)

    fun setEnabled(guildId: Long, enabled: Boolean) {
        ensureGuild(guildId)
        GuildSettingsService.set(guildId, MODULE, "enabled", enabled.toString())
    }

    // ── Ticket CRUD ──

    fun openTicketCount(guildId: Long): Int {
        return transaction {
            Tickets.selectAll().where {
                (Tickets.guildId eq guildId) and (Tickets.status eq "open")
            }.count().toInt()
        }
    }

    fun getUserOpenTicket(guildId: Long, userId: Long): TicketData? {
        return transaction {
            Tickets.selectAll().where {
                (Tickets.guildId eq guildId) and
                (Tickets.userId eq userId) and
                (Tickets.status eq "open")
            }.firstOrNull()?.let { rowToTicket(it) }
        }
    }

    fun create(guildId: Long, channelId: Long, userId: Long, category: String = "general"): TicketData {
        val now = System.currentTimeMillis()
        return transaction {
            com.krogie.betterghast.util.GuildUtil.ensureGuild(guildId)
            val id = Tickets.insert {
                it[Tickets.guildId] = guildId
                it[Tickets.channelId] = channelId
                it[Tickets.userId] = userId
                it[Tickets.category] = category
                it[Tickets.status] = "open"
                it[Tickets.createdAt] = now
            } get Tickets.id

            TicketData(
                id = id,
                guildId = guildId,
                channelId = channelId,
                userId = userId,
                claimedBy = null,
                category = category,
                status = "open",
                createdAt = now,
                closedAt = null
            )
        }.also { logger.info("Guild $guildId: Ticket #${it.id} opened by $userId in $channelId") }
    }

    fun close(guildId: Long, channelId: Long): Boolean {
        val now = System.currentTimeMillis()
        return transaction {
            Tickets.update({
                (Tickets.guildId eq guildId) and
                (Tickets.channelId eq channelId) and
                (Tickets.status eq "open")
            }) {
                it[Tickets.status] = "closed"
                it[Tickets.closedAt] = now
            } > 0
        }.also { if (it) logger.info("Guild $guildId: Ticket in channel $channelId closed") }
    }

    fun claim(guildId: Long, channelId: Long, claimedBy: Long): Boolean {
        return transaction {
            Tickets.update({
                (Tickets.guildId eq guildId) and
                (Tickets.channelId eq channelId) and
                (Tickets.status eq "open")
            }) {
                it[Tickets.claimedBy] = claimedBy
            } > 0
        }.also { if (it) logger.info("Guild $guildId: Ticket in $channelId claimed by $claimedBy") }
    }

    fun getByChannel(channelId: Long): TicketData? {
        return transaction {
            Tickets.selectAll().where { Tickets.channelId eq channelId }
                .firstOrNull()?.let { rowToTicket(it) }
        }
    }

    fun getOpenTickets(guildId: Long): List<TicketData> {
        return transaction {
            Tickets.selectAll().where {
                (Tickets.guildId eq guildId) and (Tickets.status eq "open")
            }.map { rowToTicket(it) }
        }
    }

    private fun ensureGuild(guildId: Long) {
        transaction {
            if (Guilds.selectAll().where { Guilds.id eq guildId }.empty()) {
                Guilds.insertIgnore { it[Guilds.id] = guildId }
            }
        }
    }

    private fun rowToTicket(row: ResultRow) = TicketData(
        id = row[Tickets.id],
        guildId = row[Tickets.guildId],
        channelId = row[Tickets.channelId],
        userId = row[Tickets.userId],
        claimedBy = row[Tickets.claimedBy],
        category = row[Tickets.category],
        status = row[Tickets.status],
        createdAt = row[Tickets.createdAt],
        closedAt = row[Tickets.closedAt]
    )
}
