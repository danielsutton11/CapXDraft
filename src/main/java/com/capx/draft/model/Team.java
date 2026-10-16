package com.capx.draft.model;

/** A captain's named team, with the Discord role and channels created for it. */
public class Team {
    public String name;
    public Long roleId;
    public Long textChannelId;
    public Long voiceChannelId;

    public Team() {}

    public Team(String name) {
        this.name = name;
    }
}
