package com.krogie.betterghast.community

import com.krogie.betterghast.tags.TagService
import dev.minn.jda.ktx.events.listener
import dev.minn.jda.ktx.events.onCommand
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import org.slf4j.LoggerFactory
import java.text.SimpleDateFormat
import java.util.Date

object PollListener {

    private val logger = LoggerFactory.getLogger(PollListener::class.java)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")
    private const val PROGRESS_BAR_LENGTH = 15

    fun register(jda: JDA) {
        jda.listener<ButtonInteractionEvent> { onButton(it) }
        jda.onCommand("poll") { onPoll(it as SlashCommandInteractionEvent) }

        // Start the expiry job
        PollService.startExpiryJob { poll ->
            val channel = jda.getTextChannelById(poll.channelId) ?: return@startExpiryJob
            channel.retrieveMessageById(poll.messageId).queue({ msg ->
                val updatedContent = buildPollMessage(poll, closed = true)
                val container = Container.of(TextDisplay.of(updatedContent)).withAccentColor(TagService.accentColor)
                msg.editMessageComponents(container).useComponentsV2().queue(null) { err ->
                    logger.warn("Failed to update expired poll #${poll.id}: ${err.message}")
                }
            }, { err -> logger.warn("Could not find message for expired poll #${poll.id}: ${err.message}") })
        }
    }

    private fun onButton(event: ButtonInteractionEvent) {
        when {
            event.componentId.startsWith("poll_vote:") -> handleVote(event)
            event.componentId.startsWith("poll_close:") -> handleClose(event)
        }
    }

    private fun handleVote(event: ButtonInteractionEvent) {
        val parts = event.componentId.removePrefix("poll_vote:").split(":")
        if (parts.size != 2) return

        val pollId = parts[0].toLongOrNull() ?: return
        val optionId = parts[1].toLongOrNull() ?: return

        val poll = PollService.getPoll(pollId) ?: run {
            event.replyContainer("Poll not found.")
            return
        }

        if (poll.closed) {
            event.replyContainer("This poll is closed.")
            return
        }

        val voted = runCatching {
            PollService.vote(pollId, optionId, event.user.idLong)
        }.getOrElse { e ->
            event.replyContainer(e.message ?: "Failed to record vote.")
            return
        }

        val action = if (voted) "voted for" else "removed vote from"
        val optionLabel = poll.options.find { it.id == optionId }?.label ?: "option"
        event.replyContainer("You $action **$optionLabel**.")

        // Update the poll message with new vote counts
        val updatedPoll = PollService.getPoll(pollId) ?: return
        val updatedContent = buildPollMessage(updatedPoll, closed = false)
        val container = Container.of(TextDisplay.of(updatedContent)).withAccentColor(TagService.accentColor)

        val buttons = buildVoteButtons(updatedPoll)
        val closeBtn = Button.danger("poll_close:$pollId", "Close Poll")
        val rows = mutableListOf<ActionRow>()
        for (chunk in buttons.chunked(5)) {
            rows.add(ActionRow.of(chunk))
        }
        rows.add(ActionRow.of(closeBtn))

        event.message.editMessageComponents(
            listOf(container) + rows
        ).useComponentsV2().queue(null) { err ->
            logger.warn("Failed to update poll message: ${err.message}")
        }
    }

    private fun handleClose(event: ButtonInteractionEvent) {
        val pollId = event.componentId.removePrefix("poll_close:").toLongOrNull() ?: return
        val poll = PollService.getPoll(pollId) ?: run {
            event.replyContainer("Poll not found.")
            return
        }

        if (poll.closed) {
            event.replyContainer("This poll is already closed.")
            return
        }

        val member = event.member ?: return
        if (poll.creatorId != member.idLong && !member.hasPermission(net.dv8tion.jda.api.Permission.MANAGE_GUILD)) {
            event.replyContainer("Only the poll creator or a server manager can close this poll.")
            return
        }

        val closedPoll = PollService.closePoll(pollId) ?: run {
            event.replyContainer("Failed to close poll.")
            return
        }

        val updatedContent = buildPollMessage(closedPoll, closed = true)
        val container = Container.of(TextDisplay.of(updatedContent)).withAccentColor(TagService.accentColor)

        event.message.editMessageComponents(container).useComponentsV2().queue(
            { event.replyContainer("Poll closed. Results are shown above.") },
            { err ->
                logger.warn("Failed to update closed poll message: ${err.message}")
                event.replyContainer("Poll closed.")
            }
        )
    }

