# Faction Draft Bot (v2, Java)

Snake-draft bot for the five X factions sharing one Discord server. Each faction runs its own
draft, in its own channel, at the same time as the others.

Stack: Java 21 · Spring Boot 3.3 · JDA 5 · Postgres (Railway).

## Commands

There are only two:

- **`/draft`** opens your private draft panel (only you can see it; it closes itself after 14 minutes).
  The faction comes from the channel you're in, or pick one with the `faction` option.
- **`/pick`** makes your pick, with live name suggestions.

### Leadership panel
Before the draft: **Set Captains** (also builds the participant list), **Name Team** (for any captain),
**Replace Captain**, **Participants**, **Add Player** (Discord member or Torn ID), **Remove Players**
(search, then tick up to 25), **Rebuild List**, and **Start Draft** (timer, auto-pick and order
options — only enabled once there are 2+ captains and every team is named).

During the draft: **Pause/Resume**, **Undo**, **Admin Pick**, **End Draft**, plus Participants,
Add/Remove Players, Replace Captain and Rename Team.

Server admins also get **Teardown**, which deletes every faction's team roles and channels.

### Captain panel
Your team, your picks and your **queue** — **Add to Queue** (search, then choose), **Remove from
Queue**, **Clear Queue** — plus **Name/Rename Team** and **Participants**. Queued players are picked
for you automatically when your turn starts.

### Everyone else
A read-only status view: who's on the clock, the captains and their teams, and the pool.

## How it behaves

- **Live board:** the only message the bot posts in each draft channel, edited after every change. It shows who's on
  the clock with a live countdown, the next three picks, the last pick and every team. If someone
  deletes it, the bot posts a new one.
- **DMs, not channel messages:** command confirmations go to whoever ran the command by DM (falling
  back to a private reply if their DMs are closed). Captains are DM'd when it's their turn, at 30
  seconds left, on auto-picks, admin picks, pause, replacement and when the draft ends.
- **Timeout:** with `autopick`, it takes their first queued player, otherwise a random one.
  Without it, the captain is DM'd that time's up and the draft waits.
- **Queues:** a captain with a queue is auto-picked the moment their turn starts. A manual `/pick`
  always beats the queue.
- **Teams:** `/nameteam Alpha` creates an "Alpha" role, an "Alpha Captain" role (given only to that
  team's captain) and a text + voice channel in the team category. Only the team (plus any roles in
  `team-channel-role-ids`, empty by default) and the bot can see them. Players get the team role as
  they're picked; renaming updates both roles and the channels; `/replacecaptain` moves the captain role.
- **Torn streaks:** Torn IDs are read from nicknames like `Name [123456]`. Active streaks are
  fetched in the background at draft start, cached, and shown in `/pick`, `/queue` and `/adminpick` suggestions.
- **Players not on Discord:** shown as "not on Discord" in suggestions, on the board and in
  `/remainingdraftees`. They can be picked like anyone else but get no roles, channels or DMs.
- **Restarts:** all state is in Postgres. After a redeploy, a running draft resumes and the
  current captain's clock restarts.

## Attack competition

Each faction's leaders set it up from `/draft`:

- **🗓️ Competition Times** — start and end in TCT (`2026-10-25 18:00`). The start can't move once it's live.
- **🔑 API Keys** — Limited keys from members with **Faction API Access**. Each key is checked
  (valid, in this faction, can read faction attacks) and stored AES-encrypted; it's never shown again.
  Add two or more so monitoring survives one being removed.

While live, the bot reads `GET /v2/faction/attacksfull?filters=outgoing` every minute with the
faction's keys (rotating), stores each attack once by its ID, and updates that faction's scoreboard
message in the scoreboard channel. After a restart it catches up from where it stopped.

**What counts:** results Attacked, Mugged, Hospitalized, Arrested, Looted, Special and Bounty; by a
drafted player or captain (matched on the `[ID]` in their nickname, or their Torn ID for players not
on Discord); defender not in one of the other family factions; attack ended inside the window.
Stealthed attacks count. The scoreboard ranks teams and lists each team's top 10 hitters, and is
marked **🏁 Final** two minutes after the end once the last hits are in.

If every key fails, the leaders who added them get a DM. Teardown is blocked while a competition is live.

## Railway setup

1. Add a **Postgres** service to the project.
2. On the bot service, set these variables:
    - `DISCORD_TOKEN`
    - `TORN_API_KEY` — a public or limited key is enough
    - `KEY_ENCRYPTION_SECRET` — any long random string; never change it once keys are stored
    - `PGHOST=${{Postgres.PGHOST}}`, `PGPORT=${{Postgres.PGPORT}}`, `PGDATABASE=${{Postgres.PGDATABASE}}`,
      `PGUSER=${{Postgres.PGUSER}}`, `PGPASSWORD=${{Postgres.PGPASSWORD}}`
3. Deploy. The Dockerfile builds the jar. The table is created automatically on first start.

There's no web port, so leave the healthcheck off.

## Discord setup

- **Server Members Intent** must be enabled in the developer portal. The old bot already used it.
- **Bot permissions:** Manage Roles, Manage Channels, Manage Messages, View
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