package com.krogie.betterghast.config

data class DbConfig(
    val host: String,
    val port: Int,
    val database: String,
    val user: String,
    val password: String,
    val useSsl: Boolean
) {
    companion object {
        fun fromConfig(): DbConfig {
            return DbConfig(
                host = Config.get("DB_HOST"),
                port = Config.getInt("DB_PORT", 3306),
                database = Config.get("DB_NAME"),
                user = Config.get("DB_USER"),
                password = Config.get("DB_PASSWORD"),
                useSsl = Config.getBoolean("DB_SSL", false)
            )
        }
    }
}