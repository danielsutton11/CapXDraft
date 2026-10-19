package com.halloween.draft.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.halloween.draft.config.BotProperties;
import com.halloween.draft.model.FactionState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RollbackTest {

    /** Stands in for Postgres: always returns a saved state with captains [1]. */
    static class FakeRepo extends StateRepository {
        final List<String> unreadable;
        int saves;

        FakeRepo(List<String> unreadable) {
            super(null, new ObjectMapper());
            this.unreadable = unreadable;
        }

        private static FactionState saved() {
            FactionState s = new FactionState("a");
            s.captains = new ArrayList<>(List.of(1L));
            return s;
        }

        @Override
        public LoadResult loadAll() {
            return unreadable.contains("a") ? new LoadResult(Map.of(), unreadable) : new LoadResult(Map.of("a", saved()), unreadable);
        }

        @Override
        public Optional<FactionState> load(String key) {
            return Optional.of(saved());
        }

        @Override
        public void save(FactionState state) {
            saves++;
        }
    }

    private static DraftManager manager(FakeRepo repo) {
        BotProperties props = new BotProperties();
        BotProperties.Faction f = new BotProperties.Faction();
        f.setKey("a");
        f.setName("A");
        props.setFactions(List.of(f));
        DraftManager dm = new DraftManager(props, repo, null, null, null, null, null, null);
        dm.load();
        return dm;
    }

    @Test
    void failedActionLeavesNoHalfFinishedChanges() {
        DraftManager dm = manager(new FakeRepo(List.of()));
        assertThrows(UserError.class, () -> dm.withLock("a", () -> {
            dm.stateOf("a").captains.add(2L); // a change made before something goes wrong...
            throw new UserError("…like a name that doesn't match");
        }));
        assertEquals(List.of(1L), dm.stateOf("a").captains);
    }

    @Test
    void successfulActionKeepsItsChanges() {
        DraftManager dm = manager(new FakeRepo(List.of()));
        dm.withLock("a", () -> dm.stateOf("a").captains.add(2L));
        assertEquals(List.of(1L, 2L), dm.stateOf("a").captains);
    }

    @Test
    void unreadableFactionIsLockedAndNeverSaved() {
        FakeRepo repo = new FakeRepo(List.of("a"));
        DraftManager dm = manager(repo);
        BotProperties.Faction f = new BotProperties.Faction();
        f.setKey("a");
        assertTrue(dm.isBroken(f));
        assertThrows(UserError.class, () -> dm.withLock("a", () -> null));
        assertEquals(0, repo.saves);
    }
}