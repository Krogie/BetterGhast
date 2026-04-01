package com.krogie.betterghast.community

import com.krogie.betterghast.tags.TagService
import dev.minn.jda.ktx.events.listener
import dev.minn.jda.ktx.events.onCommand
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import net.dv8tion.jda.api.utils.FileUpload
import org.slf4j.LoggerFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumSet

object TicketListener {

    private val logger = LoggerFactory.getLogger(TicketListener::class.java)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    fun register(jda: JDA) {
        jda.listener<ButtonInteractionEvent> { onButton(it) }
        jda.onCommand("ticket") { onTicket(it as SlashCommandInteractionEvent) }
    }

    private fun onButton(event: ButtonInteractionEvent) {
        when {
            event.componentId == "ticket_create" -> handleTicketCreate(event)
            event.componentId.startsWith("ticket_close:") -> handleTicketClose(event)
            event.componentId.startsWith("ticket_claim:") -> handleTicketClaim(event)
            event.componentId.startsWith("ticket_confirm_close:") -> handleTicketConfirmClose(event)
        }
    }

    private fun handleTicketCreate(event: ButtonInteractionEvent) {
        val guild = event.guild ?: return
        val member = event.member ?: return

        if (!TicketService.isEnabled(guild.idLong)) {
            event.replyContainer("The ticket system is not currently enabled.")
            return
        }

        val existing = TicketService.getUserOpenTicket(guild.idLong, member.idLong)
        if (existing != null) {
            event.replyContainer("You already have an open ticket: <#${existing.channelId}>")
            return
        }

        val openCount = TicketService.openTicketCount(guild.idLong)
        if (openCount >= TicketService.MAX_OPEN_TICKETS) {
            event.replyContainer("The maximum number of open tickets (${ TicketService.MAX_OPEN_TICKETS}) has been reached. Please try again later.")
            return
        }

        val categoryId = TicketService.getCategory(guild.idLong)
        val supportRoleId = TicketService.getSupportRoleId(guild.idLong)

        // Defer reply while we create the channel
        event.deferReply(true).queue()

        val channelAction = if (categoryId != null) {
            val category = guild.getCategoryById(categoryId)
            category?.createTextChannel("ticket-${member.user.name.lowercase().take(20)}")
                ?: guild.createTextChannel("ticket-${member.user.name.lowercase().take(20)}")
        } else {
            guild.createTextChannel("ticket-${member.user.name.lowercase().take(20)}")
        }

        channelAction
            .addPermissionOverride(guild.publicRole, null, EnumSet.of(Permission.VIEW_CHANNEL))
            .addPermissionOverride(member, EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND), null)
            .let { action ->
                if (supportRoleId != null) {
                    val supportRole = guild.getRoleById(supportRoleId)
                    if (supportRole != null) {
                        action.addPermissionOverride(supportRole, EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND), null)
                    } else action
                } else action
            }
            .queue({ channel ->
                val ticket = TicketService.create(guild.idLong, channel.idLong, member.idLong)

                val headerText = "### Ticket #${ticket.id}\n\nHello ${member.asMention}! A staff member will be with you shortly.\n\nUse the buttons below to manage your ticket."
                val container = Container.of(TextDisplay.of(headerText)).withAccentColor(TagService.accentColor)
                val closeBtn = Button.danger("ticket_close:${ticket.id}", "Close Ticket")
                val claimBtn = Button.primary("ticket_claim:${ticket.id}", "Claim Ticket")

                channel.sendComponents(container).useComponentsV2()
                    .addComponents(ActionRow.of(closeBtn, claimBtn))
                    .queue(null) { err -> logger.warn("Failed to send ticket header: ${err.message}") }

                event.hook.editOriginal("Your ticket has been created: ${channel.asMention}").queue()
                logger.info("Guild ${guild.idLong}: Ticket #${ticket.id} created for ${member.id} in ${channel.id}")
            }, { err ->
                logger.error("Failed to create ticket channel: ${err.message}")
                event.hook.editOriginal("Failed to create ticket channel. Check my permissions.").queue()
            })
    }

    private fun handleTicketClose(event: ButtonInteractionEvent) {
        val guild = event.guild ?: return
        val member = event.member ?: return
        val ticket = TicketService.getByChannel(event.channel.idLong) ?: run {
            event.replyContainer("No ticket found for this channel.")
            return
        }

        val supportRoleId = TicketService.getSupportRoleId(guild.idLong)
        val isStaff = supportRoleId != null && member.roles.any { it.idLong == supportRoleId }
            || member.hasPermission(Permission.MANAGE_CHANNEL)

        if (ticket.userId != member.idLong && !isStaff) {
            event.replyContainer("Only the ticket owner or staff can close this ticket.")
            return
        }

        val confirmText = "Are you sure you want to close this ticket?"
        val container = Container.of(TextDisplay.of(confirmText)).withAccentColor(TagService.accentColor)
        val confirmBtn = Button.danger("ticket_confirm_close:${ticket.id}", "Confirm Close")

        event.replyComponents(container).useComponentsV2()
            .addComponents(ActionRow.of(confirmBtn))
            .setEphemeral(true).queue()
    }

    private fun handleTicketClaim(event: ButtonInteractionEvent) {
        val guild = event.guild ?: return
        val member = event.member ?: return
        val supportRoleId = TicketService.getSupportRoleId(guild.idLong)

        val isStaff = (supportRoleId != null && member.roles.any { it.idLong == supportRoleId })
            || member.hasPermission(Permission.MANAGE_CHANNEL)

        if (!isStaff) {
            event.replyContainer("Only staff members can claim tickets.")
            return
        }

        val claimed = TicketService.claim(guild.idLong, event.channel.idLong, member.idLong)
        if (claimed) {
            event.replyContainer("Ticket claimed by ${member.asMention}.")
        } else {
            event.replyContainer("Could not claim this ticket.")
        }
    }

    private fun handleTicketConfirmClose(event: ButtonInteractionEvent) {
        val guild = event.guild ?: return
        val channel = event.channel

        event.deferReply(true).queue()

        // Generate transcript before closing
        channel.getHistoryFromBeginning(100).queue({ history ->
            val sb = StringBuilder("# Ticket Transcript\n\nChannel: #${channel.name}\nGenerated: ${dateFormat.format(Date())}\n\n---\n\n")
            for (msg in history.retrievedHistory.reversed()) {
                val time = dateFormat.format(Date(msg.timeCreated.toEpochSecond() * 1000))
                sb.append("[$time] **${msg.author.name}**: ${msg.contentDisplay}\n")
            }

            val logChannelId = TicketService.getLogChannelId(guild.idLong)
            val bytes = sb.toString().toByteArray(Charsets.UTF_8)
            val upload = FileUpload.fromData(bytes, "transcript-${channel.name}.md")

            val sendTranscript: (() -> Unit) = {
                logChannelId?.let { logId ->
                    val logChannel = guild.getTextChannelById(logId)
                    logChannel?.sendMessage("Ticket closed: **#${channel.name}**")
                        ?.addFiles(upload)
                        ?.queue(null) { err -> logger.warn("Failed to send transcript: ${err.message}") }
                }
            }

            TicketService.close(guild.idLong, channel.idLong)
            sendTranscript()

            event.hook.editOriginal("Ticket closed. Deleting channel in 5 seconds...").queue()
            channel.delete().queueAfter(5, java.util.concurrent.TimeUnit.SECONDS, null) { err ->
                logger.warn("Failed to delete ticket channel: ${err.message}")
            }
        }, { err ->
            logger.error("Failed to fetch ticket history: ${err.message}")
            event.hook.editOriginal("Failed to generate transcript. Closing anyway.").queue()
            TicketService.close(guild.idLong, channel.idLong)
            channel.delete().queueAfter(5, java.util.concurrent.TimeUnit.SECONDS)
        })
    }

    private fun onTicket(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: run {
            event.replyContainer("This command can only be used in a server.")
            return
        }

        when (event.subcommandName) {
            "create" -> {
                val category = event.getOption("category")?.asString ?: "general"

                if (!TicketService.isEnabled(guild.idLong)) {
                    event.replyContainer("The ticket system is not enabled.")
                    return
                }

                val existing = TicketService.getUserOpenTicket(guild.idLong, event.user.idLong)
                if (existing != null) {
                    event.replyContainer("You already have an open ticket: <#${existing.channelId}>")
                    return
                }

                val openCount = TicketService.openTicketCount(guild.idLong)
                if (openCount >= TicketService.MAX_OPEN_TICKETS) {
                    event.replyContainer("Maximum open tickets reached. Please try again later.")
                    return
                }

                val member = event.member ?: run {
                    event.replyContainer("Could not resolve your member.")
                    return
                }

                event.deferReply(true).queue()

                val categoryId = TicketService.getCategory(guild.idLong)
                val supportRoleId = TicketService.getSupportRoleId(guild.idLong)

                val channelAction = if (categoryId != null) {
                    val cat = guild.getCategoryById(categoryId)
                    cat?.createTextChannel("ticket-${member.user.name.lowercase().take(20)}")
                        ?: guild.createTextChannel("ticket-${member.user.name.lowercase().take(20)}")
                } else {
                    guild.createTextChannel("ticket-${member.user.name.lowercase().take(20)}")
                }

                channelAction
                    .addPermissionOverride(guild.publicRole, null, EnumSet.of(Permission.VIEW_CHANNEL))
                    .addPermissionOverride(member, EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND), null)
                    .let { action ->
                        if (supportRoleId != null) {
                            val supportRole = guild.getRoleById(supportRoleId)
                            if (supportRole != null) {
                                action.addPermissionOverride(supportRole, EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND), null)
                            } else action
                        } else action
                    }
                    .queue({ channel ->
                        val ticket = TicketService.create(guild.idLong, channel.idLong, member.idLong, category)
                        val headerText = "### Ticket #${ticket.id} — $category\n\nHello ${member.asMention}! A staff member will be with you shortly."
                        val container = Container.of(TextDisplay.of(headerText)).withAccentColor(TagService.accentColor)
                        val closeBtn = Button.danger("ticket_close:${ticket.id}", "Close Ticket")
                        val claimBtn = Button.primary("ticket_claim:${ticket.id}", "Claim Ticket")
                        channel.sendComponents(container).useComponentsV2()
                            .addComponents(ActionRow.of(closeBtn, claimBtn))
                            .queue()
                        event.hook.editOriginal("Ticket created: ${channel.asMention}").queue()
                    }, { err ->
                        event.hook.editOriginal("Failed to create ticket: ${err.message}").queue()
                    })
            }

            "close" -> {
                val ticket = TicketService.getByChannel(event.channel.idLong) ?: run {
                    event.replyContainer("This is not a ticket channel.")
                    return
                }
                val member = event.member ?: return
                val supportRoleId = TicketService.getSupportRoleId(guild.idLong)
                val isStaff = (supportRoleId != null && member.roles.any { it.idLong == supportRoleId })
                    || member.hasPermission(Permission.MANAGE_CHANNEL)

                if (ticket.userId != member.idLong && !isStaff) {
                    event.replyContainer("Only the ticket owner or staff can close this ticket.")
                    return
                }

                val confirmText = "Are you sure you want to close this ticket?"
                val container = Container.of(TextDisplay.of(confirmText)).withAccentColor(TagService.accentColor)
                val confirmBtn = Button.danger("ticket_confirm_close:${ticket.id}", "Confirm Close")
                event.replyComponents(container).useComponentsV2()
                    .addComponents(ActionRow.of(confirmBtn))
                    .setEphemeral(true).queue()
            }

            "claim" -> {
                val ticket = TicketService.getByChannel(event.channel.idLong) ?: run {
                    event.replyContainer("This is not a ticket channel.")
                    return
                }
                val member = event.member ?: return
                val supportRoleId = TicketService.getSupportRoleId(guild.idLong)
                val isStaff = (supportRoleId != null && member.roles.any { it.idLong == supportRoleId })
                    || member.hasPermission(Permission.MANAGE_CHANNEL)

                if (!isStaff) {
                    event.replyContainer("Only staff members can claim tickets.")
                    return
                }

                val claimed = TicketService.claim(guild.idLong, event.channel.idLong, member.idLong)
                if (claimed) {
                    event.replyContainer("Ticket #${ticket.id} claimed by ${member.asMention}.")
                } else {
                    event.replyContainer("Could not claim this ticket.")
                }
            }

            "transcript" -> {
                val ticket = TicketService.getByChannel(event.channel.idLong) ?: run {
                    event.replyContainer("This is not a ticket channel.")
                    return
                }
                event.deferReply(true).queue()
                event.channel.getHistoryFromBeginning(100).queue({ history ->
                    val sb = StringBuilder("# Ticket Transcript\n\nChannel: #${event.channel.name}\nGenerated: ${dateFormat.format(Date())}\n\n---\n\n")
                    for (msg in history.retrievedHistory.reversed()) {
                        val time = dateFormat.format(Date(msg.timeCreated.toEpochSecond() * 1000))
                        sb.append("[$time] **${msg.author.name}**: ${msg.contentDisplay}\n")
                    }
                    val bytes = sb.toString().toByteArray(Charsets.UTF_8)
                    val upload = FileUpload.fromData(bytes, "transcript-${event.channel.name}.md")
                    event.hook.sendMessage("Transcript for ticket #${ticket.id}:").addFiles(upload).queue()
                }, { err ->
                    event.hook.editOriginal("Failed to generate transcript: ${err.message}").queue()
                })
            }

            "setup" -> {
                val member = event.member ?: return
                if (!member.hasPermission(Permission.MANAGE_GUILD)) {
                    event.replyContainer("You need Manage Server permission to set up tickets.")
                    return
                }

                val channelOption = event.getOption("channel")?.asChannel
                val channelId = channelOption?.idLong ?: event.channel.idLong
                val supportRole = event.getOption("supportrole")?.asRole
                val categoryOption = event.getOption("category")?.asChannel

                if (supportRole != null) TicketService.setSupportRoleId(guild.idLong, supportRole.idLong)
                if (categoryOption != null) TicketService.setCategory(guild.idLong, categoryOption.idLong)
                TicketService.setPanelChannelId(guild.idLong, channelId)
                TicketService.setEnabled(guild.idLong, true)

                // Post the ticket panel
                val targetChannel = guild.getTextChannelById(channelId) ?: run {
                    event.replyContainer("Could not find the specified channel.")
                    return
                }

                val headerText = "### Support Tickets\n\nClick the button below to open a support ticket. A staff member will assist you shortly."
                val container = Container.of(TextDisplay.of(headerText)).withAccentColor(TagService.accentColor)
                val createBtn = Button.primary("ticket_create", "Open Ticket")

                targetChannel.sendComponents(container).useComponentsV2()
                    .addComponents(ActionRow.of(createBtn))
                    .queue({
                        event.replyContainer("Ticket system set up in ${targetChannel.asMention}.")
                    }, { err ->
                        event.replyContainer("Failed to post panel: ${err.message}")
                    })
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
