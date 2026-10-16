package com.capx.draft.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DraftTest {

    private static Draft draft(int captains, int players) {
        Draft d = new Draft();
        for (int c = 1; c <= captains; c++) {
            d.captainOrder.add((long) c);
            d.captainNames.put((long) c, "Cap" + c);
        }
        for (int p = 1; p <= players; p++) d.eligible.add(new Player(100 + p, "Player" + p, null));
        return d;
    }

    @Test
    void snakeOrderReversesEachRound() {
        Draft d = draft(3, 9);
        List<Long> order = new ArrayList<>();
        for (int i = 0; i < 9; i++) order.add(d.captainForPick(i));
        assertEquals(List.of(1L, 2L, 3L, 3L, 2L, 1L, 1L, 2L, 3L), order);
        assertEquals(1, d.roundForPick(2));
        assertEquals(2, d.roundForPick(3));
    }

    @Test
    void picksAdvanceTurnAndCompleteDraft() {
        Draft d = draft(2, 3);
        assertEquals(Long.valueOf(1), d.currentCaptain());
        d.recordPick(d.eligible.get(0), PickMethod.MANUAL);
        assertEquals(Long.valueOf(2), d.currentCaptain());
        d.recordPick(d.eligible.get(0), PickMethod.MANUAL);
        assertEquals(Long.valueOf(2), d.currentCaptain()); // snake: captain 2 picks again
        d.recordPick(d.eligible.get(0), PickMethod.MANUAL);
        assertNull(d.currentCaptain());
        assertEquals(DraftStatus.COMPLETE, d.status);
        assertEquals(2, d.picksFor(2L).size());
    }

    @Test
    void undoReturnsPlayerAndTurn() {
        Draft d = draft(2, 2);
        Player p1 = d.eligible.get(0);
        d.recordPick(p1, PickMethod.MANUAL);
        d.recordPick(d.eligible.get(0), PickMethod.MANUAL);
        assertEquals(DraftStatus.COMPLETE, d.status);

        Pick undone = d.undoLast().orElseThrow();
        assertEquals(2, undone.number);
        assertEquals(DraftStatus.ACTIVE, d.status);
        assertEquals(Long.valueOf(2), d.currentCaptain());
        assertTrue(d.suppressQueueOnce);
        assertEquals(1, d.eligible.size());
    }

    @Test
    void pickingSomeoneRemovesThemFromEveryQueue() {
        Draft d = draft(2, 3);
        Player target = d.eligible.get(2);
        d.queueFor(1L).add(target.id);
        d.queueFor(2L).add(target.id);
        d.recordPick(target, PickMethod.MANUAL);
        assertTrue(d.queueFor(1L).isEmpty());
        assertTrue(d.queueFor(2L).isEmpty());
    }

    @Test
    void firstQueuedSkipsTakenPlayers() {
        Draft d = draft(2, 3);
        long taken = d.eligible.get(0).id;
        long wanted = d.eligible.get(1).id;
        d.queueFor(2L).addAll(List.of(taken, wanted));
        d.eligible.removeIf(p -> p.id == taken);
        assertEquals(wanted, d.firstQueued(2L).orElseThrow().id);
        assertEquals(List.of(wanted), d.queueFor(2L));
    }

    @Test
    void replaceCaptainMovesEverything() {
        Draft d = draft(2, 4);
        Player newCapInPool = d.eligible.get(3);
        d.recordPick(d.eligible.get(0), PickMethod.MANUAL); // captain 1
        d.queueFor(1L).add(d.eligible.get(1).id);

        d.replaceCaptain(1L, newCapInPool.id, "NewCap");
        assertEquals(List.of(newCapInPool.id, 2L), d.captainOrder);
        assertEquals(1, d.picksFor(newCapInPool.id).size());
        assertEquals(1, d.queueFor(newCapInPool.id).size());
        assertTrue(d.findEligible(newCapInPool.id).isEmpty(), "new captain leaves the pool");
        assertEquals("NewCap", d.captainName(newCapInPool.id));
    }

    @Test
    void upcomingCaptainsStopWhenPoolRunsOut() {
        Draft d = draft(3, 2);
        assertEquals(List.of(2L), d.upcomingCaptains(3));
    }

    @Test
    void addingPlayersMidDraftKeepsSnakeOrder() {
        Draft d = draft(2, 1);
        d.recordPick(d.eligible.get(0), PickMethod.MANUAL);
        assertEquals(DraftStatus.COMPLETE, d.status);
        d.eligible.add(new Player(999, "Late", null));
        d.status = DraftStatus.ACTIVE;
        assertEquals(Long.valueOf(2), d.currentCaptain());
    }
}
