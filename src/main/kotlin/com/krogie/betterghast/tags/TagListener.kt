package com.krogie.betterghast.tags

import dev.minn.jda.ktx.events.listener
import dev.minn.jda.ktx.events.onCommand
import dev.minn.jda.ktx.events.onCommandAutocomplete
import dev.minn.jda.ktx.interactions.components.Modal
import dev.minn.jda.ktx.interactions.components.StringSelectMenu
import dev.minn.jda.ktx.interactions.components.option
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.components.textinput.TextInputStyle
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.utils.FileUpload
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.exceptions.ErrorHandler
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import net.dv8tion.jda.api.requests.ErrorResponse
import org.slf4j.LoggerFactory

object TagListener {

    private val logger = LoggerFactory.getLogger(TagListener::class.java)
    private const val TAGS_PER_PAGE = 15

    fun register(jda: JDA) {
        jda.listener<MessageReceivedEvent> { onPrefix(it) }
        jda.onCommandAutocomplete("tags", "name") { onAutocomplete(it) }
        jda.onCommand("tags") { onSlashCommand(it as SlashCommandInteractionEvent) }
        jda.listener<ModalInteractionEvent> { onModal(it) }
        jda.listener<ButtonInteractionEvent> { onButton(it) }
    }

    private fun onPrefix(event: MessageReceivedEvent) {
        if (event.author.isBot || !event.isFromGuild) return
        val msg = event.message.contentRaw
        if (!msg.startsWith("!t ")) return

        val keyword = msg.removePrefix("!t ").trim()
        if (keyword.isBlank()) return

        val guildId = event.guild.idLong
        val tag = TagService.find(guildId, keyword) ?: return

        val cooldown = TagService.checkCooldown(event.channel.idLong, tag.primary)
        if (cooldown > 0) {
            deleteSilently(event.message, guildId)
            return
        }

        deleteSilently(event.message, guildId)

        try {
            val container = tag.cachedContainer
            val action = if (container != null) {
                event.channel.sendMessageComponents(container).useComponentsV2()
            } else {
                event.channel.sendMessage(tag.content)
            }

            action.queue({
                TagService.incrementUsage(guildId, tag.primary)
            }, { error ->
                logger.warn("Failed to send tag in Guild $guildId: ${error.message}")
            })
        } catch (e: InsufficientPermissionException) {
            logger.warn("Missing permissions in Guild $guildId: ${e.permission}")
        } catch (e: Exception) {
            logger.error("Error sending tag in Guild $guildId", e)
        }
    }

    private fun onAutocomplete(event: CommandAutoCompleteInteractionEvent) {
        if (event.subcommandName !in listOf("manage", "info")) return
        val guild = event.guild ?: return
        val query = event.focusedOption.value
        event.replyChoices(TagService.autocomplete(guild.idLong, query)).queue(null) { error ->
            logger.warn("Autocomplete failed for Guild {}: {}", guild.id, error.message)
        }
    }

    private fun onSlashCommand(event: SlashCommandInteractionEvent) {
        when (event.subcommandName) {
            "show" -> onShowTags(event)
            "manage" -> onManageTag(event)
            "search" -> onSearchTags(event)
            "stats" -> onTagStats(event)
            "info" -> onTagInfo(event)
            "export" -> onExportTags(event)
            "help" -> onHelp(event)
        }
    }

    // ── /tags show (with pagination) ──

    private fun onShowTags(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return
        val tags = TagService.listTags(guild.idLong)
            .sortedWith(compareByDescending<Tag> { it.usages }.thenBy { it.primary })

        if (tags.isEmpty()) {
            event.replyContainer("This server doesn't have any tags yet.")
            return
        }

        val totalPages = (tags.size + TAGS_PER_PAGE - 1) / TAGS_PER_PAGE
        if (totalPages <= 1) {
            event.replyContainer(formatTagList(guild.name, tags, 1, 1))
            return
        }

        val pageContent = formatTagList(guild.name, tags.take(TAGS_PER_PAGE), 1, totalPages)
        val container = Container.of(TextDisplay.of(pageContent)).withAccentColor(TagService.accentColor)
        val buttons = buildPageButtons(guild.idLong, 1, totalPages)

        event.replyComponents(container, ActionRow.of(buttons)).useComponentsV2().setEphemeral(true)
            .queue(null) { e ->
                logger.warn("Failed to send tag list: {}", e.message)
            }
    }

