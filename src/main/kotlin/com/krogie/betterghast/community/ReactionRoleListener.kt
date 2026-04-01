package com.krogie.betterghast.community

import com.krogie.betterghast.tags.TagService
import dev.minn.jda.ktx.events.listener
import dev.minn.jda.ktx.events.onCommand
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.selections.StringSelectMenu
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import org.slf4j.LoggerFactory

object ReactionRoleListener {

    private val logger = LoggerFactory.getLogger(ReactionRoleListener::class.java)

    fun register(jda: JDA) {
        jda.listener<ButtonInteractionEvent> { onButton(it) }
        jda.listener<StringSelectInteractionEvent> { onSelect(it) }
        jda.onCommand("rolepanel") { onRolePanel(it as SlashCommandInteractionEvent) }
    }

    private fun onButton(event: ButtonInteractionEvent) {
        val id = event.componentId
        if (!id.startsWith("rr_role:")) return

        val roleId = id.removePrefix("rr_role:").toLongOrNull() ?: return
        val guild = event.guild ?: return
        val member = event.member ?: return

        val role = guild.getRoleById(roleId) ?: run {
            event.replyContainer("Role not found. The panel may be outdated.")
            return
        }

        val hasRole = member.roles.any { it.idLong == roleId }
        if (hasRole) {
            guild.removeRoleFromMember(member, role).queue({
                event.replyContainer("Removed role **${role.name}**.")
            }, { err ->
                logger.warn("Failed to remove role $roleId from ${member.id}: ${err.message}")
                event.replyContainer("Failed to remove role. Check my permissions.")
            })
        } else {
            guild.addRoleToMember(member, role).queue({
                event.replyContainer("Gave you role **${role.name}**.")
            }, { err ->
                logger.warn("Failed to add role $roleId to ${member.id}: ${err.message}")
                event.replyContainer("Failed to give role. Check my permissions.")
            })
        }
    }

    private fun onSelect(event: StringSelectInteractionEvent) {
        val id = event.componentId
        if (!id.startsWith("rr_dropdown:")) return

        val guild = event.guild ?: return
        val member = event.member ?: return
        val panel = ReactionRoleService.getPanelByMessageId(event.messageIdLong) ?: return

        val selectedRoleIds = event.values.mapNotNull { it.toLongOrNull() }.toSet()
        val panelRoleIds = panel.entries.map { it.roleId }.toSet()

        for (roleId in panelRoleIds) {
            val role = guild.getRoleById(roleId) ?: continue
            val hasRole = member.roles.any { it.idLong == roleId }
            val shouldHave = roleId in selectedRoleIds

            if (shouldHave && !hasRole) {
                guild.addRoleToMember(member, role).queue(null) { err ->
                    logger.warn("Failed to add role $roleId: ${err.message}")
                }
            } else if (!shouldHave && hasRole) {
                guild.removeRoleFromMember(member, role).queue(null) { err ->
                    logger.warn("Failed to remove role $roleId: ${err.message}")
                }
            }
        }

        val names = selectedRoleIds.mapNotNull { guild.getRoleById(it)?.name }
        if (names.isEmpty()) {
            event.replyContainer("All roles from this panel removed.")
        } else {
            event.replyContainer("Roles updated: **${names.joinToString(", ")}**")
        }
    }

