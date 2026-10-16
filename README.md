# Faction Draft Bot (v2, Java)

Snake-draft bot for the five X factions sharing one Discord server. Each faction runs its own
draft, in its own channel, at the same time as the others.

Stack: Java 21 · Spring Boot 3.3 · JDA 5 · Postgres (Railway).

## Commands

**Leadership** (the faction's leadership role, or server admins). `faction` is optional: it
defaults to the draft channel you're in, or the one faction you lead.

| Command | What it does |
|---|---|
| `/setcaptains captain1..captain9 [random]` | Sets captains in pick order and gives them the captain role |
| `/startdraft [timer] [autopick] [random_order]` | Pulls everyone with the faction's draft role into the pool and starts |
| `/pause` · `/resume` | Freezes the clock; resume keeps the time that was left (min 15s) |
| `/undo` | Reverts the last pick, puts the player back and gives that captain the turn again |
| `/adminpick player` | Picks for whoever is on the clock |
| `/replacecaptain old new` | Swaps a captain — new one inherits team, queue, role and turn |
| `/addplayer member` · `/removeplayer player` | Late joiners and dropouts (removing a drafted player keeps the slot used) |
| `/enddraft` | Ends early, with a confirm button |

**Captains:** `/pick`, `/queue` (up to 5), `/myqueue`, `/cancelqueue`, `/nameteam name`.

**Everyone:** `/draftstatus`, `/remainingdraftees`.

**Server admins:** `/teardownteams [clear_captains]` — deletes every faction's team roles and
channels, and by default removes the captain role from everyone. Refuses while any draft is running.

## How it behaves

- **Live board:** one pinned message per faction, edited after every change. It shows who's on
  the clock with a live countdown, the next three picks, the last pick and every team. If someone
  deletes it, the bot posts a new one.
- **Turns:** the captain gets a channel ping and a DM. The previous ping is deleted, so the channel
  stays clean. There's a DM warning at 30 seconds left.
- **Timeout:** with `autopick`, it takes their first queued player, otherwise a random one.
  Without it, the bot announces time's up and waits.
- **Queues:** a captain with a queue is auto-picked the moment their turn starts. A manual `/pick`
  always beats the queue.
- **Teams:** `/nameteam` creates a role with the team name, plus a text and a voice channel in the
  team category. Only that team, the faction's leadership and the bot can see them. Players get the
  role as they're picked; renaming updates the role and channels.
- **Torn streaks:** Torn IDs are read from nicknames like `Name [123456]`. Active streaks are
  fetched in the background at draft start, cached, and shown in `/pick` suggestions and on the board.
- **Restarts:** all state is in Postgres. After a redeploy, a running draft resumes and the
  current captain's clock restarts.

## Railway setup

1. Add a **Postgres** service to the project.
2. On the bot service, set these variables:
   - `DISCORD_TOKEN`
   - `TORN_API_KEY` — a public or limited key is enough
   - `PGHOST=${{Postgres.PGHOST}}`, `PGPORT=${{Postgres.PGPORT}}`, `PGDATABASE=${{Postgres.PGDATABASE}}`,
     `PGUSER=${{Postgres.PGUSER}}`, `PGPASSWORD=${{Postgres.PGPASSWORD}}`
3. Deploy. The Dockerfile builds the jar. The table is created automatically on first start.

There's no web port, so leave the healthcheck off.

## Discord setup

- **Server Members Intent** must be enabled in the developer portal. The old bot already used it.
- **Bot permissions:** Manage Roles, Manage Channels, Manage Messages (to pin the board), View
  Channels, Send Messages, Embed Links, Connect.
- **Role order:** the bot's role must sit **above** the captain role. Team roles it creates are
  placed under its own role automatically.

## Config

Faction roles, draft channels, the captain role and the team category are set in
`src/main/resources/application.yml`.

## Building locally

```
mvn package     # runs the draft-logic tests and builds target/draft-bot.jar
```
