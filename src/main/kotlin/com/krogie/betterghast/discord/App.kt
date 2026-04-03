package com.krogie.betterghast.discord

import com.krogie.betterghast.community.*
import com.krogie.betterghast.config.AppConfig
import com.krogie.betterghast.moderation.*
import com.krogie.betterghast.tags.TagListener
import com.krogie.betterghast.util.GuildUtil
import dev.minn.jda.ktx.events.listener
import dev.minn.jda.ktx.jdabuilder.light
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.guild.GuildReadyEvent
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.requests.GatewayIntent
import org.slf4j.LoggerFactory

class App(private val config: AppConfig) {

    private val logger = LoggerFactory.getLogger(App::class.java)
    private lateinit var jda: JDA
    private lateinit var allCommands: List<CommandData>

    fun start() {
        jda = light(config.discordToken, enableCoroutines = true) {
            enableIntents(
                GatewayIntent.GUILD_MESSAGES,
                GatewayIntent.MESSAGE_CONTENT,
                GatewayIntent.GUILD_MEMBERS
            )
        }

        jda.awaitReady()
        allCommands = buildCommands()
        deployCommandsToAllGuilds(jda)
        registerListeners(jda)
        registerGuildEvents(jda)
        logger.info("BetterGhast v3.0 is ready")
    }

    fun shutdown() {
        if (::jda.isInitialized) {
            jda.shutdown()
            logger.info("JDA shut down")
        }
    }

    private fun registerListeners(jda: JDA) {
        TagListener.register(jda)
        AutoResponseListener.register(jda)
        WarningListener.register(jda)
        AntiSpamListener.register(jda)
        WelcomeListener.register(jda)
        ReactionRoleListener.register(jda)
        TicketListener.register(jda)
        LevelingListener.register(jda)
        PollListener.register(jda)
        AskListener.register(jda)
    }

    private fun registerGuildEvents(jda: JDA) {
        // When bot joins a new server: register commands + ensure guild in DB
        jda.listener<GuildJoinEvent> {
            val guild = it.guild
            GuildUtil.ensureGuild(guild.idLong)
            if (config.allowedGuilds.isEmpty() || guild.idLong in config.allowedGuilds) {
                guild.updateCommands().addCommands(allCommands).queue(
                    { logger.info("Commands deployed for new guild ${guild.id} (${guild.name})") },
                    { err -> logger.error("Failed to deploy commands for ${guild.id}", err) }
                )
            }
        }
        // Ensure guild exists in DB when bot loads existing guilds
        jda.listener<GuildReadyEvent> {
            GuildUtil.ensureGuild(it.guild.idLong)
        }
    }

    private fun deployCommandsToAllGuilds(jda: JDA) {
        for (guild in jda.guilds) {
            GuildUtil.ensureGuild(guild.idLong)
            if (config.allowedGuilds.isEmpty() || guild.idLong in config.allowedGuilds) {
                guild.updateCommands().addCommands(allCommands).queue(
                    { logger.info("Commands deployed for ${guild.id} (${guild.name})") },
                    { logger.error("Failed to deploy commands for ${guild.id} (${guild.name})", it) }
                )
            } else {
                guild.updateCommands().queue(null) { error ->
                    logger.error("Failed to clear commands for ${guild.id}", error)
                }
            }
        }
    }

