package com.krogie.betterghast.util

import com.krogie.betterghast.db.GuildSettings
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert

object GuildSettingsService {

    fun get(guildId: Long, module: String, key: String): String? {
        return transaction {
            GuildSettings.selectAll().where {
                (GuildSettings.guildId eq guildId) and
                (GuildSettings.module eq module) and
                (GuildSettings.settingKey eq key)
            }.firstOrNull()?.get(GuildSettings.settingValue)
        }
    }

    fun getBoolean(guildId: Long, module: String, key: String, default: Boolean = false): Boolean {
        return get(guildId, module, key)?.toBooleanStrictOrNull() ?: default
    }

    fun getLong(guildId: Long, module: String, key: String, default: Long = 0): Long {
        return get(guildId, module, key)?.toLongOrNull() ?: default
    }

    fun set(guildId: Long, module: String, key: String, value: String) {
        transaction {
            GuildSettings.upsert {
                it[GuildSettings.guildId] = guildId
                it[GuildSettings.module] = module
                it[GuildSettings.settingKey] = key
                it[GuildSettings.settingValue] = value
            }
        }
    }

    fun delete(guildId: Long, module: String, key: String) {
        transaction {
            GuildSettings.deleteWhere {
                (GuildSettings.guildId eq guildId) and
                (GuildSettings.module eq module) and
                (GuildSettings.settingKey eq key)
            }
        }
    }

    fun getAll(guildId: Long, module: String): Map<String, String> {
        return transaction {
            GuildSettings.selectAll().where {
                (GuildSettings.guildId eq guildId) and (GuildSettings.module eq module)
            }.associate { it[GuildSettings.settingKey] to it[GuildSettings.settingValue] }
        }
    }
}
