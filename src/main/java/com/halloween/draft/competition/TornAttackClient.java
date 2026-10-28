package com.halloween.draft.competition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.halloween.draft.competition.CompetitionRepository.AttackRow;
import com.halloween.draft.core.UserError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Torn API v2 calls made with a faction member's own key (not the bot's key):
 * checking a key and fetching the faction's outgoing attacks.
 * See https://www.torn.com/swagger.php - GET /v2/faction/attacksfull.
 */
@Component
public class TornAttackClient {
    private static final Logger log = LoggerFactory.getLogger(TornAttackClient.class);
    private static final String V2 = "https://api.torn.com/v2";
    private static final int FULL_PAGE = 1000;   // attacksfull: up to 1000 simplified rows per call
    private static final int DETAIL_PAGE = 100;  // attacks: up to 100 detailed rows per call
    /** attacksfull counts as behind when /attacks has an attack this much newer. */
    private static final long STALE_SECONDS = 120;

    /** The key itself is the problem (wrong, no faction access, paused...) - try another key. */
    public static class KeyProblem extends RuntimeException {
        public KeyProblem(String message) {
            super(message);
        }
    }

    public record KeyOwner(long tornId, String name, long factionId) {}

    private final ObjectMapper mapper;
    /** Torn faction ID → start time of the newest outgoing attack Torn reported, from the last fetch. */
    private final java.util.Map<Long, Long> tornNewest = new java.util.concurrent.ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public TornAttackClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Confirms a key works, belongs to someone in {@code factionId}, and has faction API access.
     * @throws UserError with a message suitable for showing to the leader
     */
    public KeyOwner verify(String key, long factionId, String factionName) {
        if (key == null || !key.matches("[A-Za-z0-9]{16}")) {
            throw new UserError("That doesn't look like a Torn API key — they're 16 letters and numbers.");
        }
        JsonNode user;
        try {
            user = get("https://api.torn.com/user/?selections=basic,profile", key);
        } catch (KeyProblem e) {
            throw new UserError("Torn rejected that key: " + e.getMessage());
        }
        long ownerId = user.path("player_id").asLong();
        String name = user.path("name").asText("Unknown");
        long keyFaction = user.path("faction").path("faction_id").asLong(0);
        if (keyFaction != factionId) {
            throw new UserError("That key belongs to " + name + ", who isn't in " + factionName + ".");
        }
        try {
            get(V2 + "/faction/attacksfull?filters=outgoing&limit=1", key);
        } catch (KeyProblem e) {
            throw new UserError(name + "'s key can't read faction attacks (" + e.getMessage()
                    + "). It needs to be a Limited key, and " + name + " needs Faction API Access in the faction.");
        }
        return new KeyOwner(ownerId, name, keyFaction);
    }

    /**
     * Outgoing attacks for {@code factionId} that started between {@code from} and {@code to}
     * (epoch seconds), oldest first, following pages until everything is fetched.
     * @throws KeyProblem if the key returns another faction's attacks (its owner has moved faction)
     */
    public List<AttackRow> outgoing(String key, long factionId, long from, long to) {
        List<AttackRow> full = page("attacksfull", FULL_PAGE, key, factionId, from, to);
        // attacksfull can stop updating while /attacks carries on (seen 28 Oct: nothing after 18:46
        // for hours, every call "successful"). One cheap call to /attacks tells us; if attacksfull is
        // behind, read this window from /attacks instead - slower (100 a page) but current.
        long fullNewest = full.stream().mapToLong(AttackRow::started).max().orElse(from);
        JsonNode latest = get(V2 + "/faction/attacks?filters=outgoing&sort=DESC&limit=1&to=" + to, key);
        long liveNewest = 0;
        for (JsonNode a : latest.path("attacks")) liveNewest = Math.max(liveNewest, a.path("started").asLong());
        if (liveNewest > 0) tornNewest.merge(factionId, liveNewest, Math::max);
        if (liveNewest > fullNewest + STALE_SECONDS && liveNewest >= from) {
            log.warn("attacksfull is behind (newest {} vs {} on /attacks) - reading {}-{} from /attacks",
                    fullNewest, liveNewest, from, to);
            return page("attacks", DETAIL_PAGE, key, factionId, from, to);
        }
        return full;
    }

