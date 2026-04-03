package com.krogie.betterghast.util

import com.krogie.betterghast.db.Guilds
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

object GuildUtil {
    fun ensureGuild(guildId: Long) {
        transaction {
            if (Guilds.selectAll().where { Guilds.id eq guildId }.empty()) {
                Guilds.insertIgnore { it[id] = guildId }
            }
        }
    }
}
