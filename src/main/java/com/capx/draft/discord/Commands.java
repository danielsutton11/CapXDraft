package com.capx.draft.discord;

import com.capx.draft.config.BotProperties;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.util.ArrayList;
import java.util.List;

/** Slash command definitions. Registered per guild so changes show up instantly. */
public final class Commands {

    private Commands() {}

    public static List<CommandData> all(BotProperties props) {
        List<CommandData> list = new ArrayList<>();

        // ---------- leadership ----------
        SlashCommandData setCaptains = slash("setcaptains", "Set a faction's draft captains, in pick order (leadership)");
        for (int i = 1; i <= 9; i++) {
            setCaptains.addOption(OptionType.USER, "captain" + i, "Captain #" + i, i <= 2);
        }
        setCaptains.addOptions(faction(props))
                .addOption(OptionType.BOOLEAN, "random", "Shuffle the pick order instead of using the order above");
        list.add(setCaptains);

        list.add(slash("startdraft", "Start a faction draft (leadership)")
                .addOptions(new OptionData(OptionType.INTEGER, "timer", "Minutes per pick: 0 = no timer, otherwise 1–60")
                        .setRequiredRange(0, 60))
                .addOption(OptionType.BOOLEAN, "autopick", "Auto-pick (queue first, then random) when time runs out")
                .addOption(OptionType.BOOLEAN, "random_order", "Shuffle the captain order")
                .addOptions(faction(props)));

        list.add(slash("pause", "Pause a draft and its timer (leadership)").addOptions(faction(props)));
        list.add(slash("resume", "Resume a paused draft (leadership)").addOptions(faction(props)));
        list.add(slash("undo", "Undo the last pick (leadership)").addOptions(faction(props)));
        list.add(slash("enddraft", "End a draft early (leadership)").addOptions(faction(props)));

        list.add(slash("adminpick", "Pick for the captain on the clock (leadership)")
                .addOptions(player("Player to pick"), faction(props)));

        list.add(slash("replacecaptain", "Swap a captain for someone else, even mid-draft (leadership)")
                .addOption(OptionType.USER, "old", "Current captain", true)
                .addOption(OptionType.USER, "new", "New captain", true)
                .addOptions(faction(props)));

        list.add(slash("buildpool", "Prepare the draft pool: draft role + Torn faction members (leadership)")
                .addOptions(faction(props)));

        list.add(slash("addplayer", "Add someone to the pool, before or during the draft (leadership)")
                .addOption(OptionType.USER, "member", "Discord member to add")
                .addOption(OptionType.INTEGER, "torn_id", "Torn ID — for faction members who aren't on Discord")
                .addOptions(faction(props)));

        SlashCommandData remove = slash("removeplayer", "Remove up to 5 players from the pool or their team (leadership)");
        for (int i = 1; i <= 5; i++) {
            remove.addOptions(new OptionData(OptionType.STRING, "player" + i, "Player to remove", i == 1, true));
        }
        list.add(remove.addOptions(faction(props)));

        // ---------- captains ----------
        list.add(slash("pick", "Make your draft pick").addOptions(player("Start typing a name")));
        list.add(slash("queue", "Add a player to your pick queue").addOptions(player("Start typing a name")));
        list.add(slash("myqueue", "Show your pick queue"));
        list.add(slash("cancelqueue", "Clear your pick queue"));
        list.add(slash("nameteam", "Name (or rename) your team — creates its role and channels")
                .addOptions(new OptionData(OptionType.STRING, "name", "Team name", true).setRequiredLength(2, 40))
                .addOption(OptionType.USER, "captain", "(Leadership) name the team for this captain instead of yourself"));

        // ---------- everyone ----------
        list.add(slash("draftstatus", "Who's on the clock, time left and who's next").addOptions(faction(props)));
        list.add(slash("viewcaptains", "Show a faction's captains, pick order and team names").addOptions(faction(props)));
        list.add(slash("remainingdraftees", "Show everyone still in the pool").addOptions(faction(props)));

        // ---------- server admins ----------
        list.add(slash("teardownteams", "Delete all team roles and channels for every faction (admins)")
                .addOption(OptionType.BOOLEAN, "clear_captains", "Also remove the captain role from everyone (default: yes)")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR)));

        return list;
    }

    private static SlashCommandData slash(String name, String description) {
        return net.dv8tion.jda.api.interactions.commands.build.Commands.slash(name, description);
    }

    private static OptionData faction(BotProperties props) {
        OptionData o = new OptionData(OptionType.STRING, "faction",
                "Faction (defaults to this channel's faction, or the one you lead)", false);
        props.getFactions().forEach(f -> o.addChoice(f.getName(), f.getKey()));
        return o;
    }

    private static OptionData player(String description) {
        return new OptionData(OptionType.STRING, "player", description, true, true);
    }
}