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

    private final Map<String, FactionState> states = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private volatile JDA jda;

    public DraftManager(BotProperties props, StateRepository repo, BoardRenderer board,
                        TeamService teams, TornService torn, TimerService timers,
                        com.halloween.draft.competition.CompetitionRepository competitionRepo) {
        this.props = props;
        this.repo = repo;
        this.board = board;
        this.teams = teams;
        this.torn = torn;
        this.timers = timers;
        this.competitionRepo = competitionRepo;
    }

    @PostConstruct
    void load() {
        Map<String, FactionState> saved = repo.loadAll();
        for (Faction f : props.getFactions()) {
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
            try {
                withLock(f.getKey(), () -> {
                    FactionState s = state(f);
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

    /** For read-only commands: option → channel → captain's faction → led faction. */
    public Faction resolveViewFaction(Member m, String option, long channelId) {
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

    public Faction leaderFactionOrNull(Member m, String option, long channelId) {
        try {
            return resolveLeaderFaction(m, option, channelId);
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

            Map<Long, Member> byId = new LinkedHashMap<>();
            chosen.forEach(m -> byId.putIfAbsent(m.getIdLong(), m));
            List<Member> unique = new ArrayList<>(byId.values());
            if (unique.stream().anyMatch(m -> m.getUser().isBot())) throw new UserError("Bots can't be captains.");
            if (unique.size() < 2) throw new UserError("You need at least 2 captains.");
            if (unique.size() > MAX_CAPTAINS) throw new UserError("Maximum is " + MAX_CAPTAINS + " captains.");
            if (random) Collections.shuffle(unique);

            List<Long> newIds = unique.stream().map(Member::getIdLong).toList();
            for (Long old : s.captains) {
                if (!newIds.contains(old) && !isCaptainElsewhere(old, f.getKey())) teams.setCaptainRole(g, old, false);
            }
            newIds.forEach(id -> teams.setCaptainRole(g, id, true));
            List<Long> previous = s.captains == null ? List.of() : s.captains;
            s.captains = new ArrayList<>(newIds);

            // Tell new captains (and anyone still without a team name) what to do next.
            // Re-saving the same, already-named captains sends nothing.
            for (Long id : newIds) {
                if (previous.contains(id) && teamNamed(s, id)) continue;
                dm(id, "👑 You're a **" + f.getName() + "** draft captain!\n"
                        + (teamNamed(s, id) ? "Your team is **" + s.teams.get(id).name + "**. "
                        : "**Name your team:** run `/draft` in <#" + f.getChannelId()
                        + "> and press 🏷️ **Name Team**.\n")
                        + "You'll get a DM here when it's your turn to pick.");
            }

            // Build (or rebuild) the pool straight away so leadership can review and trim it.
            PoolResult pool = buildPool(g, f, s);
            s.pool = pool.players();
            save(s);
            fetchStreaks(f, pool.players().stream().filter(p -> p.streak == null).toList());

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
                teams.setCaptainRole(g, m.getIdLong(), true);
            });
            d.eligible = pool;
            d.sortEligible();
            d.timerSeconds = timerSeconds;
            d.autopick = autopick;
            d.channelId = f.getChannelId();
            d.status = DraftStatus.ACTIVE;
            s.draft = d;
            s.pool = null; // the prepared pool now lives in the draft

            clearOwnMessages(channel); // previous boards, old pings - the new board is the only message
            refreshBoard(f, s, g);
            advance(f, s, g, null);
            commit(f, s, g);
            fetchStreaks(f, pool.stream().filter(p -> p.streak == null).toList());
            long offDiscord = pool.stream().filter(p -> !p.onDiscord()).count();
            return "🏁 " + f.getName() + " draft started in " + channel.getAsMention() + " — " + pool.size()
                    + " players (" + offDiscord + " not on Discord), " + order.size() + " captains.\nOrder: "
                    + order.stream().map(Member::getEffectiveName).collect(Collectors.joining(" → ")) + poolNote;
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
            long oldId = oldCap.getIdLong();
            long newId = newCap.getIdLong();
            if (newCap.getUser().isBot()) throw new UserError("Bots can't be captains.");
            if (oldId == newId) throw new UserError("That's the same person.");
            List<Long> current = s.activeCaptains();
            if (!current.contains(oldId)) throw new UserError(oldCap.getEffectiveName() + " isn't a " + f.getName() + " captain.");
            if (current.contains(newId)) throw new UserError(newCap.getEffectiveName() + " is already a captain.");
            if (s.draft != null && s.draftRunning() && s.draft.isDrafted(newId)) {
                throw new UserError(newCap.getEffectiveName() + " has already been drafted — undo or remove that pick first.");
            }

            s.captains.replaceAll(id -> id == oldId ? newId : id);
            boolean running = s.draftRunning();
            boolean wasOnClock = false;
            if (running) {
                wasOnClock = Objects.equals(s.draft.currentCaptain(), oldId);
                s.draft.replaceCaptain(oldId, newId, newCap.getEffectiveName());
            }
            Team team = s.teams.remove(oldId);
            if (team != null) {
                s.teams.put(newId, team);
                teams.removeMember(g, team, oldId);
                teams.addMember(g, team, newId);
                teams.setTeamCaptain(g, team, oldId, false);
                teams.setTeamCaptain(g, team, newId, true);
            }
            if (!isCaptainElsewhere(oldId, f.getKey())) teams.setCaptainRole(g, oldId, false);
            teams.setCaptainRole(g, newId, true);

            boolean named = team != null && team.roleId != null;
            dm(newId, "🔁 You're now a " + f.getName() + " captain"
                    + (named ? " of **" + team.name + "**" : "") + ", replacing " + oldCap.getEffectiveName() + "."
                    + (named ? "" : "\n**Name your team:** run `/draft` in <#" + f.getChannelId()
                    + "> and press 🏷️ **Name Team**."));
            dm(oldId, "🔁 You've been replaced as a " + f.getName() + " captain by " + newCap.getEffectiveName() + ".");
            if (running) {
                if (wasOnClock && s.draft.status == DraftStatus.ACTIVE) startClock(f, s, g, null, true);
                commit(f, s, g);
            } else {
                // Before the draft: new captain leaves the prepared pool, old captain goes back in.
                if (s.pool != null) s.pool = buildPool(g, f, s).players();
                save(s);
            }
            return newCap.getEffectiveName() + " is now captain in place of " + oldCap.getEffectiveName() + ".";
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
            final Player p;
            if (member != null) {
                if (member.getUser().isBot()) throw new UserError("Bots can't be drafted.");
                p = toPlayer(member);
            } else {
                Member linked = memberByTornId(g, tornId);
                p = linked != null ? toPlayer(linked) : Player.tornOnly(tornId, torn.fetchName(tornId));
            }
            if (s.activeCaptains().contains(p.id)) throw new UserError(p.display + " is a captain.");

            if (preDraft(s)) {
                if (s.pool == null) s.pool = buildPool(g, f, s).players();
                s.excludedIds.remove(p.id);
                if (p.tornId != null) s.excludedTornIds.remove(p.tornId);
                if (s.pool.stream().anyMatch(x -> x.id == p.id)) return p.display + " is already a participant.";
                s.pool.add(p);
                s.pool.sort(Comparator.comparing(x -> x.display.toLowerCase(Locale.ROOT)));
                save(s);
                fetchStreaks(f, List.of(p));
                return "➕ " + p.display + " added to the " + f.getName() + " participants"
                        + (p.onDiscord() ? "." : " (not on Discord).");
            }

            Draft d = s.draft;
            if (d.findEligible(p.id).isPresent()) throw new UserError(p.display + " is already a participant.");
            if (d.isDrafted(p.id)) throw new UserError(p.display + " has already been drafted.");
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
     * Pool = everyone with the faction's draft role + every member of the Torn faction.
     * Torn members are linked to Discord when a member's nickname contains their Torn ID;
     * the rest join as Torn-only players. Captains and excluded players are left out.
     */
    private PoolResult buildPool(Guild g, Faction f, FactionState s) {
        Role draftRole = g.getRoleById(f.getDraftRoleId());
        if (draftRole == null) throw new UserError("I can't find the " + f.getName() + " draft role.");
        Set<Long> capIds = new HashSet<>(s.activeCaptains());
        Set<Long> capTorn = capIds.stream().map(g::getMemberById).filter(Objects::nonNull)
                .map(m -> TornService.parseTornId(m.getEffectiveName())).filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, Player> byId = new LinkedHashMap<>();
        for (Member m : g.getMembersWithRoles(draftRole)) {
            if (m.getUser().isBot() || capIds.contains(m.getIdLong())) continue;
            Player p = toPlayer(m);
            if (p.tornId != null && capTorn.contains(p.tornId)) continue;
            byId.put(p.id, p);
        }

        String note = "";
        if (f.getTornFactionId() > 0) {
            Map<Long, String> members = Map.of();
            try {
                members = torn.fetchFactionMembers(f.getTornFactionId());
            } catch (UserError e) {
                note = "\n⚠️ Couldn't load the Torn faction member list (" + e.getMessage()
                        + ") — the list only has Discord members with the draft role.";
            }
            Map<Long, Member> discordByTorn = new HashMap<>();
            for (Member m : g.getMembers()) {
                Long t = TornService.parseTornId(m.getEffectiveName());
                if (t != null && !m.getUser().isBot()) discordByTorn.putIfAbsent(t, m);
            }
            Set<Long> tornInPool = byId.values().stream().map(p -> p.tornId).filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            for (Map.Entry<Long, String> e : members.entrySet()) {
                long tid = e.getKey();
                if (capTorn.contains(tid) || tornInPool.contains(tid)) continue;
                Member m = discordByTorn.get(tid);
                if (m != null && capIds.contains(m.getIdLong())) continue;
                Player p = m != null ? toPlayer(m) : Player.tornOnly(tid, e.getValue());
                byId.putIfAbsent(p.id, p);
            }
        }

        List<Player> players = byId.values().stream()
                .filter(p -> !s.isExcluded(p))
                .sorted(Comparator.comparing(p -> p.display.toLowerCase(Locale.ROOT)))
                .collect(Collectors.toCollection(ArrayList::new));
        int discord = (int) players.stream().filter(Player::onDiscord).count();
        return new PoolResult(players, discord, players.size() - discord, note);
    }

    private Member memberByTornId(Guild g, long tornId) {
        return g.getMembers().stream()
                .filter(m -> !m.getUser().isBot() && Objects.equals(TornService.parseTornId(m.getEffectiveName()), tornId))
                .findFirst().orElse(null);
    }

    /** Validates /enddraft before the confirm buttons are shown. */
    public Faction checkEndDraft(Member invoker, String opt, long channelId) {
        Faction f = resolveLeaderFaction(invoker, opt, channelId);
        if (!state(f).draftRunning()) throw new UserError("No " + f.getName() + " draft is running.");
        return f;
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

    public String queueShow(Member captain, long channelId) {
        Faction f = resolveCaptainFaction(captain.getIdLong(), channelId, true);
        return withLock(f.getKey(), () -> {
            Draft d = requireRunning(state(f), f);
            d.firstQueued(captain.getIdLong()); // drops anyone already taken
            return describeQueue(d, captain.getIdLong());
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
                    refused.addAll(teams.grantCaptainChannelAccess(g, team, channelId));
                } catch (Exception e) {
                    log.error("Team creation failed for {}", name, e);
                    teams.delete(g, team);
                    s.teams.remove(me);
                    if (e instanceof UserError ue) throw ue;
                    throw new UserError("I couldn't create the team role/channels (" + e.getMessage() + "). "
                            + "Check my role has Manage Roles and Manage Channels and sits above the team roles.");
                }
                reply = "Team **" + name + "** created: team role, **" + name + " Captain** role ("
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
    // Read-only
    // =====================================================================

    public MessageEmbed status(Member m, String opt, long channelId) {
        Faction f = resolveViewFaction(m, opt, channelId);
        return withLock(f.getKey(), () -> {
            FactionState s = state(f);
            if (s.draft == null) throw new UserError("No " + f.getName() + " draft has been run yet.");
            return board.header(f, s);
        });
    }

    public MessageEmbed viewCaptains(Guild g, Member m, String opt, long channelId) {
        Faction f = resolveViewFaction(m, opt, channelId);
        return withLock(f.getKey(), () -> {
            FactionState s = state(f);
            List<Long> caps = s.activeCaptains();
            if (caps.isEmpty()) throw new UserError("No " + f.getName() + " captains set yet.");
            StringBuilder sb = new StringBuilder();
            int unnamed = 0;
            for (int i = 0; i < caps.size(); i++) {
                long id = caps.get(i);
                Team t = s.teams.get(id);
                boolean named = t != null && t.roleId != null;
                if (!named) unnamed++;
                sb.append("**").append(i + 1).append(".** <@").append(id).append("> — ")
                        .append(named ? "**" + t.name + "**" : "⚠️ *team not named yet*");
                if (s.draftRunning()) sb.append(" · ").append(s.draft.picksFor(id).stream().filter(p -> !p.dropped).count()).append(" picked");
                sb.append('\n');
            }
            String footer = s.draftRunning()
                    ? "Draft order · " + s.draft.status.name().toLowerCase()
                    : unnamed == 0 ? "All teams named — ready for /startdraft"
                    : unnamed + " team" + (unnamed == 1 ? "" : "s") + " still to be named before /startdraft";
            return new EmbedBuilder()
                    .setTitle("👑 " + f.getName() + " captains")
                    .setDescription(sb.toString())
                    .setFooter(footer)
                    .setColor(0x5865F2)
                    .build();
        });
    }

    public List<MessageEmbed> remaining(Member m, String opt, long channelId) {
        Faction f = resolveViewFaction(m, opt, channelId);
        return withLock(f.getKey(), () -> {
            FactionState s = state(f);
            // Before the draft this shows the prepared pool so leadership can review it.
            boolean prepared = !s.draftRunning() && s.pool != null;
            List<Player> players = prepared ? s.pool : requireRunning(s, f).eligible;
            if (players.isEmpty()) throw new UserError("There are no participants.");
            String label = prepared ? " participants — " + players.size() : " participants — " + players.size() + " left";
            List<MessageEmbed> embeds = new ArrayList<>();
            int perPage = 60;
            int pages = (players.size() + perPage - 1) / perPage;
            for (int i = 0; i < pages && i < 10; i++) {
                String body = players.subList(i * perPage, Math.min(players.size(), (i + 1) * perPage)).stream()
                        .map(p -> "• " + p.display + p.streakLabel() + (p.onDiscord() ? "" : " *(not on Discord)*"))
                        .collect(Collectors.joining("\n"));
                embeds.add(new EmbedBuilder()
                        .setTitle(f.getName() + label + (pages > 1 ? " (" + (i + 1) + "/" + pages + ")" : ""))
                        .setDescription(body)
                        .setColor(0x5865F2)
                        .build());
            }
            return embeds;
        });
    }

    // =====================================================================
    // Panel data (read-only views for the /draft panels)
    // =====================================================================

    public record CaptainInfo(long id, String name, String team, int picks) {}

    /** Everything the main panel shows. {@code status} is null when no draft has been run since teardown. */
    public record PanelInfo(Faction faction, boolean running, DraftStatus status, List<CaptainInfo> captains,
                            boolean poolPrepared, int poolSize, int poolOffDiscord, Long onClock,
                            Long deadlineMs, Long pausedRemainingMs, int timerSeconds, boolean autopick,
                            int picksMade, boolean allTeamsNamed, int excluded) {}

    /**
     * A captain's own view: their team, picks and queue, plus where they are in the order.
     * {@code picksAway} is 0 when they're on the clock and null when they have no picks left;
     * {@code nextPick} is that pick's overall number (1-based).
     */
    public record CaptainView(Faction faction, String team, List<Player> picks, List<Player> queue,
                              boolean running, boolean onClock, boolean paused, Long clockCaptain,
                              Long deadlineMs, Long pausedRemainingMs, Integer picksAway, Integer nextPick) {}

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
                int picks = running ? (int) d.picksFor(id).stream().filter(p -> !p.dropped).count() : 0;
                caps.add(new CaptainInfo(id, name, team, picks));
            }
            List<Player> pool = running ? d.eligible : s.pool != null ? s.pool : List.of();
            int off = (int) pool.stream().filter(p -> !p.onDiscord()).count();
            return new PanelInfo(f, running, d == null ? null : d.status, caps, running || s.pool != null,
                    pool.size(), off, running ? d.currentCaptain() : null,
                    running ? d.turnDeadlineMs : null, running ? d.pausedRemainingMs : null,
                    d == null ? 0 : d.timerSeconds, d != null && d.autopick,
                    d == null ? 0 : (int) d.picks.stream().filter(p -> !p.dropped).count(), allNamed,
                    s.excludedIds.size());
        });
    }

    public CaptainView captainView(Faction f, long captainId) {
        return withLock(f.getKey(), () -> {
            FactionState s = state(f);
            Team t = s.teams.get(captainId);
            String team = t != null && t.roleId != null ? t.name : null;
            if (!s.draftRunning()) {
                return new CaptainView(f, team, List.of(), List.of(), false, false, false, null, null, null, null, null);
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
                    away, next);
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
    public record TeamRoster(long captainId, String teamName, List<RosterMember> members) {}

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
                out.add(new TeamRoster(cap, s.teamName(cap, capName), members));
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
            copy.finalPosted = c.finalPosted;
            copy.keysFailingNotified = c.keysFailingNotified;
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
            if (end <= start) throw new UserError("The end has to be after the start.");
            if (c.live(now) && !start.equals(c.startEpoch)) {
                throw new UserError("The competition has already started — only the end time can change.");
            }
            if (!c.live(now) && end <= now) throw new UserError("That end time is already in the past.");
            if (!start.equals(c.startEpoch)) c.lastFetchedEpoch = null; // new window: fetch from its start
            c.startEpoch = start;
            c.endEpoch = end;
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
    // Admin teardown (all factions)
    // =====================================================================

    public String checkTeardown(Member invoker) {
        if (!invoker.hasPermission(Permission.ADMINISTRATOR)) throw new UserError("Server admins only.");
        long nowEpoch = System.currentTimeMillis() / 1000;
        List<String> live = props.getFactions().stream().filter(f -> state(f).competition.live(nowEpoch))
                .map(Faction::getName).toList();
        if (!live.isEmpty()) {
            throw new UserError("Competitions are live for: " + String.join(", ", live)
                    + ". Clean Up removes the teams they're scored on, so wait until they finish.");
        }
        List<String> running = props.getFactions().stream().filter(f -> state(f).draftRunning()).map(Faction::getName).toList();
        if (!running.isEmpty()) {
            throw new UserError("These drafts are still running: " + String.join(", ", running) + ". End them first.");
        }
        int count = props.getFactions().stream().mapToInt(f -> state(f).teams.size()).sum();
        return count + " team" + (count == 1 ? "" : "s") + " across all factions";
    }

    public String teardown(Guild g, Member invoker, boolean clearCaptains) {
        checkTeardown(invoker);
        int deleted = 0;
        for (Faction f : props.getFactions()) {
            deleted += withLock(f.getKey(), () -> {
                FactionState s = state(f);
                int n = s.teams.size();
                s.teams.values().forEach(t -> teams.delete(g, t));
                s.teams.clear();
                s.draft = null; // finished drafts stay visible on their boards
                s.pool = null;
                s.excludedIds.clear();
                s.excludedTornIds.clear();
                s.competition = new com.halloween.draft.model.Competition(); // final scoreboards stay posted
                if (clearCaptains) s.captains.clear();
                save(s);
                return n;
            });
        }
        int captainsCleared = clearCaptains ? teams.clearCaptainRole(g) : 0;
        return "Deleted " + deleted + " team" + (deleted == 1 ? "" : "s") + " (roles, text and voice channels)"
                + (clearCaptains ? " and removed the captain role from " + captainsCleared + " member(s)." : ".");
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
            dm(d.currentCaptain(), "⏳ **30 seconds left** on your " + f.getName() + " draft pick! Use `/pick` in <#"
                    + d.channelId + ">" + (d.autopick ? " or one will be made for you." : "."));
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
                dm(captain, "⏰ Your time is up! You can still `/pick`, or leadership can pick for you with 🛡️ Admin Pick.");
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

    /** Turn notification - by DM only, so the board stays the only message in the channel. */
    private void ping(Guild g, FactionState s, long captain) {
        Draft d = s.draft;
        deletePing(g, d); // clears any old-style channel ping
        int idx = d.nextPickIndex();
        String clock = d.turnDeadlineMs != null ? " — time's up <t:" + d.turnDeadlineMs / 1000 + ":R>" : "";
        dm(captain, "🎯 **It's your pick** (#" + (idx + 1) + ")! Use `/pick` in <#" + d.channelId + ">"
                + clock + ".\nDraft board: " + boardLink(g, d));
    }

    /** Deletes the bot's own recent messages in a channel. Other people's messages are left alone. */
    private void clearOwnMessages(TextChannel ch) {
        try {
            long self = ch.getGuild().getSelfMember().getIdLong();
            List<Message> mine = ch.getHistory().retrievePast(100).complete().stream()
                    .filter(m -> m.getAuthor().getIdLong() == self).toList();
            mine.forEach(m -> m.delete().queue(null, e -> {}));
        } catch (Exception e) {
            log.warn("Couldn't clear old bot messages in #{}: {}", ch.getName(), e.getMessage());
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

    private void refreshBoard(Faction f, FactionState s, Guild g) {
        Draft d = s.draft;
        if (d == null || g == null) return;
        TextChannel ch = g.getTextChannelById(d.channelId);
        if (ch == null) return;
        List<MessageEmbed> embeds = board.render(f, s);
        if (d.boardMessageId != null) {
            try {
                ch.editMessageEmbedsById(d.boardMessageId, embeds).complete();
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
            Message msg = ch.sendMessageEmbeds(embeds).complete();
            d.boardMessageId = msg.getIdLong(); // not pinned: pinning posts a system message in the channel
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
        Map<Long, Long> ids = new LinkedHashMap<>();
        players.stream().filter(p -> p.tornId != null).forEach(p -> ids.put(p.id, p.tornId));
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

    private boolean isCaptainElsewhere(long userId, String exceptKey) {
        return states.values().stream()
                .filter(s -> !s.factionKey.equals(exceptKey))
                .anyMatch(s -> s.captains.contains(userId) || s.activeCaptains().contains(userId));
    }

    private Guild guildFor(Draft d) {
        JDA j = jda;
        if (j == null) return null;
        TextChannel ch = j.getTextChannelById(d.channelId);
        return ch == null ? null : ch.getGuild();
    }

    private FactionState state(Faction f) {
        return states.computeIfAbsent(f.getKey(), FactionState::new);
    }

    /** Refresh the board, persist, and update the autocomplete snapshot. */
    private void commit(Faction f, FactionState s, Guild g) {
        refreshBoard(f, s, g);
        save(s);
    }

    private void save(FactionState s) {
        repo.save(s);
        snapshot(s);
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

    private <T> T withLock(String key, Supplier<T> work) {
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock(true));
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }
}