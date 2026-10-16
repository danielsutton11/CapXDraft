package com.capx.draft.core;

import com.capx.draft.config.BotProperties;
import com.capx.draft.model.Team;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.IPermissionHolder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Creates, renames and deletes the Discord role + text/voice channels behind a named team. */
@Service
public class TeamService {
    private static final Logger log = LoggerFactory.getLogger(TeamService.class);

    private static final EnumSet<Permission> TEXT_ALLOW = EnumSet.of(
            Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY,
            Permission.MESSAGE_ATTACH_FILES, Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_ADD_REACTION);
    private static final EnumSet<Permission> VOICE_ALLOW = EnumSet.of(
            Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK, Permission.VOICE_STREAM);
    private static final EnumSet<Permission> BOT_TEXT = EnumSet.of(
            Permission.VIEW_CHANNEL, Permission.MANAGE_CHANNEL, Permission.MESSAGE_SEND);
    private static final EnumSet<Permission> BOT_VOICE = EnumSet.of(
            Permission.VIEW_CHANNEL, Permission.MANAGE_CHANNEL, Permission.VOICE_CONNECT);
    private static final EnumSet<Permission> NONE = EnumSet.noneOf(Permission.class);
    private static final EnumSet<Permission> HIDE = EnumSet.of(Permission.VIEW_CHANNEL);

    private final BotProperties props;

    public TeamService(BotProperties props) {
        this.props = props;
    }

    /**
     * Creates the role and both channels. Blocking - call from a worker thread.
     * @return permissions Discord refused to grant (empty when everything went through)
     */
    public Set<String> create(Guild guild, BotProperties.Faction faction, Team team, int colorIndex,
                              Collection<Long> memberIds) {
        Category category = guild.getCategoryById(props.getTeamCategoryId());
        if (category == null) {
            throw new UserError("I can't find the team category (" + props.getTeamCategoryId() + ").");
        }
        Member self = guild.getSelfMember();
        EnumSet<Permission> has = EnumSet.copyOf(self.getPermissions(category));
        EnumSet<Permission> required = EnumSet.of(
                Permission.VIEW_CHANNEL, Permission.MANAGE_CHANNEL, Permission.MANAGE_PERMISSIONS);
        required.removeAll(has);
        if (!required.isEmpty()) {
            throw new UserError("In the team category I'm missing: " + names(required)
                    + ". Give my role those there (Edit Category → Permissions) and try again.");
        }

        Role role = guild.createRole()
                .setName(team.name)
                .setColor(BoardRenderer.color(colorIndex))
                .setMentionable(true)
                .complete();
        team.roleId = role.getIdLong();
        Role leaders = guild.getRoleById(faction.getLeadershipRoleId());
        Set<String> refused = new TreeSet<>();

        // Born private: hidden from @everyone, visible to the bot. Everything else is added afterwards.
        TextChannel text = category.createTextChannel(textName(faction, team.name))
                .addRolePermissionOverride(guild.getPublicRole().getIdLong(), NONE, HIDE)
                .addMemberPermissionOverride(self.getIdLong(), HIDE, NONE)
                .complete();
        team.textChannelId = text.getIdLong();
        grant(text, self, only(BOT_TEXT, has), refused, "bot");
        grant(text, role, only(TEXT_ALLOW, has), refused, "team");
        if (leaders != null) grant(text, leaders, only(TEXT_ALLOW, has), refused, "leadership");

        VoiceChannel voice = category.createVoiceChannel(voiceName(faction, team.name))
                .addRolePermissionOverride(guild.getPublicRole().getIdLong(), NONE, HIDE)
                .addMemberPermissionOverride(self.getIdLong(), HIDE, NONE)
                .complete();
        team.voiceChannelId = voice.getIdLong();
        grant(voice, self, only(BOT_VOICE, has), refused, "bot");
        grant(voice, role, only(VOICE_ALLOW, has), refused, "team");
        if (leaders != null) grant(voice, leaders, only(VOICE_ALLOW, has), refused, "leadership");

        memberIds.forEach(id -> addMember(guild, team, id));
        if (!refused.isEmpty()) log.warn("Team {}: Discord refused {}", team.name, refused);
        return refused;
    }

    /**
     * Applies one permission override. If Discord refuses the whole set, adds the permissions
     * one at a time, keeping those it accepts and recording the ones it refuses.
     */
    private void grant(IPermissionContainer container, IPermissionHolder holder, EnumSet<Permission> allow,
                       Set<String> refused, String who) {
        if (allow.isEmpty()) return;
        try {
            container.upsertPermissionOverride(holder).setAllowed(allow).complete();
            return;
        } catch (RuntimeException e) {
            log.warn("Override for {} on #{} refused as a set ({}); trying one at a time",
                    who, container.getName(), e.getMessage());
        }
        EnumSet<Permission> accepted = EnumSet.noneOf(Permission.class);
        for (Permission p : allow) {
            EnumSet<Permission> attempt = EnumSet.copyOf(accepted);
            attempt.add(p);
            try {
                container.upsertPermissionOverride(holder).setAllowed(attempt).complete();
                accepted.add(p);
            } catch (RuntimeException e) {
                refused.add(p.getName() + " (" + who + ", " + container.getName() + ")");
            }
        }
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

    private static EnumSet<Permission> only(EnumSet<Permission> wanted, EnumSet<Permission> has) {
        EnumSet<Permission> out = EnumSet.copyOf(wanted);
        out.retainAll(has);
        return out;
    }

    private static String names(Collection<Permission> perms) {
        return perms.stream().map(Permission::getName).collect(Collectors.joining(", "));
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