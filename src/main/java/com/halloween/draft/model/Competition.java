package com.halloween.draft.model;

/**
 * A faction's attack competition window and monitoring progress. Times are epoch seconds (UTC,
 * shown as TCT). Persisted with the rest of the faction's state.
 */
public class Competition {
    public Long startEpoch;
    public Long endEpoch;
    /** Newest attack end-time fetched so far; the next poll starts a little before this. */
    public Long lastFetchedEpoch;
    public Long lastCheckedMs;
    public String lastError;
    public Long scoreboardMessageId;
    /** Which team image the scoreboard message has attached, so it's only re-uploaded when it changes. */
    public String scoreboardImageSig;
    public boolean finalPosted;
    public boolean keysFailingNotified;
    /** Leaders were told the competition starts soon while the draft is still running. */
    public boolean draftSoonWarned;
    /** Leaders were told the competition started while the draft is still running. */
    public boolean draftLiveWarned;

    public boolean scheduled() {
        return startEpoch != null && endEpoch != null;
    }

    public boolean live(long nowEpoch) {
        return scheduled() && nowEpoch >= startEpoch && nowEpoch < endEpoch;
    }

    public boolean over(long nowEpoch) {
        return scheduled() && nowEpoch >= endEpoch;
    }
}
