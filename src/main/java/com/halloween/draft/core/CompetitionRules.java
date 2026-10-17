package com.halloween.draft.core;

import com.halloween.draft.model.Competition;

/** The rules for setting a competition's times. Pure logic, so it can be unit tested. */
public final class CompetitionRules {

    private CompetitionRules() {}

    /**
     * Checks a new start and end (epoch seconds, TCT) against the current competition.
     * Before it's live: the start must be in the future. Once live: only the end can change.
     * @throws UserError explaining what's wrong
     */
    public static void checkNewTimes(Competition current, long start, long end, long now) {
        if (end <= start) throw new UserError("The end has to be after the start.");
        boolean live = current != null && current.live(now);
        if (live && current.startEpoch != null && start != current.startEpoch) {
            throw new UserError("The competition has already started — only the end time can change.");
        }
        if (!live && start <= now) {
            throw new UserError("The start time has to be in the future — it's " + DraftManager.tct(now) + " TCT now.");
        }
    }
}
