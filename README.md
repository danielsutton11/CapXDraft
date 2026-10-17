# Faction Draft Bot (v2, Java)

Snake-draft bot for the five X factions sharing one Discord server. Each faction runs its own
draft, in its own channel, at the same time as the others, followed by an attack competition
between the drafted teams.

Stack: Java 21 · Spring Boot 3.3 · JDA 6 · Postgres (Railway).

## Commands

There's only one:

- **`/draft`** opens your private draft panel (only you can see it; it closes itself after 14 minutes).
  It only works in a faction's draft channel, and opens that faction's panel. Server admins can use
  it anywhere and pick a faction with the `faction` option.

Everything else, picking included, happens through the panel's buttons, dropdowns and pop-up forms.

### Leadership panel
One coloured box with section headings.

Before the draft:
- **📋 Setup — required, in order:** a checklist plus one row of buttons — **Captains** (also builds
  the participant list) → **Team Names** → **Competition Times** → **API Keys**. Each gets a ✅ when
  done and the next step is blue. **▶️ Start Draft** appears under them once all four are done.
- **👥 Participants:** View, Add (Discord member or Torn ID), Remove, Rebuild List.
- **👑 Captains:** the list, Replace Captain, My Team (if you're a captain too).
- **🏁 Competition:** status, once times are set.
- **🧹 Clean Up** (server admins only) and Close.

During the draft: Pause/Resume, Undo and Admin Pick at the top (plus 🎯 Pick Player if it's your own
pick), then Participants, Teams (Replace Captain, Team Names), Competition (times and keys), and End Draft.

After the draft: the teams, the competition, and a reminder that an admin needs to run Clean Up
before the next draft.

### Captain panel
Where you are in the order (with the countdown), and **🎯 Pick Player** when you're on the clock
(search, then choose). Then your **queue** — Queue Player, Unqueue, Clear — and your picks, plus
Name/Rename Team and Participants. Queued players are picked for you automatically when your turn
starts. The queue limit is `max-queue` in the config.

A leader who's also a captain lands on their captain panel while it's their pick; the 🎛️ Control
Panel button always takes them to the leader panel.

### Everyone else
A read-only status view: who's on the clock, the captains and their teams, and the participants.

## Rules worth knowing

- **Clean Up before a new draft.** Once a draft has finished or been ended, captains can't be set
  again until an admin runs 🧹 Clean Up for that faction. That stops old team roles and members
  carrying over.
- **Clean Up is per faction.** It deletes that faction's teams (roles, text and voice channels),
  participant list, removals and competition, clears the bot's messages from its draft channel and
  removes its scoreboard from the live scores channel. Other factions are untouched. It's blocked
  while the faction's draft is running or its competition is live.
- **Rosters lock while the competition is live.** Undo, Add, Remove, Admin Pick and Replace Captain
  are refused until it ends, because scores follow whoever is on each team. Captains can still pick
  if the draft has overrun.
- **The competition must start after the draft does.** Start times in the past are refused, and so
  is starting a draft once its competition has begun. Leaders are DM'd 30 minutes before the start,
  and again at the start, if the draft is still running.
- **Team role names carry a faction tag**, e.g. "CX · Alpha" and "CX · Alpha Captain", so teams with
  the same name in different factions can be told apart. Tags are set per faction in the config.

## How it behaves

- **Live board:** the only lasting message the bot posts in each draft channel, edited after every
  change. It shows who's on the clock with a live countdown, the next three picks, the last pick and
  every team. If someone deletes it, the bot posts a new one.
- **DMs:** captains are DM'd when it's their turn, at 30 seconds left, on auto-picks, admin picks,
  pause, replacement and when the draft ends. If the "it's your pick" DM can't be delivered, the
  captain is pinged in the draft channel instead, and that ping is deleted when the turn moves on.
- **Timeout:** with auto-pick on, it takes their first queued player, otherwise a random one.
  Without it, the captain is DM'd that time's up and the draft waits.
- **Queues:** a captain with a queue is auto-picked the moment their turn starts. A manual pick
  always beats the queue.
- **Teams:** naming a team creates a team role, a team captain role (given only to that team's
  captain) and a text + voice channel in the team category. Only the team (plus any roles in
  `team-channel-role-ids`, empty by default) and the bot can see them. The captain role can also see
  the faction's draft channel. Players get the team role as they're picked; renaming updates both
  roles and the channels.
- **Replace Captain** moves the team, queue and turn to the new captain. If the new captain was in
  the participants or already drafted (matched by Discord ID or Torn ID), they're taken out — a
  drafted player's slot stays used, and that team's captain is DM'd. The old captain goes back into
  the participants.
- **Participants:** the Torn faction's members, matched to Discord by the `[ID]` in their nickname,
  or failing that by their nickname being their Torn name. Faction members nobody on Discord matches
  join as "not on Discord" players (no roles, channels or DMs, but draftable). Draft-role members who
  aren't in the Torn faction are left out and listed, since their hits can't be tracked; ➕ Add brings
  them in anyway. If Torn can't be reached, the list is just the draft-role members.
- **Torn streaks:** active streaks are fetched in the background and shown in search results.
- **Restarts:** all state is in Postgres. After a redeploy, a running draft resumes and the
  current captain's clock restarts.
- **Failed actions roll back:** if an action fails partway, the faction goes back to its last saved
  state. Discord changes that already happened (like a role being added) aren't undone.
- **Unreadable saved data:** if a faction's saved state can't be read at startup, it's copied to the
  `faction_state_backup` table and that faction is locked (its panel says so) so it's never
  overwritten. Fix or delete its row in `faction_draft_state` and restart. The other factions carry on.

## Attack competition

Each faction's leaders set it up from `/draft`:

- **🗓️ Competition Times** — start and end in TCT (`25-10-2026 18:00`). The start must be in the
  future, and can't move once it's live. Clearing both times cancels it and deletes the scoreboard.
- **🔑 API Keys** — Limited keys from members with **Faction API Access**. Each key is checked
  (valid, in this faction, can read faction attacks) and stored AES-encrypted; it's never shown again.
  At least two are needed so monitoring survives one failing.

While live, the bot reads `GET /v2/faction/attacksfull?filters=outgoing` every minute with the
faction's keys (rotating, working keys first), stores each attack once by its ID, and updates that
faction's scoreboard message in the scoreboard channel. After a restart it catches up from where it
stopped. This runs on its own thread, so slow Torn responses never hold up the draft's timers.

**What counts:** results Attacked, Mugged, Hospitalized, Arrested, Looted, Special and Bounty; by a
drafted player or captain (matched on Torn ID); defender not in any of the five family factions —
your own included — at the time of the attack; attack ended inside the window. Stealthed attacks
count. The scoreboard ranks teams with each team's top 10 hitters, and is marked **🏁 Final results**
two minutes after the end once the last hits are in.

**Keeping it honest:**
- Every 15 minutes the bot checks the Torn faction's member list. Drafted players who've left are
  marked "(left faction)" on the scoreboard and listed on the leader panel; their earlier hits still
  count, but later ones can't be seen.
- Every attack says which faction the attacker was in. If a key starts returning another faction's
  attacks (its owner moved), it's marked as failing. Keys are also re-checked hourly and flagged if
  their owner has left the faction.
- If every key fails, the leaders who added them get a DM.

## Railway setup

1. Add a **Postgres** service to the project.
2. On the bot service, set these variables:
   - `DISCORD_TOKEN`
   - `TORN_API_KEY` — a public or limited key is enough
   - `KEY_ENCRYPTION_SECRET` — any long random string (16+ characters); never change it once keys are stored
   - `PGHOST=${{Postgres.PGHOST}}`, `PGPORT=${{Postgres.PGPORT}}`, `PGDATABASE=${{Postgres.PGDATABASE}}`,
     `PGUSER=${{Postgres.PGUSER}}`, `PGPASSWORD=${{Postgres.PGPASSWORD}}`
3. Deploy. The Dockerfile builds the jar. Tables are created automatically on start.

There's no web port, so leave the healthcheck off.

## Discord setup

- **Server Members Intent** must be enabled in the developer portal.
- **Bot permissions:** Manage Roles, Manage Channels, Manage Messages, View Channels, Send Messages,
  Embed Links, Connect.
- **Role order:** team roles and team captain roles are placed under the bot's own role automatically.
- **Command channels (optional):** Server Settings → Integrations → the bot → `/draft` can be limited
  to the five draft channels. The bot enforces this itself too, except for server admins.

## Config

Faction roles, draft channels, faction tags and the team category are set in
`src/main/resources/application.yml`.

## Building locally

```
mvn package     # runs the tests and builds target/draft-bot.jar
```
