package com.halloween.draft.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Everything persisted for one faction: its captains, named teams and current/last draft. */
public class FactionState {
    public String factionKey;
    public List<Long> captains = new ArrayList<>();      // order set by /setcaptains
    public Map<Long, Team> teams = new HashMap<>();      // keyed by captain id
    public Draft draft;                                  // null until the first draft
    public Competition competition = new Competition();  // attack competition window + progress

    /** Pool prepared before the draft (/buildpool), reviewed and trimmed by leadership. */
    public List<Player> pool;
    /** Players leadership removed before the draft; a rebuild won't add them back. */
    public Set<Long> excludedIds = new HashSet<>();      // Player.id values
    public Set<Long> excludedTornIds = new HashSet<>();

    public boolean isExcluded(Player p) {
        return excludedIds.contains(p.id) || (p.tornId != null && excludedTornIds.contains(p.tornId));
    }

    public FactionState() {}

    public FactionState(String factionKey) {
        this.factionKey = factionKey;
    }

    public boolean draftRunning() {
        return draft != null && draft.status.running();
    }

    /**
     * A draft has finished or been ended and its teams haven't been cleaned up. A new draft needs
     * Clean Up first, so old team roles and members don't carry over.
     */
    public boolean needsCleanUp() {
        return draft != null && !draft.status.running();
    }

    /** Rosters are fixed while the competition is live: hits are scored on whoever is on each team. */
    public boolean rostersLocked(long nowEpoch) {
        return competition != null && competition.live(nowEpoch);
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