    private fun formatTagList(guildName: String, tags: List<Tag>, page: Int, totalPages: Int): String {
        val uncategorized = mutableListOf<String>()
        val categories = mutableMapOf<String, MutableList<String>>()

        for (tag in tags) {
            val parts = tag.primary.split(" ", limit = 2)
            if (parts.size > 1) {
                categories.getOrPut(parts[0]) { mutableListOf() }.add(parts[1])
            } else {
                uncategorized.add(tag.primary)
            }
        }

        val sb = StringBuilder()
        val pageInfo = if (totalPages > 1) " (Page $page/$totalPages)" else ""
        sb.append("### Tags of $guildName (${tags.size})$pageInfo\n")

        if (uncategorized.isNotEmpty()) {
            sb.append(uncategorized.joinToString(", ") { "`$it`" })
        }

        for ((category, items) in categories.toSortedMap()) {
            val entry = "\n\n**$category** (${items.size}):\n" +
                    items.joinToString(", ") { "`$it`" }

            if (sb.length + entry.length > 3950) {
                sb.append("\n\n...and more.")
                break
            }
            sb.append(entry)
        }

        return sb.toString()
    }

    private fun buildPageButtons(guildId: Long, currentPage: Int, totalPages: Int): List<Button> {
        val buttons = mutableListOf<Button>()
        if (currentPage > 1) {
            buttons.add(Button.secondary("tags_page:${guildId}:${currentPage - 1}", "◀ Previous"))
        } else {
            buttons.add(Button.secondary("tags_page:${guildId}:0", "◀ Previous").asDisabled())
        }

        buttons.add(Button.secondary("tags_page_info:${guildId}", "$currentPage / $totalPages").asDisabled())

        if (currentPage < totalPages) {
            buttons.add(Button.secondary("tags_page:${guildId}:${currentPage + 1}", "Next ▶"))
        } else {
            buttons.add(Button.secondary("tags_page:${guildId}:0", "Next ▶").asDisabled())
        }
        return buttons
    }

    // ── /tags search ──

    private fun onSearchTags(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return
        val query = event.getOption("query")?.asString ?: return

        val results = TagService.searchTags(guild.idLong, query)

        if (results.isEmpty()) {
            event.replyContainer("No tags found matching `$query`.")
            return
        }

        val sb = StringBuilder("### Search results for \"$query\" (${results.size})\n\n")
        for (tag in results.take(10)) {
            val preview = tag.content.take(80).replace("\n", " ")
            sb.append("**`${tag.primary}`** — $preview")
            if (tag.content.length > 80) sb.append("...")
            sb.append("\n")
        }
        if (results.size > 10) {
            sb.append("\n*...and ${results.size - 10} more results.*")
        }

        event.replyContainer(sb.toString())
    }

    // ── /tags stats ──

    private fun onTagStats(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return
        val tags = TagService.listTags(guild.idLong)
            .sortedByDescending { it.usages }

        if (tags.isEmpty()) {
            event.replyContainer("No tags to show statistics for.")
            return
        }

        val totalUsages = tags.sumOf { it.usages }
        val topTags = tags.take(10)

        val sb = StringBuilder("### Tag Statistics for ${guild.name}\n\n")
        sb.append("**Total tags:** ${tags.size}\n")
        sb.append("**Total usages:** $totalUsages\n\n")

        if (topTags.isNotEmpty()) {
            sb.append("**Top ${topTags.size} most used:**\n")
            for ((i, tag) in topTags.withIndex()) {
                val bar = "█".repeat(
                    if (totalUsages > 0) ((tag.usages.toDouble() / topTags.first().usages) * 10).toInt().coerceAtLeast(1)
                    else 1
                )
                sb.append("${i + 1}. `${tag.primary}` — ${tag.usages} uses $bar\n")
            }
        }

        val neverUsed = tags.count { it.usages == 0L }
        if (neverUsed > 0) {
            sb.append("\n*$neverUsed tag(s) have never been used.*")
        }

        event.replyContainer(sb.toString())
    }

