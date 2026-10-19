package com.halloween.draft.competition;

import com.halloween.draft.competition.CompetitionRepository.ApiKey;
import com.halloween.draft.competition.CompetitionRepository.AttackRow;
import com.halloween.draft.config.BotProperties;
import com.halloween.draft.config.BotProperties.Faction;
import com.halloween.draft.core.DraftManager;
import com.halloween.draft.core.DraftManager.RosterMember;
import com.halloween.draft.core.DraftManager.TeamRoster;
import com.halloween.draft.core.TornService;
import com.halloween.draft.core.UserError;
import com.halloween.draft.model.Competition;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Attack competitions. Once a minute, for each faction whose window is live (or just ended), fetch
 * outgoing attacks with the faction's keys, store them, and refresh that faction's scoreboard.
 *
 * Counting rules: successful results only, by drafted players (matched on Torn ID), excluding hits
 * on any of the five family factions (the scoring faction included), with the attack's end time
 * inside the window.
 *
 * Runs on its own thread so slow Torn calls can never hold up the draft's pick timers.
 */
@Service
public class CompetitionService {
    private static final Logger log = LoggerFactory.getLogger(CompetitionService.class);

    /** Successful outcomes. Lost, Stalemate, Assist, Escape, Timeout, Interrupted and None don't count. */
    public static final List<String> COUNTED = List.of(
            "Attacked", "Mugged", "Hospitalized", "Arrested", "Looted", "Special", "Bounty");
    /** Keys needed before a draft can start, so monitoring survives one key failing. */
    public static final int MIN_KEYS = 2;
    private static final long POLL_MS = 60_000;
    private static final long KEY_CHECK_MS = 60 * 60_000;
    private static final long MEMBER_CHECK_SECONDS = 15 * 60;
    private static final long OVERLAP_SECONDS = 600; // re-read 10 minutes to catch long fights
    private static final long DRAFT_WARN_SECONDS = 30 * 60;
    private static final int TOP_HITTERS = 10;
    private static final String[] MEDALS = {"🥇", "🥈", "🥉"};

    /** For the panel: everything about a faction's competition at a glance. */
    public record Status(Long start, Long end, boolean live, boolean over, boolean finalPosted, int keys,
                         int keysWorking, Long lastCheckedMs, String lastError, int missingTornIds,
                         List<String> leftFaction) {}

