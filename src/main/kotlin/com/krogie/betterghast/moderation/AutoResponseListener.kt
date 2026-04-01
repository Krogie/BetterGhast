package com.krogie.betterghast.moderation

import com.krogie.betterghast.tags.TagService
import dev.minn.jda.ktx.events.listener
import dev.minn.jda.ktx.events.onCommand
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import org.slf4j.LoggerFactory

object AutoResponseListener {

    private val logger = LoggerFactory.getLogger(AutoResponseListener::class.java)

    fun register(jda: JDA) {
        jda.listener<MessageReceivedEvent> { onMessage(it) }
        jda.onCommand("autoresponse") { onSlashCommand(it as SlashCommandInteractionEvent) }
    }

    private fun onMessage(event: MessageReceivedEvent) {
        if (event.author.isBot || !event.isFromGuild) return

        val guildId = event.guild.idLong
        val channelId = event.channel.idLong
        val content = event.message.contentRaw

        val trigger = AutoResponseService.checkMessage(guildId, channelId, content) ?: return
        val tag = TagService.find(guildId, trigger.tagPrimary) ?: return

        try {
            val container = tag.cachedContainer
            val action = if (container != null) {
                event.channel.sendMessageComponents(container).useComponentsV2()
            } else {
                event.channel.sendMessage(tag.content)
            }

            action.queue(null) { error ->
                logger.warn("Failed to send auto-response in Guild $guildId: ${error.message}")
            }
        } catch (e: InsufficientPermissionException) {
            logger.warn("Missing permissions for auto-response in Guild $guildId: ${e.permission}")
        } catch (e: Exception) {
            logger.error("Error sending auto-response in Guild $guildId", e)
        }
    }

    private fun onSlashCommand(event: SlashCommandInteractionEvent) {
        when (event.subcommandName) {
            "add" -> onAdd(event)
            "remove" -> onRemove(event)
            "list" -> onList(event)
            "toggle" -> onToggle(event)
        }
    }

    private fun onAdd(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return
        val pattern = event.getOption("pattern")?.asString ?: return
        val tagName = event.getOption("tag_name")?.asString ?: return
        val isRegex = event.getOption("is_regex")?.asBoolean ?: false
        val channel = event.getOption("channel")?.asChannel

        val tag = TagService.find(guild.idLong, tagName)
        if (tag == null) {
            event.replyContainer("Tag `$tagName` does not exist. Create it first with `/tags manage`.")
            return
        }

        if (isRegex) {
            val valid = runCatching { Regex(pattern) }.isSuccess
            if (!valid) {
                event.replyContainer("Invalid regex pattern: `$pattern`")
                return
            }
        }

        val id = AutoResponseService.addTrigger(
            guildId = guild.idLong,
            pattern = pattern,
            isRegex = isRegex,
            tagPrimary = tag.primary,
            channelId = channel?.idLong,
            cooldownMs = 30000L
        )

        val channelInfo = if (channel != null) " in ${channel.asMention}" else " in all channels"
        event.replyContainer("Auto-response trigger **#$id** created.\nPattern: `$pattern`${if (isRegex) " (regex)" else ""}\nTag: `${tag.primary}`$channelInfo")
    }

    private fun onRemove(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return
        val id = event.getOption("id")?.asLong ?: return

        val removed = AutoResponseService.removeTrigger(guild.idLong, id)
        if (removed) {
            event.replyContainer("Auto-response trigger **#$id** has been removed.")
        } else {
            event.replyContainer("No auto-response trigger found with ID **#$id**.")
        }
    }

    private fun onList(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return
        val triggers = AutoResponseService.listTriggers(guild.idLong)

        if (triggers.isEmpty()) {
            event.replyContainer("No auto-response triggers configured for this server.")
            return
        }

        val sb = StringBuilder("### Auto-Response Triggers (${triggers.size})\n\n")
        for (trigger in triggers) {
            val status = if (trigger.enabled) "ON" else "OFF"
            val type = if (trigger.regex != null) "regex" else "text"
            val channel = if (trigger.channelId != null) "<#${trigger.channelId}>" else "all channels"
            sb.append("**#${trigger.id}** [$status] `${trigger.pattern}` ($type) -> `${trigger.tagPrimary}` in $channel\n")
        }

        event.replyContainer(sb.toString())
    }

    private fun onToggle(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return
        val id = event.getOption("id")?.asLong ?: return

        val newState = AutoResponseService.toggleTrigger(guild.idLong, id)
        val stateText = if (newState) "enabled" else "disabled"
        event.replyContainer("Auto-response trigger **#$id** is now **$stateText**.")
    }

    private fun IReplyCallback.replyContainer(markdown: String) {
        val container = Container.of(TextDisplay.of(markdown)).withAccentColor(TagService.accentColor)
        this.replyComponents(container).useComponentsV2().setEphemeral(true).queue(null) { error ->
            logger.warn("Failed to send ephemeral reply: {}", error.message)
        }
    }
}
