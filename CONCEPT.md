# BetterGhast v3.0 - Update Concept

## Vision
BetterGhast wird von einem einfachen Tag-Bot zu einem vollwertigen Server-Management-Bot erweitert. Der Fokus liegt auf Moderation, Community-Engagement und Automatisierung.

---

## Phase 1: Tag-System Erweiterungen

### Tag Permissions System
- **Tag-Rollen:** Tags koennen bestimmten Rollen zugewiesen werden (nur diese Rollen koennen sie nutzen)
- **Tag-Owner:** Jeder Tag hat einen Ersteller, der ihn bearbeiten kann (nicht nur Moderatoren)
- **Read-Only Tags:** Tags die nur von Admins erstellt aber von allen genutzt werden koennen

### Tag Templates
- **Platzhalter:** `{user}`, `{channel}`, `{server}`, `{date}` werden automatisch ersetzt
- **Mention-Support:** `!t welcome @User` ersetzt `{mention}` im Tag-Content
- **Conditional Content:** Verschiedener Content je nach Rolle des Nutzers

### Tag Analytics Dashboard
- **Nutzungs-Trends:** Wann werden Tags am meisten genutzt (nach Stunde/Tag)
- **Top-Nutzer:** Wer nutzt welche Tags am meisten
- **Unused Tag Cleanup:** Automatische Benachrichtigung ueber nie genutzte Tags

### Tag Import/Export (v2.0 - bereits implementiert)
- JSON Export aller Tags
- Import von Tags aus anderen Servern
- Backup/Restore Funktion

---

## Phase 2: Auto-Moderation

### Auto-Response System
- **Trigger-Words:** Automatisch Tags senden wenn bestimmte Woerter im Chat auftauchen
- **Regex-Matching:** Flexible Pattern-Erkennung fuer Trigger
- **Cooldown pro Trigger:** Verhindert Spam
- **Channel-Filter:** Trigger nur in bestimmten Channels aktiv

### Warning System
- `/warn @user reason` - Verwarnung aussprechen
- Automatische Aktionen bei X Verwarnungen (Mute, Kick, Ban)
- Warning-Log in eigenem Channel
- Warning-Decay: Verwarnungen laufen nach X Tagen ab

### Anti-Spam
- **Duplicate Detection:** Erkennt kopierte Nachrichten
- **Rate-Limiting:** Zu schnelles Schreiben erkennen
- **Link-Filter:** Unbekannte Links automatisch pruefen
- **Invite-Filter:** Discord-Einladungen blockieren (mit Whitelist)

---

## Phase 3: Community Features

### Reaction Roles
- `/reactionrole setup` - Nachricht mit Reactions erstellen
- Rollen automatisch zuweisen/entfernen bei Reaction
- Support fuer Button-basierte Rollen (moderner als Reactions)
- Dropdown-Menues fuer Rollen-Auswahl

### Welcome System
- **Join-Message:** Anpassbare Willkommensnachricht mit Platzhaltern
- **Leave-Message:** Abschiedsnachricht
- **Auto-Role:** Automatisch Rollen bei Join vergeben
- **Welcome-DM:** Optionale DM an neue Mitglieder

### Ticket System
- `/ticket create` - Support-Ticket eroeffnen
- Eigener Channel pro Ticket
- Ticket-Transcript bei Schliessung
- Ticket-Kategorien (Support, Bug, Feedback)
- Claim-System fuer Supporter

### Leveling System
- XP fuer Nachrichten und Voice-Zeit
- Level-Rollen automatisch vergeben
- Leaderboard mit `/top`
- XP-Multiplikatoren fuer bestimmte Channels
- Level-Up Benachrichtigungen

---

## Phase 4: Utility & Tools

### Server Stats
- **Member Counter:** Live-Counter in Channel-Namen
- **Bot Counter:** Anzahl Bots
- **Online Counter:** Aktuell online
- **Boost Counter:** Server Boosts anzeigen

### Logging System
- **Message Logs:** Geloeschte/editierte Nachrichten loggen
- **Join/Leave Logs:** Mitglieder-Bewegungen
- **Role Logs:** Rollenaenderungen
- **Voice Logs:** Voice-Channel Aktivitaet
- **Mod Logs:** Alle Moderations-Aktionen

### Reminder System
- `/remind 2h Meeting starten` - Persoenliche Erinnerungen
- Wiederholende Reminder (taeglich, woechentlich)
- Channel-Reminder fuer Announcements

### Poll System
- `/poll "Frage" "Option 1" "Option 2"` - Umfragen erstellen
- Zeitlimit fuer Polls
- Anonyme Abstimmungen
- Multi-Choice Support

---

## Phase 5: Dashboard & API

### Web Dashboard
- **Tag Management:** Tags ueber Browser verwalten
- **Server Config:** Bot-Einstellungen ueber Web-Interface
- **Analytics:** Nutzungsstatistiken visualisiert
- **Audit Log:** Alle Aktionen nachvollziehbar

### REST API
- Endpoints fuer Tag CRUD
- Webhook-Integration fuer externe Tools
- Discord OAuth2 Login
- Rate-Limiting & API Keys

---

## Technische Verbesserungen

### Datenbank
- [ ] Flyway Migration Framework einbauen
- [ ] Tag-Versioning (History-Tabelle)
- [ ] Audit-Log Tabelle
- [ ] Redis Cache fuer haeufig genutzte Tags

### Performance
- [ ] Lazy-Loading fuer Guild-Caches
- [ ] Connection Pool Optimierung
- [ ] Batch-Updates fuer Usage-Counter
- [ ] Async Event Processing Pipeline

### Code-Qualitaet
- [ ] Unit Tests mit JUnit 5
- [ ] Integration Tests mit Testcontainers (MariaDB)
- [ ] CI/CD Pipeline (GitHub Actions)
- [ ] Docker Deployment
- [ ] Gradle Shadow JAR fuer einfaches Deployment

### Monitoring
- [ ] Health-Check Endpoint
- [ ] Prometheus Metrics
- [ ] Structured Logging (JSON)
- [ ] Error Tracking (Sentry Integration)

---

## Prioritaeten

| Prioritaet | Feature | Aufwand |
|------------|---------|--------|
| Hoch | Auto-Response System | Mittel |
| Hoch | Warning System | Mittel |
| Hoch | Logging System | Mittel |
| Hoch | CI/CD + Docker | Klein |
| Mittel | Reaction Roles | Klein |
| Mittel | Welcome System | Klein |
| Mittel | Tag Permissions | Mittel |
| Mittel | Tag Templates | Mittel |
| Niedrig | Ticket System | Gross |
| Niedrig | Leveling System | Gross |
| Niedrig | Web Dashboard | Gross |
| Niedrig | REST API | Gross |
