package com.capx.draft.core;

import com.capx.draft.config.BotProperties;
import com.capx.draft.model.Team;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/** Creates, renames and deletes the Discord role + text/voice channels behind a named team. */
@Service
public class TeamService {
    private static final Logger log = LoggerFactory.getLogger(TeamService.class);

    private static final EnumSet<Permission> TEXT_ALLOW = EnumSet.of(
            Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY,
            Permission.MESSAGE_ATTACH_FILES, Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_ADD_REACTION);
    private static final EnumSet<Permission> VOICE_ALLOW = EnumSet.of(
            Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK, Permission.VOICE_STREAM);
    private static final EnumSet<Permission> BOT_ALLOW = EnumSet.of(
            Permission.VIEW_CHANNEL, Permission.MANAGE_CHANNEL, Permission.MESSAGE_SEND, Permission.VOICE_CONNECT);
    private static final EnumSet<Permission> NONE = EnumSet.noneOf(Permission.class);
    private static final EnumSet<Permission> HIDE = EnumSet.of(Permission.VIEW_CHANNEL);

    private final BotProperties props;

    public TeamService(BotProperties props) {
        this.props = props;
    }

    /** Creates the role and both channels. Blocking - call from a worker thread. */
    public void create(Guild guild, BotProperties.Faction faction, Team team, int colorIndex,
                       Collection<Long> memberIds) {
        Category category = guild.getCategoryById(props.getTeamCategoryId());
        if (category == null) {
            throw new IllegalStateException("Team category " + props.getTeamCategoryId() + " not found");
        }

        Role role = guild.createRole()
                .setName(team.name)
                .setColor(BoardRenderer.color(colorIndex))
                .setMentionable(true)
                .complete();
        team.roleId = role.getIdLong();

        long everyone = guild.getPublicRole().getIdLong();
        long leaders = faction.getLeadershipRoleId();
        long self = guild.getSelfMember().getIdLong();

        TextChannel text = category.createTextChannel(textName(faction, team.name))
                .addRolePermissionOverride(everyone, NONE, HIDE)
                .addRolePermissionOverride(role.getIdLong(), TEXT_ALLOW, NONE)
                .addRolePermissionOverride(leaders, TEXT_ALLOW, NONE)
                .addMemberPermissionOverride(self, BOT_ALLOW, NONE)
                .complete();
        team.textChannelId = text.getIdLong();

        VoiceChannel voice = category.createVoiceChannel(voiceName(faction, team.name))
                .addRolePermissionOverride(everyone, NONE, HIDE)
                .addRolePermissionOverride(role.getIdLong(), VOICE_ALLOW, NONE)
                .addRolePermissionOverride(leaders, VOICE_ALLOW, NONE)
                .addMemberPermissionOverride(self, BOT_ALLOW, NONE)
                .complete();
        team.voiceChannelId = voice.getIdLong();

        memberIds.forEach(id -> addMember(guild, team, id));
    }

    /** Renames role and channels in place. Blocking. */
    public void rename(Guild guild, BotProperties.Faction faction, Team team, String newName) {
        team.name = newName;
        Role role = team.roleId == null ? null : guild.getRoleById(team.roleId);
        if (role != null) role.getManager().setName(newName).complete();
        TextChannel text = team.textChannelId == null ? null : guild.getTextChannelById(team.textChannelId);
        if (text != null) text.getManager().setName(textName(faction, newName)).complete();
        VoiceChannel voice = team.voiceChannelId == null ? null : guild.getVoiceChannelById(team.voiceChannelId);
        if (voice != null) voice.getManager().setName(voiceName(faction, newName)).complete();
    }

    /** Deletes role and channels, ignoring anything already gone. Blocking. */
    public void delete(Guild guild, Team team) {
        try {
            TextChannel text = team.textChannelId == null ? null : guild.getTextChannelById(team.textChannelId);
            if (text != null) text.delete().complete();
            VoiceChannel voice = team.voiceChannelId == null ? null : guild.getVoiceChannelById(team.voiceChannelId);
            if (voice != null) voice.delete().complete();
            Role role = team.roleId == null ? null : guild.getRoleById(team.roleId);
            if (role != null) role.delete().complete();
        } catch (Exception e) {
            log.warn("Problem deleting team {}: {}", team.name, e.getMessage());
        }
    }

    public void addMember(Guild guild, Team team, long memberId) {
        Role role = team == null || team.roleId == null ? null : guild.getRoleById(team.roleId);
        if (role != null) {
            guild.addRoleToMember(UserSnowflake.fromId(memberId), role)
                    .queue(null, e -> log.warn("Could not add team role to {}: {}", memberId, e.getMessage()));
        }
    }

    public void removeMember(Guild guild, Team team, long memberId) {
        Role role = team == null || team.roleId == null ? null : guild.getRoleById(team.roleId);
        if (role != null) {
            guild.removeRoleFromMember(UserSnowflake.fromId(memberId), role)
                    .queue(null, e -> log.warn("Could not remove team role from {}: {}", memberId, e.getMessage()));
        }
    }

    public void setCaptainRole(Guild guild, long memberId, boolean give) {
        Role role = guild.getRoleById(props.getCaptainRoleId());
        if (role == null) return;
        var action = give
                ? guild.addRoleToMember(UserSnowflake.fromId(memberId), role)
                : guild.removeRoleFromMember(UserSnowflake.fromId(memberId), role);
        action.queue(null, e -> log.warn("Could not update captain role for {}: {}", memberId, e.getMessage()));
    }

    /** Removes the shared captain role from everyone who has it. */
    public int clearCaptainRole(Guild guild) {
        Role role = guild.getRoleById(props.getCaptainRoleId());
        if (role == null) return 0;
        List<net.dv8tion.jda.api.entities.Member> holders = guild.getMembersWithRoles(role);
        holders.forEach(m -> guild.removeRoleFromMember(UserSnowflake.fromId(m.getIdLong()), role)
                .queue(null, e -> {}));
        return holders.size();
    }

    static String textName(BotProperties.Faction faction, String teamName) {
        String slug = (faction.getName() + " " + teamName).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.length() > 100 ? slug.substring(0, 100) : slug;
    }

    static String voiceName(BotProperties.Faction faction, String teamName) {
        String name = faction.getName() + " · " + teamName;
        return name.length() > 100 ? name.substring(0, 100) : name;
    }
}
