package com.halloween.draft.model;

/** A captain's named team, with the Discord role and channels created for it. */
public class Team {
    public String name;
    public Long roleId;
    public Long captainRoleId; // "<name> Captain", held only by this team's captain
    public Long textChannelId;
    public Long voiceChannelId;
    /** The captain's uploaded team image (a row in team_images), and its file type, e.g. "png". */
    public Long imageId;
    public String imageExt;

    public Team() {}

    public Team(String name) {
        this.name = name;
    }
}
