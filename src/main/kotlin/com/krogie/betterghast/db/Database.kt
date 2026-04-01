package com.krogie.betterghast.db

import com.krogie.betterghast.config.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Connection

object DatabaseFactory {
    private var dataSource: HikariDataSource? = null

    fun init(config: AppConfig) {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = "jdbc:mariadb://${config.db.host}:${config.db.port}/${config.db.database}?useUnicode=true&characterEncoding=utf8&connectionCollation=utf8mb4_general_ci"
            driverClassName = "org.mariadb.jdbc.Driver"
            username = config.db.user
            password = config.db.password

            maximumPoolSize = 15
            minimumIdle = 3
            connectionTimeout = 5000
            leakDetectionThreshold = 5000
            poolName = "BetterGhast-Pool"
        }

        val ds = HikariDataSource(hikariConfig)
        dataSource = ds
        val db = Database.connect(ds)

        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_REPEATABLE_READ

        transaction(db) {
            SchemaUtils.create(
                Guilds, Tags, TagUsageLogs, GuildSettings,
                AutoResponses, Warnings,
                ReactionRolePanels, ReactionRoleEntries,
                Tickets, UserLevels, LevelRoles,
                Polls, PollOptions, PollVotes
            )
        }
    }

    fun shutdown() {
        dataSource?.close()
        dataSource = null
    }
}

// ── Core Tables ──

object Guilds : Table("guilds") {
    val id = long("guildId")
    override val primaryKey = PrimaryKey(id)
}

object Tags : Table("tags") {
    val guildId = long("guildId").references(Guilds.id, onDelete = ReferenceOption.CASCADE)
    val primaryKeyword = varchar("tagPrimaryKeyword", 200)
    val keywords = varchar("tagKeywords", 200)
    val content = varchar("tagContent", 4000)
    val style = varchar("tagStyle", 50)
    val usages = long("usages").default(0)
    val creatorId = long("creatorId").nullable().default(null)
    val requiredRoleId = long("requiredRoleId").nullable().default(null)
    val createdAt = long("createdAt").default(0)

    override val primaryKey = PrimaryKey(guildId, primaryKeyword)
}

object TagUsageLogs : Table("tag_usage_logs") {
    val id = long("id").autoIncrement()
    val guildId = long("guildId").references(Guilds.id, onDelete = ReferenceOption.CASCADE)
    val tagPrimary = varchar("tagPrimary", 200)
    val userId = long("userId")
    val channelId = long("channelId")
    val usedAt = long("usedAt")

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, guildId, usedAt)
        index(false, guildId, tagPrimary)
    }
}

object GuildSettings : Table("guild_settings") {
    val guildId = long("guildId").references(Guilds.id, onDelete = ReferenceOption.CASCADE)
    val module = varchar("module", 50)
    val settingKey = varchar("settingKey", 100)
    val settingValue = varchar("settingValue", 2000)

    override val primaryKey = PrimaryKey(guildId, module, settingKey)
}

// ── Auto-Moderation Tables ──

object AutoResponses : Table("auto_responses") {
    val id = long("id").autoIncrement()
    val guildId = long("guildId").references(Guilds.id, onDelete = ReferenceOption.CASCADE)
    val pattern = varchar("pattern", 500)
    val isRegex = bool("isRegex").default(false)
    val tagPrimary = varchar("tagPrimary", 200)
    val channelId = long("channelId").nullable().default(null)
    val cooldownMs = long("cooldownMs").default(30000)
    val enabled = bool("enabled").default(true)

    override val primaryKey = PrimaryKey(id)
}

object Warnings : Table("warnings") {
    val id = long("id").autoIncrement()
    val guildId = long("guildId").references(Guilds.id, onDelete = ReferenceOption.CASCADE)
    val userId = long("userId")
    val moderatorId = long("moderatorId")
    val reason = varchar("reason", 1000)
    val issuedAt = long("issuedAt")
    val expiresAt = long("expiresAt").nullable().default(null)
    val active = bool("active").default(true)

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, guildId, userId, active)
    }
}

// ── Community Tables ──

object ReactionRolePanels : Table("reaction_role_panels") {
    val id = long("id").autoIncrement()
    val guildId = long("guildId").references(Guilds.id, onDelete = ReferenceOption.CASCADE)
    val channelId = long("channelId")
    val messageId = long("messageId").default(0)
    val title = varchar("title", 200)
    val style = varchar("style", 50).default("buttons")

    override val primaryKey = PrimaryKey(id)
}

object ReactionRoleEntries : Table("reaction_role_entries") {
    val id = long("id").autoIncrement()
    val panelId = long("panelId").references(ReactionRolePanels.id, onDelete = ReferenceOption.CASCADE)
    val roleId = long("roleId")
    val label = varchar("label", 100)
    val emoji = varchar("emoji", 100).nullable().default(null)
    val description = varchar("description", 200).nullable().default(null)

    override val primaryKey = PrimaryKey(id)
}

object Tickets : Table("tickets") {
    val id = long("id").autoIncrement()
    val guildId = long("guildId").references(Guilds.id, onDelete = ReferenceOption.CASCADE)
    val channelId = long("channelId")
    val userId = long("userId")
    val claimedBy = long("claimedBy").nullable().default(null)
    val category = varchar("category", 100).default("general")
    val status = varchar("status", 50).default("open")
    val createdAt = long("createdAt")
    val closedAt = long("closedAt").nullable().default(null)

    override val primaryKey = PrimaryKey(id)
}

object UserLevels : Table("user_levels") {
    val guildId = long("guildId").references(Guilds.id, onDelete = ReferenceOption.CASCADE)
    val userId = long("userId")
    val xp = long("xp").default(0)
    val level = integer("level").default(0)
    val totalMessages = long("totalMessages").default(0)
    val lastXpGain = long("lastXpGain").default(0)

    override val primaryKey = PrimaryKey(guildId, userId)
}

object LevelRoles : Table("level_roles") {
    val guildId = long("guildId").references(Guilds.id, onDelete = ReferenceOption.CASCADE)
    val level = integer("level")
    val roleId = long("roleId")

    override val primaryKey = PrimaryKey(guildId, level)
}

object Polls : Table("polls") {
    val id = long("id").autoIncrement()
    val guildId = long("guildId").references(Guilds.id, onDelete = ReferenceOption.CASCADE)
    val channelId = long("channelId")
    val messageId = long("messageId").default(0)
    val creatorId = long("creatorId")
    val question = varchar("question", 500)
    val multiChoice = bool("multiChoice").default(false)
    val anonymous = bool("anonymous").default(false)
    val endsAt = long("endsAt").nullable().default(null)
    val closed = bool("closed").default(false)

    override val primaryKey = PrimaryKey(id)
}

object PollOptions : Table("poll_options") {
    val id = long("id").autoIncrement()
    val pollId = long("pollId").references(Polls.id, onDelete = ReferenceOption.CASCADE)
    val label = varchar("label", 200)
    val position = integer("position")

    override val primaryKey = PrimaryKey(id)
}

object PollVotes : Table("poll_votes") {
    val id = long("id").autoIncrement()
    val pollId = long("pollId").references(Polls.id, onDelete = ReferenceOption.CASCADE)
    val optionId = long("optionId").references(PollOptions.id, onDelete = ReferenceOption.CASCADE)
    val userId = long("userId")

    override val primaryKey = PrimaryKey(id)
}
