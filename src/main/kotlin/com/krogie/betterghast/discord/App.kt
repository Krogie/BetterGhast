package com.krogie.betterghast.discord

import com.krogie.betterghast.config.AppConfig
import com.krogie.betterghast.tags.TagListener
import dev.minn.jda.ktx.jdabuilder.light
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.requests.GatewayIntent
import org.slf4j.LoggerFactory

class App(private val config: AppConfig) {

    private val logger = LoggerFactory.getLogger(App::class.java)
    private lateinit var jda: JDA

    fun start() {
        jda = light(config.discordToken, enableCoroutines = true) {
            enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
        }

        jda.awaitReady()
        registerCommands(jda)
        TagListener.register(jda)
        logger.info("BetterGhast v2.0 is ready")
    }

    fun shutdown() {
        if (::jda.isInitialized) {
            jda.shutdown()
            logger.info("JDA shut down")
        }
    }

    private fun registerCommands(jda: JDA) {
        val commandData = Commands.slash("tags", "Manage tags for this server.")
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
                SubcommandData("export", "Export all tags as JSON.")
            )

        for (guild in jda.guilds) {
            if (guild.idLong in config.allowedGuilds) {
                guild.upsertCommand(commandData).queue(
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
}