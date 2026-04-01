package com.krogie.betterghast.moderation

import com.krogie.betterghast.tags.TagService
import dev.minn.jda.ktx.events.onCommand
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.TimeUnit

object WarningListener {

    private val logger = LoggerFactory.getLogger(WarningListener::class.java)

    fun register(jda: JDA) {
        jda.onCommand("warn") { onWarn(it as SlashCommandInteractionEvent) }
        jda.onCommand("warnings") { onWarnings(it as SlashCommandInteractionEvent) }
        jda.onCommand("clearwarning") { onClearWarning(it as SlashCommandInteractionEvent) }
    }

    private fun onWarn(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return
        val targetUser = event.getOption("user")?.asUser ?: return
        val reason = event.getOption("reason")?.asString ?: "No reason provided"

        if (targetUser.isBot) {
            event.replyContainer("You cannot warn bots.")
            return
        }

        val member = guild.getMember(targetUser)
        if (member == null) {
            event.replyContainer("That user is not a member of this server.")
            return
        }

        val (warning, activeCount) = WarningService.warn(
            guildId = guild.idLong,
            userId = targetUser.idLong,
            moderatorId = event.user.idLong,
            reason = reason
        )

        val sb = StringBuilder()
        sb.append("### Warning Issued\n\n")
        sb.append("**User:** ${targetUser.asMention} (${targetUser.name})\n")
        sb.append("**Reason:** $reason\n")
        sb.append("**Active warnings:** $activeCount\n")
        sb.append("**Warning ID:** #${warning.id}\n")

        val escalation = WarningService.getEscalationAction(activeCount)
        if (escalation != null) {
            sb.append("\n**Escalation:** ")
            when (escalation.lowercase()) {
                "mute" -> {
                    member.timeoutFor(Duration.ofHours(1)).queue(
                        { logger.info("Guild ${guild.idLong}: Muted ${targetUser.id} (escalation at $activeCount warnings)") },
                        { error -> logger.warn("Failed to mute ${targetUser.id}: ${error.message}") }
                    )
                    sb.append("User has been muted for 1 hour.")
                }
                "kick" -> {
                    member.kick().queue(
                        { logger.info("Guild ${guild.idLong}: Kicked ${targetUser.id} (escalation at $activeCount warnings)") },
                        { error -> logger.warn("Failed to kick ${targetUser.id}: ${error.message}") }
                    )
                    sb.append("User has been kicked from the server.")
                }
                "ban" -> {
                    member.ban(0, TimeUnit.DAYS).queue(
                        { logger.info("Guild ${guild.idLong}: Banned ${targetUser.id} (escalation at $activeCount warnings)") },
                        { error -> logger.warn("Failed to ban ${targetUser.id}: ${error.message}") }
                    )
                    sb.append("User has been banned from the server.")
                }
                else -> sb.append("Unknown action: $escalation")
            }
        }

        event.replyContainer(sb.toString())
    }

    private fun onWarnings(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return
        val targetUser = event.getOption("user")?.asUser ?: return
        val showAll = event.getOption("all")?.asBoolean ?: false

        val warnings = WarningService.getWarnings(guild.idLong, targetUser.idLong, activeOnly = !showAll)

        if (warnings.isEmpty()) {
            val scope = if (showAll) "" else " active"
            event.replyContainer("${targetUser.asMention} has no$scope warnings.")
            return
        }

        val sb = StringBuilder()
        val activeCount = warnings.count { it.active }
        sb.append("### Warnings for ${targetUser.name}\n")
        sb.append("**Active:** $activeCount | **Total shown:** ${warnings.size}\n\n")

        for (warning in warnings.take(15)) {
            val status = if (warning.active) "ACTIVE" else "EXPIRED"
            val date = java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date(warning.issuedAt))
            sb.append("**#${warning.id}** [$status] $date — ${warning.reason} (by <@${warning.moderatorId}>)\n")
        }

        if (warnings.size > 15) {
            sb.append("\n*...and ${warnings.size - 15} more warning(s).*")
        }

        event.replyContainer(sb.toString())
    }

    private fun onClearWarning(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return
        val id = event.getOption("id")?.asLong ?: return

        val cleared = WarningService.clearWarning(guild.idLong, id)
        if (cleared) {
            event.replyContainer("Warning **#$id** has been cleared.")
        } else {
            event.replyContainer("No warning found with ID **#$id** in this server.")
        }
    }

    private fun IReplyCallback.replyContainer(markdown: String) {
        val container = Container.of(TextDisplay.of(markdown)).withAccentColor(TagService.accentColor)
        this.replyComponents(container).useComponentsV2().setEphemeral(true).queue(null) { error ->
            logger.warn("Failed to send ephemeral reply: {}", error.message)
        }
    }
}
