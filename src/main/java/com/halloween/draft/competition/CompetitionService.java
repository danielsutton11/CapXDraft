package com.halloween.draft.competition;

import com.halloween.draft.competition.CompetitionRepository.ApiKey;
import com.halloween.draft.competition.CompetitionRepository.AttackRow;
import com.halloween.draft.config.BotProperties;
import com.halloween.draft.config.BotProperties.Faction;
import com.halloween.draft.core.DraftManager;
import com.halloween.draft.core.DraftManager.RosterMember;
import com.halloween.draft.core.DraftManager.TeamRoster;
import com.halloween.draft.core.TeamImageService;
import com.halloween.draft.core.TornService;
import com.halloween.draft.core.UserError;
import com.halloween.draft.model.Competition;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
    private final TeamImageService images;
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
                              TornAttackClient torn, TornService tornService, KeyCrypto crypto,
                              TeamImageService images) {
        this.props = props;
        this.drafts = drafts;
        this.repo = repo;
        this.torn = torn;
        this.tornService = tornService;
        this.crypto = crypto;
        this.images = images;
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
            // The leading team's image (the winner's, large, on the final results) is attached to the
            // message, and only re-uploaded when it changes: an edit without files keeps it.
            List<TeamScore> teams = teamScores(f);
            TeamScore featured = featured(c, teams);
            String wanted = featured == null ? "" : TeamImageService.fileName(featured.imageId(), featured.imageExt());
            // The stored signature is the file name plus IMAGE_STYLE, so changing how the image is drawn
            // (the banner) re-uploads it once even when the leading team hasn't changed.
            boolean upload = !sig(wanted).equals(c.scoreboardImageSig == null ? "" : c.scoreboardImageSig);
            if (c.scoreboardMessageId != null) {
                try {
                    FileUpload file = upload && featured != null ? banner(images.upload(featured.imageId(), featured.imageExt())) : null;
                    String shown = upload ? (file == null ? null : file.getName()) : (wanted.isEmpty() ? null : wanted);
                    var edit = ch.editMessageEmbedsById(c.scoreboardMessageId, render(f, c, teams, shown))
                            .setComponents(teamsButtonRow(f));
                    if (upload) edit = edit.setFiles(file == null ? List.<FileUpload>of() : List.of(file));
                    edit.complete();
                    if (upload) drafts.updateCompetition(f, x -> x.scoreboardImageSig = sig(shown == null ? "" : shown));
                    return;
                } catch (ErrorResponseException e) {
                    if (e.getErrorResponse() != ErrorResponse.UNKNOWN_MESSAGE) {
                        log.warn("Scoreboard edit failed: {}", e.getMessage());
                        return;
                    }
                }
            }
            try {
                FileUpload file = featured == null ? null : banner(images.upload(featured.imageId(), featured.imageExt()));
                String shown = file == null ? null : file.getName();
                Message msg = ch.sendMessageEmbeds(render(f, c, teams, shown)).setComponents(teamsButtonRow(f))
                        .setFiles(file == null ? List.<FileUpload>of() : List.of(file)).complete();
                drafts.updateCompetition(f, x -> {
                    x.scoreboardMessageId = msg.getIdLong();
                    x.scoreboardImageSig = sig(shown == null ? "" : shown);
                });
            } catch (Exception e) {
                log.warn("Scoreboard post failed: {}", e.getMessage());
            }
        }
    }

    /** One player's score; {@code name} is their display name without the [ID]. */
    public record Hitter(String name, int hits, boolean left) {}
    /** A team's total and every member's hits, highest first. */
    public record TeamScore(String name, int total, List<Hitter> hitters, Long imageId, String imageExt) {}

    /** Every team's score for the faction's competition, best team first. Empty before the draft. */
    public List<TeamScore> teamScores(Faction f) {
        Competition c = drafts.competition(f);
        List<Long> family = familyFactionIds(props);
        Set<Long> left = leftFaction.getOrDefault(f.getKey(), Set.of());
        Map<Long, Integer> hits = c.startEpoch == null ? Map.of()
                : repo.hitsByAttacker(f.getKey(), c.startEpoch, c.endEpoch, COUNTED, family);

        List<TeamScore> teams = new ArrayList<>();
        for (TeamRoster t : drafts.rosters(f)) {
            List<Hitter> hitters = new ArrayList<>();
            for (RosterMember m : t.members()) {
                int n = m.tornId() == null ? 0 : hits.getOrDefault(m.tornId(), 0);
                hitters.add(new Hitter(cleanName(m.display()), n, m.tornId() != null && left.contains(m.tornId())));
            }
            hitters.sort(Comparator.comparingInt(Hitter::hits).reversed()
                    .thenComparing(Hitter::name, String.CASE_INSENSITIVE_ORDER));
            teams.add(new TeamScore(t.teamName(), hitters.stream().mapToInt(Hitter::hits).sum(), hitters,
                    t.imageId(), t.imageExt()));
        }
        teams.sort(Comparator.comparingInt(TeamScore::total).reversed());
        return teams;
    }

    /** Button under each scoreboard that opens a private per-team breakdown. */
    public static final String TEAMS_BUTTON_PREFIX = "dp:sbteams:";

    private static ActionRow teamsButtonRow(Faction f) {
        return ActionRow.of(Button.secondary(TEAMS_BUTTON_PREFIX + f.getKey(), "📊 Team breakdown"));
    }

    /**
     * The team whose image the scoreboard shows: the outright leader once there are hits (no image
     * on a tie), and the winner on the final results. Null if that team has no image.
     */
    static TeamScore featured(Competition c, List<TeamScore> teams) {
        if (teams.isEmpty() || c.startEpoch == null) return null;
        TeamScore top = teams.get(0);
        if (top.total() <= 0 || (teams.size() > 1 && teams.get(1).total() == top.total())) return null;
        return top.imageId() == null ? null : top;
    }

    /** {@code imageFile}: the attached team image to show, or null — large on the final results, small otherwise. */
    private List<MessageEmbed> render(Faction f, Competition c, List<TeamScore> teams, String imageFile) {
        long now = Instant.now().getEpochSecond();
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
            main.addField("Total hits", "**" + total + "**\n" + SPACER, true);
            // Code blocks so names line up on the left and numbers on the right. With no thumbnail the
            // fields get the embed's full width; rows are kept to ~31 characters, which a phone with a
            // larger text size still fits. A code block always fills the field's width, so numbers can't
            // sit against its right edge; instead both tables are padded to the same row width, so
            // their number columns end in the same place on every screen.
            Map<String, String> codes = teamCodes(teams);

            record Row(String name, int hits, String team, boolean left) {}
            List<Row> hitters = new ArrayList<>();
            for (TeamScore t : teams) {
                for (Hitter h : t.hitters()) hitters.add(new Row(h.name(), h.hits(), t.name(), h.left()));
            }
            hitters.sort(Comparator.comparingInt(Row::hits).reversed().thenComparing(Row::name, String.CASE_INSENSITIVE_ORDER));
            List<Row> top = hitters.stream().filter(r -> r.hits() > 0).limit(TOP_OVERALL).toList();

            int rankWidth = String.valueOf(teams.size()).length() + 1;
            int teamWidth = Math.min(TEAM_WIDTH, teams.stream().mapToInt(t -> t.name().length()).max().orElse(4));
            int totalDigits = teams.stream().mapToInt(t -> String.valueOf(t.total()).length()).max().orElse(1);
            // At least as wide as the column headers: Player, Team, Hits.
            int nameWidth = Math.max(6, Math.min(NAME_WIDTH, top.stream().mapToInt(r -> r.name().length() + (r.left() ? 1 : 0)).max().orElse(4)));
            int codeWidth = Math.max(4, top.stream().mapToInt(r -> codes.get(r.team()).length()).max().orElse(2));
            int hitDigits = Math.max(4, top.isEmpty() ? 1 : String.valueOf(top.get(0).hits()).length());

            teamWidth = Math.max(4, teamWidth);
            totalDigits = Math.max(5, totalDigits); // fits the "Total" heading
            int standingsRow = rankWidth + 1 + teamWidth + 2 + totalDigits;
            int hittersRow = nameWidth + 2 + codeWidth + 2 + hitDigits;
            int rowWidth = Math.max(standingsRow, top.isEmpty() ? 0 : hittersRow);
            String standingsGap = " ".repeat(2 + rowWidth - standingsRow);
            String hittersGap = " ".repeat(2 + rowWidth - hittersRow);

            // A zero-width first line gives a gap between the field heading and its table.
            StringBuilder standings = new StringBuilder(SPACER + "\n```\n");
            standings.append(" ".repeat(rankWidth + 1)).append(pad("Team", teamWidth)).append(standingsGap)
                    .append(String.format("%" + totalDigits + "s", "Total")).append('\n')
                    .append("─".repeat(rowWidth)).append('\n');
            for (int i = 0; i < teams.size(); i++) {
                TeamScore t = teams.get(i);
                standings.append(pad((i + 1) + ".", rankWidth)).append(' ')
                        .append(pad(t.name(), teamWidth)).append(standingsGap)
                        .append(String.format("%" + totalDigits + "d", t.total())).append('\n');
            }
            main.addField("🏆 Team standings", standings.append("```\n").append(SPACER).toString(), false);

            if (top.isEmpty()) {
                main.addField("⚔️ Top hitters", SPACER + "\n*No hits yet.*", false);
            } else {
                // "Chuck_Norris  SB      1341": name, team code, hits right-aligned.
                StringBuilder table = new StringBuilder(SPACER + "\n```\n");
                table.append(pad("Player", nameWidth)).append("  ").append(pad("Team", codeWidth)).append(hittersGap)
                        .append(String.format("%" + hitDigits + "s", "Hits")).append('\n')
                        .append("─".repeat(rowWidth)).append('\n');
                for (Row r : top) {
                    table.append(pad(r.name() + (r.left() ? "*" : ""), nameWidth)).append("  ")
                            .append(pad(codes.get(r.team()), codeWidth)).append(hittersGap)
                            .append(String.format("%" + hitDigits + "d", r.hits())).append('\n');
                }
                main.addField("⚔️ Top hitters", table.append("```").toString(), false);
                anyLeft = top.stream().anyMatch(Row::left);
            }

            // Says whose image is underneath: Discord always puts the embed image after the fields.
            if (imageFile != null) {
                main.addField(SPACER, (c.finalPosted ? "🏆 **Winners: " : "👑 **Leading: ")
                        + MarkdownSanitizer.escape(teams.get(0).name()) + "**", false);
            }
        }

        // The leader's (or winner's) image, large at the bottom. It's sent as a wide banner (see
        // banner()), because on desktop Discord narrows an embed to its image's width. Never a
        // thumbnail: on mobile that reserves a column down the right and squeezes every field.
        if (imageFile != null) main.setImage("attachment://" + imageFile);

        String updated = c.lastCheckedMs == null ? "Not checked yet"
                : "Updated " + DraftManager.tct(c.lastCheckedMs / 1000).substring(11) + " TCT";
        main.setFooter("Successful outgoing hits by drafted players · family factions excluded"
                + (anyLeft ? " · * left the faction" : "") + " · " + updated);

        List<MessageEmbed> embeds = new ArrayList<>();
        embeds.add(main.build());

        return embeds;
    }

    /** Team name column in the standings: "1. " + 22 + 2 spaces + 4 digits = 31 characters. */
    private static final int TEAM_WIDTH = 22;
    /** Top hitters name column: 15 + 2 + code (≤4) + 2 + 4 digits ≤ 27 characters. */
    private static final int NAME_WIDTH = 15;

    /** A zero-width line: Discord trims blank lines, so this is what adds a gap after a field. */
    private static final String SPACER = "\u200B";
    private static final Set<String> CODE_SKIP_WORDS = Set.of("the", "of", "and", "a", "an");

    /**
     * Short codes per team name: word initials ("Basket Bandits" → BB, "The John Frakes Gang" → JFG),
     * capitals of a single CamelCase word ("GameJunkies" → GJ), else its first three letters
     * ("Jawbreakers" → JAW). Clashes get the team's rank added (BB, BB2).
     */
    static Map<String, String> teamCodes(List<TeamScore> teams) {
        Map<String, String> codes = new java.util.LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < teams.size(); i++) {
            String name = teams.get(i).name();
            if (codes.containsKey(name)) continue;
            String code = baseCode(name);
            if (!used.add(code)) {
                code = code + (i + 1);
                used.add(code);
            }
            codes.put(name, code);
        }
        return codes;
    }

    private static String baseCode(String name) {
        List<String> words = new ArrayList<>();
        for (String w : name.trim().split("[\\s_-]+")) {
            String clean = w.replaceAll("[^\\p{L}\\p{N}]", "");
            if (!clean.isEmpty()) words.add(clean);
        }
        // Skip filler words and ones starting with a digit ("House of 10k Corpses" → HC).
        List<String> kept = words.stream().filter(w -> !CODE_SKIP_WORDS.contains(w.toLowerCase())
                && !Character.isDigit(w.charAt(0))).toList();
        if (kept.isEmpty()) kept = words;
        if (kept.isEmpty()) return "?";
        if (kept.size() == 1) {
            String w = kept.get(0);
            String caps = w.replaceAll("[^\\p{Lu}]", "");
            if (caps.length() >= 2 && caps.length() <= 4) return caps;
            return w.substring(0, Math.min(3, w.length())).toUpperCase();
        }
        StringBuilder sb = new StringBuilder();
        for (String w : kept) {
            if (sb.length() == 4) break;
            sb.append(Character.toUpperCase(w.charAt(0)));
        }
        return sb.toString();
    }
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

    private static final int BANNER_W = 1200, BANNER_H = 400;
    /** Bump when banner() changes, so every scoreboard re-uploads its image once. */
    private static final String IMAGE_STYLE = "banner1";

    private static String sig(String fileName) {
        return fileName.isEmpty() ? "" : fileName + "|" + IMAGE_STYLE;
    }

    /**
     * Turns the team image into a 3:1 banner: the image centred at full height, the sides filled with
     * a blurred, darkened copy of itself. Discord sizes an embed to its image on desktop, so a wide
     * image keeps the scoreboard full width. Keeps the file name, so the "has the image changed?"
     * check still matches. Anything it can't read (an animated GIF, WebP) is sent unchanged.
     */
    static FileUpload banner(FileUpload original) {
        if (original == null) return null;
        String name = original.getName();
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
        if (!ext.equals("png") && !ext.equals("jpg") && !ext.equals("jpeg")) return original;
        byte[] bytes;
        try (InputStream in = original.getData()) {
            bytes = in.readAllBytes();
        } catch (Exception e) {
            log.warn("Couldn't read {}: {}", name, e.getMessage());
            return null;
        }
        try {
            BufferedImage src = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (src == null) return FileUpload.fromData(bytes, name);
            if ((double) src.getWidth() / src.getHeight() >= 2.5) return FileUpload.fromData(bytes, name); // already wide

            BufferedImage out = new BufferedImage(BANNER_W, BANNER_H, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            // Background: the image scaled to cover the banner, shrunk to a few pixels and stretched back
            // up (a cheap, smooth blur), then darkened so the centre stands out.
            double cover = Math.max((double) BANNER_W / src.getWidth(), (double) BANNER_H / src.getHeight());
            int cw = (int) Math.ceil(src.getWidth() * cover), ch = (int) Math.ceil(src.getHeight() * cover);
            BufferedImage tiny = new BufferedImage(BANNER_W / 40, BANNER_H / 40, BufferedImage.TYPE_INT_RGB);
            Graphics2D tg = tiny.createGraphics();
            tg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            tg.drawImage(src, (tiny.getWidth() - cw / 40) / 2, (tiny.getHeight() - ch / 40) / 2, cw / 40, ch / 40, null);
            tg.dispose();
            // Scale up in two steps: one big bilinear jump leaves visible blocks.
            BufferedImage mid = new BufferedImage(BANNER_W / 8, BANNER_H / 8, BufferedImage.TYPE_INT_RGB);
            Graphics2D mg = mid.createGraphics();
            mg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            mg.drawImage(tiny, 0, 0, mid.getWidth(), mid.getHeight(), null);
            mg.dispose();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(mid, 0, 0, BANNER_W, BANNER_H, null);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, BANNER_W, BANNER_H);
            g.setComposite(AlphaComposite.SrcOver);

            // Foreground: the whole image, full height, centred.
            double fit = Math.min((double) BANNER_W / src.getWidth(), (double) BANNER_H / src.getHeight());
            int fw = (int) Math.round(src.getWidth() * fit), fh = (int) Math.round(src.getHeight() * fit);
            g.drawImage(src, (BANNER_W - fw) / 2, (BANNER_H - fh) / 2, fw, fh, null);
            g.dispose();

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            ImageIO.write(out, ext.equals("png") ? "png" : "jpg", buf);
            return FileUpload.fromData(buf.toByteArray(), name);
        } catch (Exception e) {
            log.warn("Couldn't make a banner from {}: {}", name, e.getMessage());
            return FileUpload.fromData(bytes, name); // the original stream is used up; resend its bytes
        }
    }

    private String eventName() {
        String e = props.getEventName();
        return e == null || e.isBlank() ? "" : e.trim() + " ";
    }
}