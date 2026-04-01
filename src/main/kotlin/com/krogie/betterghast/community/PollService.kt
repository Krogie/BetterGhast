package com.krogie.betterghast.community

import com.krogie.betterghast.db.Guilds
import com.krogie.betterghast.db.PollOptions
import com.krogie.betterghast.db.PollVotes
import com.krogie.betterghast.db.Polls
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

data class PollData(
    val id: Long,
    val guildId: Long,
    val channelId: Long,
    val messageId: Long,
    val creatorId: Long,
    val question: String,
    val options: List<PollOptionData>,
    val multiChoice: Boolean,
    val anonymous: Boolean,
    val endsAt: Long?,
    val closed: Boolean
)

data class PollOptionData(
    val id: Long,
    val pollId: Long,
    val label: String,
    val position: Int,
    val voteCount: Int = 0
)

object PollService {

    private val logger = LoggerFactory.getLogger(PollService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun createPoll(
        guildId: Long,
        channelId: Long,
        creatorId: Long,
        question: String,
        optionLabels: List<String>,
        multiChoice: Boolean = false,
        anonymous: Boolean = false,
        endsAt: Long? = null
    ): Long {
        return transaction {
            if (Guilds.selectAll().where { Guilds.id eq guildId }.empty()) {
                Guilds.insertIgnore { it[Guilds.id] = guildId }
            }

            val pollId = Polls.insert {
                it[Polls.guildId] = guildId
                it[Polls.channelId] = channelId
                it[Polls.creatorId] = creatorId
                it[Polls.question] = question
                it[Polls.multiChoice] = multiChoice
                it[Polls.anonymous] = anonymous
                it[Polls.endsAt] = endsAt
                it[Polls.closed] = false
            } get Polls.id

            for ((index, label) in optionLabels.withIndex()) {
                PollOptions.insert {
                    it[PollOptions.pollId] = pollId
                    it[PollOptions.label] = label
                    it[PollOptions.position] = index
                }
            }

            pollId
        }.also { logger.info("Guild $guildId: Poll #$it created: '$question'") }
    }

    fun setMessageId(pollId: Long, messageId: Long) {
        transaction {
            Polls.update({ Polls.id eq pollId }) {
                it[Polls.messageId] = messageId
            }
        }
    }

    fun getPoll(pollId: Long): PollData? {
        return transaction {
            val pollRow = Polls.selectAll().where { Polls.id eq pollId }.firstOrNull() ?: return@transaction null
            buildPollData(pollRow)
        }
    }

    fun getPollByMessage(messageId: Long): PollData? {
        return transaction {
            val pollRow = Polls.selectAll().where { Polls.messageId eq messageId }.firstOrNull() ?: return@transaction null
            buildPollData(pollRow)
        }
    }

    /**
     * Toggle a vote for a user on a specific option.
     * Returns true if voted, false if vote was removed.
     * Throws if poll is closed or user already voted on another option in single-choice mode.
     */
    fun vote(pollId: Long, optionId: Long, userId: Long): Boolean {
        return transaction {
            val poll = Polls.selectAll().where { Polls.id eq pollId }.firstOrNull()
                ?: throw IllegalArgumentException("Poll not found.")
            if (poll[Polls.closed]) throw IllegalStateException("This poll is closed.")

            // Check if user already voted for this specific option
            val existingVote = PollVotes.selectAll().where {
                (PollVotes.pollId eq pollId) and
                (PollVotes.optionId eq optionId) and
                (PollVotes.userId eq userId)
            }.firstOrNull()

            if (existingVote != null) {
                // Remove vote (toggle off)
                PollVotes.deleteWhere {
                    (PollVotes.pollId eq pollId) and
                    (PollVotes.optionId eq optionId) and
                    (PollVotes.userId eq userId)
                }
                false
            } else {
                // Single-choice: remove all other votes first
                if (!poll[Polls.multiChoice]) {
                    PollVotes.deleteWhere {
                        (PollVotes.pollId eq pollId) and (PollVotes.userId eq userId)
                    }
                }
                PollVotes.insert {
                    it[PollVotes.pollId] = pollId
                    it[PollVotes.optionId] = optionId
                    it[PollVotes.userId] = userId
                }
                true
            }
        }
    }

    fun closePoll(pollId: Long): PollData? {
        transaction {
            Polls.update({ Polls.id eq pollId }) {
                it[Polls.closed] = true
            }
        }
        logger.info("Poll #$pollId closed")
        return getPoll(pollId)
    }

    fun getOpenExpiredPolls(): List<PollData> {
        val now = System.currentTimeMillis()
        return transaction {
            Polls.selectAll().where {
                (Polls.closed eq false) and
                (Polls.endsAt.isNotNull()) and
                (Polls.endsAt lessEq now)
            }.map { buildPollData(it) }
        }
    }

    fun getVoters(pollId: Long, optionId: Long): List<Long> {
        return transaction {
            PollVotes.selectAll().where {
                (PollVotes.pollId eq pollId) and (PollVotes.optionId eq optionId)
            }.map { it[PollVotes.userId] }
        }
    }

    fun hasVoted(pollId: Long, userId: Long): Boolean {
        return transaction {
            PollVotes.selectAll().where {
                (PollVotes.pollId eq pollId) and (PollVotes.userId eq userId)
            }.count() > 0
        }
    }

    fun startExpiryJob(onExpire: (PollData) -> Unit) {
        scope.launch {
            while (isActive) {
                try {
                    val expired = getOpenExpiredPolls()
                    for (poll in expired) {
                        val closed = closePoll(poll.id) ?: continue
                        onExpire(closed)
                    }
                } catch (e: Exception) {
                    logger.error("Poll expiry job error: ${e.message}")
                }
                delay(30_000L)
            }
        }
        logger.info("Poll expiry job started")
    }

    private fun buildPollData(pollRow: ResultRow): PollData {
        val pollId = pollRow[Polls.id]
        val options = PollOptions.selectAll().where { PollOptions.pollId eq pollId }
            .orderBy(PollOptions.position)
            .map { optRow ->
                val optId = optRow[PollOptions.id]
                val voteCount = PollVotes.selectAll().where { PollVotes.optionId eq optId }.count().toInt()
                PollOptionData(
                    id = optId,
                    pollId = pollId,
                    label = optRow[PollOptions.label],
                    position = optRow[PollOptions.position],
                    voteCount = voteCount
                )
            }

        return PollData(
            id = pollId,
            guildId = pollRow[Polls.guildId],
            channelId = pollRow[Polls.channelId],
            messageId = pollRow[Polls.messageId],
            creatorId = pollRow[Polls.creatorId],
            question = pollRow[Polls.question],
            options = options,
            multiChoice = pollRow[Polls.multiChoice],
            anonymous = pollRow[Polls.anonymous],
            endsAt = pollRow[Polls.endsAt],
            closed = pollRow[Polls.closed]
        )
    }
}
