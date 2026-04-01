package com.krogie.betterghast.moderation

import com.krogie.betterghast.tags.TagService
import com.krogie.betterghast.util.GuildSettingsService
import dev.minn.jda.ktx.events.listener
import dev.minn.jda.ktx.events.onCommand
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import org.slf4j.LoggerFactory

object AntiSpamListener {

    private val logger = LoggerFactory.getLogger(AntiSpamListener::class.java)
    private const val MODULE = "antispam"

    fun register(jda: JDA) {
        jda.listener<MessageReceivedEvent> { onMessage(it) }
        jda.onCommand("antispam") { onSlashCommand(it as SlashCommandInteractionEvent) }
    }

    private fun onMessage(event: MessageReceivedEvent) {
        if (event.author.isBot || !event.isFromGuild) return

        val guildId = event.guild.idLong
        if (!AntiSpamService.isEnabled(guildId)) return

        val member = event.member ?: return
        // Skip users with Manage Messages permission
        if (member.hasPermission(Permission.MESSAGE_MANAGE)) return

        val userId = event.author.idLong
        val content = event.message.contentRaw
        var reason: String? = null

        when {
            AntiSpamService.checkRateLimit(userId) -> reason = "Rate limit exceeded"
            AntiSpamService.checkDuplicate(userId, content) -> reason = "Duplicate message spam"
            AntiSpamService.checkInvite(guildId, content) -> reason = "Unauthorized Discord invite"
            AntiSpamService.checkLink(guildId, content) -> reason = "Unauthorized link"
        }

        if (reason != null) {
            try {
                event.message.delete().queue(null) { error ->
                    logger.warn("Failed to delete spam message in Guild $guildId: ${error.message}")
                }
            } catch (e: InsufficientPermissionException) {
                logger.warn("Missing permissions to delete message in Guild $guildId: ${e.permission}")
            }

            val autoWarn = GuildSettingsService.getBoolean(guildId, MODULE, "autoWarn", default = false)
            if (autoWarn) {
                WarningService.warn(
                    guildId = guildId,
                    userId = userId,
                    moderatorId = event.jda.selfUser.idLong,
                    reason = "[Anti-Spam] $reason"
                )
            }

            logger.info("Guild $guildId: Deleted spam from $userId ($reason)")
        }
    }

    private fun onSlashCommand(event: SlashCommandInteractionEvent) {
        when (event.subcommandName) {
            "toggle" -> onToggle(event)
            "ratelimit" -> onRateLimit(event)
            "linkfilter" -> onLinkFilter(event)
            "invitefilter" -> onInviteFilter(event)
            "whitelist" -> onWhitelist(event)
            "status" -> onStatus(event)
        }
    }

    private fun onToggle(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return
        val current = GuildSettingsService.getBoolean(guild.idLong, MODULE, "enabled", default = false)
        val newState = !current
        GuildSettingsService.set(guild.idLong, MODULE, "enabled", newState.toString())
        event.replyContainer("Anti-spam has been **${if (newState) "enabled" else "disabled"}**.")
    }

    private fun onRateLimit(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return
        val limit = event.getOption("messages")?.asInt
        val window = event.getOption("window")?.asLong

        if (limit != null) {
            GuildSettingsService.set(guild.idLong, MODULE, "rateLimit", limit.toString())
        }
        if (window != null) {
            GuildSettingsService.set(guild.idLong, MODULE, "rateWindow", window.toString())
        }

        val currentLimit = GuildSettingsService.getLong(guild.idLong, MODULE, "rateLimit", 5)
        val currentWindow = GuildSettingsService.getLong(guild.idLong, MODULE, "rateWindow", 5000)

        event.replyContainer("Rate limit updated: **$currentLimit** messages per **${currentWindow}ms**.")
    }

    private fun onLinkFilter(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return
        val current = GuildSettingsService.getBoolean(guild.idLong, MODULE, "linkFilter", default = false)
        val newState = !current
        GuildSettingsService.set(guild.idLong, MODULE, "linkFilter", newState.toString())
        event.replyContainer("Link filter has been **${if (newState) "enabled" else "disabled"}**.")
    }

    private fun onInviteFilter(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return
        val current = GuildSettingsService.getBoolean(guild.idLong, MODULE, "inviteFilter", default = false)
        val newState = !current
        GuildSettingsService.set(guild.idLong, MODULE, "inviteFilter", newState.toString())
        event.replyContainer("Invite filter has been **${if (newState) "enabled" else "disabled"}**.")
    }

    private fun onWhitelist(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return
        val action = event.getOption("action")?.asString ?: return
        val entry = event.getOption("entry")?.asString ?: return

        val current = GuildSettingsService.get(guild.idLong, MODULE, "whitelist") ?: ""
        val entries = current.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

        when (action.lowercase()) {
            "add" -> {
                if (entry.lowercase() !in entries.map { it.lowercase() }) {
                    entries.add(entry)
                    GuildSettingsService.set(guild.idLong, MODULE, "whitelist", entries.joinToString(","))
                    event.replyContainer("Added `$entry` to the whitelist.")
                } else {
                    event.replyContainer("`$entry` is already in the whitelist.")
                }
            }
            "remove" -> {
                val removed = entries.removeAll { it.equals(entry, ignoreCase = true) }
                if (removed) {
                    GuildSettingsService.set(guild.idLong, MODULE, "whitelist", entries.joinToString(","))
                    event.replyContainer("Removed `$entry` from the whitelist.")
                } else {
                    event.replyContainer("`$entry` is not in the whitelist.")
                }
            }
            else -> event.replyContainer("Invalid action. Use `add` or `remove`.")
        }
    }

    private fun onStatus(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return
        val settings = GuildSettingsService.getAll(guild.idLong, MODULE)

        val enabled = settings["enabled"]?.toBooleanStrictOrNull() ?: false
        val rateLimit = settings["rateLimit"] ?: "5"
        val rateWindow = settings["rateWindow"] ?: "5000"
        val linkFilter = settings["linkFilter"]?.toBooleanStrictOrNull() ?: false
        val inviteFilter = settings["inviteFilter"]?.toBooleanStrictOrNull() ?: false
        val autoWarn = settings["autoWarn"]?.toBooleanStrictOrNull() ?: false
        val whitelist = settings["whitelist"]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

        val sb = StringBuilder("### Anti-Spam Configuration\n\n")
        sb.append("**Enabled:** ${if (enabled) "Yes" else "No"}\n")
        sb.append("**Rate limit:** $rateLimit messages / ${rateWindow}ms\n")
        sb.append("**Link filter:** ${if (linkFilter) "On" else "Off"}\n")
        sb.append("**Invite filter:** ${if (inviteFilter) "On" else "Off"}\n")
        sb.append("**Auto-warn:** ${if (autoWarn) "On" else "Off"}\n")

        if (whitelist.isNotEmpty()) {
            sb.append("\n**Whitelist (${whitelist.size}):**\n")
            sb.append(whitelist.joinToString(", ") { "`$it`" })
        } else {
            sb.append("\n**Whitelist:** None")
        }

        event.replyContainer(sb.toString())
    }

    private fun IReplyCallback.replyContainer(markdown: String) {
        val container = Container.of(TextDisplay.of(markdown)).withAccentColor(TagService.accentColor)
        this.replyComponents(container).useComponentsV2().setEphemeral(true).queue(null) { error ->
            logger.warn("Failed to send ephemeral reply: {}", error.message)
        }
    }
}
