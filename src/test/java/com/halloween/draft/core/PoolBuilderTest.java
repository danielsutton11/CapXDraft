package com.halloween.draft.core;

import com.halloween.draft.core.PoolBuilder.Person;
import com.halloween.draft.model.Player;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PoolBuilderTest {

    private static Map<Long, String> faction(Object... idName) {
        Map<Long, String> m = new LinkedHashMap<>();
        for (int i = 0; i < idName.length; i += 2) m.put(((Number) idName[i]).longValue(), (String) idName[i + 1]);
        return m;
    }

    private static PoolBuilder.Result build(List<Person> role, List<Person> everyone, Map<Long, String> members,
                                            Set<Long> captains) {
        return PoolBuilder.build(role, everyone, members, captains, Set.of(), p -> false);
    }

    private static Player find(PoolBuilder.Result r, long tornId) {
        return r.players().stream().filter(p -> p.tornId != null && p.tornId == tornId).findFirst().orElse(null);
    }

    @Test
    void matchesByNicknameId() {
        List<Person> people = List.of(new Person(10, "Bob [111]"));
        var r = build(people, people, faction(111, "Bob"), Set.of());
        assertEquals(1, r.players().size());
        assertEquals(10, r.players().get(0).id);
        assertEquals(Long.valueOf(111), r.players().get(0).tornId);
    }

    @Test
    void matchesByNameWhenThereIsNoId() {
        List<Person> people = List.of(new Person(10, "bob"));
        var r = build(people, people, faction(111, "Bob"), Set.of());
        assertEquals(1, r.players().size(), "one person, not a Discord copy plus a 'not on Discord' copy");
        assertEquals(10, r.players().get(0).id);
        assertEquals(Long.valueOf(111), r.players().get(0).tornId);
        assertTrue(r.leftOut().isEmpty());
    }

    @Test
    void draftRoleMembersOutsideTheFactionAreLeftOut() {
        List<Person> people = List.of(new Person(10, "Bob [111]"), new Person(11, "Stranger"), new Person(12, "Ex [999]"));
        var r = build(people, people, faction(111, "Bob"), Set.of());
        assertEquals(List.of("Ex [999]", "Stranger"), r.leftOut());
        assertEquals(1, r.players().size());
    }

    @Test
    void factionMembersNotOnDiscordAreAddedOnce() {
        List<Person> people = List.of(new Person(10, "Bob [111]"));
        var r = build(people, people, faction(111, "Bob", 222, "Sam"), Set.of());
        Player sam = find(r, 222);
        assertNotNull(sam);
        assertFalse(sam.onDiscord());
        assertEquals(2, r.players().size());
    }

    @Test
    void factionMemberWithoutTheRoleIsLinkedToTheirDiscordAccount() {
        List<Person> role = List.of();
        List<Person> everyone = List.of(new Person(20, "Sam"));
        var r = build(role, everyone, faction(222, "Sam"), Set.of());
        assertEquals(20, find(r, 222).id);
    }

    @Test
    void sharedTornNamesAreNeverUsedForMatching() {
        List<Person> people = List.of(new Person(10, "Twin"));
        var r = build(people, people, faction(111, "Twin", 222, "twin"), Set.of());
        assertEquals(List.of("Twin"), r.leftOut());
        assertTrue(r.players().stream().noneMatch(Player::onDiscord));
    }

    @Test
    void captainsAreNeverParticipantsEvenWhenMatchedByName() {
        List<Person> people = List.of(new Person(1, "Captain"), new Person(10, "Bob [111]"));
        var r = build(people, people, faction(111, "Bob", 333, "Captain"), Set.of(1L));
        assertNull(find(r, 333), "the captain's Torn entry mustn't come back as a 'not on Discord' player");
        assertEquals(1, r.players().size());
    }

    @Test
    void excludedPlayersStayOut() {
        List<Person> people = List.of(new Person(10, "Bob [111]"));
        var r = PoolBuilder.build(people, people, faction(111, "Bob", 222, "Sam"), Set.of(), Set.of(),
                p -> p.tornId != null && p.tornId == 222);
        assertNull(find(r, 222));
    }

    @Test
    void withoutTornEveryoneWithTheRoleIsKept() {
        List<Person> people = List.of(new Person(10, "Bob [111]"), new Person(11, "Stranger"));
        var r = build(people, people, null, Set.of());
        assertEquals(2, r.players().size());
        assertTrue(r.leftOut().isEmpty());
    }

    @Test
    void baseNameDropsIdAndCase() {
        assertEquals("bob", PoolBuilder.baseName("  Bob [2087183] "));
    }
}
