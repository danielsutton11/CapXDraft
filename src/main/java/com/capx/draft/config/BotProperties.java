package com.capx.draft.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ConfigurationProperties(prefix = "draftbot")
public class BotProperties {

    private String token;
    private String tornApiKey;
    private long captainRoleId;
    private long teamCategoryId;
    private int maxQueue = 5;
    private String eventName = "";
    private List<Long> teamChannelRoleIds = new ArrayList<>();
    private List<Faction> factions = new ArrayList<>();

    public Optional<Faction> faction(String key) {
        return factions.stream().filter(f -> f.getKey().equalsIgnoreCase(key)).findFirst();
    }

    public Optional<Faction> factionForChannel(long channelId) {
        return factions.stream().filter(f -> f.getChannelId() == channelId).findFirst();
    }

    public static class Faction {
        private String key;
        private String name;
        private long draftRoleId;
        private long leadershipRoleId;
        private long channelId;
        private long tornFactionId;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public long getDraftRoleId() { return draftRoleId; }
        public void setDraftRoleId(long draftRoleId) { this.draftRoleId = draftRoleId; }
        public long getLeadershipRoleId() { return leadershipRoleId; }
        public void setLeadershipRoleId(long leadershipRoleId) { this.leadershipRoleId = leadershipRoleId; }
        public long getChannelId() { return channelId; }
        public void setChannelId(long channelId) { this.channelId = channelId; }
        public long getTornFactionId() { return tornFactionId; }
        public void setTornFactionId(long tornFactionId) { this.tornFactionId = tornFactionId; }
    }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getTornApiKey() { return tornApiKey; }
    public void setTornApiKey(String tornApiKey) { this.tornApiKey = tornApiKey; }
    public long getCaptainRoleId() { return captainRoleId; }
    public void setCaptainRoleId(long captainRoleId) { this.captainRoleId = captainRoleId; }
    public long getTeamCategoryId() { return teamCategoryId; }
    public void setTeamCategoryId(long teamCategoryId) { this.teamCategoryId = teamCategoryId; }
    public String getEventName() { return eventName; }
    public void setEventName(String eventName) { this.eventName = eventName; }
    public int getMaxQueue() { return maxQueue; }
    public void setMaxQueue(int maxQueue) { this.maxQueue = maxQueue; }
    public List<Long> getTeamChannelRoleIds() { return teamChannelRoleIds; }
    public void setTeamChannelRoleIds(List<Long> ids) { this.teamChannelRoleIds = ids; }
    public List<Faction> getFactions() { return factions; }
    public void setFactions(List<Faction> factions) { this.factions = factions; }
}