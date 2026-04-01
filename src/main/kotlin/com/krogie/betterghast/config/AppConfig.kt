package com.krogie.betterghast.config

data class AppConfig(
    val discordToken: String,
    val allowedGuilds: Set<Long>,
    val tagCooldown: Long,
    val accentColor: Int,
    val db: DbConfig
) {
    companion object {
        fun load(): AppConfig {
            val guildsRaw = Config.get("ALLOWED_GUILDS", "")
            val guilds = guildsRaw.split(",")
                .mapNotNull { it.trim().toLongOrNull() }
                .toSet()

            val colorHex = Config.get("ACCENT_COLOR", "B5C8B4")
            val color = runCatching { colorHex.removePrefix("#").toInt(16) }.getOrElse {
                System.err.println("Invalid ACCENT_COLOR '$colorHex', falling back to default B5C8B4")
                0xB5C8B4
            }

            return AppConfig(
                discordToken = Config.get("DISCORD_TOKEN"),
                allowedGuilds = guilds,
                tagCooldown = Config.getLong("TAG_COOLDOWN_MS", 2500L),
                accentColor = color,
                db = DbConfig.fromConfig()
            )
        }
    }
}