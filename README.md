# BetterGhast

A powerful Discord tag management bot built with Kotlin, JDA 6, and MariaDB. Fork of Ghastling -- rebuilt, optimized, and extended.

[![Add to Discord](https://img.shields.io/badge/Add%20to%20Discord-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.com/oauth2/authorize?client_id=1488805332741525595&permissions=274877975552&scope=bot+applications.commands)
[![Website](https://img.shields.io/badge/Website-0d1117?style=for-the-badge&logo=github&logoColor=white)](https://krogie.github.io/BetterGhast)

---

## Why BetterGhast?

BetterGhast is a cleaner, more stable, and feature-rich version of the original Ghastling bot. Here's what sets it apart:

- **Stability** -- Fixed critical bugs like database connection pool leaks, unsafe null assertions, and missing guild authorization checks
- **More Commands** -- Added `/tags info`, `/tags export`, `/tags search`, and `/tags stats` on top of the original manage/show commands
- **Safer Code** -- All `!!` operators replaced with safe `?.` calls, input validation on modals, config fallback values
- **Graceful Shutdown** -- Both JDA and the database pool shut down cleanly via shutdown hook, no more leaked connections
- **Better UX** -- Delete confirmation dialogs, paginated tag lists, visual usage bar charts, tag search by content
- **Export & Backup** -- Export your entire tag collection as JSON with a single command
- **Active Development** -- See the roadmap below for what's coming next

---

## Features

| Command | Description |
|---------|-------------|
| `!t keyword` | Trigger a tag via prefix command |
| `/tags manage` | Create, edit, or delete tags via modal |
| `/tags show` | List all tags with pagination |
| `/tags search` | Search tags by keyword or content |
| `/tags stats` | View usage statistics with bar chart |
| `/tags info` | Detailed info about a specific tag |
| `/tags export` | Export all tags as a JSON file |

**Tag Styles:**
- **Accent Embed** -- Styled container with your custom accent color
- **No Accent** -- Clean embed without color
- **Raw Message** -- Plain text, no embed

**Other Features:**
- Per-channel cooldowns to prevent spam
- Keyword aliases (multiple triggers per tag)
- Autocomplete for tag names
- Conflict detection when creating tags
- Media gallery auto-detection for URLs
- Markdown support in tag content
- Guild allowlist for multi-server setups

---

## Planned Features & Roadmap

### v2.1 -- Tag System Improvements
- Tag permissions (role-based access, tag ownership)
- Tag templates with placeholders (`{user}`, `{channel}`, `{server}`)
- Tag analytics (usage trends, top users, cleanup suggestions)

### v2.5 -- Auto-Moderation
- Auto-response triggers (keyword-based automatic tag replies)
- Warning system (`/warn @user reason`) with escalation (mute/kick/ban)
- Anti-spam (duplicate detection, rate limiting, link/invite filters)

### v3.0 -- Community Features
- Reaction roles (button & dropdown based)
- Welcome/leave messages with auto-role
- Ticket system with transcripts
- Leveling system with XP and leaderboards
- Poll system with time limits and multi-choice

### v4.0 -- Dashboard & API
- Web dashboard for tag management and server config
- REST API with Discord OAuth2
- Prometheus metrics and monitoring
- Docker deployment

See [CONCEPT.md](CONCEPT.md) for the full detailed roadmap.

---

## Quick Deploy (Docker)

The fastest way to get BetterGhast running on your server:

```bash
git clone https://github.com/Krogie/BetterGhast.git
cd BetterGhast
bash deploy.sh
```

The deploy script will:
1. Install Docker if needed
2. Ask for your Discord token and create the `.env` config
3. Start MariaDB + BetterGhast in Docker containers

That's it. Bot is running.

**Useful commands after deploy:**
```bash
docker compose logs -f bot     # View live logs
docker compose restart bot     # Restart the bot
docker compose down            # Stop everything
git pull && docker compose up -d --build  # Update to latest version
```

---

## Manual Installation

If you prefer to run without Docker:

### Requirements

- **Java 21** or higher
- **MariaDB 10.6+** (or compatible MySQL)
- A **Discord Bot Token** with Message Content Intent enabled

### 1. Clone and configure

```bash
git clone https://github.com/Krogie/BetterGhast.git
cd BetterGhast
cp .env.example .env
```

Edit `.env` with your values:

| Variable | Description | Default |
|----------|-------------|---------|
| `DISCORD_TOKEN` | Your Discord bot token | *required* |
| `DB_HOST` | MariaDB host | *required* |
| `DB_PORT` | MariaDB port | `3306` |
| `DB_NAME` | Database name | *required* |
| `DB_USER` | Database user | *required* |
| `DB_PASSWORD` | Database password | *required* |
| `ALLOWED_GUILDS` | Comma-separated guild IDs (empty = all guilds) | *empty* |
| `TAG_COOLDOWN_MS` | Cooldown between tag uses per channel in ms | `2500` |
| `ACCENT_COLOR` | Hex color for embed accent (without #) | `B5C8B4` |

### 2. Set up the database

```sql
CREATE DATABASE betterghast CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER 'betterghast'@'localhost' IDENTIFIED BY 'your_password';
GRANT ALL PRIVILEGES ON betterghast.* TO 'betterghast'@'localhost';
FLUSH PRIVILEGES;
```

### 3. Build and run

```bash
./gradlew build && ./gradlew run
```

### 4. Discord Bot Setup

1. Go to the [Discord Developer Portal](https://discord.com/developers/applications)
2. Create a new application or select your existing one
3. Go to **Bot** and enable **Message Content Intent**
4. Go to **OAuth2 > URL Generator**
5. Select scopes: `bot`, `applications.commands`
6. Select permissions: `Send Messages`, `Manage Messages`, `Embed Links`, `Use External Emojis`, `Read Message History`
7. Use the generated URL to invite the bot to your server

---

## Tech Stack

- **Kotlin 2.2** with Coroutines
- **JDA 6.2** (Java Discord API)
- **Exposed ORM** for database access
- **HikariCP** for connection pooling
- **MariaDB** as the database
- **Logback** for logging
- **Gradle** with Kotlin DSL

---

## Contributing

Contributions are welcome! Feel free to open issues or submit pull requests.

---

## Credits

BetterGhast is a fork of [Ghastling](https://github.com/foenichs/Ghastling) by [foenichs](https://github.com/foenichs). The original project laid the foundation for the tag system, Discord integration, and database architecture that BetterGhast builds upon.

---

## License

This project is licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE) for details.
