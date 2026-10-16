package com.capx.draft.model;

/** A draftable member. Torn ID is parsed from the "Name [123456]" nickname format. */
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

    public String streakLabel() {
        return streak == null ? "" : " 🔥" + streak;
    }
}
