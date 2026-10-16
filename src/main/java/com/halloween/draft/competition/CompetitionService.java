package com.halloween.draft.competition;

import com.halloween.draft.competition.CompetitionRepository.ApiKey;
import com.halloween.draft.competition.CompetitionRepository.AttackRow;
import com.halloween.draft.config.BotProperties;
import com.halloween.draft.config.BotProperties.Faction;
import com.halloween.draft.core.BoardRenderer;
import com.halloween.draft.core.DraftManager;
import com.halloween.draft.core.DraftManager.RosterMember;
import com.halloween.draft.core.DraftManager.TeamRoster;
import com.halloween.draft.core.TimerService;
import com.halloween.draft.core.UserError;
import com.halloween.draft.model.Competition;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Attack competitions. Once a minute, for each faction whose window is live (or just ended), fetch
 * outgoing attacks with the faction's keys, store them, and refresh that faction's scoreboard.
 *
 * Counting rules: successful results only, by drafted players (matched on Torn ID), excluding hits
 * on the other family factions, with the attack's end time inside the window.
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
    private static final long OVERLAP_SECONDS = 600; // re-read 10 minutes to catch long fights
    private static final int TOP_HITTERS = 10;
    private static final String[] MEDALS = {"🥇", "🥈", "🥉"};

    /** For the panel: everything about a faction's competition at a glance. */
    public record Status(Long start, Long end, boolean live, boolean over, boolean finalPosted, int keys,
                         int keysWorking, Long lastCheckedMs, String lastError, int missingTornIds) {}

    private final BotProperties props;
    private final DraftManager drafts;
    private final CompetitionRepository repo;
    private final TornAttackClient torn;
    private final KeyCrypto crypto;
    private final TimerService timers;
    private final BoardRenderer board;
    private final AtomicInteger rotation = new AtomicInteger();
    private volatile JDA jda;

    public CompetitionService(BotProperties props, DraftManager drafts, CompetitionRepository repo,
                              TornAttackClient torn, KeyCrypto crypto, TimerService timers, BoardRenderer board) {
        this.props = props;
        this.drafts = drafts;
        this.repo = repo;
        this.torn = torn;
        this.crypto = crypto;
        this.timers = timers;
        this.board = board;
    }

    /** Starts the once-a-minute loop. Called when the bot has connected. */
    public void start(JDA jda) {
        this.jda = jda;
        timers.every(15_000, POLL_MS, this::tick);
        log.info("Competition monitor running (every {}s)", POLL_MS / 1000);
    }

    void tick() {
        for (Faction f : props.getFactions()) {
            try {
                tick(f);
            } catch (Exception e) {
                log.error("Competition tick failed for {}", f.getName(), e);
            }
        }
    }

    private void tick(Faction f) {
        Competition c = drafts.competition(f);
        if (!c.scheduled() || c.finalPosted) return;
        long now = Instant.now().getEpochSecond();

        if (c.live(now) || c.over(now)) {
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

    /** Fetches with the faction's keys in turn until one works. Returns false if none did. */
    private boolean fetch(Faction f, long from, long to) {
        List<ApiKey> keys = repo.keys(f.getKey());
        if (keys.isEmpty()) {
            failed(f, "No API keys added — add one under 🔑 API Keys.", keys);
            return false;
        }
        int startAt = Math.floorMod(rotation.getAndIncrement(), keys.size());
        String lastProblem = null;
        for (int i = 0; i < keys.size(); i++) {
            ApiKey k = keys.get((startAt + i) % keys.size());
            try {
                List<AttackRow> rows = torn.outgoing(crypto.decrypt(k.encryptedKey()), from, to);
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
        failed(f, lastProblem, keys);
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
                + "). Open `/draft` → 🔑 API Keys to check or add a key — attacks are caught up once a key works.";
        notify.forEach(id -> jda.openPrivateChannelById(id).flatMap(pc -> pc.sendMessage(text)).queue(null, e -> {}));
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
        int missing = (int) drafts.rosters(f).stream().flatMap(t -> t.members().stream())
                .filter(m -> m.tornId() == null).count();
        return new Status(c.startEpoch, c.endEpoch, c.live(now), c.over(now), c.finalPosted, keys.size(), working,
                c.lastCheckedMs, c.lastError, missing);
    }

    // =====================================================================
    // Scoreboard
    // =====================================================================

    /** Posts or edits the faction's scoreboard message. Safe to call any time. */
    public void refreshScoreboard(Faction f) {
        JDA j = jda;
        if (j == null) return;
        Competition c = drafts.competition(f);
        if (!c.scheduled()) return;
        TextChannel ch = j.getTextChannelById(props.getScoreboardChannelId());
        if (ch == null) {
            log.warn("Scoreboard channel {} not found", props.getScoreboardChannelId());
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

    private record Hitter(String name, int hits) {}
    private record TeamScore(String name, int total, List<Hitter> hitters) {}

    private List<MessageEmbed> render(Faction f, Competition c) {
        long now = Instant.now().getEpochSecond();
        List<Long> family = props.getFactions().stream().map(Faction::getTornFactionId)
                .filter(id -> id > 0 && id != f.getTornFactionId()).toList();
        Map<Long, Integer> hits = c.startEpoch == null ? Map.of()
                : repo.hitsByAttacker(f.getKey(), c.startEpoch, c.endEpoch, COUNTED, family);

        List<TeamScore> teams = new ArrayList<>();
        for (TeamRoster t : drafts.rosters(f)) {
            List<Hitter> hitters = new ArrayList<>();
            for (RosterMember m : t.members()) {
                int n = m.tornId() == null ? 0 : hits.getOrDefault(m.tornId(), 0);
                hitters.add(new Hitter(m.display(), n));
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
        if (teams.isEmpty()) {
            main.addField("Teams", "*No teams yet — they appear once the draft has run.*", false);
        } else {
            main.addField("Total hits", "**" + total + "**", true);
            StringBuilder standings = new StringBuilder();
            for (int i = 0; i < teams.size(); i++) {
                standings.append(i < MEDALS.length ? MEDALS[i] : "`" + (i + 1) + ".`").append(" **")
                        .append(teams.get(i).name()).append("** · ").append(teams.get(i).total()).append('\n');
            }
            main.addField("Standings", standings.toString(), false);
        }

        String updated = c.lastCheckedMs == null ? "Not checked yet"
                : "Updated " + DraftManager.tct(c.lastCheckedMs / 1000).substring(11) + " TCT";
        main.setFooter("Successful outgoing hits by drafted players · family factions excluded\n" + updated);

        List<MessageEmbed> embeds = new ArrayList<>();
        embeds.add(main.build());

        for (int i = 0; i < teams.size() && embeds.size() < 10; i++) {
            TeamScore t = teams.get(i);
            StringBuilder body = new StringBuilder();
            int shown = 0;
            for (Hitter h : t.hitters()) {
                if (shown >= TOP_HITTERS) break;
                body.append('`').append(String.format("%2d", ++shown)).append("` ").append(h.name())
                        .append(" — **").append(h.hits()).append("**\n");
            }
            int rest = t.hitters().size() - shown;
            if (rest > 0) {
                int restHits = t.hitters().stream().skip(shown).mapToInt(Hitter::hits).sum();
                body.append("*+").append(rest).append(" more: ").append(restHits).append(" hits*");
            }
            embeds.add(new EmbedBuilder()
                    .setTitle((i < MEDALS.length ? MEDALS[i] + " " : "") + t.name() + " — " + t.total() + " hits")
                    .setDescription(body.length() == 0 ? "*No members*" : body.toString())
                    .setColor(BoardRenderer.color(i))
                    .build());
        }
        return embeds;
    }

    private String eventName() {
        String e = props.getEventName();
        return e == null || e.isBlank() ? "" : e.trim() + " ";
    }
}