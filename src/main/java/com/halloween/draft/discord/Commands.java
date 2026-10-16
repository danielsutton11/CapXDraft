package com.halloween.draft.discord;

import com.halloween.draft.config.BotProperties;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.util.ArrayList;
import java.util.List;

/** Slash command definitions. Registered per guild so changes show up instantly. */
public final class Commands {

    private Commands() {}

    /** Everything else, picking included, happens in the /draft panel (buttons, dropdowns and pop-up forms). */
    public static List<CommandData> all(BotProperties props) {
        List<CommandData> list = new ArrayList<>();
        list.add(slash("draft", "Open the draft panel — controls for leaders, team & queue for captains")
                .addOptions(faction(props)));
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
}