package com.capx.draft.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Everything persisted for one faction: its captains, named teams and current/last draft. */
public class FactionState {
    public String factionKey;
    public List<Long> captains = new ArrayList<>();      // order set by /setcaptains
    public Map<Long, Team> teams = new HashMap<>();      // keyed by captain id
    public Draft draft;                                  // null until the first draft

    public FactionState() {}

    public FactionState(String factionKey) {
        this.factionKey = factionKey;
    }

    public boolean draftRunning() {
        return draft != null && draft.status.running();
    }

    /** Captains relevant right now: the live draft's order if one is running, else the configured list. */
    public List<Long> activeCaptains() {
        return draftRunning() ? draft.captainOrder : captains;
    }

    public String teamName(long captainId, String captainDisplay) {
        Team t = teams.get(captainId);
        return t != null && t.name != null ? t.name : captainDisplay + "'s team";
    }
}
