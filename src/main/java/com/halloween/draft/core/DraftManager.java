package com.halloween.draft.core;

import com.halloween.draft.config.BotProperties;
import com.halloween.draft.config.BotProperties.Faction;
import com.halloween.draft.model.Draft;
import com.halloween.draft.model.DraftStatus;
import com.halloween.draft.model.FactionState;
import com.halloween.draft.model.Pick;
import com.halloween.draft.model.PickMethod;
import com.halloween.draft.model.Player;
import com.halloween.draft.model.Team;
import jakarta.annotation.PostConstruct;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.EmbedBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Owns every faction's draft. Each faction has its own lock, so the five drafts run
 * independently; all state changes for a faction happen under its lock and are saved
 * to Postgres before the lock is released.
 *
 * Methods here block on Discord calls, so they must be called from worker threads,
 * never from the JDA event thread.
 */
@Service
public class DraftManager {
    private static final Logger log = LoggerFactory.getLogger(DraftManager.class);
    private static final long WARNING_MS = 30_000;
    public static final int MAX_CAPTAINS = 9; // header + 9 team embeds = Discord's 10-embed limit

    /** Lock-free copy of what autocomplete needs, refreshed on every save. */
    public record Snapshot(List<Player> eligible, List<Player> drafted, Set<Long> captains, DraftStatus status,
                           List<Long> captainOrder, Map<Long, String> teamNames, Long compStart, Long compEnd) {}

    private final BotProperties props;
    private final StateRepository repo;
    private final BoardRenderer board;
    private final TeamService teams;
    private final TornService torn;
    private final TimerService timers;
    private final com.halloween.draft.competition.CompetitionRepository competitionRepo;
    private final TeamImageService images;

    private final Map<String, FactionState> states = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    /** Factions whose saved state couldn't be read: backed up, and locked so it's never overwritten. */
    private final Set<String> broken = ConcurrentHashMap.newKeySet();
    /** Torn IDs whose streak lookup is queued or running, per faction, so the same player isn't looked up twice at once. */
    private final Map<String, Set<Long>> streaksInFlight = new ConcurrentHashMap<>();
    private volatile JDA jda;

    public DraftManager(BotProperties props, StateRepository repo, BoardRenderer board,
                        TeamService teams, TornService torn, TimerService timers,
                        com.halloween.draft.competition.CompetitionRepository competitionRepo,
                        TeamImageService images) {
        this.props = props;
        this.repo = repo;
        this.board = board;
        this.teams = teams;
        this.torn = torn;
        this.timers = timers;
        this.competitionRepo = competitionRepo;
        this.images = images;
    }

    @PostConstruct
    void load() {
        StateRepository.LoadResult result = repo.loadAll();
        Map<String, FactionState> saved = result.states();
        broken.addAll(result.unreadable());
        for (Faction f : props.getFactions()) {
            if (broken.contains(f.getKey())) {
                locks.put(f.getKey(), new ReentrantLock(true));
                log.error("{}: saved state unreadable - faction locked until the row is fixed", f.getName());
                continue;
            }
            FactionState s = saved.getOrDefault(f.getKey(), new FactionState(f.getKey()));
            s.factionKey = f.getKey();
            states.put(f.getKey(), s);
            locks.put(f.getKey(), new ReentrantLock(true));
            snapshot(s);
        }
        log.info("Loaded draft state for {} factions", states.size());
    }

    /** Called once the bot is connected: restarts clocks for drafts that were live before a restart. */
    public void attach(JDA jda) {
        this.jda = jda;
        for (Faction f : props.getFactions()) {
            if (broken.contains(f.getKey())) continue;
            try {
                withLock(f.getKey(), () -> {
                    FactionState s = state(f);
                    TextChannel home = jda.getTextChannelById(f.getChannelId());
                    Guild hg = home == null ? null : home.getGuild();
                    if (hg != null) {
                        s.teams.values().forEach(t -> teams.styleCaptainRole(hg, t)); // existing captain roles too
                        // The draft channel always has a message with the Open my draft panel button:
                        // the board once a draft exists, the launcher before that.
                        if (s.draft == null) ensureLauncher(f, s, hg);
                        else if (!s.draftRunning()) commit(f, s, hg);
                    }
                    if (!s.draftRunning()) return null;
                    Guild g = guildFor(s.draft);
                    if (g == null) return null;
                    if (s.draft.status == DraftStatus.ACTIVE) {
                        advance(f, s, g, null);
                    }
                    commit(f, s, g);
                    return null;
                });
                fetchMissingStreaks(f, 1); // anything a previous run didn't get to
            } catch (Exception e) {
                log.error("Failed to resume draft for {}", f.getKey(), e);
            }
        }
        // If the board or launcher gets deleted (by hand, a purge bot...), put it back within a few minutes.
        timers.every(5 * 60_000, 5 * 60_000, this::healDraftChannels);
        // Streaks change at Torn's day reset, so refresh them once a day just after it (00:15 TCT).
        timers.every(msUntilNextTct(0, 15), 24 * 60 * 60_000L, this::refreshAllStreaks);
    }

