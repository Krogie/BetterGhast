package com.krogie.betterghast.config

data class AppConfig(
    val discordToken: String,
    val allowedGuilds: Set<Long>,
    val tagCooldown: Long,
    val accentColor: Int,
    val db: DbConfig,
    // Warnings
    val warnDecayDays: Int,
    val warnThresholds: Map<Int, String>,
    // Anti-Spam
    val antiSpamRateLimit: Int,
    val antiSpamRateWindow: Long,
    // Leveling
    val levelingXpMin: Int,
    val levelingXpMax: Int,
    val levelingCooldown: Long,
    // AI
    val claudeApiKey: String
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

            // Parse warn thresholds: "3:mute,5:kick,7:ban"
            val thresholds = Config.get("WARN_THRESHOLDS", "3:mute,5:kick,7:ban")
                .split(",")
                .mapNotNull { entry ->
                    val parts = entry.trim().split(":")
                    if (parts.size == 2) parts[0].toIntOrNull()?.let { it to parts[1].trim() } else null
                }
                .toMap()

            return AppConfig(
                discordToken = Config.get("DISCORD_TOKEN"),
                allowedGuilds = guilds,
                tagCooldown = Config.getLong("TAG_COOLDOWN_MS", 2500L),
                accentColor = color,
                db = DbConfig.fromConfig(),
                warnDecayDays = Config.getInt("WARN_DECAY_DAYS", 30),
                warnThresholds = thresholds,
                antiSpamRateLimit = Config.getInt("ANTISPAM_RATE_LIMIT", 5),
                antiSpamRateWindow = Config.getLong("ANTISPAM_RATE_WINDOW", 5000L),
                levelingXpMin = Config.getInt("LEVELING_XP_MIN", 15),
                levelingXpMax = Config.getInt("LEVELING_XP_MAX", 25),
                levelingCooldown = Config.getLong("LEVELING_COOLDOWN", 60000L),
                claudeApiKey = Config.get("CLAUDE_API_KEY", "")
            )
        }
    }
}
