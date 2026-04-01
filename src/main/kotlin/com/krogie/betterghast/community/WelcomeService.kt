package com.krogie.betterghast.community

import com.krogie.betterghast.db.Guilds
import com.krogie.betterghast.util.GuildSettingsService
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

data class WelcomeConfig(
    val channelId: Long?,
    val message: String?,
    val leaveMessage: String?,
    val autoRoleId: Long?,
    val dmEnabled: Boolean,
    val enabled: Boolean
)

object WelcomeService {

    private val logger = LoggerFactory.getLogger(WelcomeService::class.java)
    private const val MODULE = "welcome"

    private fun ensureGuild(guildId: Long) {
        transaction {
            if (Guilds.selectAll().where { Guilds.id eq guildId }.empty()) {
                Guilds.insertIgnore { it[Guilds.id] = guildId }
            }
        }
    }

    fun getConfig(guildId: Long): WelcomeConfig {
        return WelcomeConfig(
            channelId = GuildSettingsService.getLong(guildId, MODULE, "channelId").takeIf { it != 0L },
            message = GuildSettingsService.get(guildId, MODULE, "message"),
            leaveMessage = GuildSettingsService.get(guildId, MODULE, "leaveMessage"),
            autoRoleId = GuildSettingsService.getLong(guildId, MODULE, "autoRoleId").takeIf { it != 0L },
            dmEnabled = GuildSettingsService.getBoolean(guildId, MODULE, "dmEnabled", false),
            enabled = GuildSettingsService.getBoolean(guildId, MODULE, "enabled", false)
        )
    }

    fun setChannel(guildId: Long, channelId: Long) {
        ensureGuild(guildId)
        GuildSettingsService.set(guildId, MODULE, "channelId", channelId.toString())
        GuildSettingsService.set(guildId, MODULE, "enabled", "true")
        logger.info("Guild $guildId: Welcome channel set to $channelId")
    }

    fun setMessage(guildId: Long, message: String) {
        ensureGuild(guildId)
        GuildSettingsService.set(guildId, MODULE, "message", message)
        logger.info("Guild $guildId: Welcome message updated")
    }

    fun setLeaveMessage(guildId: Long, message: String) {
        ensureGuild(guildId)
        GuildSettingsService.set(guildId, MODULE, "leaveMessage", message)
        logger.info("Guild $guildId: Leave message updated")
    }

    fun setAutoRole(guildId: Long, roleId: Long?) {
        ensureGuild(guildId)
        if (roleId != null) {
            GuildSettingsService.set(guildId, MODULE, "autoRoleId", roleId.toString())
            logger.info("Guild $guildId: Auto-role set to $roleId")
        } else {
            GuildSettingsService.delete(guildId, MODULE, "autoRoleId")
            logger.info("Guild $guildId: Auto-role cleared")
        }
    }

    fun setDmEnabled(guildId: Long, enabled: Boolean) {
        ensureGuild(guildId)
        GuildSettingsService.set(guildId, MODULE, "dmEnabled", enabled.toString())
    }

    fun setEnabled(guildId: Long, enabled: Boolean) {
        ensureGuild(guildId)
        GuildSettingsService.set(guildId, MODULE, "enabled", enabled.toString())
    }
}
