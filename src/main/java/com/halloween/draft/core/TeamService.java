package com.halloween.draft.core;

import com.halloween.draft.config.BotProperties;
import com.halloween.draft.model.Team;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Icon;
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
        Role captainRole = createCaptainRole(guild, captainRoleName(faction, team.name));
        team.captainRoleId = captainRole.getIdLong();
        // New roles land at the bottom of the list - move them up under the bot's own role,
        // team role first, then the captain role above it.
        moveBelowBot(guild, captainRole, role); // highest first: captain role above team role
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
     * Creates a team captain role with the shared captain gradient, hoisted ("Display role members
     * separately"). If Discord refuses the gradient (no enhanced role colours), it's created with
     * the first colour as a solid colour instead.
     */
    private Role createCaptainRole(Guild guild, String name) {
        int primary = hex(props.getCaptainColorPrimary(), 0xA475FE);
        int secondary = hex(props.getCaptainColorSecondary(), 0x9FC1FF);
        try {
            return guild.createRole().setName(name).setGradientColors(primary, secondary)
                    .setMentionable(true).setHoisted(true).complete();
        } catch (RuntimeException e) {
            log.warn("Gradient refused for role {} ({}), using a solid colour", name, e.getMessage());
            return guild.createRole().setName(name).setColor(primary)
                    .setMentionable(true).setHoisted(true).complete();
        }
    }

    /** Brings an existing team captain role in line with the shared captain gradient. Failures are logged. */
    public void styleCaptainRole(Guild guild, Team team) {
        Role role = team == null || team.captainRoleId == null ? null : guild.getRoleById(team.captainRoleId);
        if (role == null) return;
        int primary = hex(props.getCaptainColorPrimary(), 0xA475FE);
        int secondary = hex(props.getCaptainColorSecondary(), 0x9FC1FF);
        var colors = role.getColors();
        if (colors != null && colors.getPrimaryRaw() == primary && colors.getSecondaryRaw() == secondary) return;
        role.getManager().setGradientColors(primary, secondary).queue(null, e -> {
            log.warn("Gradient refused for role {} ({}), using a solid colour", role.getName(), e.getMessage());
            role.getManager().setColor(primary).queue(null, e2 -> {});
        });
    }

    /**
     * Puts the team image on the team role and its captain role as a role icon (shown next to names
     * in chat), or takes it off with {@code icon} null. Role icons need the server at boost level 2.
     * @return null if it worked, otherwise a short reason it didn't
     */
    public String setRoleIcons(Guild guild, Team team, byte[] icon) {
        if (!guild.getFeatures().contains("ROLE_ICONS")) {
            return icon == null ? null : "role icons need the server at boost level 2";
        }
        Icon ic = icon == null ? null : Icon.from(icon, Icon.IconType.PNG);
        String problem = null;
        for (Long id : new Long[]{team.roleId, team.captainRoleId}) {
            Role role = id == null ? null : guild.getRoleById(id);
            if (role == null) continue;
            try {
                role.getManager().setIcon(ic).complete();
            } catch (Exception e) {
                log.warn("Couldn't set the role icon on {}: {}", role.getName(), e.getMessage());
                problem = "Discord refused the role icon (" + e.getMessage() + ")";
            }
        }
        return problem;
    }

    /** "#A475FE" → 0xA475FE; anything unreadable gives the fallback. */
    static int hex(String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim().replace("#", ""), 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return fallback;
        }
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

    /** Role reordering rewrites positions server-wide, so only one runs at a time (all factions). */
    private static final Object ROLE_ORDER_LOCK = new Object();

    /**
     * Moves roles to sit directly below the bot's own integration role ("Halloween Draft Bot"), or
     * its highest role if it has no integration role, keeping the order given (highest first).
     * All moves go in one request, so two reorders can never work from an out-of-date role list.
     * Retries once with a fresh list; failure is logged, not fatal.
     */
    private void moveBelowBot(Guild guild, Role... highestFirst) {
        synchronized (ROLE_ORDER_LOCK) {
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    List<Role> botRoles = guild.getSelfMember().getRoles(); // highest first
                    if (botRoles.isEmpty()) return;
                    Role anchor = botRoles.stream().filter(Role::isManaged).findFirst().orElse(botRoles.get(0));

                    var order = guild.modifyRolePositions();
                    List<Role> start = order.getCurrentOrder();
                    if (start.size() < 2 || start.indexOf(anchor) < 0) return;
                    // Work out the list direction from real positions rather than trusting a flag:
                    // "topFirst" means a lower index is higher up the role list.
                    boolean topFirst = start.get(0).getPosition() > start.get(start.size() - 1).getPosition();

                    boolean changed = false;
                    // Lowest role first: each one is put directly under the anchor, pushing the
                    // earlier ones down, so they end up in the order given.
                    for (int i = highestFirst.length - 1; i >= 0; i--) {
                        Role role = highestFirst[i];
                        List<Role> current = order.getCurrentOrder();
                        int anchorIdx = current.indexOf(anchor);
                        int roleIdx = current.indexOf(role);
                        if (anchorIdx < 0 || roleIdx < 0) continue;
                        // moveTo removes the role then re-inserts it at the target index.
                        int target = topFirst
                                ? (roleIdx > anchorIdx ? anchorIdx + 1 : anchorIdx)
                                : (roleIdx < anchorIdx ? anchorIdx - 1 : anchorIdx);
                        if (target == roleIdx) continue;
                        order.selectPosition(role).moveTo(target);
                        changed = true;
                    }
                    if (!changed) return;
                    order.complete();
                    log.info("Moved {} under {}", java.util.Arrays.stream(highestFirst).map(Role::getName).toList(),
                            anchor.getName());
                    return;
                } catch (Exception e) {
                    log.warn("Couldn't move roles under the bot's role (attempt {}): {}", attempt, e.toString());
                    try {
                        Thread.sleep(2_000); // let Discord's role list settle, then try with a fresh copy
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
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
