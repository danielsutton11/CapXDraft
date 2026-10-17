package com.halloween.draft.core;

import com.halloween.draft.model.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Works out a faction's participant list from Discord and Torn. Pure logic (no Discord or HTTP
 * calls), so it can be unit tested.
 *
 * Discord members are matched to Torn faction members by the [ID] in their nickname, or failing
 * that by their nickname (minus any [ID]) being the same as their Torn name, ignoring case. Names
 * shared by two Torn members are never used for matching. Each person appears at most once.
 */
public final class PoolBuilder {

    private PoolBuilder() {}

    /** A Discord member: id and display name (nickname). */
    public record Person(long id, String display) {}

    /**
     * @param players  the participants, sorted by name
     * @param leftOut  display names of draft-role members who match nobody in the Torn faction
     */
    public record Result(List<Player> players, List<String> leftOut) {}

    /**
     * @param roleHolders   Discord members with the faction's draft role (bots already removed)
     * @param everyone      every Discord member (bots removed), to link faction members without the role
     * @param members       Torn faction members (Torn ID → name), or null if they couldn't be loaded
     * @param captainIds    Discord ids of the captains, who are never participants
     * @param excluded      players leadership removed earlier
     */
    public static Result build(Collection<Person> roleHolders, Collection<Person> everyone,
                               Map<Long, String> members, Set<Long> captainIds, Set<Long> captainTornIds,
                               Predicate<Player> excluded) {
        Map<Long, Player> byId = new LinkedHashMap<>();
        List<String> leftOut = new ArrayList<>();

        if (members == null) {
            // Torn unavailable: keep everyone with the draft role, as matched by nickname ID only.
            for (Person p : roleHolders) {
                if (captainIds.contains(p.id())) continue;
                Long tornId = TornService.parseTornId(p.display());
                if (tornId != null && captainTornIds.contains(tornId)) continue;
                byId.putIfAbsent(p.id(), new Player(p.id(), p.display(), tornId));
            }
            return finish(byId, leftOut, excluded);
        }

        Map<String, Long> tornByName = uniqueIndex(members);
        // Captains' Torn IDs: from their nickname, or their name, so they're never added as players.
        Set<Long> capTorn = new HashSet<>(captainTornIds);
        for (Person p : everyone) {
            if (captainIds.contains(p.id())) {
                Long t = match(p, members, tornByName);
                if (t != null) capTorn.add(t);
            }
        }

        Set<Long> linkedTorn = new HashSet<>();
        for (Person p : roleHolders) {
            if (captainIds.contains(p.id())) continue;
            Long tornId = match(p, members, tornByName);
            if (tornId == null) {
                leftOut.add(p.display());
                continue;
            }
            if (capTorn.contains(tornId) || linkedTorn.contains(tornId)) continue;
            linkedTorn.add(tornId);
            byId.putIfAbsent(p.id(), new Player(p.id(), p.display(), tornId));
        }

        // Faction members not yet linked: someone on Discord without the role, or "not on Discord".
        Map<Long, Person> discordByTorn = new HashMap<>();
        Map<String, List<Person>> discordByName = new HashMap<>();
        for (Person p : everyone) {
            Long t = TornService.parseTornId(p.display());
            if (t != null) discordByTorn.putIfAbsent(t, p);
            discordByName.computeIfAbsent(baseName(p.display()), k -> new ArrayList<>()).add(p);
        }
        for (Map.Entry<Long, String> e : members.entrySet()) {
            long tornId = e.getKey();
            if (capTorn.contains(tornId) || linkedTorn.contains(tornId)) continue;
            Person p = discordByTorn.get(tornId);
            if (p == null) {
                List<Person> sameName = discordByName.getOrDefault(baseName(e.getValue()), List.of());
                // Only a single Discord member with that name, and only if the name isn't shared in Torn.
                if (sameName.size() == 1 && tornByName.containsKey(baseName(e.getValue()))) p = sameName.get(0);
            }
            if (p != null && captainIds.contains(p.id())) continue;
            if (p != null && byId.containsKey(p.id())) p = null; // that Discord member is already in as someone else
            Player player = p != null ? new Player(p.id(), p.display(), tornId) : Player.tornOnly(tornId, e.getValue());
            linkedTorn.add(tornId);
            byId.putIfAbsent(player.id, player);
        }
        return finish(byId, leftOut, excluded);
    }

    /** Torn ID for a Discord member: the [ID] in their nickname if it's a faction member, else a name match. */
    static Long match(Person p, Map<Long, String> members, Map<String, Long> tornByName) {
        Long fromNick = TornService.parseTornId(p.display());
        if (fromNick != null) return members.containsKey(fromNick) ? fromNick : null;
        return tornByName.get(baseName(p.display()));
    }

    /** "Bob [123456]" → "bob". */
    static String baseName(String display) {
        return display == null ? "" : display.replaceAll("\\[\\d{1,10}]", "").trim().toLowerCase(Locale.ROOT);
    }

    /** Lower-cased Torn name → Torn ID, leaving out names two members share. */
    private static Map<String, Long> uniqueIndex(Map<Long, String> members) {
        Map<String, Long> out = new HashMap<>();
        Set<String> shared = new HashSet<>();
        members.forEach((id, name) -> {
            String key = baseName(name);
            if (key.isEmpty()) return;
            if (out.putIfAbsent(key, id) != null) shared.add(key);
        });
        shared.forEach(out::remove);
        return out;
    }

    private static Result finish(Map<Long, Player> byId, List<String> leftOut, Predicate<Player> excluded) {
        List<Player> players = byId.values().stream()
                .filter(p -> !excluded.test(p))
                .sorted(Comparator.comparing(p -> p.display.toLowerCase(Locale.ROOT)))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        leftOut.sort(String.CASE_INSENSITIVE_ORDER);
        return new Result(players, leftOut);
    }
}
