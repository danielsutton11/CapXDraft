package com.halloween.draft.core;

import com.halloween.draft.model.Competition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompetitionRulesTest {

    private static final long NOW = 1_800_000_000L;

    private static Competition window(long start, long end) {
        Competition c = new Competition();
        c.startEpoch = start;
        c.endEpoch = end;
        return c;
    }

    @Test
    void futureWindowIsAccepted() {
        assertDoesNotThrow(() -> CompetitionRules.checkNewTimes(new Competition(), NOW + 60, NOW + 3600, NOW));
    }

    @Test
    void startInThePastIsRefused() {
        assertThrows(UserError.class, () -> CompetitionRules.checkNewTimes(new Competition(), NOW - 60, NOW + 3600, NOW));
        assertThrows(UserError.class, () -> CompetitionRules.checkNewTimes(new Competition(), NOW, NOW + 3600, NOW));
    }

    @Test
    void endMustBeAfterStart() {
        assertThrows(UserError.class, () -> CompetitionRules.checkNewTimes(new Competition(), NOW + 100, NOW + 100, NOW));
    }

    @Test
    void onceLiveOnlyTheEndCanChange() {
        Competition live = window(NOW - 600, NOW + 600);
        assertDoesNotThrow(() -> CompetitionRules.checkNewTimes(live, NOW - 600, NOW + 7200, NOW));
        assertThrows(UserError.class, () -> CompetitionRules.checkNewTimes(live, NOW + 60, NOW + 7200, NOW));
    }

    @Test
    void finishedCompetitionNeedsAFutureStartToRunAgain() {
        Competition over = window(NOW - 7200, NOW - 3600);
        assertThrows(UserError.class, () -> CompetitionRules.checkNewTimes(over, NOW - 7200, NOW + 3600, NOW));
        assertDoesNotThrow(() -> CompetitionRules.checkNewTimes(over, NOW + 60, NOW + 3600, NOW));
    }
}
