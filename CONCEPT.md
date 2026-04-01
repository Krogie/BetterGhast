# BetterGhast v3.0 - Update Concept

## Vision
BetterGhast will evolve from a simple tag bot into a full-featured server management bot. The focus is on moderation, community engagement, and automation.

---

## Phase 1: Tag System Extensions

### Tag Permissions System
- **Tag Roles:** Tags can be assigned to specific roles (only those roles can use them)
- **Tag Ownership:** Each tag has a creator who can edit it (not just moderators)
- **Read-Only Tags:** Tags that can only be created by admins but used by everyone

### Tag Templates
- **Placeholders:** `{user}`, `{channel}`, `{server}`, `{date}` are automatically replaced
- **Mention Support:** `!t welcome @User` replaces `{mention}` in tag content
- **Conditional Content:** Different content depending on the user's role

### Tag Analytics Dashboard
- **Usage Trends:** When are tags used the most (by hour/day)
- **Top Users:** Who uses which tags the most
- **Unused Tag Cleanup:** Automatic notification about never-used tags

### Tag Import/Export (v2.0 - already implemented)
- JSON export of all tags
- Import tags from other servers
- Backup/Restore functionality

---

## Phase 2: Auto-Moderation

### Auto-Response System
- **Trigger Words:** Automatically send tags when certain words appear in chat
- **Regex Matching:** Flexible pattern recognition for triggers
- **Cooldown per Trigger:** Prevents spam
- **Channel Filter:** Triggers only active in specific channels

### Warning System
- `/warn @user reason` - Issue a warning
- Automatic actions after X warnings (mute, kick, ban)
- Warning log in a dedicated channel
- Warning decay: Warnings expire after X days

### Anti-Spam
- **Duplicate Detection:** Detects copied messages
- **Rate Limiting:** Detects rapid message sending
- **Link Filter:** Automatically check unknown links
- **Invite Filter:** Block Discord invites (with whitelist)

---

## Phase 3: Community Features

### Reaction Roles
- `/reactionrole setup` - Create a message with reactions
- Automatically assign/remove roles on reaction
- Support for button-based roles (more modern than reactions)
- Dropdown menus for role selection

### Welcome System
- **Join Message:** Customizable welcome message with placeholders
- **Leave Message:** Farewell message
- **Auto-Role:** Automatically assign roles on join
- **Welcome DM:** Optional DM to new members

### Ticket System
- `/ticket create` - Open a support ticket
- Dedicated channel per ticket
- Ticket transcript on close
- Ticket categories (Support, Bug, Feedback)
- Claim system for supporters

### Leveling System
- XP for messages and voice time
- Automatically assign level roles
- Leaderboard with `/top`
- XP multipliers for specific channels
- Level-up notifications

---

## Phase 4: Utility & Tools

### Server Stats
- **Member Counter:** Live counter in channel names
- **Bot Counter:** Number of bots
- **Online Counter:** Currently online
- **Boost Counter:** Display server boosts

### Logging System
- **Message Logs:** Log deleted/edited messages
- **Join/Leave Logs:** Member movements
- **Role Logs:** Role changes
- **Voice Logs:** Voice channel activity
- **Mod Logs:** All moderation actions

### Reminder System
- `/remind 2h Start meeting` - Personal reminders
- Recurring reminders (daily, weekly)
- Channel reminders for announcements

### Poll System
- `/poll "Question" "Option 1" "Option 2"` - Create polls
- Time limits for polls
- Anonymous voting
- Multi-choice support

---

## Phase 5: Dashboard & API

### Web Dashboard
- **Tag Management:** Manage tags via browser
- **Server Config:** Bot settings via web interface
- **Analytics:** Visualized usage statistics
- **Audit Log:** All actions traceable

### REST API
- Endpoints for tag CRUD
- Webhook integration for external tools
- Discord OAuth2 login
- Rate limiting & API keys

---

## Technical Improvements

### Database
- [ ] Flyway migration framework
- [ ] Tag versioning (history table)
- [ ] Audit log table
- [ ] Redis cache for frequently used tags

### Performance
- [ ] Lazy loading for guild caches
- [ ] Connection pool optimization
- [ ] Batch updates for usage counters
- [ ] Async event processing pipeline

### Code Quality
- [ ] Unit tests with JUnit 5
- [ ] Integration tests with Testcontainers (MariaDB)
- [ ] CI/CD pipeline (GitHub Actions)
- [ ] Docker deployment
- [ ] Gradle Shadow JAR for easy deployment

### Monitoring
- [ ] Health check endpoint
- [ ] Prometheus metrics
- [ ] Structured logging (JSON)
- [ ] Error tracking (Sentry integration)

---

## Priorities

| Priority | Feature | Effort |
|----------|---------|--------|
| High | Auto-Response System | Medium |
| High | Warning System | Medium |
| High | Logging System | Medium |
| High | CI/CD + Docker | Small |
| Medium | Reaction Roles | Small |
| Medium | Welcome System | Small |
| Medium | Tag Permissions | Medium |
| Medium | Tag Templates | Medium |
| Low | Ticket System | Large |
| Low | Leveling System | Large |
| Low | Web Dashboard | Large |
| Low | REST API | Large |