    /** Every outgoing attack started in [from, to] from one endpoint, oldest first, page by page. */
    private List<AttackRow> page(String endpoint, int pageSize, String key, long factionId, long from, long to) {
        List<AttackRow> out = new ArrayList<>();
        long cursor = from;
        for (int page = 0; page < 500; page++) {
            JsonNode root = get(V2 + "/faction/" + endpoint + "?filters=outgoing&sort=ASC&limit=" + pageSize
                    + "&from=" + cursor + "&to=" + to, key);
            List<AttackRow> rows = parseAttacks(root, factionId);
            out.addAll(rows);
            long newest = rows.stream().mapToLong(AttackRow::started).max().orElse(cursor);
            if (rows.size() < pageSize || newest <= cursor) break; // last page (or no progress - stop rather than loop)
            cursor = newest;                                       // overlap by one second; duplicates are ignored on save
        }
        return out;
    }

    /**
     * Start time of the newest outgoing attack Torn has reported for this faction (seen on the
     * last fetch), or null if none yet. Lets the watchdog spot the bot falling behind Torn.
     */
    public Long tornNewest(long factionId) {
        return tornNewest.get(factionId);
    }

    /**
     * Reads one page of attacksfull. Every outgoing attack names the attacker's faction. Torn shows
     * an attacker's current faction, so one member joining or leaving can put an odd row on a page;
     * that's kept. Only when every row is another faction's has the key's owner moved, and then the
     * key is no use to us. (Failing on a single row used to stall fetching for every key.)
     */
    static List<AttackRow> parseAttacks(JsonNode root, long factionId) {
        List<AttackRow> out = new ArrayList<>();
        int ours = 0;
        Long foreign = null;
        for (JsonNode a : root.path("attacks")) {
            Long attackerFaction = factionOf(a.path("attacker"));
            if (attackerFaction != null && attackerFaction != 0 && attackerFaction != factionId) foreign = attackerFaction;
            else ours++;
            long started = a.path("started").asLong();
            out.add(new AttackRow(
                    a.path("id").asLong(),
                    nullableLong(a.path("attacker").path("id")),
                    nullableLong(a.path("defender").path("id")),
                    factionOf(a.path("defender")),
                    a.path("result").asText("None"),
                    started,
                    a.path("ended").asLong(started)));
        }
        if (!out.isEmpty() && ours == 0) {
            throw new KeyProblem("it now returns another faction's attacks (faction " + foreign
                    + ") — its owner has probably left. Remove it and add a current member's key");
        }
        return out;
    }

    /**
     * The key's owner and their current faction, for the hourly check that keys still belong to
     * someone in the faction.
     * @throws KeyProblem if Torn rejects the key
     */
    public KeyOwner owner(String key) {
        JsonNode user = get("https://api.torn.com/user/?selections=basic,profile", key);
        return new KeyOwner(user.path("player_id").asLong(), user.path("name").asText("Unknown"),
                user.path("faction").path("faction_id").asLong(0));
    }

    private JsonNode get(String url, String key) {
        String full = url + (url.contains("?") ? "&" : "?") + "comment=HalloweenDraft&key="
                + URLEncoder.encode(key, StandardCharsets.UTF_8);
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(full))
                        .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
                String body = res.body() == null ? "" : res.body().stripLeading();
                if (body.startsWith("<")) { // Torn's down or in maintenance: a web page, not JSON
                    log.warn("Torn API returned a web page instead of data (HTTP {}) — Torn is probably down or in maintenance",
                            res.statusCode());
                    sleep(3_000);
                    continue;
                }
                JsonNode root = mapper.readTree(body);
                if (root.has("error")) {
                    int code = root.path("error").path("code").asInt();
                    String msg = root.path("error").path("error").asText("error " + code);
                    if (code == 5 || code == 8 || code == 9 || code == 17) { // rate limit / temporary
                        sleep(10_000);
                        continue;
                    }
                    throw new KeyProblem(msg);
                }
                return root;
            } catch (KeyProblem e) {
                throw e;
            } catch (Exception e) {
                log.warn("Torn API call failed: {}", e.getMessage());
                sleep(3_000);
            }
        }
        throw new IllegalStateException("Torn API unreachable");
    }

    /** attacksfull gives "faction_id"; /attacks gives "faction": {"id": ...} or null. */
    private static Long factionOf(JsonNode person) {
        Long flat = nullableLong(person.path("faction_id"));
        return flat != null ? flat : nullableLong(person.path("faction").path("id"));
    }

    private static Long nullableLong(JsonNode n) {
        return n.isMissingNode() || n.isNull() || n.asText().isBlank() ? null : n.asLong();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}