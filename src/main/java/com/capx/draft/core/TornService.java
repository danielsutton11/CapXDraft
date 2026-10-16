package com.capx.draft.core;

import com.capx.draft.config.BotProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks up each player's current Torn active streak.
 *
 * Calls go through a single thread, spaced out to stay well under Torn's 100 calls/minute
 * per key, and results are handed back so the draft can cache them on the player.
 */
@Service
public class TornService {
    private static final Logger log = LoggerFactory.getLogger(TornService.class);
    private static final Pattern TORN_ID = Pattern.compile("\\[(\\d{1,10})]");
    private static final long SPACING_MS = 700;

    private final BotProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "torn-api");
        t.setDaemon(true);
        return t;
    });

    public TornService(BotProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    /** Reads the Torn ID out of a "Name [123456]" style nickname. */
    public static Long parseTornId(String displayName) {
        if (displayName == null) return null;
        Matcher m = TORN_ID.matcher(displayName);
        Long found = null;
        while (m.find()) found = Long.parseLong(m.group(1)); // last [..] wins
        return found;
    }

    public boolean enabled() {
        return props.getTornApiKey() != null && !props.getTornApiKey().isBlank();
    }

    /**
     * Fetches streaks in the background.
     * @param tornIdsByDiscordId discord id -> torn id
     * @param onResult called once per successful lookup with (discord id, streak)
     * @param onDone called after the whole batch
     */
    public void fetchStreaks(Map<Long, Long> tornIdsByDiscordId,
                             BiConsumer<Long, Integer> onResult,
                             Runnable onDone) {
        if (!enabled() || tornIdsByDiscordId.isEmpty()) return;
        worker.submit(() -> {
            for (Map.Entry<Long, Long> e : tornIdsByDiscordId.entrySet()) {
                Integer streak = fetchStreak(e.getValue());
                if (streak != null) onResult.accept(e.getKey(), streak);
                else log.info("No active streak returned for Torn ID {}", e.getValue());
                sleep(SPACING_MS);
            }
            onDone.run();
        });
    }

    /**
     * Current members of a Torn faction, as Torn ID → name. Blocking.
     * @throws UserError if the key is missing or Torn returns an error
     */
    public Map<Long, String> fetchFactionMembers(long factionId) {
        JsonNode root = call("https://api.torn.com/faction/" + factionId + "?selections=basic");
        Map<Long, String> out = new java.util.LinkedHashMap<>();
        root.path("members").fields().forEachRemaining(e -> {
            try {
                out.put(Long.parseLong(e.getKey()), e.getValue().path("name").asText("Unknown"));
            } catch (NumberFormatException ignored) {
                // not a member entry
            }
        });
        if (out.isEmpty()) throw new UserError("Torn returned no members for faction " + factionId + ".");
        return out;
    }

    /** A player's current Torn name. Blocking. */
    public String fetchName(long tornId) {
        JsonNode root = call("https://api.torn.com/user/" + tornId + "?selections=basic");
        String name = root.path("name").asText("");
        if (name.isBlank()) throw new UserError("Torn didn't return a name for " + tornId + ".");
        return name;
    }

    private JsonNode call(String baseUrl) {
        if (!enabled()) throw new UserError("No Torn API key is set (TORN_API_KEY), so I can't look up Torn data.");
        String url = baseUrl + "&comment=DraftBot&key=" + props.getTornApiKey();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpResponse<String> res = http.send(
                        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                JsonNode root = mapper.readTree(res.body());
                if (root.has("error")) {
                    int code = root.path("error").path("code").asInt();
                    if (code == 5) { // too many requests
                        sleep(10_000);
                        continue;
                    }
                    throw new UserError("Torn API error: " + root.path("error").path("error").asText());
                }
                return root;
            } catch (UserError e) {
                throw e;
            } catch (Exception ex) {
                log.warn("Torn API call failed: {}", ex.getMessage());
                sleep(2_000);
            }
        }
        throw new UserError("Couldn't reach the Torn API — try again in a minute.");
    }

    private Integer fetchStreak(long tornId) {
        String url = "https://api.torn.com/user/" + tornId
                + "?selections=personalstats&comment=DraftBot&key=" + props.getTornApiKey();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpResponse<String> res = http.send(
                        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                JsonNode root = mapper.readTree(res.body());
                if (root.has("error")) {
                    int code = root.path("error").path("code").asInt();
                    if (code == 5) { // too many requests - back off and retry
                        sleep(30_000);
                        continue;
                    }
                    log.warn("Torn API error for {}: {}", tornId, root.path("error").path("error").asText());
                    return null;
                }
                JsonNode stats = root.path("personalstats");
                if (stats.has("activestreak")) return stats.get("activestreak").asInt();
                return null;
            } catch (Exception ex) {
                log.warn("Torn API call failed for {}: {}", tornId, ex.getMessage());
                sleep(2_000);
            }
        }
        return null;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}