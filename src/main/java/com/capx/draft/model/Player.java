package com.capx.draft.model;

/**
 * A draftable player. Discord members use their Discord id; faction members who aren't on Discord
 * use the negative of their Torn ID, so the two can never collide.
 */
public class Player {
    public long id;
    public String display;
    public Long tornId;
    public Integer streak; // Torn active streak in days, null until fetched / unknown

    public Player() {}

    public Player(long id, String display, Long tornId) {
        this.id = id;
        this.display = display;
        this.tornId = tornId;
    }

    /** Torn-only player (in the faction but not matched to anyone on Discord). */
    public static Player tornOnly(long tornId, String name) {
        return new Player(-tornId, name + " [" + tornId + "]", tornId);
    }

    /** False for Torn-only players, who can't get roles or DMs. */
    public boolean onDiscord() {
        return id > 0;
    }

    public String streakLabel() {
        return streak == null ? "" : " 🔥" + streak;
    }
}