package com.halloween.draft.discord;

import com.halloween.draft.config.BotProperties;
import com.halloween.draft.core.DraftManager;
import com.halloween.draft.core.UserError;
import com.halloween.draft.model.Player;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.events.guild.GuildReadyEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Routes Discord events. /draft opens a panel (see {@link PanelController}); /pick is the only other
 * command. Everything is acknowledged straight away and run on worker threads, because draft
 * operations make blocking Discord calls.
 */
@Component
public class DraftListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(DraftListener.class);
    private static final int ERROR_SECONDS = 20;
    private static final int REPLY_SECONDS = 20;

    private final DraftManager drafts;
    private final PanelController panels;
    private final BotProperties props;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public DraftListener(DraftManager drafts, PanelController panels, BotProperties props) {
        this.drafts = drafts;
        this.panels = panels;
        this.props = props;
    }

    // ---------- lifecycle ----------

    @Override
    public void onReady(ReadyEvent event) {
        // Clear global commands left over from the old Python bot so they don't show twice.
        event.getJDA().updateCommands().queue();
        log.info("Logged in as {}", event.getJDA().getSelfUser().getName());
    }

    @Override
    public void onGuildReady(GuildReadyEvent event) {
        event.getGuild().updateCommands().addCommands(Commands.all(props)).queue(
                ok -> log.info("Registered {} commands in {}", ok.size(), event.getGuild().getName()),
                err -> log.error("Command registration failed in {}", event.getGuild().getName(), err));
    }

    // ---------- slash commands ----------

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent e) {
        if (e.getGuild() == null || e.getMember() == null) {
            e.reply("Use this in the server.").setEphemeral(true).queue();
            return;
        }
        e.deferReply(true).queue();
        InteractionHook hook = e.getHook();
        long ch = channelId(e.getChannel());

        workers.submit(() -> {
            try {
                switch (e.getName()) {
                    case "draft" -> {
                        OptionMapping f = e.getOption("faction");
                        panels.open(hook, e.getGuild(), e.getMember(), f == null ? null : f.getAsString(), ch);
                    }
                    case "pick" -> {
                        OptionMapping p = e.getOption("player");
                        dmOrReply(hook, e.getUser(), drafts.pick(e.getGuild(), e.getMember(), ch,
                                p == null ? null : p.getAsString()));
                    }
                    default -> hook.editOriginal("❌ Unknown command.").queue(ok -> autoHide(hook, ERROR_SECONDS));
                }
            } catch (UserError err) {
                hook.editOriginal("❌ " + err.getMessage()).queue(ok -> autoHide(hook, ERROR_SECONDS));
            } catch (Exception ex) {
                log.error("/{} failed", e.getName(), ex);
                hook.editOriginal("⚠️ Something went wrong — the error has been logged.")
                        .queue(ok -> autoHide(hook, ERROR_SECONDS));
            }
        });
    }

    // ---------- panel components ----------

    @Override
    public void onButtonInteraction(ButtonInteractionEvent e) {
        if (panels.handles(e.getComponentId())) safely(() -> panels.onButton(e), e.getComponentId());
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent e) {
        if (panels.handles(e.getComponentId())) safely(() -> panels.onStringSelect(e), e.getComponentId());
    }

    @Override
    public void onEntitySelectInteraction(EntitySelectInteractionEvent e) {
        if (panels.handles(e.getComponentId())) safely(() -> panels.onEntitySelect(e), e.getComponentId());
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent e) {
        if (panels.handles(e.getModalId())) safely(() -> panels.onModal(e), e.getModalId());
    }

    private static void safely(Runnable r, String id) {
        try {
            r.run();
        } catch (Exception ex) {
            log.error("Panel interaction {} failed", id, ex);
        }
    }

    // ---------- /pick autocomplete ----------

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent e) {
        if (!e.getName().equals("pick") || !e.getFocusedOption().getName().equals("player")) {
            e.replyChoices(List.of()).queue();
            return;
        }
        var f = drafts.captainFactionOrNull(e.getUser().getIdLong(), channelId(e.getChannel()));
        DraftManager.Snapshot snap = f == null ? null : drafts.snapshot(f.getKey());
        if (snap == null) {
            e.replyChoices(List.of()).queue();
            return;
        }
        String query = e.getFocusedOption().getValue().toLowerCase(Locale.ROOT);
        List<Command.Choice> choices = new ArrayList<>();
        for (Player p : snap.eligible()) {
            if (choices.size() >= 25) break;
            if (!query.isEmpty() && !p.display.toLowerCase(Locale.ROOT).contains(query)) continue;
            String label = p.display + (p.streak != null ? " · 🔥 " + p.streak + "d streak" : "")
                    + (p.onDiscord() ? "" : " · not on Discord");
            if (label.length() > 100) label = label.substring(0, 100);
            choices.add(new Command.Choice(label, String.valueOf(p.id)));
        }
        e.replyChoices(choices).queue(null, err -> {});
    }

    // ---------- helpers ----------

    /** /pick confirmations go to DMs so the draft channel only shows the board. */
    private static void dmOrReply(InteractionHook hook, User user, String text) {
        user.openPrivateChannel()
                .flatMap(pc -> pc.sendMessage(text))
                .queue(ok -> hook.deleteOriginal().queue(null, err -> {}),
                        err -> hook.editOriginal(text).queue(ok -> autoHide(hook, REPLY_SECONDS)));
    }

    private static void autoHide(InteractionHook hook, int seconds) {
        hook.deleteOriginal().queueAfter(seconds, TimeUnit.SECONDS, null, err -> {});
    }

    private static long channelId(Channel channel) {
        return channel == null ? 0 : channel.getIdLong();
    }
}