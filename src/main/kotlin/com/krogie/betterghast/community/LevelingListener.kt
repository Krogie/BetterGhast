package com.krogie.betterghast.community

import com.krogie.betterghast.tags.TagService
import dev.minn.jda.ktx.events.listener
import dev.minn.jda.ktx.events.onCommand
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import org.slf4j.LoggerFactory

object LevelingListener {

    private val logger = LoggerFactory.getLogger(LevelingListener::class.java)
    private const val PROGRESS_BAR_LENGTH = 20

    fun register(jda: JDA) {
        jda.listener<MessageReceivedEvent> { onMessage(it) }
        jda.onCommand("rank") { onRank(it as SlashCommandInteractionEvent) }
        jda.onCommand("top") { onTop(it as SlashCommandInteractionEvent) }
        jda.onCommand("xp") { onXp(it as SlashCommandInteractionEvent) }
    }

    private fun onMessage(event: MessageReceivedEvent) {
        if (event.author.isBot || !event.isFromGuild) return
        val guild = event.guild
        val member = event.member ?: return

        if (!LevelingService.isEnabled(guild.idLong)) return

        val result = LevelingService.tryAwardXp(guild.idLong, member.idLong) ?: return
        val (newLevel, isLevelUp) = result

        if (isLevelUp) {
            logger.info("Guild ${guild.idLong}: ${member.user.name} reached level $newLevel")

            // Assign level role if configured
            val roleId = LevelingService.getRoleForLevel(guild.idLong, newLevel)
            if (roleId != null) {
                val role = guild.getRoleById(roleId)
                if (role != null) {
                    guild.addRoleToMember(member, role).queue(null) { err ->
                        logger.warn("Failed to add level role $roleId: ${err.message}")
                    }
                }
            }

            // Announce level-up
            val announceChannelId = LevelingService.getAnnounceChannelId(guild.idLong)
            val targetChannel = if (announceChannelId != null) {
                guild.getTextChannelById(announceChannelId)
            } else {
                event.channel as? net.dv8tion.jda.api.entities.channel.concrete.TextChannel
            }

            targetChannel?.let { ch ->
                val text = "${member.asMention} levelled up to **Level $newLevel**!"
                val container = Container.of(TextDisplay.of(text)).withAccentColor(TagService.accentColor)
                ch.sendComponents(container).useComponentsV2().queue(null) { err ->
                    logger.warn("Failed to send level-up message: ${err.message}")
                }
            }
        }
    }

    private fun onRank(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: run {
            event.replyContainer("This command can only be used in a server.")
            return
        }

        val targetUser = event.getOption("user")?.asUser ?: event.user
        val userLevel = LevelingService.getUserLevel(guild.idLong, targetUser.idLong)

        if (userLevel == null) {
            event.replyContainer("**${targetUser.name}** has not earned any XP yet.")
            return
        }

        val rank = LevelingService.getRank(guild.idLong, targetUser.idLong)
        val currentXp = userLevel.xp
        val currentLevel = userLevel.level
        val xpForCurrentLevel = LevelingService.xpForLevel(currentLevel)
        val xpForNextLevel = LevelingService.xpForLevel(currentLevel + 1)
        val progress = currentXp - xpForCurrentLevel
        val needed = xpForNextLevel - xpForCurrentLevel
        val filled = if (needed > 0) ((progress.toDouble() / needed) * PROGRESS_BAR_LENGTH).toInt().coerceIn(0, PROGRESS_BAR_LENGTH) else PROGRESS_BAR_LENGTH
        val bar = "█".repeat(filled) + "░".repeat(PROGRESS_BAR_LENGTH - filled)

        val sb = StringBuilder()
        sb.append("### ${targetUser.name}'s Rank\n\n")
        sb.append("**Rank:** #$rank\n")
        sb.append("**Level:** $currentLevel\n")
        sb.append("**XP:** $currentXp (${progress}/${needed} to next level)\n")
        sb.append("**Messages:** ${userLevel.totalMessages}\n\n")
        sb.append("`$bar`")

        event.replyContainer(sb.toString())
    }

