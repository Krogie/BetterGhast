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

            maximumPoolSize = 10      // Keep concurrent connections reasonable
            minimumIdle = 2           // Always keep a few ready
            connectionTimeout = 5000  // Fail fast (5s)
            leakDetectionThreshold = 5000 // Warn if queries are slow (>5s)
            poolName = "BetterGhast-Pool"
        }

        val ds = HikariDataSource(hikariConfig)
        dataSource = ds
        val db = Database.connect(ds)

        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_REPEATABLE_READ

        transaction(db) {
            SchemaUtils.create(Guilds, Tags)
        }
    }

    fun shutdown() {
        dataSource?.close()
        dataSource = null
    }
}

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

    override val primaryKey = PrimaryKey(guildId, primaryKeyword)
}