package com.halloween.draft.core;

import com.halloween.draft.config.BotProperties;
import com.halloween.draft.model.Team;
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
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
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
    /** Captains only need to see the draft channel: the board's button opens their panel. */
    private static final EnumSet<Permission> CAPTAIN_CHANNEL_ALLOW = EnumSet.of(
            Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY);
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
                              long captainId, Collection<Long> memberIds) {
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
                .setName(roleName(faction, team.name))
                .setColor(BoardRenderer.color(colorIndex))
                .setMentionable(true)
                .complete();
        team.roleId = role.getIdLong();
        Role captainRole = guild.createRole()
                .setName(captainRoleName(faction, team.name))
                .setColor(BoardRenderer.color(colorIndex))
                .setMentionable(true)
                .setHoisted(true) // "Display role members separately from online members"
                .complete();
        team.captainRoleId = captainRole.getIdLong();
        // New roles land at the bottom of the list - move them up under the bot's own role,
        // team role first, then the captain role above it.
        moveBelowBot(guild, role);
        moveBelowBot(guild, captainRole);
        List<Role> extraRoles = props.getTeamChannelRoleIds().stream()
                .map(guild::getRoleById).filter(java.util.Objects::nonNull).toList();
        Set<String> refused = new TreeSet<>();

        // Born private: nothing copied from the category, hidden from @everyone, visible to the bot.
        // Team access is added afterwards.
        TextChannel text = category.createTextChannel(textName(faction, team.name))
                .clearPermissionOverrides()
                .addRolePermissionOverride(guild.getPublicRole().getIdLong(), NONE, HIDE)
                .addMemberPermissionOverride(self.getIdLong(), HIDE, NONE)
                .complete();
        team.textChannelId = text.getIdLong();
        grant(text, self, only(BOT_TEXT, has), refused, "bot");
        grant(text, role, only(TEXT_ALLOW, has), refused, "team");
        for (Role r : extraRoles) grant(text, r, only(TEXT_ALLOW, has), refused, r.getName());

        VoiceChannel voice = category.createVoiceChannel(voiceName(faction, team.name))
                .clearPermissionOverrides()
                .addRolePermissionOverride(guild.getPublicRole().getIdLong(), NONE, HIDE)
                .addMemberPermissionOverride(self.getIdLong(), HIDE, NONE)
                .complete();
        team.voiceChannelId = voice.getIdLong();
        grant(voice, self, only(BOT_VOICE, has), refused, "bot");
        grant(voice, role, only(VOICE_ALLOW, has), refused, "team");
        for (Role r : extraRoles) grant(voice, r, only(VOICE_ALLOW, has), refused, r.getName());

        memberIds.forEach(id -> addMember(guild, team, id));
        setTeamCaptain(guild, team, captainId, true);
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

    /**
     * Moves a role to sit directly below the bot's own integration role ("Halloween Draft Bot"),
     * or its highest role if it has no integration role. Failure is logged, not fatal.
     */
    private void moveBelowBot(Guild guild, Role role) {
        try {
            List<Role> botRoles = guild.getSelfMember().getRoles(); // highest first
            if (botRoles.isEmpty()) return;
            Role anchor = botRoles.stream().filter(Role::isManaged).findFirst().orElse(botRoles.get(0));

            var order = guild.modifyRolePositions();
            List<Role> current = order.getCurrentOrder();
            int anchorIdx = current.indexOf(anchor);
            int roleIdx = current.indexOf(role);
            if (anchorIdx < 0 || roleIdx < 0 || current.size() < 2) return;

            // Work out the list direction from real positions rather than trusting a flag:
            // "topFirst" means a lower index is higher up the role list.
            boolean topFirst = current.get(0).getPosition() > current.get(current.size() - 1).getPosition();

            // moveTo removes the role then re-inserts it at the target index.
            int target;
            if (topFirst) {
                // directly below = the next index; removing a role after the anchor doesn't shift it
                target = roleIdx > anchorIdx ? anchorIdx + 1 : anchorIdx;
            } else {
                // directly below = the previous index; removing a role before the anchor shifts it down one
                target = roleIdx < anchorIdx ? anchorIdx - 1 : anchorIdx;
            }
            log.info("Moving role {} (index {}) under {} (index {}), topFirst={}, target {}",
                    role.getName(), roleIdx, anchor.getName(), anchorIdx, topFirst, target);
            if (target == roleIdx) return;
            order.selectPosition(role).moveTo(target).complete();
        } catch (Exception e) {
            log.warn("Couldn't move role {} under the bot's role: {}", role.getName(), e.toString());
        }
    }

    /** Renames role and channels in place. Blocking. */
    public void rename(Guild guild, BotProperties.Faction faction, Team team, String newName) {
        team.name = newName;
        Role role = team.roleId == null ? null : guild.getRoleById(team.roleId);
        if (role != null) role.getManager().setName(roleName(faction, newName)).complete();
        Role captainRole = team.captainRoleId == null ? null : guild.getRoleById(team.captainRoleId);
        if (captainRole != null) captainRole.getManager().setName(captainRoleName(faction, newName)).complete();
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
            Role captainRole = team.captainRoleId == null ? null : guild.getRoleById(team.captainRoleId);
            if (captainRole != null) captainRole.delete().complete();
        } catch (Exception e) {
            log.warn("Problem deleting team {}: {}", team.name, e.getMessage());
        }
    }

    public void addMember(Guild guild, Team team, long memberId) {
        if (memberId <= 0) return; // Torn-only player - not on Discord
        Role role = team == null || team.roleId == null ? null : guild.getRoleById(team.roleId);
        if (role != null) {
            guild.addRoleToMember(UserSnowflake.fromId(memberId), role)
                    .queue(null, e -> log.warn("Could not add team role to {}: {}", memberId, e.getMessage()));
        }
    }

    public void removeMember(Guild guild, Team team, long memberId) {
        if (memberId <= 0) return;
        Role role = team == null || team.roleId == null ? null : guild.getRoleById(team.roleId);
        if (role != null) {
            guild.removeRoleFromMember(UserSnowflake.fromId(memberId), role)
                    .queue(null, e -> log.warn("Could not remove team role from {}: {}", memberId, e.getMessage()));
        }
    }

    /**
     * Lets the team's captain role see and use a channel (e.g. the draft channel /nameteam was run in).
     * @return permissions Discord refused, empty if all went through
     */
    public Set<String> grantCaptainChannelAccess(Guild guild, Team team, long channelId) {
        Set<String> refused = new TreeSet<>();
        Role role = team == null || team.captainRoleId == null ? null : guild.getRoleById(team.captainRoleId);
        GuildChannel channel = guild.getGuildChannelById(channelId);
        if (role == null || !(channel instanceof IPermissionContainer container)) return refused;
        EnumSet<Permission> has = EnumSet.copyOf(guild.getSelfMember().getPermissions(channel));
        if (!has.contains(Permission.MANAGE_PERMISSIONS)) {
            refused.add("all (I need Manage Permissions in #" + channel.getName() + ")");
            return refused;
        }
        grant(container, role, only(CAPTAIN_CHANNEL_ALLOW, has), refused, role.getName());
        return refused;
    }

    /** Gives or takes the team's own "<name> Captain" role. */
    public void setTeamCaptain(Guild guild, Team team, long memberId, boolean give) {
        Role role = team == null || team.captainRoleId == null ? null : guild.getRoleById(team.captainRoleId);
        if (role == null) return;
        var action = give
                ? guild.addRoleToMember(UserSnowflake.fromId(memberId), role)
                : guild.removeRoleFromMember(UserSnowflake.fromId(memberId), role);
        action.queue(null, e -> log.warn("Could not update team captain role for {}: {}", memberId, e.getMessage()));
    }

    private static EnumSet<Permission> only(EnumSet<Permission> wanted, EnumSet<Permission> has) {
        EnumSet<Permission> out = EnumSet.copyOf(wanted);
        out.retainAll(has);
        return out;
    }

    private static String names(Collection<Permission> perms) {
        return perms.stream().map(Permission::getName).collect(Collectors.joining(", "));
    }

    /** "CX · Alpha": the faction tag keeps same-named teams in different factions apart. */
    static String roleName(BotProperties.Faction faction, String teamName) {
        String name = faction.getTag() + " · " + teamName;
        return name.length() > 100 ? name.substring(0, 100) : name;
    }

    /** "CX · Alpha Captain". */
    static String captainRoleName(BotProperties.Faction faction, String teamName) {
        String name = faction.getTag() + " · " + teamName + " Captain";
        return name.length() > 100 ? name.substring(0, 100) : name;
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