    private fun onTop(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: run {
            event.replyContainer("This command can only be used in a server.")
            return
        }

        val page = ((event.getOption("page")?.asLong ?: 1L) - 1L).coerceAtLeast(0).toInt()
        val pageSize = 10
        val leaderboard = LevelingService.getLeaderboard(guild.idLong, page, pageSize)

        if (leaderboard.isEmpty()) {
            if (page == 0) {
                event.replyContainer("No users have earned XP yet.")
            } else {
                event.replyContainer("No more entries on page ${page + 1}.")
            }
            return
        }

        val sb = StringBuilder("### Leaderboard — Page ${page + 1}\n\n")
        val startRank = page * pageSize + 1

        for ((index, ul) in leaderboard.withIndex()) {
            val rank = startRank + index
            val medal = when (rank) {
                1 -> "1."
                2 -> "2."
                3 -> "3."
                else -> "$rank."
            }
            sb.append("$medal <@${ul.userId}> — Level ${ul.level} (${ul.xp} XP)\n")
        }

        event.replyContainer(sb.toString())
    }

    private fun onXp(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: run {
            event.replyContainer("This command can only be used in a server.")
            return
        }
        val member = event.member ?: return

        when (event.subcommandName) {
            "toggle" -> {
                if (!member.hasPermission(Permission.MANAGE_GUILD)) {
                    event.replyContainer("You need Manage Server permission.")
                    return
                }
                val enabled = LevelingService.isEnabled(guild.idLong)
                LevelingService.setEnabled(guild.idLong, !enabled)
                event.replyContainer("Leveling system ${if (!enabled) "enabled" else "disabled"}.")
            }

            "addrole" -> {
                if (!member.hasPermission(Permission.MANAGE_GUILD)) {
                    event.replyContainer("You need Manage Server permission.")
                    return
                }
                val level = event.getOption("level")?.asLong?.toInt() ?: run {
                    event.replyContainer("Please provide a level.")
                    return
                }
                val role = event.getOption("role")?.asRole ?: run {
                    event.replyContainer("Please provide a role.")
                    return
                }
                LevelingService.setLevelRole(guild.idLong, level, role.idLong)
                event.replyContainer("Level role set: reaching level **$level** will grant ${role.asMention}.")
            }

            "removerole" -> {
                if (!member.hasPermission(Permission.MANAGE_GUILD)) {
                    event.replyContainer("You need Manage Server permission.")
                    return
                }
                val level = event.getOption("level")?.asLong?.toInt() ?: run {
                    event.replyContainer("Please provide a level.")
                    return
                }
                val removed = LevelingService.removeLevelRole(guild.idLong, level)
                if (removed) {
                    event.replyContainer("Level role for level **$level** removed.")
                } else {
                    event.replyContainer("No level role configured for level **$level**.")
                }
            }

            "multiplier" -> {
                if (!member.hasPermission(Permission.MANAGE_GUILD)) {
                    event.replyContainer("You need Manage Server permission.")
                    return
                }
                val multiplier = event.getOption("value")?.asString?.toDoubleOrNull() ?: run {
                    event.replyContainer("Please provide a valid multiplier (e.g. `1.5`).")
                    return
                }
                if (multiplier < 0.1 || multiplier > 10.0) {
                    event.replyContainer("Multiplier must be between 0.1 and 10.0.")
                    return
                }
                LevelingService.setMultiplier(guild.idLong, multiplier)
                event.replyContainer("XP multiplier set to **${multiplier}x**.")
            }

            "status" -> {
                val enabled = LevelingService.isEnabled(guild.idLong)
                val multiplier = LevelingService.getMultiplier(guild.idLong)
                val announceChannelId = LevelingService.getAnnounceChannelId(guild.idLong)
                val levelRoles = LevelingService.getLevelRoles(guild.idLong)

                val sb = StringBuilder("### Leveling Status\n\n")
                sb.append("**Enabled:** $enabled\n")
                sb.append("**XP multiplier:** ${multiplier}x\n")
                sb.append("**Announce channel:** ${announceChannelId?.let { "<#$it>" } ?: "current channel"}\n")
                sb.append("**Level roles:** ${levelRoles.size} configured\n")

                if (levelRoles.isNotEmpty()) {
                    sb.append("\n**Role thresholds:**\n")
                    levelRoles.entries.sortedBy { it.key }.forEach { (level, roleId) ->
                        sb.append("Level $level → <@&$roleId>\n")
                    }
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
