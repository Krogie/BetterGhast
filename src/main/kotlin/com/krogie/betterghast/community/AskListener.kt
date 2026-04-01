package com.krogie.betterghast.community

import com.krogie.betterghast.tags.TagService
import dev.minn.jda.ktx.events.onCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import org.slf4j.LoggerFactory

object AskListener {
    private val logger = LoggerFactory.getLogger(AskListener::class.java)
    private val scope = CoroutineScope(Dispatchers.IO)

    fun register(jda: JDA) {
        jda.onCommand("ask") { onAsk(it as SlashCommandInteractionEvent) }
    }

    private fun onAsk(event: SlashCommandInteractionEvent) {
        val question = event.getOption("question")?.asString
        if (question.isNullOrBlank()) {
            replyContainer(event, "Please provide a question.")
            return
        }

        if (!AskService.isEnabled()) {
            replyContainer(event, "KrogieBot is not configured yet. The server admin needs to set `CLAUDE_API_KEY` in the bot configuration.")
            return
        }

        // Defer reply since API call takes time
        event.deferReply(true).queue()

        scope.launch {
            try {
                val answer = AskService.ask(question)

                // Truncate if too long for Discord
                val truncated = if (answer.length > 3900) {
                    answer.take(3900) + "\n\n*...response truncated. Ask a more specific question for details.*"
                } else answer

                val container = Container.of(
                    TextDisplay.of("### KrogieBot\n$truncated")
                ).withAccentColor(TagService.accentColor)

                event.hook.editOriginalComponents(container).setReplace(true).useComponentsV2().queue(null) { e ->
                    logger.warn("Failed to send AI response: ${e.message}")
                }
            } catch (e: Exception) {
                logger.error("KrogieBot error", e)
                event.hook.editOriginal("Sorry, something went wrong. Please try again later.").queue()
            }
        }
    }

    private fun replyContainer(event: SlashCommandInteractionEvent, text: String) {
        val container = Container.of(TextDisplay.of(text)).withAccentColor(TagService.accentColor)
        event.replyComponents(container).useComponentsV2().setEphemeral(true).queue(null) { e ->
            logger.warn("Failed to reply: ${e.message}")
        }
    }
}
