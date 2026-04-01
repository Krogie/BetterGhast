package com.krogie.betterghast.community

import com.krogie.betterghast.db.Guilds
import com.krogie.betterghast.db.ReactionRoleEntries
import com.krogie.betterghast.db.ReactionRolePanels
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

data class PanelData(
    val id: Long,
    val guildId: Long,
    val channelId: Long,
    val messageId: Long,
    val title: String,
    val style: String,
    val entries: List<RoleEntry>
)

data class RoleEntry(
    val id: Long,
    val panelId: Long,
    val roleId: Long,
    val label: String,
    val emoji: String?,
    val description: String?
)

object ReactionRoleService {

    private val logger = LoggerFactory.getLogger(ReactionRoleService::class.java)

    fun createPanel(guildId: Long, channelId: Long, title: String, style: String = "buttons"): Long {
        return transaction {
            if (Guilds.selectAll().where { Guilds.id eq guildId }.empty()) {
                Guilds.insertIgnore { it[Guilds.id] = guildId }
            }
            ReactionRolePanels.insert {
                it[ReactionRolePanels.guildId] = guildId
                it[ReactionRolePanels.channelId] = channelId
                it[ReactionRolePanels.title] = title
                it[ReactionRolePanels.style] = style
            } get ReactionRolePanels.id
        }.also { logger.info("Guild $guildId: Created role panel #$it '$title'") }
    }

    fun addRole(panelId: Long, roleId: Long, label: String, emoji: String? = null, description: String? = null): Long {
        return transaction {
            ReactionRoleEntries.insert {
                it[ReactionRoleEntries.panelId] = panelId
                it[ReactionRoleEntries.roleId] = roleId
                it[ReactionRoleEntries.label] = label
                it[ReactionRoleEntries.emoji] = emoji
                it[ReactionRoleEntries.description] = description
            } get ReactionRoleEntries.id
        }.also { logger.info("Panel $panelId: Added role $roleId as '$label'") }
    }

    fun removeRole(entryId: Long): Boolean {
        return transaction {
            ReactionRoleEntries.deleteWhere { ReactionRoleEntries.id eq entryId } > 0
        }
    }

    fun deletePanel(panelId: Long): Boolean {
        return transaction {
            ReactionRolePanels.deleteWhere { ReactionRolePanels.id eq panelId } > 0
        }.also { if (it) logger.info("Panel $panelId deleted") }
    }

    fun setMessageId(panelId: Long, messageId: Long) {
        transaction {
            ReactionRolePanels.update({ ReactionRolePanels.id eq panelId }) {
                it[ReactionRolePanels.messageId] = messageId
            }
        }
    }

    fun getPanel(panelId: Long): PanelData? {
        return transaction {
            val panelRow = ReactionRolePanels.selectAll()
                .where { ReactionRolePanels.id eq panelId }
                .firstOrNull() ?: return@transaction null

            val entries = ReactionRoleEntries.selectAll()
                .where { ReactionRoleEntries.panelId eq panelId }
                .map { rowToEntry(it) }

            rowToPanel(panelRow, entries)
        }
    }

    fun getPanelByMessageId(messageId: Long): PanelData? {
        return transaction {
            val panelRow = ReactionRolePanels.selectAll()
                .where { ReactionRolePanels.messageId eq messageId }
                .firstOrNull() ?: return@transaction null

            val entries = ReactionRoleEntries.selectAll()
                .where { ReactionRoleEntries.panelId eq panelRow[ReactionRolePanels.id] }
                .map { rowToEntry(it) }

            rowToPanel(panelRow, entries)
        }
    }

    fun getPanelsForGuild(guildId: Long): List<PanelData> {
        return transaction {
            ReactionRolePanels.selectAll()
                .where { ReactionRolePanels.guildId eq guildId }
                .map { panelRow ->
                    val panelId = panelRow[ReactionRolePanels.id]
                    val entries = ReactionRoleEntries.selectAll()
                        .where { ReactionRoleEntries.panelId eq panelId }
                        .map { rowToEntry(it) }
                    rowToPanel(panelRow, entries)
                }
        }
    }

    private fun rowToPanel(row: ResultRow, entries: List<RoleEntry>) = PanelData(
        id = row[ReactionRolePanels.id],
        guildId = row[ReactionRolePanels.guildId],
        channelId = row[ReactionRolePanels.channelId],
        messageId = row[ReactionRolePanels.messageId],
        title = row[ReactionRolePanels.title],
        style = row[ReactionRolePanels.style],
        entries = entries
    )

    private fun rowToEntry(row: ResultRow) = RoleEntry(
        id = row[ReactionRoleEntries.id],
        panelId = row[ReactionRoleEntries.panelId],
        roleId = row[ReactionRoleEntries.roleId],
        label = row[ReactionRoleEntries.label],
        emoji = row[ReactionRoleEntries.emoji],
        description = row[ReactionRoleEntries.description]
    )
}