    private fun onRolePanel(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: run {
            event.replyContainer("This command can only be used in a server.")
            return
        }

        when (event.subcommandName) {
            "create" -> {
                val title = event.getOption("title")?.asString ?: run {
                    event.replyContainer("Please provide a panel title.")
                    return
                }
                val channelOption = event.getOption("channel")?.asChannel
                val channelId = channelOption?.idLong ?: event.channel.idLong
                val style = event.getOption("style")?.asString ?: "buttons"

                val panelId = ReactionRoleService.createPanel(guild.idLong, channelId, title, style)
                event.replyContainer("Role panel **#$panelId** created. Use `/rolepanel addrole` to add roles, then `/rolepanel send` to post it.")
            }

            "addrole" -> {
                val panelId = event.getOption("panelid")?.asLong ?: run {
                    event.replyContainer("Please provide the panel ID.")
                    return
                }
                val role = event.getOption("role")?.asRole ?: run {
                    event.replyContainer("Please provide a role.")
                    return
                }
                val label = event.getOption("label")?.asString ?: role.name
                val emoji = event.getOption("emoji")?.asString
                val description = event.getOption("description")?.asString

                val panel = ReactionRoleService.getPanel(panelId)
                if (panel == null || panel.guildId != guild.idLong) {
                    event.replyContainer("Panel #$panelId not found.")
                    return
                }

                if (panel.entries.size >= 25) {
                    event.replyContainer("This panel already has 25 roles (Discord limit).")
                    return
                }

                ReactionRoleService.addRole(panelId, role.idLong, label, emoji, description)
                event.replyContainer("Added **${role.name}** to panel #$panelId.")
            }

            "send" -> {
                val panelId = event.getOption("panelid")?.asLong ?: run {
                    event.replyContainer("Please provide the panel ID.")
                    return
                }
                val panel = ReactionRoleService.getPanel(panelId)
                if (panel == null || panel.guildId != guild.idLong) {
                    event.replyContainer("Panel #$panelId not found.")
                    return
                }

                if (panel.entries.isEmpty()) {
                    event.replyContainer("This panel has no roles. Add roles first with `/rolepanel addrole`.")
                    return
                }

                val channel = guild.getTextChannelById(panel.channelId) ?: run {
                    event.replyContainer("The configured channel no longer exists.")
                    return
                }

                val headerText = "### ${panel.title}\n\nClick a button below to toggle a role:"
                val container = Container.of(TextDisplay.of(headerText)).withAccentColor(TagService.accentColor)

                if (panel.style == "dropdown") {
                    val menuBuilder = StringSelectMenu.create("rr_dropdown:${panel.id}")
                        .setPlaceholder("Select roles...")
                        .setMinValues(0)
                        .setMaxValues(panel.entries.size)

                    for (entry in panel.entries) {
                        val optBuilder = net.dv8tion.jda.api.components.selects.SelectOption
                            .of(entry.label, entry.roleId.toString())
                        menuBuilder.addOptions(
                            if (entry.description != null) optBuilder.withDescription(entry.description) else optBuilder
                        )
                    }
                    // Send as separate message with select menu
                    channel.sendMessageComponents(container, ActionRow.of(menuBuilder.build())).useComponentsV2()
                        .queue({ sentMsg: net.dv8tion.jda.api.entities.Message ->
                            ReactionRoleService.setMessageId(panelId, sentMsg.idLong)
                            event.replyContainer("Panel posted in ${channel.asMention}.")
                        }, { e ->
                            logger.error("Failed to send panel $panelId: ${e.message}")
                            event.replyContainer("Failed to post panel: ${e.message}")
                        })
                } else {
                    // Button style: up to 25 roles in 5 rows of 5
                    val rows = panel.entries.chunked(5).map { chunk ->
                        ActionRow.of(chunk.map { entry ->
                            val btn = Button.secondary("rr_role:${entry.roleId}", entry.label)
                            val emojiStr = entry.emoji
                            if (!emojiStr.isNullOrBlank()) {
                                runCatching { btn.withEmoji(net.dv8tion.jda.api.entities.emoji.Emoji.fromFormatted(emojiStr)) }
                                    .getOrDefault(btn)
                            } else btn
                        })
                    }

                    val allComponents = mutableListOf(container, *rows.toTypedArray())
                    channel.sendMessageComponents(allComponents).useComponentsV2()
                        .queue({ sentMsg: net.dv8tion.jda.api.entities.Message ->
                            ReactionRoleService.setMessageId(panelId, sentMsg.idLong)
                            event.replyContainer("Panel posted in ${channel.asMention}.")
                        }, { e ->
                            logger.error("Failed to send panel $panelId: ${e.message}")
                            event.replyContainer("Failed to post panel: ${e.message}")
                        })
                }
            }

            "delete" -> {
                val panelId = event.getOption("panelid")?.asLong ?: run {
                    event.replyContainer("Please provide the panel ID.")
                    return
                }
                val panel = ReactionRoleService.getPanel(panelId)
                if (panel == null || panel.guildId != guild.idLong) {
                    event.replyContainer("Panel #$panelId not found.")
                    return
                }
                ReactionRoleService.deletePanel(panelId)
                event.replyContainer("Panel #$panelId deleted.")
            }

            "list" -> {
                val panels = ReactionRoleService.getPanelsForGuild(guild.idLong)
                if (panels.isEmpty()) {
                    event.replyContainer("No role panels found. Create one with `/rolepanel create`.")
                    return
                }
                val sb = StringBuilder("### Role Panels\n\n")
                for (panel in panels) {
                    sb.append("**#${panel.id}** — ${panel.title} (${panel.entries.size} roles, ${panel.style})")
                    if (panel.messageId != 0L) sb.append(" — <#${panel.channelId}>")
                    sb.append("\n")
                }
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
