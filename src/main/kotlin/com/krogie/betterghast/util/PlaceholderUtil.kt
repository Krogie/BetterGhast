package com.krogie.betterghast.util

import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object PlaceholderUtil {
    private val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    fun replace(
        text: String,
        member: Member? = null,
        channel: GuildMessageChannel? = null,
        mentionedUser: Member? = null
    ): String {
        var result = text
        if (member != null) {
            result = result
                .replace("{user}", member.effectiveName)
                .replace("{user.tag}", member.user.name)
                .replace("{user.mention}", member.asMention)
                .replace("{user.id}", member.id)
        }
        if (channel != null) {
            result = result
                .replace("{channel}", channel.name)
                .replace("{channel.mention}", channel.asMention)
        }
        if (member?.guild != null) {
            result = result
                .replace("{server}", member.guild.name)
                .replace("{server.members}", member.guild.memberCount.toString())
        }
        if (mentionedUser != null) {
            result = result
                .replace("{mention}", mentionedUser.asMention)
                .replace("{mention.name}", mentionedUser.effectiveName)
        }
        result = result.replace("{date}", LocalDateTime.now().format(formatter))
        return result
    }
}