    private fun buildCommands(): List<CommandData> {
        // ── /tags (with all subcommands) ──
        val tagsCmd = Commands.slash("tags", "Manage tags for this server.")
            .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MESSAGE_MANAGE))
            .addSubcommands(
                SubcommandData("manage", "Create, edit or delete tags.")
                    .addOption(OptionType.STRING, "name", "The tag keyword.", true, true)
                    .addOption(OptionType.BOOLEAN, "remove", "Permanently delete this tag.", false),
                SubcommandData("show", "Find and display tags."),
                SubcommandData("search", "Search tags by keyword or content.")
                    .addOption(OptionType.STRING, "query", "The search term.", true),
                SubcommandData("stats", "Show tag usage statistics."),
                SubcommandData("info", "Show detailed info about a specific tag.")
                    .addOption(OptionType.STRING, "name", "The tag keyword.", true, true),
                SubcommandData("export", "Export all tags as JSON."),
                SubcommandData("analytics", "View tag usage analytics."),
                SubcommandData("permissions", "Set or view tag role restrictions.")
                    .addOption(OptionType.STRING, "name", "The tag keyword.", true, true)
                    .addOption(OptionType.ROLE, "role", "Role required to use this tag.", false)
            )

        // ── /autoresponse ──
        val autoResponseCmd = Commands.slash("autoresponse", "Manage auto-response triggers.")
            .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
            .addSubcommands(
                SubcommandData("add", "Add an auto-response trigger.")
                    .addOption(OptionType.STRING, "pattern", "Keyword or regex pattern.", true)
                    .addOption(OptionType.STRING, "tag", "Tag to send when triggered.", true)
                    .addOption(OptionType.BOOLEAN, "regex", "Use regex matching.", false)
                    .addOption(OptionType.CHANNEL, "channel", "Limit to a specific channel.", false),
                SubcommandData("remove", "Remove an auto-response trigger.")
                    .addOption(OptionType.INTEGER, "id", "Trigger ID.", true),
                SubcommandData("list", "List all auto-response triggers."),
                SubcommandData("toggle", "Enable/disable a trigger.")
                    .addOption(OptionType.INTEGER, "id", "Trigger ID.", true)
            )

        // ── /warn ──
        val warnCmd = Commands.slash("warn", "Issue a warning to a user.")
            .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MODERATE_MEMBERS))
            .addOption(OptionType.USER, "user", "The user to warn.", true)
            .addOption(OptionType.STRING, "reason", "Reason for the warning.", true)

        // ── /warnings ──
        val warningsCmd = Commands.slash("warnings", "View warnings for a user.")
            .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MODERATE_MEMBERS))
            .addOption(OptionType.USER, "user", "The user to check.", true)

        // ── /clearwarning ──
        val clearWarningCmd = Commands.slash("clearwarning", "Remove a warning.")
            .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MODERATE_MEMBERS))
            .addOption(OptionType.INTEGER, "id", "Warning ID to remove.", true)

        // ── /antispam ──
        val antispamCmd = Commands.slash("antispam", "Configure anti-spam settings.")
            .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
            .addSubcommands(
                SubcommandData("toggle", "Enable or disable anti-spam."),
                SubcommandData("ratelimit", "Set rate limit settings.")
                    .addOption(OptionType.INTEGER, "messages", "Max messages in window.", false)
                    .addOption(OptionType.INTEGER, "window", "Window in milliseconds.", false),
                SubcommandData("linkfilter", "Toggle link filtering.")
                    .addOption(OptionType.BOOLEAN, "enabled", "Enable link filter.", true),
                SubcommandData("invitefilter", "Toggle invite filtering.")
                    .addOption(OptionType.BOOLEAN, "enabled", "Enable invite filter.", true),
                SubcommandData("whitelist", "Add or remove from whitelist.")
                    .addOption(OptionType.STRING, "action", "add or remove", true)
                    .addOption(OptionType.STRING, "domain", "Domain to whitelist.", true),
                SubcommandData("status", "Show current anti-spam config.")
            )

        // ── /welcome ──
        val welcomeCmd = Commands.slash("welcome", "Configure welcome messages.")
            .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
            .addSubcommands(
                SubcommandData("channel", "Set welcome channel.")
                    .addOption(OptionType.CHANNEL, "channel", "The welcome channel.", true),
                SubcommandData("message", "Set join message.")
                    .addOption(OptionType.STRING, "text", "Message with {user}, {server}, etc.", true),
                SubcommandData("leave", "Set leave message.")
                    .addOption(OptionType.STRING, "text", "Leave message.", true),
                SubcommandData("autorole", "Set auto-assign role on join.")
                    .addOption(OptionType.ROLE, "role", "Role to assign.", true),
                SubcommandData("dm", "Configure join DM.")
                    .addOption(OptionType.BOOLEAN, "enabled", "Enable DM on join.", true)
                    .addOption(OptionType.STRING, "text", "DM message text.", false),
                SubcommandData("test", "Preview the welcome message."),
                SubcommandData("status", "Show current welcome config.")
            )

        // ── /rolepanel ──
        val rolepanelCmd = Commands.slash("rolepanel", "Manage reaction role panels.")
            .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_ROLES))
            .addSubcommands(
                SubcommandData("create", "Create a new role panel.")
                    .addOption(OptionType.STRING, "title", "Panel title.", true)
                    .addOption(OptionType.STRING, "style", "buttons or dropdown.", false),
                SubcommandData("addrole", "Add a role to a panel.")
                    .addOption(OptionType.INTEGER, "panel", "Panel ID.", true)
                    .addOption(OptionType.ROLE, "role", "Role to add.", true)
                    .addOption(OptionType.STRING, "label", "Button/option label.", true)
                    .addOption(OptionType.STRING, "emoji", "Emoji for the button.", false),
                SubcommandData("send", "Send the panel to the current channel.")
                    .addOption(OptionType.INTEGER, "panel", "Panel ID.", true),
                SubcommandData("delete", "Delete a role panel.")
                    .addOption(OptionType.INTEGER, "panel", "Panel ID.", true),
                SubcommandData("list", "List all role panels.")
            )

        // ── /ticket ──
        val ticketCmd = Commands.slash("ticket", "Manage support tickets.")
            .addSubcommands(
                SubcommandData("create", "Open a new ticket.")
                    .addOption(OptionType.STRING, "category", "Ticket category.", false),
                SubcommandData("close", "Close this ticket."),
                SubcommandData("claim", "Claim this ticket."),
                SubcommandData("transcript", "Generate a transcript of this ticket."),
                SubcommandData("setup", "Set up ticket system (admin).")
                    .addOption(OptionType.ROLE, "supportrole", "Support team role.", true)
                    .addOption(OptionType.STRING, "categories", "Comma-separated categories.", false)
            )

        // ── /rank ──
        val rankCmd = Commands.slash("rank", "View your level and XP.")
            .addOption(OptionType.USER, "user", "User to check.", false)

        // ── /top ──
        val topCmd = Commands.slash("top", "View the server leaderboard.")
            .addOption(OptionType.INTEGER, "page", "Page number.", false)

        // ── /xp ──
        val xpCmd = Commands.slash("xp", "Configure the leveling system.")
            .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
            .addSubcommands(
                SubcommandData("toggle", "Enable or disable the leveling system."),
                SubcommandData("addrole", "Add a level reward role.")
                    .addOption(OptionType.INTEGER, "level", "Level required.", true)
                    .addOption(OptionType.ROLE, "role", "Role to assign.", true),
                SubcommandData("removerole", "Remove a level reward role.")
                    .addOption(OptionType.INTEGER, "level", "Level to remove.", true),
                SubcommandData("multiplier", "Set XP multiplier for a channel.")
                    .addOption(OptionType.CHANNEL, "channel", "Channel.", true)
                    .addOption(OptionType.NUMBER, "multiplier", "XP multiplier (e.g. 1.5).", true),
                SubcommandData("status", "Show leveling config.")
            )

        // ── /poll ──
        val pollCmd = Commands.slash("poll", "Create a poll.")
            .addOption(OptionType.STRING, "question", "The poll question.", true)
            .addOption(OptionType.STRING, "options", "Comma-separated options.", true)
            .addOption(OptionType.STRING, "duration", "Duration (e.g. 1h, 30m, 1d).", false)
            .addOption(OptionType.BOOLEAN, "anonymous", "Hide voter names.", false)
            .addOption(OptionType.BOOLEAN, "multichoice", "Allow multiple choices.", false)

        // ── /help ──
        val helpCmd = Commands.slash("help", "Show all BetterGhast commands and documentation.")

        // ── /ask ──
        val askCmd = Commands.slash("ask", "Ask KrogieBot (AI) a question about BetterGhast.")
            .addOption(OptionType.STRING, "question", "Your question.", true)

        return listOf(
            tagsCmd, autoResponseCmd, warnCmd, warningsCmd, clearWarningCmd,
            antispamCmd, welcomeCmd, rolepanelCmd, ticketCmd,
            rankCmd, topCmd, xpCmd, pollCmd, helpCmd, askCmd
        )
    }
}