    // ── /tags help ──

    private fun onHelp(event: SlashCommandInteractionEvent) {
        val help = """
            ### BetterGhast v2.0 — Commands

            **Tag Usage**
            `!t keyword` — Trigger a tag via prefix

            **Slash Commands** (require Manage Messages permission)
            `/tags manage name:<keyword>` — Create or edit a tag
            `/tags manage name:<keyword> remove:true` — Delete a tag
            `/tags show` — List all tags (with pagination)
            `/tags search query:<term>` — Search tags by keyword or content
            `/tags stats` — Usage statistics with bar chart
            `/tags info name:<keyword>` — Detailed info about a tag
            `/tags export` — Export all tags as JSON file
            `/tags help` — This help message

            **Tag Styles**
            When creating a tag, you can choose between:
            - **Accent Embed** — Styled with accent color
            - **No Accent** — Clean embed without color
            - **Raw Message** — Plain text, no embed

            **Links**
            [Website](https://krogie.github.io/BetterGhast) — [GitHub](https://github.com/Krogie/BetterGhast)
        """.trimIndent()

        event.replyContainer(help)
    }

    // ── /tags info ──

    private fun onTagInfo(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return
        val name = event.getOption("name")?.asString ?: return
        val tag = TagService.getTagInfo(guild.idLong, name)

        if (tag == null) {
            event.replyContainer("No tag found with keyword `$name`.")
            return
        }

        val sb = StringBuilder("### Tag Info: `${tag.primary}`\n\n")
        sb.append("**Keywords:** ${tag.keywordList.joinToString(", ") { "`$it`" }}\n")
        sb.append("**Style:** ${tag.style.name}\n")
        sb.append("**Usages:** ${tag.usages}\n")
        sb.append("**Content length:** ${tag.content.length} characters\n\n")
        sb.append("**Preview:**\n${tag.content.take(200)}")
        if (tag.content.length > 200) sb.append("...")

        event.replyContainer(sb.toString())
    }

    // ── /tags export ──

