package com.halloween.draft.competition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.halloween.draft.config.BotProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CountingRulesTest {

    private static BotProperties props(long... tornFactionIds) {
        BotProperties p = new BotProperties();
        List<BotProperties.Faction> list = new ArrayList<>();
        int i = 0;
        for (long id : tornFactionIds) {
            BotProperties.Faction f = new BotProperties.Faction();
            f.setKey("f" + i);
            f.setName("F" + i++);
            f.setTornFactionId(id);
            list.add(f);
        }
        p.setFactions(list);
        return p;
    }

    @Test
    void familyIncludesTheScoringFactionItself() {
        List<Long> family = CompetitionService.familyFactionIds(props(9357, 9528, 29107, 18560, 22932));
        assertEquals(List.of(9357L, 9528L, 29107L, 18560L, 22932L), family);
    }

    @Test
    void familySkipsUnsetFactionIds() {
        assertEquals(List.of(9357L), CompetitionService.familyFactionIds(props(9357, 0)));
    }

    @Test
    void onlySuccessfulResultsCount() {
        assertTrue(CompetitionService.COUNTED.containsAll(List.of("Attacked", "Mugged", "Hospitalized")));
        assertFalse(CompetitionService.COUNTED.contains("Lost"));
        assertFalse(CompetitionService.COUNTED.contains("Stalemate"));
        assertFalse(CompetitionService.COUNTED.contains("Assist"));
    }

    private static final String PAGE = """
            {"attacks":[
              {"id":1,"started":100,"ended":130,"result":"Hospitalized",
               "attacker":{"id":11,"faction_id":9357},"defender":{"id":21,"faction_id":5}},
              {"id":2,"started":200,"ended":210,"result":"Lost",
               "attacker":{"id":12,"faction_id":9357},"defender":{"id":22,"faction_id":null}}
            ]}""";

    @Test
    void attacksFromTheRightFactionAreRead() throws Exception {
        var rows = TornAttackClient.parseAttacks(new ObjectMapper().readTree(PAGE), 9357);
        assertEquals(2, rows.size());
        assertEquals(Long.valueOf(11), rows.get(0).attackerId());
        assertEquals(Long.valueOf(5), rows.get(0).defenderFaction());
        assertNull(rows.get(1).defenderFaction());
        assertEquals(130, rows.get(0).ended());
    }

    @Test
    void attacksFromAnotherFactionMeanTheKeyOwnerMoved() throws Exception {
        var root = new ObjectMapper().readTree(PAGE);
        assertThrows(TornAttackClient.KeyProblem.class, () -> TornAttackClient.parseAttacks(root, 9528));
    }
}
