package com.krogie.betterghast

import com.krogie.betterghast.config.AppConfig
import com.krogie.betterghast.db.DatabaseFactory
import com.krogie.betterghast.discord.App
import com.krogie.betterghast.tags.TagService
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.krogie.betterghast.Main")

fun main() {
    try {
        val config = AppConfig.load()
        TagService.init(config)
        DatabaseFactory.init(config)

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