    private fun onExportTags(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return
        val tags = TagService.exportTags(guild.idLong)

        if (tags.isEmpty()) {
            event.replyContainer("No tags to export.")
            return
        }

        val json = buildString {
            append("[\n")
            for ((i, tag) in tags.withIndex()) {
                append("  {\n")
                append("    \"primary\": \"${escapeJson(tag["primary"].toString())}\",\n")
                append("    \"keywords\": \"${escapeJson(tag["keywords"].toString())}\",\n")
                append("    \"content\": \"${escapeJson(tag["content"].toString())}\",\n")
                append("    \"style\": \"${tag["style"]}\",\n")
                append("    \"usages\": ${tag["usages"]}\n")
                append("  }")
                if (i < tags.size - 1) append(",")
                append("\n")
            }
            append("]")
        }

        // Send as file attachment if too long for a message
        if (json.length > 1900) {
            event.reply("Exported ${tags.size} tags.").setEphemeral(true)
                .addFiles(net.dv8tion.jda.api.utils.FileUpload.fromData(json.toByteArray(), "${guild.name}-tags.json"))
                .queue(null) { error ->
                    logger.warn("Failed to send tag export: {}", error.message)
                }
        } else {
            event.replyContainer("### Exported ${tags.size} tags\n```json\n$json\n```")
        }
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    // ── /tags manage (with delete confirmation) ──

    private fun onManageTag(event: SlashCommandInteractionEvent) {
        val guildId = event.guild?.idLong ?: return
        val name = event.getOption("name")?.asString ?: return
        val delete = event.getOption("remove")?.asBoolean ?: false
        val existing = TagService.find(guildId, name)

        if (delete) {
            if (existing != null) {
                val container = Container.of(
                    TextDisplay.of("### Delete tag `${existing.primary}`?\nThis action cannot be undone. The tag has been used **${existing.usages}** time(s).")
                ).withAccentColor(TagService.accentColor)

                event.replyComponents(container, ActionRow.of(
                        Button.danger("tag_delete_confirm:${guildId}:${existing.primary}", "Delete"),
                        Button.secondary("tag_delete_cancel", "Cancel")
                    )).useComponentsV2().setEphemeral(true)
                    .queue(null) { e ->
                        logger.warn("Failed to send delete confirmation: {}", e.message)
                    }
            } else {
                event.replyContainer("Couldn't find a tag with keyword `$name`.")
            }
            return
        }

        if (existing == null) {
            val conflicts = TagService.findConflicts(guildId, name)
            if (conflicts.isNotEmpty()) {
                event.replyContainer(formatConflicts(conflicts))
                return
            }
        }

        event.replyModal(buildTagModal(existing, name)).queue(null) { error ->
            logger.warn("Failed to open tag modal in Guild {}: {}", guildId, error.message)
        }
    }

    // ── Button handler ──

    private fun onButton(event: ButtonInteractionEvent) {
        val id = event.componentId

        when {
            id.startsWith("tag_delete_confirm:") -> {
                val parts = id.removePrefix("tag_delete_confirm:").split(":", limit = 2)
                if (parts.size != 2) return
                val guildId = parts[0].toLongOrNull() ?: return
                val primary = parts[1]

                // Validate the button user is in the right guild
                if (event.guild?.idLong != guildId) return

                TagService.delete(guildId, primary)
                event.editMessage("Successfully deleted the `$primary` tag.").setReplace(true)
                    .setComponents(emptyList())
                    .queue(null) { error ->
                        logger.warn("Failed to confirm deletion: {}", error.message)
                    }
            }

            id == "tag_delete_cancel" -> {
                event.editMessage("Tag deletion cancelled.").setReplace(true)
                    .setComponents(emptyList())
                    .queue(null) { error ->
                        logger.warn("Failed to cancel deletion: {}", error.message)
                    }
            }

            id.startsWith("tags_page:") -> {
                val parts = id.removePrefix("tags_page:").split(":", limit = 2)
                if (parts.size != 2) return
                val guildId = parts[0].toLongOrNull() ?: return
                val page = parts[1].toIntOrNull() ?: return
                if (page < 1) return

                val guild = event.guild ?: return
                if (guild.idLong != guildId) return

                val tags = TagService.listTags(guildId)
                    .sortedWith(compareByDescending<Tag> { it.usages }.thenBy { it.primary })

                val totalPages = (tags.size + TAGS_PER_PAGE - 1) / TAGS_PER_PAGE
                if (page > totalPages) return

                val start = (page - 1) * TAGS_PER_PAGE
                val pageTags = tags.drop(start).take(TAGS_PER_PAGE)
                val pageContent = formatTagList(guild.name, pageTags, page, totalPages)

                val container = Container.of(TextDisplay.of(pageContent)).withAccentColor(TagService.accentColor)
                val buttons = buildPageButtons(guildId, page, totalPages)

                event.editComponents(container, ActionRow.of(buttons)).setReplace(true)
                    .queue(null) { e ->
                        logger.warn("Failed to update page: {}", e.message)
                    }
            }
        }
    }

    // ── Modal handler (with ID validation) ──

    private fun onModal(event: ModalInteractionEvent) {
        if (!event.modalId.startsWith("tag_modal:")) return
        val guildId = event.guild?.idLong ?: return
        val rawOldPrimary = event.modalId.removePrefix("tag_modal:").ifEmpty { null }

        // Validate oldPrimary: must be alphanumeric, spaces, hyphens only (prevent injection)
        val oldPrimary = rawOldPrimary?.takeIf { it.matches(Regex("^[\\w\\s,\\-]+$")) }

        val keywords = event.getValue("keywords")?.asString ?: return
        val content = event.getValue("content")?.asString ?: return
        val styleStr = event.getValue("style")?.asStringList?.firstOrNull() ?: return
        val style = runCatching { TagStyle.valueOf(styleStr) }.getOrDefault(TagStyle.Accent)

        val conflicts = TagService.findConflicts(guildId, keywords, ignorePrimary = oldPrimary)
        if (conflicts.isNotEmpty()) {
            event.replyContainer(formatConflicts(conflicts))
            return
        }

        try {
            val newPrimary = TagService.createOrUpdate(guildId, keywords, content, style, oldPrimary)
            event.replyContainer("Successfully saved the `$newPrimary` tag.")
            logger.info("Guild $guildId: Tag '$newPrimary' updated by ${event.user.name}")
        } catch (e: IllegalArgumentException) {
            event.replyContainer("Error: ${e.message}")
        } catch (e: Exception) {
            logger.error("Modal Error", e)
            event.replyContainer("An unexpected error occurred. Please try again.")
        }
    }

    private fun IReplyCallback.replyContainer(markdown: String) {
        val container = Container.of(TextDisplay.of(markdown)).withAccentColor(TagService.accentColor)
        this.replyComponents(container).useComponentsV2().setEphemeral(true).queue(null) { error ->
            logger.warn("Failed to send ephemeral reply: {}", error.message)
        }
    }

    private fun deleteSilently(message: Message, guildId: Long) {
        try {
            message.delete().queue(
                null, ErrorHandler().ignore(ErrorResponse.UNKNOWN_MESSAGE, ErrorResponse.MISSING_PERMISSIONS)
            )
        } catch (e: InsufficientPermissionException) {
            logger.debug("Cannot delete message in Guild {}: {}", guildId, e.permission)
        }
    }

    private fun buildTagModal(existing: Tag?, name: String) =
        Modal("tag_modal:${existing?.primary ?: ""}", if (existing != null) "Edit Tag: ${existing.primary}" else "Create Tag") {
            val kw = TextInput.create("keywords", TextInputStyle.SHORT).setPlaceholder("nolfg, m, lfg")
                .setValue(existing?.keywords ?: name).setRequired(true).build()
            label(
                "Keywords",
                child = kw,
                description = "These will trigger the tag, with the first one being the primary one. Separated by commas."
            )

            val content = TextInput.create("content", TextInputStyle.PARAGRAPH)
                .setPlaceholder("### Multiplayer requests are not allowed here!\nPlease use the LFG category for multiplayer...")
                .setValue(existing?.content).setRequired(true).build()
            label(
                "Content",
                child = content,
                description = "The actual content of the tag, fully supporting markdown."
            )

            val defaultStyle = existing?.style ?: TagStyle.Accent
            val style = StringSelectMenu("style", placeholder = "Select a style") {
                option(
                    "BetterGhast Embed",
                    value = "Accent",
                    default = defaultStyle == TagStyle.Accent,
                    description = "An embed with BetterGhast's accent color."
                )
                option(
                    "No Accent",
                    "NoAccent",
                    default = defaultStyle == TagStyle.NoAccent,
                    description = "An embed without an accent color."
                )
                option(
                    "Raw Message",
                    "Raw",
                    default = defaultStyle == TagStyle.Raw,
                    description = "No embed, just the raw tag content."
                )
            }
            label("Style", child = style, description = "Choose how the tag content will be displayed.")
        }

    private fun formatConflicts(conflicts: Map<String, String>): String {
        return "### Conflicting keywords\n" + conflicts.entries.joinToString("\n") { "- `${it.key}` -> `${it.value}`" }
    }
}
