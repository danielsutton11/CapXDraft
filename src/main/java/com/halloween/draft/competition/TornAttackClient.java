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
    private static final int PAGE = 1000; // attacksfull returns up to 1000 rows per call

    /** The key itself is the problem (wrong, no faction access, paused...) - try another key. */
    public static class KeyProblem extends RuntimeException {
        public KeyProblem(String message) {
            super(message);
        }
    }

    public record KeyOwner(long tornId, String name, long factionId) {}

    private final ObjectMapper mapper;
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
     * Outgoing attacks for the key owner's faction that started between {@code from} and {@code to}
     * (epoch seconds), oldest first, following pages until everything is fetched.
     */
    public List<AttackRow> outgoing(String key, long from, long to) {
        List<AttackRow> out = new ArrayList<>();
        long cursor = from;
        for (int page = 0; page < 50; page++) {
            JsonNode root = get(V2 + "/faction/attacksfull?filters=outgoing&sort=ASC&limit=" + PAGE
                    + "&from=" + cursor + "&to=" + to, key);
            JsonNode attacks = root.path("attacks");
            int count = 0;
            long newest = cursor;
            for (JsonNode a : attacks) {
                count++;
                long started = a.path("started").asLong();
                newest = Math.max(newest, started);
                out.add(new AttackRow(
                        a.path("id").asLong(),
                        nullableLong(a.path("attacker").path("id")),
                        nullableLong(a.path("defender").path("id")),
                        nullableLong(a.path("defender").path("faction_id")),
                        a.path("result").asText("None"),
                        started,
                        a.path("ended").asLong(started)));
            }
            if (count < PAGE || newest <= cursor) break; // last page (or no progress - stop rather than loop)
            cursor = newest;                              // overlap by one second; duplicates are ignored on save
        }
        return out;
    }

    private JsonNode get(String url, String key) {
        String full = url + (url.contains("?") ? "&" : "?") + "comment=HalloweenDraft&key="
                + URLEncoder.encode(key, StandardCharsets.UTF_8);
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(full))
                        .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
                JsonNode root = mapper.readTree(res.body());
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