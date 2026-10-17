package com.halloween.draft.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FactionStateRulesTest {

    private static final long NOW = 1_800_000_000L;

    @Test
    void rostersLockOnlyWhileTheCompetitionIsLive() {
        FactionState s = new FactionState("x");
        assertFalse(s.rostersLocked(NOW), "no competition");
        s.competition.startEpoch = NOW + 60;
        s.competition.endEpoch = NOW + 3600;
        assertFalse(s.rostersLocked(NOW), "not started yet");
        assertTrue(s.rostersLocked(NOW + 61), "live");
        assertFalse(s.rostersLocked(NOW + 3600), "over");
    }

    @Test
    void finishedOrEndedDraftNeedsCleanUp() {
        FactionState s = new FactionState("x");
        assertFalse(s.needsCleanUp(), "no draft yet");
        s.draft = new Draft();
        s.draft.status = DraftStatus.ACTIVE;
        assertFalse(s.needsCleanUp(), "running");
        s.draft.status = DraftStatus.PAUSED;
        assertFalse(s.needsCleanUp(), "paused is still running");
        s.draft.status = DraftStatus.COMPLETE;
        assertTrue(s.needsCleanUp());
        s.draft.status = DraftStatus.ENDED;
        assertTrue(s.needsCleanUp());
    }
}
