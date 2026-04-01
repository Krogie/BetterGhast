package com.krogie.betterghast

import com.krogie.betterghast.community.LevelingService
import com.krogie.betterghast.community.PollService
import com.krogie.betterghast.config.AppConfig
import com.krogie.betterghast.db.DatabaseFactory
import com.krogie.betterghast.discord.App
import com.krogie.betterghast.moderation.AntiSpamService
import com.krogie.betterghast.moderation.AutoResponseService
import com.krogie.betterghast.moderation.WarningService
import com.krogie.betterghast.tags.TagService
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.krogie.betterghast.Main")

fun main() {
    try {
        val config = AppConfig.load()

        // Core
        TagService.init(config)
        DatabaseFactory.init(config)

        // Moderation
        AutoResponseService.init()
        WarningService.init(config)
        AntiSpamService.init(config)

        // Community
        LevelingService.init(config)

        // Background jobs
        WarningService.startDecayJob()
        PollService.startExpiryJob()

        val app = App(config)
        Runtime.getRuntime().addShutdownHook(Thread {
            app.shutdown()
            DatabaseFactory.shutdown()
            logger.info("BetterGhast shutdown complete")
        })
        app.start()
    } catch (e: Exception) {
        logger.error("Failed to start BetterGhast", e)
        System.exit(1)
    }
}