    private final BotProperties props;
    private final DraftManager drafts;
    private final CompetitionRepository repo;
    private final TornAttackClient torn;
    private final TornService tornService;
    private final KeyCrypto crypto;
    private final AtomicInteger rotation = new AtomicInteger();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "competition");
        t.setDaemon(true);
        return t;
    });
    /** One scoreboard update at a time per faction, so two can never post a message each. */
    private final Map<String, Object> scoreboardLocks = new ConcurrentHashMap<>();
    /** Drafted Torn IDs no longer in the Torn faction, per faction, from the last member check. */
    private final Map<String, Set<Long>> leftFaction = new ConcurrentHashMap<>();
    private final Map<String, Long> lastMemberCheck = new ConcurrentHashMap<>();
    private volatile JDA jda;

    public CompetitionService(BotProperties props, DraftManager drafts, CompetitionRepository repo,
                              TornAttackClient torn, TornService tornService, KeyCrypto crypto) {
        this.props = props;
        this.drafts = drafts;
        this.repo = repo;
        this.torn = torn;
        this.tornService = tornService;
        this.crypto = crypto;
    }

    /** Starts the once-a-minute loop and the hourly key check. Called when the bot has connected. */
    public void start(JDA jda) {
        this.jda = jda;
        scheduler.scheduleWithFixedDelay(() -> guard("Competition tick", this::tick), 15_000, POLL_MS, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(() -> guard("Key check", this::checkKeys), 5 * 60_000, KEY_CHECK_MS, TimeUnit.MILLISECONDS);
        log.info("Competition monitor running (every {}s)", POLL_MS / 1000);
    }

    private static void guard(String what, Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.error("{} failed", what, e);
        }
    }

    /** Torn faction IDs whose members can't be counted as targets: all five, the scoring faction included. */
    public static List<Long> familyFactionIds(BotProperties props) {
        return props.getFactions().stream().map(Faction::getTornFactionId).filter(id -> id > 0).distinct().toList();
    }

    void tick() {
        for (Faction f : props.getFactions()) {
            if (drafts.isBroken(f)) continue;
            try {
                tick(f);
            } catch (Exception e) {
                log.error("Competition tick failed for {}", f.getName(), e);
            }
        }
    }

    private void tick(Faction f) {
        Competition c = drafts.competition(f);
        if (!c.scheduled()) {
            if (c.scoreboardMessageId != null) refreshScoreboard(f); // times were cleared: remove the old scoreboard
            return;
        }
        if (c.finalPosted) return;
        long now = Instant.now().getEpochSecond();
        warnIfDraftOverruns(f, c, now);

        if (c.live(now) || c.over(now)) {
            checkMembership(f, now);
            long to = Math.min(now, c.endEpoch);
            // Attacks are filtered on start time, so look back a little: a fight that started just
            // before the window (or the last check) can end inside it. Repeats are ignored on save.
            long from = (c.lastFetchedEpoch == null ? c.startEpoch : c.lastFetchedEpoch) - OVERLAP_SECONDS;
            boolean fetched = fetch(f, Math.max(0, from), to);
            if (fetched) {
                drafts.updateCompetition(f, x -> {
                    x.lastFetchedEpoch = to;
                    x.lastCheckedMs = System.currentTimeMillis();
                    x.lastError = null;
                    x.keysFailingNotified = false;
                    // Final once a successful fetch has covered the whole window (plus a little settling time).
                    if (now >= x.endEpoch + 120) x.finalPosted = true;
                });
            }
        }
        refreshScoreboard(f);
    }

    /**
     * DMs the faction's leaders if its draft is still running 30 minutes before the competition
     * starts, and again when it starts: hits count for whoever is on a team, so players picked
     * after the start would bring their earlier hits with them.
     */
    private void warnIfDraftOverruns(Faction f, Competition c, long now) {
        DraftManager.Snapshot snap = drafts.snapshot(f.getKey());
        boolean running = snap != null && snap.status() != null && snap.status().running();
        if (!running || c.over(now)) return;

        String text;
        if (c.live(now) && !c.draftLiveWarned) {
            text = "🚨 **" + f.getName() + ":** the competition started at " + DraftManager.tct(c.startEpoch)
                    + " TCT but the draft is still running (" + snap.eligible().size() + " players left). "
                    + "Anyone picked from now on brings their hits since the start with them. Undo, add, remove, "
                    + "admin pick and replace captain are now locked. Finish the draft quickly, or use "
                    + "🎛️ Open my draft panel → 🛑 End Draft.";
            drafts.updateCompetition(f, x -> { x.draftLiveWarned = true; x.draftSoonWarned = true; });
        } else if (!c.live(now) && c.startEpoch - now <= DRAFT_WARN_SECONDS && !c.draftSoonWarned) {
            text = "⏰ **" + f.getName() + ":** the competition starts <t:" + c.startEpoch + ":R> ("
                    + DraftManager.tct(c.startEpoch) + " TCT) and the draft is still running ("
                    + snap.eligible().size() + " players left). Players picked after the start would bring "
                    + "their earlier hits with them, so try to finish before then — or move the start under "
                    + "🗓️ Competition Times.";
            drafts.updateCompetition(f, x -> x.draftSoonWarned = true);
        } else {
            return;
        }
        dmLeaders(f, text);
    }

    private void dmLeaders(Faction f, String text) {
        JDA j = jda;
        if (j == null) return;
        TextChannel ch = j.getTextChannelById(f.getChannelId());
        Role role = ch == null ? null : ch.getGuild().getRoleById(f.getLeadershipRoleId());
        if (role == null) {
            log.warn("Can't DM {} leaders: leadership role {} not found", f.getName(), f.getLeadershipRoleId());
            return;
        }
        ch.getGuild().getMembersWithRoles(role).stream().filter(m -> !m.getUser().isBot()).forEach(m ->
                m.getUser().openPrivateChannel().flatMap(pc -> pc.sendMessage(text)).queue(null, e -> {}));
    }

    /**
     * Every 15 minutes during the competition: which drafted players are no longer in the Torn
     * faction? Their hits stop appearing in the faction's attack log, so they're flagged.
     */
    private void checkMembership(Faction f, long now) {
        Long last = lastMemberCheck.get(f.getKey());
        if (last != null && now - last < MEMBER_CHECK_SECONDS) return;
        lastMemberCheck.put(f.getKey(), now);
        if (f.getTornFactionId() <= 0) return;
        try {
            Set<Long> members = tornService.fetchFactionMembers(f.getTornFactionId()).keySet();
            Set<Long> left = new HashSet<>();
            for (TeamRoster t : drafts.rosters(f)) {
                for (RosterMember m : t.members()) {
                    if (m.tornId() != null && !members.contains(m.tornId())) left.add(m.tornId());
                }
            }
            leftFaction.put(f.getKey(), Set.copyOf(left));
        } catch (Exception e) {
            log.warn("{}: couldn't check faction membership: {}", f.getName(), e.getMessage());
        }
    }

    /** Fetches with the faction's keys in turn until one works. Returns false if none did. */
    private boolean fetch(Faction f, long from, long to) {
        List<ApiKey> all = repo.keys(f.getKey());
        if (all.isEmpty()) {
            failed(f, "No API keys added — add one under 🔑 API Keys.", all);
            return false;
        }
        // Keys without a known problem first, rotating between them; flagged keys only as a last resort.
        List<ApiKey> good = all.stream().filter(k -> k.lastError() == null).toList();
        List<ApiKey> flagged = all.stream().filter(k -> k.lastError() != null).toList();
        List<ApiKey> order = new ArrayList<>();
        int startAt = good.isEmpty() ? 0 : Math.floorMod(rotation.getAndIncrement(), good.size());
        for (int i = 0; i < good.size(); i++) order.add(good.get((startAt + i) % good.size()));
        order.addAll(flagged);

        String lastProblem = null;
        for (ApiKey k : order) {
            try {
                List<AttackRow> rows = torn.outgoing(crypto.decrypt(k.encryptedKey()), f.getTornFactionId(), from, to);
                repo.saveAttacks(f.getKey(), rows);
                repo.markKeyOk(k.id());
                log.debug("{}: {} attacks fetched with {}'s key", f.getName(), rows.size(), k.ownerName());
                return true;
            } catch (TornAttackClient.KeyProblem e) {
                repo.markKeyError(k.id(), e.getMessage());
                lastProblem = k.ownerName() + "'s key: " + e.getMessage();
            } catch (Exception e) {
                lastProblem = e.getMessage();
                log.warn("{}: fetch with {}'s key failed: {}", f.getName(), k.ownerName(), e.getMessage());
            }
        }
        failed(f, lastProblem, all);
        return false;
    }

    /** Records the problem and, the first time, DMs whoever added the keys. */
    private void failed(Faction f, String problem, List<ApiKey> keys) {
        Competition c = drafts.competition(f);
        drafts.updateCompetition(f, x -> {
            x.lastError = problem;
            x.lastCheckedMs = System.currentTimeMillis();
            x.keysFailingNotified = true;
        });
        if (c.keysFailingNotified || jda == null) return;
        Set<Long> notify = new HashSet<>();
        keys.forEach(k -> notify.add(k.addedBy()));
        String text = "⚠️ **" + f.getName() + " competition:** I can't fetch attacks right now (" + problem
                + "). Open your draft panel → 🔑 API Keys to check or add a key — attacks are caught up once a key works.";
        notify.forEach(id -> jda.openPrivateChannelById(id).flatMap(pc -> pc.sendMessage(text)).queue(null, e -> {}));
    }

    /** Hourly: is every stored key still working, and does its owner still belong to the faction? */
    void checkKeys() {
        for (Faction f : props.getFactions()) {
            if (drafts.isBroken(f) || f.getTornFactionId() <= 0) continue;
            for (ApiKey k : repo.keys(f.getKey())) {
                try {
                    TornAttackClient.KeyOwner owner = torn.owner(crypto.decrypt(k.encryptedKey()));
                    if (owner.factionId() != f.getTornFactionId()) {
                        repo.markKeyError(k.id(), owner.name() + " has left " + f.getName()
                                + " — remove this key and add a current member's key");
                        log.info("{}: {}'s key flagged, owner is no longer in the faction", f.getName(), owner.name());
                    }
                } catch (TornAttackClient.KeyProblem e) {
                    repo.markKeyError(k.id(), e.getMessage());
                } catch (Exception e) {
                    log.warn("{}: key check for {} failed: {}", f.getName(), k.ownerName(), e.getMessage());
                }
                sleep(1_000);
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // =====================================================================
    // Keys (leader panel)
    // =====================================================================

    public List<ApiKey> keys(Faction f) {
        return repo.keys(f.getKey());
    }

    public String addKey(Member invoker, Faction f, String rawKey) {
        requireLeader(invoker, f);
        if (!crypto.configured()) {
            String secret = props.getKeyEncryptionSecret();
            throw new UserError(secret == null || secret.isBlank()
                    ? "KEY_ENCRYPTION_SECRET isn't set on the server, so keys can't be stored safely."
                    : "KEY_ENCRYPTION_SECRET is too short (" + secret.length()
                    + " characters) — it needs at least 16, so keys can't be stored safely.");
        }
        if (f.getTornFactionId() <= 0) throw new UserError("No Torn faction ID is configured for " + f.getName() + ".");
        TornAttackClient.KeyOwner owner = torn.verify(rawKey.trim(), f.getTornFactionId(), f.getName());
        repo.saveKey(f.getKey(), owner.tornId(), owner.name(), crypto.encrypt(rawKey.trim()), invoker.getIdLong());
        drafts.updateCompetition(f, x -> x.keysFailingNotified = false);
        return "🔑 Added **" + owner.name() + "**'s key — it has faction API access.";
    }

    public String removeKey(Member invoker, Faction f, long keyId) {
        requireLeader(invoker, f);
        return repo.deleteKey(f.getKey(), keyId) > 0 ? "🔑 Key removed." : "That key was already removed.";
    }

    private void requireLeader(Member m, Faction f) {
        if (!drafts.isLeader(m, f)) throw new UserError("Only " + f.getName() + " leadership can manage keys.");
    }

    // =====================================================================
    // Status (leader panel)
    // =====================================================================

    public Status status(Faction f) {
        Competition c = drafts.competition(f);
        long now = Instant.now().getEpochSecond();
        List<ApiKey> keys = repo.keys(f.getKey());
        int working = (int) keys.stream().filter(k -> k.lastError() == null).count();
        List<TeamRoster> rosters = drafts.rosters(f);
        int missing = (int) rosters.stream().flatMap(t -> t.members().stream())
                .filter(m -> m.tornId() == null).count();
        Set<Long> left = leftFaction.getOrDefault(f.getKey(), Set.of());
        List<String> leftNames = rosters.stream().flatMap(t -> t.members().stream())
                .filter(m -> m.tornId() != null && left.contains(m.tornId())).map(RosterMember::display).toList();
        return new Status(c.startEpoch, c.endEpoch, c.live(now), c.over(now), c.finalPosted, keys.size(), working,
                c.lastCheckedMs, c.lastError, missing, leftNames);
    }

    // =====================================================================
    // Scoreboard
    // =====================================================================

    /**
     * Posts, edits or (when the times have been cleared) deletes the faction's scoreboard message.
     * Safe to call any time; calls for the same faction run one after another.
     */
    public void refreshScoreboard(Faction f) {
        synchronized (scoreboardLocks.computeIfAbsent(f.getKey(), k -> new Object())) {
            JDA j = jda;
            if (j == null || drafts.isBroken(f)) return;
            Competition c = drafts.competition(f);
            TextChannel ch = j.getTextChannelById(props.getScoreboardChannelId());
            if (ch == null) {
                log.warn("Scoreboard channel {} not found", props.getScoreboardChannelId());
                return;
            }
            if (!c.scheduled()) {
                if (c.scoreboardMessageId != null) {
                    ch.deleteMessageById(c.scoreboardMessageId).queue(null, e -> {});
                    drafts.updateCompetition(f, x -> x.scoreboardMessageId = null);
                }
                return;
            }
            List<MessageEmbed> embeds = render(f, c);
            if (c.scoreboardMessageId != null) {
                try {
                    ch.editMessageEmbedsById(c.scoreboardMessageId, embeds).complete();
                    return;
                } catch (ErrorResponseException e) {
                    if (e.getErrorResponse() != ErrorResponse.UNKNOWN_MESSAGE) {
                        log.warn("Scoreboard edit failed: {}", e.getMessage());
                        return;
                    }
                }
            }
            try {
                Message msg = ch.sendMessageEmbeds(embeds).complete();
                drafts.updateCompetition(f, x -> x.scoreboardMessageId = msg.getIdLong());
            } catch (Exception e) {
                log.warn("Scoreboard post failed: {}", e.getMessage());
            }
        }
    }

    private record Hitter(String name, int hits, boolean left) {}
    private record TeamScore(String name, int total, List<Hitter> hitters) {}

    private List<MessageEmbed> render(Faction f, Competition c) {
        long now = Instant.now().getEpochSecond();
        List<Long> family = familyFactionIds(props);
        Set<Long> left = leftFaction.getOrDefault(f.getKey(), Set.of());
        Map<Long, Integer> hits = c.startEpoch == null ? Map.of()
                : repo.hitsByAttacker(f.getKey(), c.startEpoch, c.endEpoch, COUNTED, family);

        List<TeamScore> teams = new ArrayList<>();
        for (TeamRoster t : drafts.rosters(f)) {
            List<Hitter> hitters = new ArrayList<>();
            for (RosterMember m : t.members()) {
                int n = m.tornId() == null ? 0 : hits.getOrDefault(m.tornId(), 0);
                hitters.add(new Hitter(m.display(), n, m.tornId() != null && left.contains(m.tornId())));
            }
            hitters.sort(Comparator.comparingInt(Hitter::hits).reversed().thenComparing(Hitter::name));
            teams.add(new TeamScore(t.teamName(), hitters.stream().mapToInt(Hitter::hits).sum(), hitters));
        }
        teams.sort(Comparator.comparingInt(TeamScore::total).reversed());
        int total = teams.stream().mapToInt(TeamScore::total).sum();

        // Status heading and colour: green while live, orange before, grey while the last hits come in, gold when final.
        String status;
        int colour;
        if (c.finalPosted) {
            status = "### 🏁 Final results";
            colour = 0xF1C40F;
        } else if (c.live(now)) {
            status = "### 🟢 Live now\nEnds <t:" + c.endEpoch + ":R>";
            colour = 0x57F287;
        } else if (now < c.startEpoch) {
            status = "### ⏳ Starts <t:" + c.startEpoch + ":R>";
            colour = 0xE67E22;
        } else {
            status = "### ⏱️ Finished\nCounting the last hits…";
            colour = 0x95A5A6;
        }

        EmbedBuilder main = new EmbedBuilder()
                .setTitle("🎃 " + f.getName() + " " + eventName() + "Competition")
                .setDescription(status)
                .setColor(colour)
                .addField("Starts", DraftManager.tct(c.startEpoch) + " TCT", true)
                .addField("Ends", DraftManager.tct(c.endEpoch) + " TCT", true);
        boolean anyLeft = false;
        if (teams.isEmpty()) {
            main.addField("Teams", "*No teams yet — they appear once the draft has run.*", false);
        } else {
            main.addField("Total hits", "**" + total + "**", true);
            // Two aligned tables: teams ranked by total, then the top hitters across every team.
            // Code blocks keep the columns straight and stop names being read as formatting.
            int teamWidth = Math.min(TEAM_WIDTH, teams.stream().mapToInt(t -> t.name().length()).max().orElse(4));
            int rankWidth = String.valueOf(teams.size()).length() + 1;
            int teamDigits = String.valueOf(Math.max(total, 0)).length();
            StringBuilder standings = new StringBuilder("```\n");
            for (int i = 0; i < teams.size(); i++) {
                TeamScore t = teams.get(i);
                standings.append(pad((i + 1) + ".", rankWidth)).append(' ')
                        .append(pad(t.name(), teamWidth)).append("  ")
                        .append(String.format("%" + teamDigits + "d", t.total())).append('\n');
            }
            standings.append("```");
            main.addField("🏆 Team standings", standings.toString(), false);

            record Row(String name, int hits, String team, boolean left) {}
            List<Row> hitters = new ArrayList<>();
            for (TeamScore t : teams) {
                for (Hitter h : t.hitters()) hitters.add(new Row(cleanName(h.name()), h.hits(), t.name(), h.left()));
            }
            hitters.sort(Comparator.comparingInt(Row::hits).reversed().thenComparing(Row::name, String.CASE_INSENSITIVE_ORDER));
            List<Row> top = hitters.stream().filter(r -> r.hits() > 0).limit(TOP_OVERALL).toList();
            if (top.isEmpty()) {
                main.addField("⚔️ Top hitters", "*No hits yet.*", false);
            } else {
                int nameWidth = Math.min(NAME_WIDTH, top.stream().mapToInt(r -> r.name().length() + (r.left() ? 1 : 0)).max().orElse(4));
                int hitDigits = String.valueOf(top.get(0).hits()).length();
                int teamCol = Math.min(TEAM_WIDTH, top.stream().mapToInt(r -> r.team().length()).max().orElse(4));
                StringBuilder table = new StringBuilder("```\n");
                for (Row r : top) {
                    table.append(pad(r.name() + (r.left() ? "*" : ""), nameWidth)).append("  ")
                            .append(String.format("%" + hitDigits + "d", r.hits())).append("  ")
                            .append(pad(r.team(), teamCol).stripTrailing()).append('\n');
                }
                table.append("```");
                main.addField("⚔️ Top hitters", table.toString(), false);
                anyLeft = top.stream().anyMatch(Row::left);
            }
        }

        String updated = c.lastCheckedMs == null ? "Not checked yet"
                : "Updated " + DraftManager.tct(c.lastCheckedMs / 1000).substring(11) + " TCT";
        main.setFooter("Successful outgoing hits by drafted players · family factions excluded"
                + (anyLeft ? " · * left the faction" : "") + " · " + updated);

        List<MessageEmbed> embeds = new ArrayList<>();
        embeds.add(main.build());

        return embeds;
    }

    private static final int NAME_WIDTH = 18;
    private static final int TEAM_WIDTH = 24;
    private static final int TOP_OVERALL = 15;

    /** "Blaz [3828215]" → "Blaz"; backticks removed so they can't break the table. */
    static String cleanName(String display) {
        String name = display == null ? "" : display.replaceAll("\\s*\\[\\d{1,10}]", "").replace("`", "'").trim();
        return name.isEmpty() ? "Unknown" : name;
    }

    /** Pads (or trims with …) to exactly {@code width} characters. */
    static String pad(String s, int width) {
        if (s.length() > width) return s.substring(0, width - 1) + "…";
        return s + " ".repeat(width - s.length());
    }

    private String eventName() {
        String e = props.getEventName();
        return e == null || e.isBlank() ? "" : e.trim() + " ";
    }
}