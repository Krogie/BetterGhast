package com.krogie.betterghast.community

import com.krogie.betterghast.tags.TagService
import com.krogie.betterghast.util.PlaceholderUtil
import dev.minn.jda.ktx.events.listener
import dev.minn.jda.ktx.events.onCommand
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import org.slf4j.LoggerFactory

object WelcomeListener {

    private val logger = LoggerFactory.getLogger(WelcomeListener::class.java)

    fun register(jda: JDA) {
        jda.listener<GuildMemberJoinEvent> { onMemberJoin(it) }
        jda.listener<GuildMemberRemoveEvent> { onMemberLeave(it) }
        jda.onCommand("welcome") { onWelcome(it as SlashCommandInteractionEvent) }
    }

    private fun onMemberJoin(event: GuildMemberJoinEvent) {
        val guild = event.guild
        val config = WelcomeService.getConfig(guild.idLong)
        if (!config.enabled) return

        // Auto-role assignment
        config.autoRoleId?.let { roleId ->
            val role = guild.getRoleById(roleId)
            if (role != null) {
                guild.addRoleToMember(event.member, role).queue(
                    { logger.info("Guild ${guild.idLong}: Auto-role $roleId applied to ${event.user.id}") },
                    { err -> logger.warn("Guild ${guild.idLong}: Failed to apply auto-role: ${err.message}") }
                )
            }
        }

        // Welcome message in channel
        config.channelId?.let { channelId ->
            val channel = guild.getTextChannelById(channelId) ?: return@let
            val rawMessage = config.message ?: "Welcome {user.mention} to **{server}**! You are member #{server.members}."
            val text = PlaceholderUtil.replace(rawMessage, member = event.member, channel = channel)
            val container = Container.of(TextDisplay.of(text)).withAccentColor(TagService.accentColor)
            channel.sendMessageComponents(container).useComponentsV2().queue(
                null,
                { err -> logger.warn("Guild ${guild.idLong}: Failed to send welcome message: ${err.message}") }
            )
        }

        // DM welcome
        if (config.dmEnabled) {
            val rawMessage = config.message ?: "Welcome to **{server}**!"
            val text = PlaceholderUtil.replace(rawMessage, member = event.member)
            event.user.openPrivateChannel().queue({ pc ->
                pc.sendMessage(text).queue(null) { err ->
                    logger.debug("Failed to DM ${event.user.id}: ${err.message}")
                }
            }, { err -> logger.debug("Cannot open DM to ${event.user.id}: ${err.message}") })
        }
    }

    private fun onMemberLeave(event: GuildMemberRemoveEvent) {
        val guild = event.guild
        val config = WelcomeService.getConfig(guild.idLong)
        if (!config.enabled) return

        val leaveMsg = config.leaveMessage ?: return
        config.channelId?.let { channelId ->
            val channel = guild.getTextChannelById(channelId) ?: return@let
            val member = event.member
            val text = if (member != null) {
                PlaceholderUtil.replace(leaveMsg, member = member, channel = channel)
            } else {
                leaveMsg.replace("{user}", event.user.name)
                    .replace("{user.tag}", event.user.name)
                    .replace("{user.mention}", event.user.asMention)
            }
            val container = Container.of(TextDisplay.of(text)).withAccentColor(TagService.accentColor)
            channel.sendMessageComponents(container).useComponentsV2().queue(
                null,
                { err -> logger.warn("Guild ${guild.idLong}: Failed to send leave message: ${err.message}") }
            )
        }
    }

    private fun onWelcome(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: run {
            event.replyContainer("This command can only be used in a server.")
            return
        }

        when (event.subcommandName) {
            "channel" -> {
                val channel = event.getOption("channel")?.asChannel
                    ?: guild.getTextChannelById(event.channel.idLong)
                    ?: run {
                        event.replyContainer("Could not resolve a channel.")
                        return
                    }
                WelcomeService.setChannel(guild.idLong, channel.idLong)
                event.replyContainer("Welcome channel set to ${channel.asMention}.")
            }

            "message" -> {
                val msg = event.getOption("text")?.asString ?: run {
                    event.replyContainer("Please provide a message text.")
                    return
                }
                WelcomeService.setMessage(guild.idLong, msg)
                event.replyContainer("Welcome message updated.\n\n**Preview:**\n${PlaceholderUtil.replace(msg, member = event.member)}")
            }

            "leave" -> {
                val msg = event.getOption("text")?.asString ?: run {
                    event.replyContainer("Please provide a leave message text.")
                    return
                }
                WelcomeService.setLeaveMessage(guild.idLong, msg)
                event.replyContainer("Leave message updated.")
            }

            "autorole" -> {
                val role = event.getOption("role")?.asRole
                if (role == null) {
                    WelcomeService.setAutoRole(guild.idLong, null)
                    event.replyContainer("Auto-role cleared.")
                } else {
                    WelcomeService.setAutoRole(guild.idLong, role.idLong)
                    event.replyContainer("Auto-role set to ${role.asMention}.")
                }
            }

            "dm" -> {
                val enabled = event.getOption("enabled")?.asBoolean ?: true
                WelcomeService.setDmEnabled(guild.idLong, enabled)
                event.replyContainer("DM welcome messages ${if (enabled) "enabled" else "disabled"}.")
            }

            "test" -> {
                val config = WelcomeService.getConfig(guild.idLong)
                val member = event.member ?: run {
                    event.replyContainer("Could not resolve your member.")
                    return
                }
                val rawMessage = config.message ?: "Welcome {user.mention} to **{server}**! You are member #{server.members}."
                val text = PlaceholderUtil.replace(rawMessage, member = member)
                event.replyContainer("**Welcome message test:**\n\n$text")
            }

            "status" -> {
                val config = WelcomeService.getConfig(guild.idLong)
                val sb = StringBuilder()
                sb.append("### Welcome Module Status\n\n")
                sb.append("**Enabled:** ${config.enabled}\n")
                sb.append("**Channel:** ${config.channelId?.let { "<#$it>" } ?: "not set"}\n")
                sb.append("**Auto-role:** ${config.autoRoleId?.let { "<@&$it>" } ?: "not set"}\n")
                sb.append("**DM enabled:** ${config.dmEnabled}\n")
                sb.append("**Welcome message:** ${config.message?.take(100) ?: "default"}\n")
                sb.append("**Leave message:** ${config.leaveMessage?.take(100) ?: "not set"}")
                event.replyContainer(sb.toString())
            }

            else -> event.replyContainer("Unknown subcommand.")
        }
    }

    private fun IReplyCallback.replyContainer(markdown: String) {
        val container = Container.of(TextDisplay.of(markdown)).withAccentColor(TagService.accentColor)
        this.replyComponents(container).useComponentsV2().setEphemeral(true).queue(null) { error ->
            logger.warn("Failed to send ephemeral reply: {}", error.message)
        }
    }
}