    private fun onPoll(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: run {
            event.replyContainer("This command can only be used in a server.")
            return
        }

        val question = event.getOption("question")?.asString ?: run {
            event.replyContainer("Please provide a question.")
            return
        }

        val optionsRaw = event.getOption("options")?.asString ?: run {
            event.replyContainer("Please provide poll options separated by commas.")
            return
        }

        val optionLabels = optionsRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (optionLabels.size < 2) {
            event.replyContainer("Please provide at least 2 options, separated by commas.")
            return
        }
        if (optionLabels.size > 25) {
            event.replyContainer("Maximum 25 options allowed.")
            return
        }

        val durationRaw = event.getOption("duration")?.asString
        val multiChoice = event.getOption("multichoice")?.asBoolean ?: false
        val anonymous = event.getOption("anonymous")?.asBoolean ?: false

        val endsAt = durationRaw?.let { parseDuration(it) }?.let { System.currentTimeMillis() + it }

        val pollId = PollService.createPoll(
            guildId = guild.idLong,
            channelId = event.channel.idLong,
            creatorId = event.user.idLong,
            question = question,
            optionLabels = optionLabels,
            multiChoice = multiChoice,
            anonymous = anonymous,
            endsAt = endsAt
        )

        val poll = PollService.getPoll(pollId) ?: run {
            event.replyContainer("Poll created but could not fetch data.")
            return
        }

        val content = buildPollMessage(poll, closed = false)
        val container = Container.of(TextDisplay.of(content)).withAccentColor(TagService.accentColor)
        val buttons = buildVoteButtons(poll)
        val closeBtn = Button.danger("poll_close:$pollId", "Close Poll")

        val rows = mutableListOf<ActionRow>()
        for (chunk in buttons.chunked(5)) {
            rows.add(ActionRow.of(chunk))
        }
        rows.add(ActionRow.of(closeBtn))

        // Reply ephemerally to confirm, then post the poll publicly
        event.deferReply(true).queue()

        var msg = event.channel.sendComponents(container).useComponentsV2()
        for (row in rows) {
            msg = msg.addComponents(row)
        }
        msg.queue({ sentMsg ->
            PollService.setMessageId(pollId, sentMsg.idLong)
            event.hook.editOriginal("Poll #$pollId posted!").queue()
        }, { err ->
            logger.error("Failed to post poll: ${err.message}")
            event.hook.editOriginal("Failed to post poll: ${err.message}").queue()
        })
    }

    // ── Helpers ──

    private fun buildPollMessage(poll: PollData, closed: Boolean): String {
        val sb = StringBuilder()
        val status = if (closed) " [CLOSED]" else ""
        sb.append("### ${poll.question}$status\n\n")

        if (poll.multiChoice) sb.append("*Multi-choice — you can vote for multiple options.*\n")
        if (poll.anonymous) sb.append("*Anonymous poll — votes are not attributed.*\n")
        if (poll.endsAt != null && !closed) {
            sb.append("*Ends: ${dateFormat.format(Date(poll.endsAt))}*\n")
        }
        sb.append("\n")

        val totalVotes = poll.options.sumOf { it.voteCount }

        for (opt in poll.options) {
            val pct = if (totalVotes > 0) (opt.voteCount.toDouble() / totalVotes * 100).toInt() else 0
            val filled = (pct.toDouble() / 100 * PROGRESS_BAR_LENGTH).toInt().coerceIn(0, PROGRESS_BAR_LENGTH)
            val bar = "█".repeat(filled) + "░".repeat(PROGRESS_BAR_LENGTH - filled)
            sb.append("**${opt.label}**\n`$bar` ${opt.voteCount} vote${if (opt.voteCount != 1) "s" else ""} ($pct%)\n\n")
        }

        if (closed) {
            sb.append("**Total votes:** $totalVotes")
            val winner = poll.options.maxByOrNull { it.voteCount }
            if (winner != null && totalVotes > 0) {
                sb.append("\n**Winner:** ${winner.label}")
            }
        }

        return sb.toString().trimEnd()
    }

    private fun buildVoteButtons(poll: PollData): List<Button> {
        return poll.options.map { opt ->
            Button.primary("poll_vote:${poll.id}:${opt.id}", opt.label.take(80))
        }
    }

    /**
     * Parse a duration string like "1h", "30m", "2d", "1h30m" into milliseconds.
     */
    private fun parseDuration(raw: String): Long? {
        var ms = 0L
        val regex = Regex("""(\d+)([dhms])""")
        val matches = regex.findAll(raw.lowercase())
        var found = false
        for (match in matches) {
            found = true
            val value = match.groupValues[1].toLongOrNull() ?: continue
            ms += when (match.groupValues[2]) {
                "d" -> value * 86_400_000L
                "h" -> value * 3_600_000L
                "m" -> value * 60_000L
                "s" -> value * 1_000L
                else -> 0L
            }
        }
        return if (found && ms > 0) ms else null
    }

    private fun IReplyCallback.replyContainer(markdown: String) {
        val container = Container.of(TextDisplay.of(markdown)).withAccentColor(TagService.accentColor)
        this.replyComponents(container).useComponentsV2().setEphemeral(true).queue(null) { error ->
            logger.warn("Failed to send ephemeral reply: {}", error.message)
        }
    }
}
