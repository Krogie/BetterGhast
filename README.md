# BetterGhast

A powerful Discord bot for tag management, moderation, and community tools. Built with Kotlin, JDA 6, and MariaDB. Fork of Ghastling -- rebuilt, optimized, and massively extended.

[![Add to Discord](https://img.shields.io/badge/Add%20to%20Discord-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.com/oauth2/authorize?client_id=1488805332741525595&permissions=274877975552&scope=bot+applications.commands)
[![Website](https://img.shields.io/badge/Website-0d1117?style=for-the-badge&logo=github&logoColor=white)](https://betterghast.krogie.com)

---

## Features

### Tag System
| Command | Description |
|---------|-------------|
| `!t keyword` | Trigger a tag via prefix |
| `/tags manage` | Create, edit, or delete tags via modal |
| `/tags show` | List all tags with pagination |
| `/tags search` | Search tags by keyword or content |
| `/tags stats` | Usage statistics with bar chart |
| `/tags info` | Detailed info about a tag |
| `/tags export` | Export all tags as JSON |
| `/tags analytics` | Usage trends, top users, unused tags |
| `/tags permissions` | Set role restrictions on tags |
| `/tags help` | Show all commands |

- **3 tag styles:** Accent Embed, No Accent, Raw Message
- **Template placeholders:** `{user}`, `{channel}`, `{server}`, `{date}`, `{mention}`
- **Role-based permissions:** Restrict tags to specific roles
- **Creator tracking:** See who created each tag and when
- Per-channel cooldowns, keyword aliases, autocomplete, conflict detection, media galleries

### Moderation
| Command | Description |
|---------|-------------|
| `/warn @user reason` | Issue a warning |
| `/warnings @user` | View user's warnings |
| `/clearwarning id` | Remove a warning |
| `/autoresponse add\|remove\|list\|toggle` | Auto-response triggers |
| `/antispam toggle\|ratelimit\|linkfilter\|invitefilter\|whitelist\|status` | Anti-spam config |

- **Warning escalation:** Auto mute/kick/ban at configurable thresholds (default: 3=mute, 5=kick, 7=ban)
- **Warning decay:** Warnings expire after configurable days (default: 30)
- **Auto-responses:** Keyword or regex triggers that auto-send tags
- **Anti-spam:** Rate limiting, duplicate detection, link/invite filters with whitelist

### Community
| Command | Description |
|---------|-------------|
| `/welcome channel\|message\|leave\|autorole\|dm\|test\|status` | Welcome system |
| `/rolepanel create\|addrole\|send\|delete\|list` | Reaction roles |
| `/ticket create\|close\|claim\|transcript\|setup` | Ticket system |
| `/rank [@user]` | View level and XP |
| `/top [page]` | Server leaderboard |
| `/xp toggle\|addrole\|removerole\|multiplier\|status` | Leveling config |
| `/poll question options [duration] [anonymous] [multichoice]` | Create polls |

- **Welcome/leave messages** with placeholders and auto-role assignment
- **Reaction roles** with button or dropdown panels
- **Ticket system** with categories, claiming, and transcript generation
- **Leveling** with XP per message, level roles, channel multipliers, leaderboards
- **Polls** with time limits, anonymous voting, and multi-choice

---

## Roadmap

- [x] **v2.0** -- Tag system with search, stats, pagination, export
- [x] **v2.1** -- Tag permissions, templates, analytics
- [x] **v2.5** -- Auto-moderation (warnings, auto-response, anti-spam)
- [x] **v3.0** -- Community features (welcome, roles, tickets, leveling, polls)
- [ ] **v4.0** -- Web dashboard, REST API, monitoring

---

## Quick Deploy (Docker)

```bash
git clone https://github.com/Krogie/BetterGhast.git
cd BetterGhast
bash deploy.sh
```

The deploy script installs Docker if needed, asks for your Discord token, and starts everything.

**After deploy:**
```bash
docker compose logs -f bot     # View live logs
docker compose restart bot     # Restart the bot
docker compose down            # Stop everything
git pull && docker compose up -d --build  # Update
```

---

## Manual Installation

### Requirements

- **Java 21+**
- **MariaDB 10.6+**
- **Discord Bot Token** with Message Content Intent + Server Members Intent enabled

### 1. Clone and configure

```bash
git clone https://github.com/Krogie/BetterGhast.git
cd BetterGhast
cp .env.example .env
```

### 2. Edit `.env`

| Variable | Description | Default |
|----------|-------------|---------|
| `DISCORD_TOKEN` | Bot token | *required* |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | Database connection | *required* |
| `ALLOWED_GUILDS` | Comma-separated guild IDs (empty = all) | *empty* |
| `TAG_COOLDOWN_MS` | Tag cooldown per channel (ms) | `2500` |
| `ACCENT_COLOR` | Embed accent hex color | `B5C8B4` |
| `WARN_DECAY_DAYS` | Days until warnings expire | `30` |
| `WARN_THRESHOLDS` | Escalation thresholds | `3:mute,5:kick,7:ban` |
| `ANTISPAM_RATE_LIMIT` | Max messages in window | `5` |
| `ANTISPAM_RATE_WINDOW` | Rate limit window (ms) | `5000` |
| `LEVELING_XP_MIN` / `LEVELING_XP_MAX` | XP range per message | `15` / `25` |
| `LEVELING_COOLDOWN` | XP cooldown (ms) | `60000` |

### 3. Set up database

```sql
CREATE DATABASE betterghast CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER 'betterghast'@'localhost' IDENTIFIED BY 'your_password';
GRANT ALL PRIVILEGES ON betterghast.* TO 'betterghast'@'localhost';
FLUSH PRIVILEGES;
```

### 4. Build and run

```bash
./gradlew build && ./gradlew run
```

### 5. Discord Bot Setup

1. Go to the [Discord Developer Portal](https://discord.com/developers/applications)
2. Enable **Message Content Intent** and **Server Members Intent** under Bot
3. Invite with scopes `bot` + `applications.commands` and permissions: Send Messages, Manage Messages, Manage Channels, Manage Roles, Moderate Members, Embed Links, Read Message History

---

## Tech Stack

- **Kotlin 2.2** with Coroutines
- **JDA 6.2** (Java Discord API)
- **Exposed ORM** for database access
- **HikariCP** for connection pooling
- **MariaDB** as the database
- **Logback** for logging
- **Docker** for deployment

---

## Contributing

Contributions are welcome! Feel free to open issues or submit pull requests.

---

## Credits

BetterGhast is a fork of [Ghastling](https://github.com/foenichs/Ghastling) by [foenichs](https://github.com/foenichs). The original project laid the foundation for the tag system, Discord integration, and database architecture that BetterGhast builds upon.

---

## License

This project is licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE) for details.