    /** Milliseconds until the next hh:mm TCT (UTC). */
    static long msUntilNextTct(int hour, int minute) {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC);
        java.time.ZonedDateTime next = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0);
        if (!next.isAfter(now)) next = next.plusDays(1);
        return java.time.Duration.between(now, next).toMillis();
    }

    /**
     * Daily: fetches fresh active streaks for everyone who can still be picked — the participant
     * list before a draft, the remaining players during one. Drafted players keep what they had.
     */
    void refreshAllStreaks() {
        for (Faction f : props.getFactions()) {
            if (broken.contains(f.getKey())) continue;
            try {
                List<Player> players = withLock(f.getKey(), () -> {
                    FactionState s = state(f);
                    if (s.draftRunning()) return List.copyOf(s.draft.eligible);
                    return s.pool == null ? List.<Player>of() : List.copyOf(s.pool);
                });
                if (!players.isEmpty()) {
                    log.info("{}: daily streak refresh for {} players", f.getName(), players.size());
                    fetchStreaks(f, players);
                }
            } catch (Exception e) {
                log.warn("{}: daily streak refresh failed: {}", f.getName(), e.getMessage());
            }
        }
    }

    /**
     * Makes sure every draft channel still has its message with the Open my draft panel button:
     * the board once a draft exists, the launcher before that. Editing an existing message is a
     * no-op for viewers; a missing one is posted again.
     */
    void healDraftChannels() {
        JDA j = jda;
        if (j == null) return;
        for (Faction f : props.getFactions()) {
            if (broken.contains(f.getKey())) continue;
            try {
                withLock(f.getKey(), () -> {
                    FactionState s = state(f);
                    TextChannel home = j.getTextChannelById(f.getChannelId());
                    if (home == null) {
                        log.warn("{}: draft channel {} not found", f.getName(), f.getChannelId());
                        return null;
                    }
                    Guild g = home.getGuild();
                    if (s.draft == null) {
                        ensureLauncher(f, s, g);
                    } else {
                        Long before = s.draft.boardMessageId;
                        refreshBoard(f, s, g);
                        if (!Objects.equals(before, s.draft.boardMessageId)) save(s);
                    }
                    return null;
                });
            } catch (Exception e) {
                log.warn("{}: draft channel check failed: {}", f.getName(), e.getMessage());
            }
        }
    }

    // =====================================================================
    // Faction / permission resolution
    // =====================================================================

    public boolean isLeader(Member m, Faction f) {
        return m.hasPermission(Permission.ADMINISTRATOR)
                || m.getRoles().stream().anyMatch(r -> r.getIdLong() == f.getLeadershipRoleId());
    }

    /** faction option → draft channel → the only faction this member leads. */
    public Faction resolveLeaderFaction(Member m, String option, long channelId) {
        Faction f = factionFromOptionOrChannel(option, channelId).orElseGet(() -> {
            List<Faction> led = props.getFactions().stream()
                    .filter(x -> m.getRoles().stream().anyMatch(r -> r.getIdLong() == x.getLeadershipRoleId()))
                    .toList();
            if (led.size() == 1) return led.get(0);
            throw new UserError("Which faction? Add the `faction` option or run this in the faction's draft channel.");
        });
        if (!isLeader(m, f)) throw new UserError("Only " + f.getName() + " leadership can do that.");
        return f;
    }

    /** The faction this user captains: preferring the channel's faction if they captain several. */
    public Faction resolveCaptainFaction(long userId, long channelId, boolean requireDraft) {
        List<Faction> candidates = props.getFactions().stream().filter(f -> {
            Snapshot snap = snapshots.get(f.getKey());
            if (snap == null || !snap.captains().contains(userId)) return false;
            return !requireDraft || (snap.status() != null && snap.status().running());
        }).toList();
        if (candidates.isEmpty()) {
            throw new UserError(requireDraft
                    ? "You're not a captain in a running draft."
                    : "You're not a captain for any faction.");
        }
        if (candidates.size() == 1) return candidates.get(0);
        return candidates.stream().filter(f -> f.getChannelId() == channelId).findFirst()
                .orElseThrow(() -> new UserError("You're a captain in more than one draft — run this in that faction's draft channel."));
    }

    /**
     * Which faction's panel /draft opens. Everyone except server admins must use a faction's draft
     * channel, and gets that faction. Admins can use the faction option anywhere: option → channel →
     * captain's faction → led faction.
     */
    public Faction resolveViewFaction(Member m, String option, long channelId) {
        if (!m.hasPermission(Permission.ADMINISTRATOR)) {
            Faction here = props.factionForChannel(channelId)
                    .orElseThrow(() -> new UserError("Use the 🎛️ **Open my draft panel** button in your faction's draft channel."));
            if (option != null && !option.isBlank() && !option.equalsIgnoreCase(here.getKey())) {
                throw new UserError("This is the " + here.getName() + " draft channel — use the 🎛️ **Open my draft panel** button in the other "
                        + "faction's channel to open theirs.");
            }
            return here;
        }
        Optional<Faction> f = factionFromOptionOrChannel(option, channelId);
        if (f.isPresent()) return f.get();
        Optional<Faction> cap = Optional.ofNullable(captainFactionOrNull(m.getIdLong(), channelId));
        if (cap.isPresent()) return cap.get();
        List<Faction> mine = props.getFactions().stream()
                .filter(x -> m.getRoles().stream().anyMatch(r ->
                        r.getIdLong() == x.getLeadershipRoleId() || r.getIdLong() == x.getDraftRoleId()))
                .toList();
        if (mine.size() == 1) return mine.get(0);
        throw new UserError("Which faction? Add the `faction` option or run this in the faction's draft channel.");
    }

    public Faction captainFactionOrNull(long userId, long channelId) {
        try {
            return resolveCaptainFaction(userId, channelId, true);
        } catch (UserError e) {
            return null;
        }
    }

    private Optional<Faction> factionFromOptionOrChannel(String option, long channelId) {
        if (option != null && !option.isBlank()) {
            return Optional.of(props.faction(option).orElseThrow(() -> new UserError("Unknown faction.")));
        }
        return props.factionForChannel(channelId);
    }

    public Snapshot snapshot(String factionKey) {
        return snapshots.get(factionKey);
    }

    // =====================================================================
    // Leader commands
    // =====================================================================

    public String setCaptains(Guild g, Member invoker, String opt, long channelId, List<Member> chosen, boolean random) {
        Faction f = resolveLeaderFaction(invoker, opt, channelId);
        return withLock(f.getKey(), () -> {
            FactionState s = state(f);
            if (s.draftRunning()) throw new UserError("A draft is running — use 🔁 Replace Captain to swap someone.");
            if (s.needsCleanUp()) {
                throw new UserError("Run 🧹 Clean Up first — the last " + f.getName() + " draft's teams still exist. "
                        + "Clean Up is admin-only.");
            }

            Map<Long, Member> byId = new LinkedHashMap<>();
            chosen.forEach(m -> byId.putIfAbsent(m.getIdLong(), m));
            List<Member> unique = new ArrayList<>(byId.values());
            if (unique.stream().anyMatch(m -> m.getUser().isBot())) throw new UserError("Bots can't be captains.");
            if (unique.size() < 2) throw new UserError("You need at least 2 captains.");
            if (unique.size() > MAX_CAPTAINS) throw new UserError("Maximum is " + MAX_CAPTAINS + " captains.");
            if (random) Collections.shuffle(unique);

            List<Long> newIds = unique.stream().map(Member::getIdLong).toList();
            List<Long> previous = s.captains == null ? List.of() : List.copyOf(s.captains);
            s.captains = new ArrayList<>(newIds);

            // Build (or rebuild) the pool straight away so leadership can review and trim it.
            PoolResult pool = buildPool(g, f, s);
            s.pool = pool.players();
            save(s);
            fetchStreaks(f, pool.players().stream().filter(p -> p.streak == null).toList());

            // Only once everything has worked: tell new captains (and anyone still without a team
            // name) what to do next. Re-saving the same, already-named captains sends nothing.
            for (Long id : newIds) {
                if (previous.contains(id) && teamNamed(s, id)) continue;
                dm(id, "👑 You're a **" + f.getName() + "** draft captain!\n"
                        + (teamNamed(s, id) ? "Your team is **" + s.teams.get(id).name + "**. "
                        : "**Name your team:** go to <#" + f.getChannelId()
                        + ">, press 🎛️ **Open my draft panel**, then 🏷️ **Name Team**.\n")
                        + "You'll get a DM here when it's your turn to pick.");
            }

            return "✅ **" + unique.size() + " captains set** and the participant list is built ("
                    + pool.players().size() + " players)." + pool.note();
        });
    }

    public String startDraft(Guild g, Member invoker, String opt, long channelId,
                             int timerSeconds, boolean autopick, boolean randomOrder) {
        Faction f = resolveLeaderFaction(invoker, opt, channelId);
        if (timerSeconds != 0 && (timerSeconds < 60 || timerSeconds > 3600)) {
            throw new UserError("Timer must be 0 (off) or between 1 and 60 minutes.");
        }
        return withLock(f.getKey(), () -> {
            FactionState s = state(f);
            if (s.draftRunning()) throw new UserError("A " + f.getName() + " draft is already running. End it first.");

            TextChannel channel = g.getTextChannelById(f.getChannelId());
            if (channel == null) throw new UserError("I can't find the " + f.getName() + " draft channel.");
            Role draftRole = g.getRoleById(f.getDraftRoleId());
            if (draftRole == null) throw new UserError("I can't find the " + f.getName() + " draft role.");

            // Captains must be set with /setcaptains, and every one of them must have named their team.
            if (s.captains.size() < 2) {
                throw new UserError("Choose at least 2 captains before starting the draft.");
            }
            List<Member> captains = new ArrayList<>();
            for (Long id : s.captains) {
                Member m = g.getMemberById(id);
                if (m == null) throw new UserError("Captain <@" + id + "> isn't in the server any more — choose captains again.");
                captains.add(m);
            }
            if (captains.size() > MAX_CAPTAINS) throw new UserError("Maximum is " + MAX_CAPTAINS + " captains.");
            List<String> unnamed = captains.stream()
                    .filter(m -> { Team t = s.teams.get(m.getIdLong()); return t == null || t.roleId == null; })
                    .map(Member::getAsMention).toList();
            if (!unnamed.isEmpty()) {
                throw new UserError("These captains still need a team name: "
                        + String.join(", ", unnamed) + ".");
            }
            // The competition has to be ready before anyone is drafted into it.
            if (!s.competition.scheduled()) {
                throw new UserError("Set the competition times first (🗓️ Competition Times).");
            }
            // Hits count for whoever is on a team, so the draft must start before the competition does;
            // otherwise hits made before the teams existed would land on late picks.
            if (System.currentTimeMillis() / 1000 >= s.competition.startEpoch) {
                throw new UserError("The competition already started at " + tct(s.competition.startEpoch)
                        + " TCT. Set a later start under 🗓️ Competition Times before starting the draft.");
            }
            int keys = competitionRepo.keys(f.getKey()).size();
            int needed = com.halloween.draft.competition.CompetitionService.MIN_KEYS;
            if (keys < needed) {
                throw new UserError("Add at least " + needed + " faction API keys first (🔑 API Keys) — "
                        + keys + " added so far.");
            }

            // Pool: the one prepared with /buildpool, or build it now (draft role + Torn faction members).
            String poolNote = "";
            List<Player> pool;
            if (s.pool != null) {
                pool = new ArrayList<>(s.pool);
            } else {
                PoolResult built = buildPool(g, f, s);
                pool = built.players();
                poolNote = built.note();
            }
            Set<Long> captainIds = captains.stream().map(Member::getIdLong).collect(Collectors.toSet());
            Set<Long> captainTornIds = captains.stream().map(m -> TornService.parseTornId(m.getEffectiveName()))
                    .filter(Objects::nonNull).collect(Collectors.toSet());
            pool.removeIf(p -> captainIds.contains(p.id) || (p.tornId != null && captainTornIds.contains(p.tornId))
                    || s.isExcluded(p));
            if (pool.isEmpty()) throw new UserError("There are no " + f.getName() + " participants left to draft.");

            Draft d = new Draft();
            List<Member> order = new ArrayList<>(captains);
            if (randomOrder) Collections.shuffle(order);
            order.forEach(m -> {
                d.captainOrder.add(m.getIdLong());
                d.captainNames.put(m.getIdLong(), m.getEffectiveName());
                d.queues.put(m.getIdLong(), new ArrayList<>());
            });
            d.eligible = pool;
            d.sortEligible();
            d.timerSeconds = timerSeconds;
            d.autopick = autopick;
            d.channelId = f.getChannelId();
            d.status = DraftStatus.ACTIVE;
            s.draft = d;
            s.pool = null; // the prepared pool now lives in the draft
            s.competition.draftSoonWarned = false; // a new draft gets its own overrun warnings
            s.competition.draftLiveWarned = false;

            clearOwnMessages(channel); // launcher, old boards and pings - the new board is the only message
            s.launcherMessageId = null;
            refreshBoard(f, s, g);
            advance(f, s, g, null);
            commit(f, s, g);
            fetchStreaks(f, pool.stream().filter(p -> p.streak == null).toList());
            long offDiscord = pool.stream().filter(p -> !p.onDiscord()).count();

            // With a timer, the slowest case is every pick using its full time. Warn if that would run
            // into the competition, since late picks would then be credited with earlier hits.
            String overrun = "";
            if (timerSeconds > 0) {
                long worstEnd = System.currentTimeMillis() / 1000 + (long) pool.size() * timerSeconds;
                if (worstEnd > s.competition.startEpoch) {
                    overrun = "\n⚠️ If every pick uses the full " + timerSeconds / 60 + " minutes, the draft could run until "
                            + tct(worstEnd) + " TCT — past the competition start at " + tct(s.competition.startEpoch)
                            + " TCT. Leaders will be DM'd 30 minutes before the start if it's still running.";
                }
            }
            return "🏁 " + f.getName() + " draft started in " + channel.getAsMention() + " — " + pool.size()
                    + " players (" + offDiscord + " not on Discord), " + order.size() + " captains.\nOrder: "
                    + order.stream().map(Member::getEffectiveName).collect(Collectors.joining(" → ")) + poolNote
                    + overrun;
        });
    }

    public String pause(Guild g, Member invoker, String opt, long channelId) {
        Faction f = resolveLeaderFaction(invoker, opt, channelId);
        return withLock(f.getKey(), () -> {
            FactionState s = state(f);
            Draft d = requireStatus(s, f, DraftStatus.ACTIVE);
            d.pausedRemainingMs = d.turnDeadlineMs == null ? null
                    : Math.max(0, d.turnDeadlineMs - System.currentTimeMillis());
            d.turnDeadlineMs = null;
            d.turnToken++;
            d.status = DraftStatus.PAUSED;
            dm(d.currentCaptain(), "⏸️ The " + f.getName() + " draft has been paused by leadership — your clock is frozen.");
            commit(f, s, g);
            return "Paused.";
        });
    }

    public String resume(Guild g, Member invoker, String opt, long channelId) {
        Faction f = resolveLeaderFaction(invoker, opt, channelId);
        return withLock(f.getKey(), () -> {
            FactionState s = state(f);
            Draft d = requireStatus(s, f, DraftStatus.PAUSED);
            Long clock = d.pausedRemainingMs == null ? null : Math.max(d.pausedRemainingMs, 15_000);
            d.pausedRemainingMs = null;
            d.status = DraftStatus.ACTIVE;
            advance(f, s, g, clock);
            commit(f, s, g);
            return "Resumed.";
        });
    }

    public String undo(Guild g, Member invoker, String opt, long channelId) {
        Faction f = resolveLeaderFaction(invoker, opt, channelId);
        return withLock(f.getKey(), () -> {
            FactionState s = state(f);
            requireRostersUnlocked(s, f);
            Draft d = s.draft;
            if (d == null || d.status == DraftStatus.ENDED) throw new UserError("No " + f.getName() + " draft to undo.");
            Pick undone = d.undoLast().orElseThrow(() -> new UserError("There are no picks to undo."));
            if (!undone.dropped) teams.removeMember(g, s.teams.get(undone.captainId), undone.player.id);

            advance(f, s, g, null);
            commit(f, s, g);
            return "↩️ Undid pick #" + undone.number + " (**" + undone.player.display + "**) — they're back in the participants.";
        });
    }

    public String adminPick(Guild g, Member invoker, String opt, long channelId, String query) {
        Faction f = resolveLeaderFaction(invoker, opt, channelId);
        return withLock(f.getKey(), () -> {
            FactionState s = state(f);
            requireRostersUnlocked(s, f);
            Draft d = requireRunning(s, f);
            Player p = resolveEligible(d, query);
            long captain = d.currentCaptain();
            Pick pick = d.recordPick(p, PickMethod.ADMIN);
            onPicked(s, g, pick);
            dm(captain, "🛡️ Leadership picked **" + p.display + "** for your team (pick #" + pick.number + ").");
            advance(f, s, g, null);
            commit(f, s, g);
            return "Picked " + p.display + " for " + d.captainName(captain) + ".";
        });
    }

    public String replaceCaptain(Guild g, Member invoker, String opt, long channelId, Member oldCap, Member newCap) {
        Faction f = resolveLeaderFaction(invoker, opt, channelId);
        return withLock(f.getKey(), () -> {
            FactionState s = state(f);
            requireRostersUnlocked(s, f);
            long oldId = oldCap.getIdLong();
            long newId = newCap.getIdLong();
            if (newCap.getUser().isBot()) throw new UserError("Bots can't be captains.");
            if (oldId == newId) throw new UserError("That's the same person.");
            List<Long> current = s.activeCaptains();
            if (!current.contains(oldId)) throw new UserError(oldCap.getEffectiveName() + " isn't a " + f.getName() + " captain.");
            if (current.contains(newId)) throw new UserError(newCap.getEffectiveName() + " is already a captain.");

            s.captains.replaceAll(id -> id == oldId ? newId : id);
            boolean running = s.draftRunning();
            boolean wasOnClock = false;
            List<String> notes = new ArrayList<>();
            Draft d = s.draft;
            if (d != null) {
                // The new captain can't also be a player: match by Discord id or Torn ID, since they
                // may be in the list as a "not on Discord" entry.
                Player newAsPlayer = toPlayer(newCap);
                for (Pick pk : d.picks) {
                    if (pk.dropped || !samePerson(pk.player, newAsPlayer)) continue;
                    pk.dropped = true;
                    teams.removeMember(g, s.teams.get(pk.captainId), pk.player.id);
                    if (pk.captainId != oldId) {
                        dm(pk.captainId, "🔁 " + newCap.getEffectiveName() + " was made a " + f.getName()
                                + " captain, so they've been removed from your team (the pick slot stays used).");
                    }
                    notes.add(newCap.getEffectiveName() + " was taken off "
                            + s.teamName(pk.captainId, d.captainName(pk.captainId)) + " (pick #" + pk.number + ")");
                }
                if (running) {
                    List<Long> gone = d.eligible.stream().filter(x -> samePerson(x, newAsPlayer)).map(x -> x.id).toList();
                    d.eligible.removeIf(x -> samePerson(x, newAsPlayer));
                    gone.forEach(d::removeFromAllQueues);
                    // The old captain becomes an ordinary participant who can be drafted.
                    Player oldAsPlayer = toPlayer(oldCap);
                    boolean present = d.eligible.stream().anyMatch(x -> samePerson(x, oldAsPlayer))
                            || d.picks.stream().anyMatch(x -> !x.dropped && samePerson(x.player, oldAsPlayer));
                    if (!present && !s.isExcluded(oldAsPlayer)) {
                        d.eligible.add(oldAsPlayer);
                        d.sortEligible();
                        notes.add(oldCap.getEffectiveName() + " is back in the participants");
                        fetchStreaks(f, List.of(oldAsPlayer));
                    }
                    wasOnClock = Objects.equals(d.currentCaptain(), oldId);
                }
                d.replaceCaptain(oldId, newId, newCap.getEffectiveName());
                if (running && d.eligible.isEmpty()) d.status = DraftStatus.COMPLETE;
            }
            Team team = s.teams.remove(oldId);
            if (team != null) {
                s.teams.put(newId, team);
                teams.removeMember(g, team, oldId);
                teams.addMember(g, team, newId);
                teams.setTeamCaptain(g, team, oldId, false);
                teams.setTeamCaptain(g, team, newId, true);
            }

            boolean named = team != null && team.roleId != null;
            dm(newId, "🔁 You're now a " + f.getName() + " captain"
                    + (named ? " of **" + team.name + "**" : "") + ", replacing " + oldCap.getEffectiveName() + "."
                    + (named ? "" : "\n**Name your team:** go to <#" + f.getChannelId()
                    + ">, press 🎛️ **Open my draft panel**, then 🏷️ **Name Team**."));
            dm(oldId, "🔁 You've been replaced as a " + f.getName() + " captain by " + newCap.getEffectiveName() + ".");
            if (running) {
                if (s.draft.status == DraftStatus.COMPLETE) finish(f, s, g);
                else if (wasOnClock && s.draft.status == DraftStatus.ACTIVE) startClock(f, s, g, null, true);
                commit(f, s, g);
            } else {
                // Before the draft: new captain leaves the prepared pool, old captain goes back in.
                if (s.pool != null) s.pool = buildPool(g, f, s).players();
                if (d != null) commit(f, s, g); else save(s);
            }
            return newCap.getEffectiveName() + " is now captain in place of " + oldCap.getEffectiveName() + "."
                    + (notes.isEmpty() ? "" : "\n" + String.join("; ", notes) + ".");
        });
    }

    /** Prepares (or rebuilds) a faction's pool before the draft so leadership can review and trim it. */
    public String buildPool(Guild g, Member invoker, String opt, long channelId) {
        Faction f = resolveLeaderFaction(invoker, opt, channelId);
        return withLock(f.getKey(), () -> {
            FactionState s = state(f);
            if (s.draftRunning()) throw new UserError("A " + f.getName() + " draft is already running.");
            PoolResult r = buildPool(g, f, s);
            s.pool = r.players();
            save(s);
            fetchStreaks(f, r.players());
            int excluded = s.excludedIds.size();
            return "🔄 **Participants rebuilt:** " + r.players().size() + " players"
                    + (excluded > 0 ? " (" + excluded + " removed earlier are still left out)" : "") + "." + r.note();
        });
    }

    public String addPlayer(Guild g, Member invoker, String opt, long channelId, Member member, Long tornId) {
        if (member == null && tornId == null) throw new UserError("Give either a `member` or a `torn_id`.");
        Faction f = resolveLeaderFaction(invoker, opt, channelId);
        return withLock(f.getKey(), () -> {
            FactionState s = state(f);
            requireRostersUnlocked(s, f);
            final Player p;
            if (member != null) {
                if (member.getUser().isBot()) throw new UserError("Bots can't be drafted.");
                p = toPlayer(member);
            } else {
                String tornName = torn.fetchName(tornId);
                Member linked = memberByTornId(g, tornId, tornName);
                p = linked != null ? new Player(linked.getIdLong(), linked.getEffectiveName(), tornId)
                        : Player.tornOnly(tornId, tornName);
            }
            if (s.activeCaptains().contains(p.id)) throw new UserError(p.display + " is a captain.");
            if (s.draft != null && s.draft.status == DraftStatus.ENDED && s.pool == null) {
                throw new UserError("The " + f.getName() + " draft was ended early, so it can't be reopened. "
                        + "An admin needs to run 🧹 Clean Up before a new draft.");
            }

            if (preDraft(s)) {
                if (s.pool == null) s.pool = buildPool(g, f, s).players();
                s.excludedIds.remove(p.id);
                if (p.tornId != null) s.excludedTornIds.remove(p.tornId);
                if (s.pool.stream().anyMatch(x -> samePerson(x, p))) return p.display + " is already a participant.";
                s.pool.add(p);
                s.pool.sort(Comparator.comparing(x -> x.display.toLowerCase(Locale.ROOT)));
                save(s);
                fetchStreaks(f, List.of(p));
                return "➕ " + p.display + " added to the " + f.getName() + " participants"
                        + (p.onDiscord() ? "." : " (not on Discord).");
            }

            Draft d = s.draft;
            if (d.eligible.stream().anyMatch(x -> samePerson(x, p))) throw new UserError(p.display + " is already a participant.");
            if (d.picks.stream().anyMatch(pk -> !pk.dropped && samePerson(pk.player, p))) {
                throw new UserError(p.display + " has already been drafted.");
            }
            d.eligible.add(p);
            d.sortEligible();
            if (d.status == DraftStatus.COMPLETE) {
                d.status = DraftStatus.ACTIVE;
                advance(f, s, g, null);
            }
            commit(f, s, g);
            fetchStreaks(f, List.of(p));
            return "➕ " + p.display + " added to the participants" + (p.onDiscord() ? "." : " (not on Discord).");
        });
    }

    public String removePlayers(Guild g, Member invoker, String opt, long channelId, List<String> queries) {
        Faction f = resolveLeaderFaction(invoker, opt, channelId);
        if (queries.isEmpty()) throw new UserError("Pick at least one player to remove.");
        return withLock(f.getKey(), () -> {
            FactionState s = state(f);
            requireRostersUnlocked(s, f);
            List<String> done = new ArrayList<>();

            if (preDraft(s)) {
                if (s.pool == null) throw new UserError("Choose captains first — that builds the participant list.");
                for (String q : queries) {
                    Player p = tryResolve(s.pool, q)
                            .orElseThrow(() -> new UserError("No participant matches `" + q + "`."));
                    s.pool.removeIf(x -> x.id == p.id);
                    s.excludedIds.add(p.id);
                    if (p.tornId != null) s.excludedTornIds.add(p.tornId);
                    done.add(p.display);
                }
                save(s);
                return "➖ **Removed:** " + String.join(", ", done) + ". They stay out on a rebuild (➕ Add brings someone back).";
            }

            Draft d = s.draft;
            if (d == null || d.status == DraftStatus.ENDED) throw new UserError("No " + f.getName() + " draft running.");
            for (String q : queries) {
                Optional<Player> inPool = tryResolve(d.eligible, q);
                if (inPool.isPresent()) {
                    Player p = inPool.get();
                    d.eligible.removeIf(x -> x.id == p.id);
                    d.removeFromAllQueues(p.id);
                    done.add(p.display + " (from the participants)");
                    continue;
                }
                List<Player> drafted = d.picks.stream().filter(pk -> !pk.dropped).map(pk -> pk.player).toList();
                Player p = tryResolve(drafted, q)
                        .orElseThrow(() -> new UserError("No participant or team member matches `" + q + "`."));
                Pick pick = d.picks.stream().filter(pk -> !pk.dropped && pk.player.id == p.id).findFirst().orElseThrow();
                pick.dropped = true;
                teams.removeMember(g, s.teams.get(pick.captainId), p.id);
                done.add(p.display + " (from " + s.teamName(pick.captainId, d.captainName(pick.captainId))
                        + " — the pick slot stays used)");
            }
            if (d.eligible.isEmpty() && d.status.running()) d.status = DraftStatus.COMPLETE;
            advanceIfNeeded(f, s, g);
            commit(f, s, g);
            return "➖ Removed: " + String.join("; ", done) + ".";
        });
    }

    /** Before a draft: no draft running, and either a pool is being prepared or there's no live/finished draft to edit. */
    private boolean preDraft(FactionState s) {
        return !s.draftRunning() && (s.pool != null || s.draft == null || s.draft.status == DraftStatus.ENDED);
    }

    private record PoolResult(List<Player> players, int discord, int tornOnly, String note) {}

    /**
     * Pool = the Torn faction's members, linked to Discord members with the faction's draft role
     * (by the [ID] in their nickname, or failing that by their nickname matching the Torn name),
     * plus anyone else on Discord whose nickname carries a faction member's ID. Faction members
     * nobody on Discord matches join as "not on Discord" players. Draft-role members who match no
     * faction member are left out (their hits can't be seen) and listed in the note. Captains and
     * excluded players are left out too.
     *
     * If the Torn member list can't be loaded, the pool is just the draft-role members, as before.
     */
    private PoolResult buildPool(Guild g, Faction f, FactionState s) {
        Role draftRole = g.getRoleById(f.getDraftRoleId());
        if (draftRole == null) throw new UserError("I can't find the " + f.getName() + " draft role.");
        Set<Long> capIds = new HashSet<>(s.activeCaptains());

        List<PoolBuilder.Person> roleHolders = g.getMembersWithRoles(draftRole).stream()
                .filter(m -> !m.getUser().isBot())
                .map(m -> new PoolBuilder.Person(m.getIdLong(), m.getEffectiveName()))
                .toList();
        List<PoolBuilder.Person> everyone = g.getMembers().stream()
                .filter(m -> !m.getUser().isBot())
                .map(m -> new PoolBuilder.Person(m.getIdLong(), m.getEffectiveName()))
                .toList();
        Set<Long> capTorn = capIds.stream().map(g::getMemberById).filter(Objects::nonNull)
                .map(m -> TornService.parseTornId(m.getEffectiveName())).filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, String> members = null;
        String note = "";
        if (f.getTornFactionId() > 0) {
            try {
                members = torn.fetchFactionMembers(f.getTornFactionId());
            } catch (UserError e) {
                note = "\n⚠️ Couldn't load the Torn faction member list (" + e.getMessage()
                        + ") — the list only has Discord members with the draft role for now. Try 🔄 Rebuild List later.";
            }
        }

        PoolBuilder.Result r = PoolBuilder.build(roleHolders, everyone, members, capIds, capTorn, s::isExcluded);
        // Keep streaks already looked up, so rebuilding the list doesn't fetch everyone again.
        Map<Long, Integer> known = new HashMap<>();
        if (s.pool != null) s.pool.stream().filter(p -> p.tornId != null && p.streak != null).forEach(p -> known.put(p.tornId, p.streak));
        if (s.draft != null) {
            s.draft.eligible.stream().filter(p -> p.tornId != null && p.streak != null).forEach(p -> known.put(p.tornId, p.streak));
            s.draft.picks.stream().map(pk -> pk.player).filter(p -> p.tornId != null && p.streak != null)
                    .forEach(p -> known.put(p.tornId, p.streak));
        }
        r.players().stream().filter(p -> p.streak == null && p.tornId != null).forEach(p -> p.streak = known.get(p.tornId));
        if (!r.leftOut().isEmpty()) {
            note += "\n⚠️ Left out " + r.leftOut().size() + " draft-role member" + (r.leftOut().size() == 1 ? "" : "s")
                    + " who aren't in the Torn faction (their hits can't be tracked): "
                    + String.join(", ", r.leftOut().stream().limit(15).toList())
                    + (r.leftOut().size() > 15 ? " and " + (r.leftOut().size() - 15) + " more" : "")
                    + ". Use ➕ Add if you want them anyway.";
        }
        int discord = (int) r.players().stream().filter(Player::onDiscord).count();
        return new PoolResult(r.players(), discord, r.players().size() - discord, note);
    }

    /** The Discord member with this Torn ID in their nickname, or failing that the only one named after them. */
    private Member memberByTornId(Guild g, long tornId, String tornName) {
        List<Member> humans = g.getMembers().stream().filter(m -> !m.getUser().isBot()).toList();
        Optional<Member> byId = humans.stream()
                .filter(m -> Objects.equals(TornService.parseTornId(m.getEffectiveName()), tornId)).findFirst();
        if (byId.isPresent()) return byId.get();
        String wanted = PoolBuilder.baseName(tornName);
        List<Member> byName = humans.stream()
                .filter(m -> TornService.parseTornId(m.getEffectiveName()) == null
                        && PoolBuilder.baseName(m.getEffectiveName()).equals(wanted)).toList();
        return byName.size() == 1 ? byName.get(0) : null;
    }

    public String endDraft(Guild g, Member invoker, String factionKey) {
        Faction f = props.faction(factionKey).orElseThrow(() -> new UserError("Unknown faction."));
        if (!isLeader(invoker, f)) throw new UserError("Only " + f.getName() + " leadership can do that.");
        return withLock(f.getKey(), () -> {
            FactionState s = state(f);
            Draft d = requireRunning(s, f);
            d.status = DraftStatus.ENDED;
            d.turnDeadlineMs = null;
            d.turnToken++;
            deletePing(g, d);
            dmCaptains(d, "🛑 The " + f.getName() + " draft was ended early by leadership. Teams so far are on the board.");
            commit(f, s, g);
            return "The " + f.getName() + " draft has been ended.";
        });
    }

    // =====================================================================
    // Captain commands
    // =====================================================================

    public String pick(Guild g, Member captain, long channelId, String query) {
        Faction f = resolveCaptainFaction(captain.getIdLong(), channelId, true);
        return withLock(f.getKey(), () -> {
            FactionState s = state(f);
            Draft d = requireStatus(s, f, DraftStatus.ACTIVE);
            Long current = d.currentCaptain();
            if (!Objects.equals(current, captain.getIdLong())) {
                throw new UserError("It's not your turn — " + d.captainName(current) + " is on the clock.");
            }
            Player p = resolveEligible(d, query); // manual choice always wins over the queue
            Pick pick = d.recordPick(p, PickMethod.MANUAL);
            onPicked(s, g, pick);
            advance(f, s, g, null);
            commit(f, s, g);
            return "✅ You picked **" + p.display + "** (pick #" + pick.number + ").";
        });
    }

    public String queueAdd(Guild g, Member captain, long channelId, String query) {
        Faction f = resolveCaptainFaction(captain.getIdLong(), channelId, true);
        return withLock(f.getKey(), () -> {
            FactionState s = state(f);
            Draft d = requireRunning(s, f);
            Player p = resolveEligible(d, query);
            long me = captain.getIdLong();

            // On the clock with the draft live: queueing someone just picks them.
            if (d.status == DraftStatus.ACTIVE && Objects.equals(d.currentCaptain(), me)) {
                Pick pick = d.recordPick(p, PickMethod.QUEUE);
                onPicked(s, g, pick);
                advance(f, s, g, null);
                commit(f, s, g);
                return "You were on the clock, so **" + p.display + "** was picked straight away (pick #" + pick.number + ").";
            }

            List<Long> q = d.queueFor(me);
            if (q.contains(p.id)) throw new UserError(p.display + " is already in your queue.");
            if (q.size() >= props.getMaxQueue()) throw new UserError("Your queue is full (" + props.getMaxQueue() + " max).");
            q.add(p.id);
            save(s);
            return "Queued **" + p.display + "** (" + q.size() + "/" + props.getMaxQueue() + ").\n" + describeQueue(d, me);
        });
    }

    public String queueClear(Member captain, long channelId) {
        Faction f = resolveCaptainFaction(captain.getIdLong(), channelId, true);
        return withLock(f.getKey(), () -> {
            FactionState s = state(f);
            requireRunning(s, f).queueFor(captain.getIdLong()).clear();
            save(s);
            return "Your queue has been cleared.";
        });
    }

    /**
     * Names (or renames) a captain's team. Captains name their own; leadership can pass
     * {@code forCaptain} to name one on a captain's behalf.
     */
    public String nameTeam(Guild g, Member invoker, long channelId, String rawName, Member forCaptain) {
        String name = rawName == null ? "" : rawName.replace("@", "").replaceAll("\\s+", " ").trim();
        if (name.length() < 2 || name.length() > 40) throw new UserError("Team names must be 2–40 characters.");
        Member captain = forCaptain != null ? forCaptain : invoker;
        boolean onBehalf = captain.getIdLong() != invoker.getIdLong();
        final Faction f = captainFactionFor(captain, channelId, onBehalf);
        if (onBehalf && !isLeader(invoker, f)) {
            throw new UserError("Only " + f.getName() + " leadership can name a team for someone else.");
        }
        long me = captain.getIdLong();

        String result = withLock(f.getKey(), () -> {
            FactionState s = state(f);
            boolean taken = s.teams.entrySet().stream()
                    .anyMatch(e -> e.getKey() != me && e.getValue().name != null && e.getValue().name.equalsIgnoreCase(name));
            if (taken) throw new UserError("Another " + f.getName() + " team is already called " + name + ".");

            Team team = s.teams.get(me);
            String reply;
            if (team == null || team.roleId == null) {
                team = new Team(name);
                s.teams.put(me, team);
                Set<Long> members = new LinkedHashSet<>();
                members.add(me);
                if (s.draft != null && s.draft.captainOrder.contains(me)) {
                    s.draft.picksFor(me).stream().filter(p -> !p.dropped).forEach(p -> members.add(p.player.id));
                }
                int colour = Math.max(0, s.activeCaptains().indexOf(me));
                Set<String> refused;
                try {
                    refused = teams.create(g, f, team, colour, me, members);
                    // Always the faction's draft channel, wherever the panel was opened.
                    refused.addAll(teams.grantCaptainChannelAccess(g, team, f.getChannelId()));
                } catch (Exception e) {
                    log.error("Team creation failed for {}", name, e);
                    teams.delete(g, team);
                    s.teams.remove(me);
                    if (e instanceof UserError ue) throw ue;
                    throw new UserError("I couldn't create the team role/channels (" + e.getMessage() + "). "
                            + "Check my role has Manage Roles and Manage Channels and sits above the team roles.");
                }
                reply = "Team **" + name + "** created: team role, **" + f.getTag() + " · " + name + " Captain** role ("
                        + (onBehalf ? captain.getEffectiveName() + "'s" : "yours") + "), <#"
                        + team.textChannelId + "> and a voice channel.";
                if (!refused.isEmpty()) {
                    reply += "\n⚠️ Discord wouldn't let me grant: " + String.join("; ", refused)
                            + ". The channels work, but give my role those permissions if you want them included.";
                }
            } else {
                String old = team.name;
                teams.rename(g, f, team, name);
                reply = "Renamed **" + old + "** to **" + name + "**.";
            }
            commit(f, s, g);
            return reply;
        });
        if (onBehalf) dm(captain.getIdLong(), "🏷️ Leadership named your " + f.getName() + " team **" + name + "**.");
        return result;
    }

    // =====================================================================
    // Team images
    // =====================================================================

    /** A named team and its image (null when it has none), for the panels. */
    public record TeamImageInfo(long captainId, String team, Long imageId, String imageExt) {
        public boolean hasImage() {
            return imageId != null;
        }
    }

    /** Every named team, in captain order, with its image. */
    public List<TeamImageInfo> teamImages(Faction f) {
        return withLock(f.getKey(), () -> {
            FactionState s = state(f);
            List<Long> order = new ArrayList<>(s.draft != null ? s.draft.captainOrder : s.captains);
            s.teams.keySet().stream().filter(id -> !order.contains(id)).forEach(order::add);
            List<TeamImageInfo> out = new ArrayList<>();
            for (Long cap : order) {
                Team t = s.teams.get(cap);
                if (t == null || t.roleId == null) continue;
                out.add(new TeamImageInfo(cap, t.name, t.imageId, t.imageExt));
            }
            return out;
        });
    }

    /** The captain's team image, or null if they have no team or no image. */
    public TeamImageInfo teamImage(Faction f, long captainId) {
        return teamImages(f).stream().filter(t -> t.captainId() == captainId).findFirst().orElse(null);
    }

    /** The image's file to attach to a panel, or null if it's gone. */
    public net.dv8tion.jda.api.utils.FileUpload teamImageFile(TeamImageInfo t) {
        return t == null ? null : images.upload(t.imageId(), t.imageExt());
    }

    public net.dv8tion.jda.api.utils.FileUpload teamImageFile(Long imageId, String imageExt) {
        return images.upload(imageId, imageExt);
    }

    /**
     * A captain sets (or replaces) their team's image. It's checked and shrunk first, then shown on
     * the board straight away and used as the team's role icon when the server allows it.
     */
    public String setTeamImage(Guild g, Member invoker, Faction f, byte[] raw, Long forCaptain) {
        long me = forCaptain != null ? forCaptain : invoker.getIdLong();
        boolean onBehalf = me != invoker.getIdLong();
        if (onBehalf && !isLeader(invoker, f)) {
            throw new UserError("Only " + f.getName() + " leadership can set another team's image.");
        }
        TeamImageService.Processed p = TeamImageService.process(raw); // outside the lock: decoding takes a moment
        return withLock(f.getKey(), () -> {
            FactionState s = state(f);
            Team team = s.teams.get(me);
            if (team == null || team.roleId == null) {
                throw new UserError(onBehalf ? "That team hasn't been named yet."
                        : s.activeCaptains().contains(me) ? "Name your team first, then add its image."
                        : "Only " + f.getName() + " captains can set a team image.");
            }
            Long old = team.imageId;
            team.imageId = images.save(f.getKey(), p, me);
            team.imageExt = p.ext();
            String iconProblem = p.icon() == null ? "the image couldn't be made small enough"
                    : teams.setRoleIcons(g, team, p.icon());
            commit(f, s, g);
            if (old != null) images.delete(old);
            if (onBehalf) {
                dm(me, "🖼️ " + f.getName() + " leadership set the image for **" + team.name
                        + "**. You can change or remove it from your draft panel.");
            }
            String whose = onBehalf ? "the team's" : "your team's";
            return "🖼️ **" + team.name + "**'s image is saved" + (p.animated() ? " (animated)" : "")
                    + ". It shows on the draft board, the 📊 Team breakdown and the scoreboard while the team leads"
                    + (iconProblem == null ? ", and as " + whose + " role icon." : ".\n-# No role icon: " + iconProblem + ".");
        });
    }

    /** Takes a team's image off. Leaders can remove any team's; captains their own. */
    public String removeTeamImage(Guild g, Member invoker, Faction f, long captainId) {
        boolean leader = isLeader(invoker, f);
        if (!leader && invoker.getIdLong() != captainId) {
            throw new UserError("Only " + f.getName() + " leadership or the team's captain can remove its image.");
        }
        return withLock(f.getKey(), () -> {
            FactionState s = state(f);
            Team team = s.teams.get(captainId);
            if (team == null || team.imageId == null) throw new UserError("That team doesn't have an image.");
            long old = team.imageId;
            team.imageId = null;
            team.imageExt = null;
            teams.setRoleIcons(g, team, null);
            commit(f, s, g);
            images.delete(old);
            if (invoker.getIdLong() != captainId) {
                dm(captainId, "🖼️ " + f.getName() + " leadership removed the image for **" + team.name
                        + "**. You can add a new one from your draft panel.");
            }
            return "🖼️ Removed **" + team.name + "**'s image.";
        });
    }

    private Faction captainFactionFor(Member captain, long channelId, boolean onBehalf) {
        try {
            return resolveCaptainFaction(captain.getIdLong(), channelId, false);
        } catch (UserError e) {
            throw new UserError(onBehalf
                    ? captain.getEffectiveName() + " isn't a captain."
                    : e.getMessage());
        }
    }

    // =====================================================================
    // Panel data (read-only views for the /draft panels)
    // =====================================================================

    public record CaptainInfo(long id, String name, String team, int picks) {}

    /** Everything the main panel shows. {@code status} is null when no draft has been run since teardown. */
    public record PanelInfo(Faction faction, boolean running, DraftStatus status, List<CaptainInfo> captains,
                            boolean poolPrepared, int poolSize, int poolOffDiscord, Long onClock,
                            Long deadlineMs, Long pausedRemainingMs, int timerSeconds, boolean autopick,
                            int picksMade, boolean allTeamsNamed, int excluded, boolean rostersLocked,
                            boolean needsCleanUp) {}

    /**
     * A captain's own view: their team, picks and queue, plus where they are in the order.
     * {@code picksAway} is 0 when they're on the clock and null when they have no picks left;
     * {@code nextPick} is that pick's overall number (1-based).
     */
    public record CaptainView(Faction faction, String team, List<Player> picks, List<Player> queue,
                              boolean running, boolean onClock, boolean paused, Long clockCaptain,
                              Long deadlineMs, Long pausedRemainingMs, Integer picksAway, Integer nextPick,
                              boolean finished) {}

    public Faction faction(String key) {
        return props.faction(key).orElseThrow(() -> new UserError("Unknown faction."));
    }

    public PanelInfo panelInfo(Guild g, Faction f) {
        return withLock(f.getKey(), () -> {
            FactionState s = state(f);
            Draft d = s.draft;
            boolean running = s.draftRunning();
            List<CaptainInfo> caps = new ArrayList<>();
            boolean allNamed = !s.activeCaptains().isEmpty();
            for (Long id : s.activeCaptains()) {
                Member m = g.getMemberById(id);
                String name = running ? d.captainName(id) : (m != null ? m.getEffectiveName() : String.valueOf(id));
                Team t = s.teams.get(id);
                String team = t != null && t.roleId != null ? t.name : null;
                if (team == null) allNamed = false;
                int picks = d != null && d.captainOrder.contains(id)
                        ? (int) d.picksFor(id).stream().filter(p -> !p.dropped).count() : 0;
                caps.add(new CaptainInfo(id, name, team, picks));
            }
            List<Player> pool = running ? d.eligible : s.pool != null ? s.pool : List.of();
            int off = (int) pool.stream().filter(p -> !p.onDiscord()).count();
            return new PanelInfo(f, running, d == null ? null : d.status, caps, running || s.pool != null,
                    pool.size(), off, running ? d.currentCaptain() : null,
                    running ? d.turnDeadlineMs : null, running ? d.pausedRemainingMs : null,
                    d == null ? 0 : d.timerSeconds, d != null && d.autopick,
                    d == null ? 0 : (int) d.picks.stream().filter(p -> !p.dropped).count(), allNamed,
                    s.excludedIds.size(), s.rostersLocked(System.currentTimeMillis() / 1000), s.needsCleanUp());
        });
    }

    public CaptainView captainView(Faction f, long captainId) {
        return withLock(f.getKey(), () -> {
            FactionState s = state(f);
            Team t = s.teams.get(captainId);
            String team = t != null && t.roleId != null ? t.name : null;
            if (!s.draftRunning()) {
                // After a finished draft, still show the captain their team.
                boolean finished = s.draft != null && s.draft.captainOrder.contains(captainId);
                List<Player> picks = finished ? s.draft.picksFor(captainId).stream().filter(p -> !p.dropped)
                        .map(p -> p.player).toList() : List.of();
                return new CaptainView(f, team, picks, List.of(), false, false, false, null, null, null, null, null,
                        finished);
            }
            Draft d = s.draft;
            d.firstQueued(captainId); // drops anyone already taken
            List<Player> queue = d.queueFor(captainId).stream().map(d::findEligible)
                    .flatMap(Optional::stream).toList();
            List<Player> picks = d.picksFor(captainId).stream().filter(p -> !p.dropped).map(p -> p.player).toList();
            Integer away = null, next = null;
            int start = d.nextPickIndex();
            for (int i = 0; i < d.eligible.size(); i++) { // one pick per remaining player
                if (Objects.equals(d.captainForPick(start + i), captainId)) {
                    away = i;
                    next = start + i + 1;
                    break;
                }
            }
            return new CaptainView(f, team, picks, queue, true, Objects.equals(d.currentCaptain(), captainId),
                    d.status == DraftStatus.PAUSED, d.currentCaptain(), d.turnDeadlineMs, d.pausedRemainingMs,
                    away, next, false);
        });
    }

    public String queueRemove(Faction f, long captainId, List<Long> playerIds) {
        return withLock(f.getKey(), () -> {
            FactionState s = state(f);
            Draft d = requireRunning(s, f);
            if (!d.captainOrder.contains(captainId)) throw new UserError("You're not a captain in this draft.");
            List<Long> q = d.queueFor(captainId);
            int before = q.size();
            q.removeAll(playerIds);
            save(s);
            int removed = before - q.size();
            return "➖ Removed " + removed + " player" + (removed == 1 ? "" : "s") + " from your queue.";
        });
    }

    /**
     * Name search over the pool (prepared pool before the draft, remaining players during it),
     * optionally including drafted players. Lock-free, from the snapshot. Max 25 results.
     */
    public List<Player> search(Faction f, String query, boolean includeDrafted) {
        Snapshot snap = snapshots.get(f.getKey());
        if (snap == null) return List.of();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Player> out = new ArrayList<>();
        List<Player> source = new ArrayList<>(snap.eligible());
        if (includeDrafted) source.addAll(snap.drafted());
        for (Player p : source) {
            if (q.isEmpty() || p.display.toLowerCase(Locale.ROOT).contains(q)
                    || (p.tornId != null && String.valueOf(p.tornId).equals(q))) {
                out.add(p);
                if (out.size() >= 25) break;
            }
        }
        return out;
    }

    /** The pool as the panels show it: prepared pool before the draft, remaining players during it. */
    public List<Player> poolList(Faction f) {
        Snapshot snap = snapshots.get(f.getKey());
        return snap == null ? List.of() : snap.eligible();
    }

    // =====================================================================
    // Competition hooks (used by CompetitionService and the panel)
    // =====================================================================

    /** A team for scoring: its name and every member's Torn ID (captain included). */
    public record RosterMember(String display, Long tornId) {}
    public record TeamRoster(long captainId, String teamName, List<RosterMember> members, Long imageId, String imageExt) {}

    /** Teams from the faction's current/last draft, captain first. Empty when no draft exists. */
    public List<TeamRoster> rosters(Faction f) {
        return withLock(f.getKey(), () -> {
            FactionState s = state(f);
            Draft d = s.draft;
            if (d == null) return List.of();
            List<TeamRoster> out = new ArrayList<>();
            for (Long cap : d.captainOrder) {
                List<RosterMember> members = new ArrayList<>();
                String capName = d.captainName(cap);
                members.add(new RosterMember(capName, TornService.parseTornId(capName)));
                d.picksFor(cap).stream().filter(p -> !p.dropped)
                        .forEach(p -> members.add(new RosterMember(p.player.display, p.player.tornId)));
                Team team = s.teams.get(cap);
                out.add(new TeamRoster(cap, s.teamName(cap, capName), members,
                        team == null ? null : team.imageId, team == null ? null : team.imageExt));
            }
            return out;
        });
    }

    /** A copy of the faction's competition settings and progress. */
    public com.halloween.draft.model.Competition competition(Faction f) {
        return withLock(f.getKey(), () -> {
            com.halloween.draft.model.Competition c = state(f).competition;
            com.halloween.draft.model.Competition copy = new com.halloween.draft.model.Competition();
            copy.startEpoch = c.startEpoch;
            copy.endEpoch = c.endEpoch;
            copy.lastFetchedEpoch = c.lastFetchedEpoch;
            copy.lastCheckedMs = c.lastCheckedMs;
            copy.lastError = c.lastError;
            copy.scoreboardMessageId = c.scoreboardMessageId;
            copy.scoreboardImageSig = c.scoreboardImageSig;
            copy.finalPosted = c.finalPosted;
            copy.keysFailingNotified = c.keysFailingNotified;
            copy.draftSoonWarned = c.draftSoonWarned;
            copy.draftLiveWarned = c.draftLiveWarned;
            return copy;
        });
    }

    /** Changes the faction's competition state under its lock and saves it. */
    public void updateCompetition(Faction f, java.util.function.Consumer<com.halloween.draft.model.Competition> change) {
        withLock(f.getKey(), () -> {
            FactionState s = state(f);
            change.accept(s.competition);
            save(s);
            return null;
        });
    }

    /**
     * Sets (or clears, with both null) the competition window. Times are epoch seconds UTC/TCT.
     * The start can't be moved once the competition has begun.
     */
    public String setCompetitionTimes(Member invoker, String opt, long channelId, Long start, Long end) {
        Faction f = resolveLeaderFaction(invoker, opt, channelId);
        long now = System.currentTimeMillis() / 1000;
        return withLock(f.getKey(), () -> {
            FactionState s = state(f);
            com.halloween.draft.model.Competition c = s.competition;
            if (start == null && end == null) {
                if (c.live(now)) throw new UserError("The competition is live — it can't be cleared now.");
                s.competition = new com.halloween.draft.model.Competition();
                s.competition.scoreboardMessageId = c.scoreboardMessageId;
                save(s);
                return "🗓️ Competition times cleared.";
            }
            if (start == null || end == null) throw new UserError("Give both a start and an end.");
            CompetitionRules.checkNewTimes(c, start, end, now);
            if (!start.equals(c.startEpoch)) c.lastFetchedEpoch = null; // new window: fetch from its start
            c.startEpoch = start;
            c.endEpoch = end;
            if (!c.live(now)) { // new or moved start: warnings about it are due again
                c.draftSoonWarned = false;
                c.draftLiveWarned = false;
            }
            if (end > now) c.finalPosted = false;
            save(s);
            return "🗓️ Competition set: **" + tct(start) + "** → **" + tct(end) + "** TCT.";
        });
    }

    /** "25-10-2026 18:00" in TCT (UTC). */
    public static String tct(long epoch) {
        return java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")
                .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochSecond(epoch));
    }

    // =====================================================================
    // Clean Up (one faction, admins only)
    // =====================================================================

    /** Checks Clean Up is allowed for this faction and returns a summary of what it will remove. */
    public String checkTeardown(Member invoker, Faction f) {
        if (!invoker.hasPermission(Permission.ADMINISTRATOR)) throw new UserError("Server admins only.");
        FactionState s = state(f);
        if (s.competition.live(System.currentTimeMillis() / 1000)) {
            throw new UserError("The " + f.getName() + " competition is live. Clean Up removes the teams it's "
                    + "scored on, so wait until it finishes.");
        }
        if (s.draftRunning()) throw new UserError("The " + f.getName() + " draft is still running. End it first.");
        int count = s.teams.size();
        return count + " " + f.getName() + " team" + (count == 1 ? "" : "s");
    }

    /**
     * Clean Up for one faction: its teams (roles and channels), captains' state, participant list,
     * competition, its draft board in its draft channel and its scoreboard in the scoreboard channel.
     */
    public String teardown(Guild g, Member invoker, Faction f, boolean clearCaptains) {
        checkTeardown(invoker, f);
        long[] scoreboardId = new long[1];
        int deleted = withLock(f.getKey(), () -> {
            FactionState s = state(f);
            int n = s.teams.size();
            s.teams.values().forEach(t -> teams.delete(g, t));
            s.teams.clear();
            if (s.competition.scoreboardMessageId != null) scoreboardId[0] = s.competition.scoreboardMessageId;
            s.draft = null;
            s.pool = null;
            s.excludedIds.clear();
            s.excludedTornIds.clear();
            s.competition = new com.halloween.draft.model.Competition();
            if (clearCaptains) s.captains.clear();
            save(s);
            return n;
        });

        int messages = clearOwnMessages(g.getTextChannelById(f.getChannelId()));
        boolean scoreboard = deleteScoreboard(g, f, scoreboardId[0]);
        withLock(f.getKey(), () -> {
            FactionState s = state(f);
            s.launcherMessageId = null;
            ensureLauncher(f, s, g);
            return null;
        });
        int attacks = competitionRepo.deleteAttacks(f.getKey());
        int keys = competitionRepo.deleteAllKeys(f.getKey());
        int pictures = images.deleteAll(f.getKey());

        return "🧹 " + f.getName() + " cleaned up: deleted " + deleted + " team" + (deleted == 1 ? "" : "s")
                + " (roles, text and voice channels)"
                + (clearCaptains ? ", cleared the captains" : "")
                + ", cleared " + messages + " bot message" + (messages == 1 ? "" : "s") + " from <#" + f.getChannelId() + ">"
                + (scoreboard ? ", removed the scoreboard" : "")
                + ", deleted " + attacks + " stored attack" + (attacks == 1 ? "" : "s")
                + ", " + keys + " API key" + (keys == 1 ? "" : "s")
                + " and " + pictures + " team image" + (pictures == 1 ? "" : "s") + ".";
    }

    /**
     * Deletes this faction's scoreboard message. Uses the stored message id, or failing that looks
     * for the bot's message titled "🎃 &lt;Faction&gt; …" among the last 100 in the scoreboard channel.
     */
    private boolean deleteScoreboard(Guild g, Faction f, long messageId) {
        TextChannel ch = g.getTextChannelById(props.getScoreboardChannelId());
        if (ch == null) return false;
        try {
            if (messageId != 0) {
                ch.deleteMessageById(messageId).complete();
                return true;
            }
        } catch (Exception e) {
            log.info("Stored scoreboard message for {} is gone ({}), searching the channel", f.getName(), e.getMessage());
        }
        try {
            long self = g.getSelfMember().getIdLong();
            String prefix = "🎃 " + f.getName() + " ";
            List<Message> mine = ch.getHistory().retrievePast(100).complete().stream()
                    .filter(m -> m.getAuthor().getIdLong() == self)
                    .filter(m -> m.getEmbeds().stream().anyMatch(e -> e.getTitle() != null && e.getTitle().startsWith(prefix)))
                    .toList();
            mine.forEach(m -> m.delete().queue(null, err -> {}));
            return !mine.isEmpty();
        } catch (Exception e) {
            log.warn("Couldn't clear the {} scoreboard: {}", f.getName(), e.getMessage());
            return false;
        }
    }

    // =====================================================================
    // Turn engine
    // =====================================================================

    /**
     * Moves the draft forward: auto-picks from queues while captains have something queued,
     * then starts the next captain's clock. {@code firstClockMs} overrides the first clock's
     * length (used when resuming with time left).
     */
    private void advance(Faction f, FactionState s, Guild g, Long firstClockMs) {
        Draft d = s.draft;
        while (true) {
            if (d.status == DraftStatus.COMPLETE) {
                finish(f, s, g);
                return;
            }
            if (d.status == DraftStatus.PAUSED) {
                d.turnToken++;
                d.turnDeadlineMs = null;
                d.pausedRemainingMs = null; // next captain gets a full clock on resume
                return;
            }
            if (d.status != DraftStatus.ACTIVE) return;

            long captain = d.currentCaptain();
            if (!d.suppressQueueOnce) {
                Optional<Player> queued = d.firstQueued(captain);
                if (queued.isPresent()) {
                    Pick pick = d.recordPick(queued.get(), PickMethod.QUEUE);
                    onPicked(s, g, pick);
                    dm(captain, "📋 Auto-picked **" + pick.player.display + "** from your queue (pick #" + pick.number + ").");
                    continue;
                }
            }
            startClock(f, s, g, firstClockMs, true);
            return;
        }
    }

    private void advanceIfNeeded(Faction f, FactionState s, Guild g) {
        if (s.draft.status == DraftStatus.COMPLETE) finish(f, s, g);
    }

    private void startClock(Faction f, FactionState s, Guild g, Long durationMs, boolean announce) {
        Draft d = s.draft;
        d.turnToken++;
        d.warningSent = false;
        long token = d.turnToken;
        long captain = d.currentCaptain();

        if (d.timerSeconds > 0) {
            long ms = durationMs != null ? durationMs : d.timerSeconds * 1000L;
            d.turnDeadlineMs = System.currentTimeMillis() + ms;
            if (ms > WARNING_MS + 5_000) timers.after(ms - WARNING_MS, () -> onWarning(f, token));
            timers.after(ms, () -> onTimeout(f, token));
        } else {
            d.turnDeadlineMs = null;
        }
        if (announce) ping(g, s, captain);
    }

    private void onWarning(Faction f, long token) {
        withLock(f.getKey(), () -> {
            FactionState s = state(f);
            Draft d = s.draft;
            if (d == null || d.status != DraftStatus.ACTIVE || d.turnToken != token || d.warningSent) return null;
            d.warningSent = true;
            dm(d.currentCaptain(), "⏳ **30 seconds left** on your " + f.getName() + " draft pick! Go to <#"
                    + d.channelId + ">, press 🎛️ **Open my draft panel**, then 🎯 **Pick Player**" + (d.autopick ? ", or one will be made for you." : "."));
            save(s);
            return null;
        });
    }

    private void onTimeout(Faction f, long token) {
        withLock(f.getKey(), () -> {
            FactionState s = state(f);
            Draft d = s.draft;
            if (d == null || d.status != DraftStatus.ACTIVE || d.turnToken != token) return null;
            Guild g = guildFor(d);
            if (g == null) return null;
            long captain = d.currentCaptain();

            if (d.autopick && !d.eligible.isEmpty()) {
                Player p = d.firstQueued(captain)
                        .orElseGet(() -> d.eligible.get(ThreadLocalRandom.current().nextInt(d.eligible.size())));
                Pick pick = d.recordPick(p, PickMethod.TIMEOUT);
                onPicked(s, g, pick);
                dm(captain, "⏰ You ran out of time — **" + p.display + "** was auto-picked for you (pick #" + pick.number + ").");
                advance(f, s, g, null);
            } else {
                d.turnDeadlineMs = null;
                d.turnToken++;
                dm(captain, "⏰ Your time is up! You can still pick: <#" + d.channelId + "> → 🎛️ **Open my draft panel** → 🎯 **Pick Player**. Or leadership can pick for you with 🛡️ Admin Pick.");
            }
            commit(f, s, g);
            return null;
        });
    }

    private void finish(Faction f, FactionState s, Guild g) {
        Draft d = s.draft;
        d.status = DraftStatus.COMPLETE;
        d.turnDeadlineMs = null;
        d.turnToken++;
        deletePing(g, d);
        dmCaptains(d, "✅ The " + f.getName() + " draft is complete! Final teams: " + boardLink(g, d));
    }

    private void onPicked(FactionState s, Guild g, Pick pick) {
        teams.addMember(g, s.teams.get(pick.captainId), pick.player.id);
    }

    // =====================================================================
    // Discord output helpers
    // =====================================================================

    /**
     * Turn notification by DM, so the board stays the only lasting message in the channel. If the
     * DM can't be delivered (DMs closed), the captain is pinged in the draft channel instead; that
     * ping is deleted when the turn moves on.
     */
    private void ping(Guild g, FactionState s, long captain) {
        Draft d = s.draft;
        deletePing(g, d); // the previous turn's channel ping, if there was one
        int idx = d.nextPickIndex();
        String clock = d.turnDeadlineMs != null ? " — time's up <t:" + d.turnDeadlineMs / 1000 + ":R>" : "";
        String text = "🎯 **It's your pick** (#" + (idx + 1) + ")! Go to <#" + d.channelId
                + ">, press 🎛️ **Open my draft panel**, then 🎯 **Pick Player**" + clock + ".\nDraft board: " + boardLink(g, d);
        JDA j = jda;
        if (j == null) return;
        String key = s.factionKey;
        long token = d.turnToken;
        j.openPrivateChannelById(captain)
                .flatMap(pc -> pc.sendMessage(text))
                .queue(null, err -> timers.after(0, () -> pingInChannel(key, captain, token)));
    }

    /** Fallback for a failed turn DM: a short mention in the draft channel, if it's still their turn. */
    private void pingInChannel(String key, long captain, long token) {
        Faction f = props.faction(key).orElse(null);
        if (f == null) return;
        try {
            withLock(key, () -> {
                FactionState s = state(f);
                Draft d = s.draft;
                if (d == null || d.status != DraftStatus.ACTIVE || d.turnToken != token
                        || !Objects.equals(d.currentCaptain(), captain)) return null;
                Guild g = guildFor(d);
                TextChannel ch = g == null ? null : g.getTextChannelById(d.channelId);
                if (ch == null) return null;
                deletePing(g, d);
                String clock = d.turnDeadlineMs != null ? " — time's up <t:" + d.turnDeadlineMs / 1000 + ":R>" : "";
                Message msg = ch.sendMessage("<@" + captain + "> 🎯 it's your pick (#" + (d.nextPickIndex() + 1)
                        + ")! I couldn't DM you — press 🎛️ **Open my draft panel** on the board, then 🎯 **Pick Player**" + clock + ".").complete();
                d.lastPingMessageId = msg.getIdLong();
                save(s);
                return null;
            });
        } catch (Exception e) {
            log.warn("{}: channel ping for {} failed: {}", key, captain, e.getMessage());
        }
    }

    /**
     * Deletes the bot's own messages among the last 100 in a channel and returns how many.
     * Other people's messages are left alone. A missing channel counts as nothing to clear.
     */
    private int clearOwnMessages(TextChannel ch) {
        if (ch == null) return 0;
        try {
            long self = ch.getGuild().getSelfMember().getIdLong();
            List<Message> mine = ch.getHistory().retrievePast(100).complete().stream()
                    .filter(m -> m.getAuthor().getIdLong() == self).toList();
            mine.forEach(m -> m.delete().queue(null, e -> {}));
            return mine.size();
        } catch (Exception e) {
            log.warn("Couldn't clear old bot messages in #{}: {}", ch.getName(), e.getMessage());
            return 0;
        }
    }

    private String boardLink(Guild g, Draft d) {
        return d.boardMessageId == null ? "<#" + d.channelId + ">"
                : "https://discord.com/channels/" + g.getId() + "/" + d.channelId + "/" + d.boardMessageId;
    }

    private void dmCaptains(Draft d, String text) {
        d.captainOrder.forEach(id -> dm(id, text));
    }

    private void deletePing(Guild g, Draft d) {
        if (d.lastPingMessageId == null) return;
        TextChannel ch = g.getTextChannelById(d.channelId);
        if (ch != null) ch.deleteMessageById(d.lastPingMessageId).queue(null, e -> {});
        d.lastPingMessageId = null;
    }

    private static boolean teamNamed(FactionState s, long captainId) {
        Team t = s.teams.get(captainId);
        return t != null && t.roleId != null;
    }

    private void dm(Long userId, String text) {
        JDA j = jda;
        if (j == null || userId == null) return;
        j.openPrivateChannelById(userId)
                .flatMap(pc -> pc.sendMessage(text))
                .queue(null, e -> log.debug("DM to {} failed: {}", userId, e.getMessage()));
    }

    /** Button id the panel controller opens a panel for (same as /draft). */
    public static final String OPEN_PANEL_PREFIX = "dp:open:";

    /** The "Open my draft panel" button that sits under the board and the launcher. */
    private static ActionRow openPanelRow(Faction f) {
        return ActionRow.of(Button.primary(OPEN_PANEL_PREFIX + f.getKey(), "🎛️ Open my draft panel"));
    }

    /**
     * Before a faction's first draft (and after Clean Up) there's no board, so the draft channel
     * gets a small launcher message with the Open my draft panel button. Captains and players then
     * only need to see the channel, not post in it. Caller holds the lock.
     */
    private void ensureLauncher(Faction f, FactionState s, Guild g) {
        TextChannel ch = g.getTextChannelById(f.getChannelId());
        if (ch == null) return;
        MessageEmbed embed = new EmbedBuilder()
                .setTitle(board.title(f))
                .setDescription("🎃 The draft hasn't started yet.\n\n"
                        + "Press **🎛️ Open my draft panel** below:\n"
                        + "• **Leaders** set up captains, team names, competition times and API keys\n"
                        + "• **Captains** name their team, and later queue and pick players\n"
                        + "• **Everyone** can see the captains and who's taking part\n\n"
                        + "-# Your panel is private: only you can see it.")
                .setColor(0xE67E22)
                .build();
        if (s.launcherMessageId != null) {
            try {
                ch.editMessageEmbedsById(s.launcherMessageId, embed).setComponents(openPanelRow(f)).complete();
                return;
            } catch (ErrorResponseException e) {
                if (e.getErrorResponse() != ErrorResponse.UNKNOWN_MESSAGE) {
                    log.warn("Launcher edit failed: {}", e.getMessage());
                    return;
                }
            } catch (Exception e) {
                log.warn("Launcher edit failed: {}", e.getMessage());
                return;
            }
        }
        try {
            s.launcherMessageId = ch.sendMessageEmbeds(embed).setComponents(openPanelRow(f)).complete().getIdLong();
            save(s);
        } catch (Exception e) {
            log.warn("{}: couldn't post the launcher: {}", f.getName(), e.getMessage());
        }
    }

    private void refreshBoard(Faction f, FactionState s, Guild g) {
        Draft d = s.draft;
        if (d == null || g == null) return;
        TextChannel ch = g.getTextChannelById(d.channelId);
        if (ch == null) return;
        // Team images are attached to the board message and only re-uploaded when they change;
        // an edit without files keeps the ones already there.
        List<String> wanted = new ArrayList<>();
        for (Long cap : d.captainOrder) {
            String name = TeamImageService.fileName(s.teams.get(cap));
            if (name != null) wanted.add(name);
        }
        boolean upload = d.boardMessageId != null && !String.join(",", wanted).equals(d.boardImageSig);
        List<net.dv8tion.jda.api.utils.FileUpload> files = new ArrayList<>();
        Set<String> attached = new HashSet<>(wanted);
        if (upload) {
            attached.clear();
            for (Long cap : d.captainOrder) {
                var file = images.upload(s.teams.get(cap));
                if (file != null) {
                    files.add(file);
                    attached.add(file.getName());
                }
            }
        }
        String sig = files.stream().map(net.dv8tion.jda.api.utils.FileUpload::getName).collect(Collectors.joining(","));
        List<MessageEmbed> embeds = board.render(f, s, attached);
        if (d.boardMessageId != null) {
            try {
                var edit = ch.editMessageEmbedsById(d.boardMessageId, embeds).setComponents(openPanelRow(f));
                if (upload) edit = edit.setFiles(files);
                edit.complete();
                if (upload) d.boardImageSig = sig;
                return;
            } catch (ErrorResponseException e) {
                if (e.getErrorResponse() != ErrorResponse.UNKNOWN_MESSAGE) {
                    log.warn("Board edit failed: {}", e.getMessage());
                    return;
                }
                d.boardMessageId = null; // board was deleted - post a new one
            } catch (Exception e) {
                log.warn("Board edit failed: {}", e.getMessage());
                return;
            }
        }
        try {
            // A new message needs every image (fresh copies: a failed edit may have used up the first ones).
            files.clear();
            for (Long cap : d.captainOrder) {
                var file = images.upload(s.teams.get(cap));
                if (file != null) files.add(file);
            }
            sig = files.stream().map(net.dv8tion.jda.api.utils.FileUpload::getName).collect(Collectors.joining(","));
            embeds = board.render(f, s, files.stream().map(net.dv8tion.jda.api.utils.FileUpload::getName)
                    .collect(Collectors.toSet()));
            Message msg = ch.sendMessageEmbeds(embeds).setComponents(openPanelRow(f)).setFiles(files).complete();
            d.boardMessageId = msg.getIdLong(); // not pinned: pinning posts a system message in the channel
            d.boardImageSig = sig;
        } catch (Exception e) {
            log.warn("Board post failed: {}", e.getMessage());
        }
    }

    // =====================================================================
    // Internals
    // =====================================================================

    private static final int STREAK_RETRIES = 3;
    private static final long STREAK_RETRY_DELAY_MS = 120_000;

    private void fetchStreaks(Faction f, List<Player> players) {
        fetchStreaks(f, players, 1);
    }

    /**
     * Looks up Torn active streaks in the background. Anyone still missing afterwards (rate limits,
     * a Torn hiccup, a redeploy mid-fetch) is retried a couple of minutes later, up to 3 passes.
     */
    private void fetchStreaks(Faction f, List<Player> players, int attempt) {
        Set<Long> inFlight = streaksInFlight.computeIfAbsent(f.getKey(), k -> ConcurrentHashMap.newKeySet());
        Map<Long, Long> ids = new LinkedHashMap<>();
        // Skip anyone already being looked up (e.g. Set Captains then Rebuild List straight after).
        players.stream().filter(p -> p.tornId != null && inFlight.add(p.tornId)).forEach(p -> ids.put(p.id, p.tornId));
        if (ids.isEmpty()) return;
        log.info("{}: fetching Torn streaks for {} players (pass {})", f.getName(), ids.size(), attempt);
        torn.fetchStreaks(ids,
                (discordId, streak) -> withLock(f.getKey(), () -> {
                    FactionState fs = state(f);
                    if (fs.pool != null) fs.pool.stream().filter(p -> p.id == discordId).forEach(p -> p.streak = streak);
                    Draft d = fs.draft;
                    if (d == null) return null;
                    d.eligible.stream().filter(p -> p.id == discordId).forEach(p -> p.streak = streak);
                    d.picks.stream().filter(p -> p.player.id == discordId).forEach(p -> p.player.streak = streak);
                    return null;
                }),
                () -> {
                    inFlight.removeAll(ids.values());
                    int missing = withLock(f.getKey(), () -> {
                        FactionState s = state(f);
                        Guild g = s.draft == null ? null : guildFor(s.draft);
                        commit(f, s, g);
                        return missingStreaks(s).size();
                    });
                    log.info("{}: streak pass {} done, {} still missing", f.getName(), attempt, missing);
                    if (missing > 0 && attempt < STREAK_RETRIES) {
                        timers.after(STREAK_RETRY_DELAY_MS, () -> fetchMissingStreaks(f, attempt + 1));
                    }
                });
    }

    private void fetchMissingStreaks(Faction f, int attempt) {
        List<Player> missing = withLock(f.getKey(), () -> missingStreaks(state(f)));
        if (!missing.isEmpty()) fetchStreaks(f, missing, attempt);
    }

    /** Players in a running draft (pool or drafted) with a Torn ID but no streak yet. */
    private List<Player> missingStreaks(FactionState s) {
        List<Player> out = new ArrayList<>();
        if (s.pool != null) s.pool.stream().filter(p -> p.tornId != null && p.streak == null).forEach(out::add);
        if (!s.draftRunning()) return out;
        s.draft.eligible.stream().filter(p -> p.tornId != null && p.streak == null).forEach(out::add);
        s.draft.picks.stream().map(pk -> pk.player).filter(p -> p.tornId != null && p.streak == null).forEach(out::add);
        return out;
    }

    /** Same Discord id, or same Torn ID (one may be a "not on Discord" entry for the other). */
    private static boolean samePerson(Player a, Player b) {
        return a.id == b.id || (a.tornId != null && a.tornId.equals(b.tornId));
    }

    private Player toPlayer(Member m) {
        return new Player(m.getIdLong(), m.getEffectiveName(), TornService.parseTornId(m.getEffectiveName()));
    }

    private Player resolveEligible(Draft d, String query) {
        return tryResolve(d.eligible, query)
                .orElseThrow(() -> new UserError("Nobody available matches `" + query + "` — pick from the suggestions."));
    }

    /** Matches an autocomplete id, an exact name, or a unique partial name. */
    private Optional<Player> tryResolve(List<Player> players, String query) {
        if (query == null || query.isBlank()) return Optional.empty();
        String q = query.trim();
        if (q.matches("-?\\d{1,20}")) {
            long n = Long.parseLong(q);
            Optional<Player> byId = players.stream()
                    .filter(p -> p.id == n || (p.tornId != null && p.tornId == n)).findFirst();
            if (byId.isPresent()) return byId;
        }
        String lower = q.toLowerCase(Locale.ROOT);
        Optional<Player> exact = players.stream().filter(p -> p.display.equalsIgnoreCase(q)).findFirst();
        if (exact.isPresent()) return exact;
        List<Player> partial = players.stream().filter(p -> p.display.toLowerCase(Locale.ROOT).contains(lower)).toList();
        if (partial.size() == 1) return Optional.of(partial.get(0));
        if (partial.size() > 1) {
            throw new UserError("Several players match `" + q + "`: "
                    + partial.stream().limit(5).map(p -> p.display).collect(Collectors.joining(", "))
                    + " — pick from the suggestions.");
        }
        return Optional.empty();
    }

    private String describeQueue(Draft d, long captain) {
        List<Long> q = d.queueFor(captain);
        if (q.isEmpty()) return "Your queue is empty.";
        StringBuilder sb = new StringBuilder("**Your queue:**\n");
        for (int i = 0; i < q.size(); i++) {
            long id = q.get(i);
            sb.append(i + 1).append(". ").append(d.findEligible(id).map(p -> p.display + p.streakLabel()).orElse("(taken)")).append('\n');
        }
        return sb.toString();
    }

    private Draft requireRunning(FactionState s, Faction f) {
        if (!s.draftRunning()) throw new UserError("No " + f.getName() + " draft is running.");
        return s.draft;
    }

    private Draft requireStatus(FactionState s, Faction f, DraftStatus status) {
        Draft d = requireRunning(s, f);
        if (d.status != status) {
            throw new UserError(switch (d.status) {
                case PAUSED -> "The " + f.getName() + " draft is paused.";
                case ACTIVE -> "The " + f.getName() + " draft isn't paused.";
                default -> "No " + f.getName() + " draft is running.";
            });
        }
        return d;
    }

    private Guild guildFor(Draft d) {
        JDA j = jda;
        if (j == null) return null;
        TextChannel ch = j.getTextChannelById(d.channelId);
        return ch == null ? null : ch.getGuild();
    }

    /** For tests: the in-memory state of a faction, or null. */
    FactionState stateOf(String key) {
        return states.get(key);
    }

    private FactionState state(Faction f) {
        if (broken.contains(f.getKey())) {
            throw new UserError("⚠️ This faction's saved data couldn't be loaded, so it's locked to protect it. "
                    + "Ask an admin to check the bot's logs.");
        }
        return states.computeIfAbsent(f.getKey(), FactionState::new);
    }

    /** Leader changes to rosters (undo, add, remove, admin pick, replace captain) wait until the competition ends. */
    private void requireRostersUnlocked(FactionState s, Faction f) {
        if (s.rostersLocked(System.currentTimeMillis() / 1000)) {
            throw new UserError("Rosters are locked while the " + f.getName() + " competition is live — "
                    + "changes would move hits between teams. They unlock when it ends.");
        }
    }

    /** Refresh the board, persist, and update the autocomplete snapshot. */
    private void commit(Faction f, FactionState s, Guild g) {
        refreshBoard(f, s, g);
        save(s);
    }

    private void save(FactionState s) {
        if (broken.contains(s.factionKey)) return; // never overwrite data we couldn't read
        repo.save(s);
        snapshot(s);
    }

    /** True when this faction's saved state couldn't be loaded; everything for it is refused. */
    public boolean isBroken(Faction f) {
        return broken.contains(f.getKey());
    }

    private void snapshot(FactionState s) {
        Draft d = s.draft;
        Set<Long> caps = new HashSet<>(s.captains);
        if (s.draftRunning()) caps.addAll(d.captainOrder);
        List<Player> available = s.draftRunning() ? d.eligible
                : s.pool != null ? s.pool
                : d != null ? d.eligible : List.of();
        snapshots.put(s.factionKey, new Snapshot(
                List.copyOf(available),
                d == null ? List.of() : d.picks.stream().filter(p -> !p.dropped).map(p -> p.player).toList(),
                Set.copyOf(caps),
                d == null ? null : d.status,
                List.copyOf(s.activeCaptains()),
                s.teams.entrySet().stream().filter(e -> e.getValue().name != null && e.getValue().roleId != null)
                        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> e.getValue().name)),
                s.competition.startEpoch, s.competition.endEpoch));
    }

    /**
     * Runs {@code work} under the faction's lock. If it fails, the faction's in-memory state is put
     * back to its last saved version, so a half-finished action leaves nothing behind. (Discord
     * changes that already went through, like a role being added, aren't undone.)
     */
    <T> T withLock(String key, Supplier<T> work) {
        if (broken.contains(key)) {
            throw new UserError("⚠️ This faction's saved data couldn't be loaded, so it's locked to protect it. "
                    + "Ask an admin to check the bot's logs.");
        }
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock(true));
        lock.lock();
        try {
            return work.get();
        } catch (RuntimeException e) {
            if (lock.getHoldCount() == 1) rollBack(key, e); // outermost call only; nested ones just rethrow
            throw e;
        } finally {
            lock.unlock();
        }
    }

    /** Replaces the in-memory state with the last saved one. Caller holds the lock. */
    private void rollBack(String key, RuntimeException cause) {
        try {
            FactionState saved = repo.load(key).orElseGet(() -> new FactionState(key));
            saved.factionKey = key;
            states.put(key, saved);
            snapshot(saved);
            if (cause instanceof UserError) {
                log.info("{}: action refused ({}), state rolled back to the last save", key, cause.getMessage());
            } else {
                log.warn("{}: action failed, state rolled back to the last save", key, cause);
            }
        } catch (StateRepository.UnreadableState e) {
            broken.add(key);
            snapshots.remove(key);
            log.error("{}: couldn't roll back - saved state unreadable, faction locked", key, e);
        } catch (Exception e) {
            log.error("{}: roll back failed", key, e);
        }
    }
}