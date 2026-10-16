package com.capx.draft.discord;

import com.capx.draft.config.BotProperties;
import com.capx.draft.core.DraftManager;
import com.capx.draft.core.UserError;
import com.capx.draft.model.Player;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.events.guild.GuildReadyEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
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
 * Routes Discord events. Every command is acknowledged immediately (ephemeral) and then run on a
 * worker thread, because draft operations make blocking Discord calls.
 */
@Component
public class DraftListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(DraftListener.class);

    private final DraftManager drafts;
    private final BotProperties props;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    // How long private replies stay before hiding themselves
    private static final int ERROR_SECONDS = 20;
    private static final int REPLY_SECONDS = 20;
    private static final int VIEW_SECONDS = 90;     // /draftstatus, /remainingdraftees
    private static final int CONFIRM_SECONDS = 60;  // unanswered Yes/No buttons

    /** A reply that needs Yes/No buttons. */
    private record Confirm(String text, Button yes, Button no) {}

    public DraftListener(DraftManager drafts, BotProperties props) {
        this.drafts = drafts;
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
        workers.submit(() -> {
            try {
                Object result = route(e);
                respond(hook, e.getUser(), result);
            } catch (UserError err) {
                hook.editOriginal("❌ " + err.getMessage()).queue(ok -> autoHide(hook, ERROR_SECONDS));
            } catch (Exception ex) {
                log.error("/{} failed", e.getName(), ex);
                hook.editOriginal("⚠️ Something went wrong — the error has been logged.")
                        .queue(ok -> autoHide(hook, ERROR_SECONDS));
            }
        });
    }

    private Object route(SlashCommandInteractionEvent e) {
        Guild g = e.getGuild();
        Member m = e.getMember();
        long ch = channelId(e.getChannel());
        String faction = str(e, "faction");

        return switch (e.getName()) {
            case "setcaptains" -> {
                List<Member> caps = new ArrayList<>();
                for (int i = 1; i <= 9; i++) {
                    OptionMapping o = e.getOption("captain" + i);
                    if (o == null) continue;
                    Member c = o.getAsMember();
                    if (c == null) throw new UserError(o.getAsUser().getName() + " isn't in this server.");
                    caps.add(c);
                }
                yield drafts.setCaptains(g, m, faction, ch, caps, bool(e, "random", false));
            }
            case "startdraft" -> drafts.startDraft(g, m, faction, ch,
                    e.getOption("timer") == null ? 0 : e.getOption("timer").getAsInt() * 60, // minutes → seconds
                    bool(e, "autopick", false), bool(e, "random_order", false));
            case "pause" -> drafts.pause(g, m, faction, ch);
            case "resume" -> drafts.resume(g, m, faction, ch);
            case "undo" -> drafts.undo(g, m, faction, ch);
            case "adminpick" -> drafts.adminPick(g, m, faction, ch, str(e, "player"));
            case "replacecaptain" -> drafts.replaceCaptain(g, m, faction, ch, member(e, "old"), member(e, "new"));
            case "buildpool" -> drafts.buildPool(g, m, faction, ch);
            case "addplayer" -> drafts.addPlayer(g, m, faction, ch,
                    e.getOption("member") == null ? null : member(e, "member"),
                    e.getOption("torn_id") == null ? null : e.getOption("torn_id").getAsLong());
            case "removeplayer" -> {
                List<String> picks = new ArrayList<>();
                for (int i = 1; i <= 5; i++) {
                    String v = str(e, "player" + i);
                    if (v != null && !v.isBlank()) picks.add(v);
                }
                yield drafts.removePlayers(g, m, faction, ch, picks);
            }
            case "enddraft" -> {
                var f = drafts.checkEndDraft(m, faction, ch);
                yield new Confirm("End the **" + f.getName() + "** draft now? This can't be undone.",
                        Button.danger("end:" + f.getKey() + ":" + m.getId(), "Yes, end draft"),
                        Button.secondary("cancel:" + m.getId(), "No, keep going"));
            }
            case "pick" -> drafts.pick(g, m, ch, str(e, "player"));
            case "queue" -> drafts.queueAdd(g, m, ch, str(e, "player"));
            case "myqueue" -> drafts.queueShow(m, ch);
            case "cancelqueue" -> drafts.queueClear(m, ch);
            case "nameteam" -> drafts.nameTeam(g, m, ch, str(e, "name"),
                    e.getOption("captain") == null ? null : member(e, "captain"));
            case "draftstatus" -> drafts.status(m, faction, ch);
            case "viewcaptains" -> drafts.viewCaptains(g, m, faction, ch);
            case "remainingdraftees" -> drafts.remaining(m, faction, ch);
            case "teardownteams" -> {
                boolean clear = bool(e, "clear_captains", true);
                String summary = drafts.checkTeardown(m);
                yield new Confirm("This deletes **" + summary + "** — their roles, text and voice channels"
                        + (clear ? ", and removes the captain role from everyone" : "") + ". Continue?",
                        Button.danger("teardown:" + (clear ? 1 : 0) + ":" + m.getId(), "Yes, delete everything"),
                        Button.secondary("cancel:" + m.getId(), "Cancel"));
            }
            default -> throw new UserError("Unknown command.");
        };
    }

    @SuppressWarnings("unchecked")
    private void respond(InteractionHook hook, User user, Object result) {
        if (result instanceof String text) {
            dmOrReply(hook, user, text);
        } else if (result instanceof Confirm c) {
            hook.editOriginal(c.text()).setComponents(ActionRow.of(c.yes(), c.no()))
                    .queue(ok -> autoHide(hook, CONFIRM_SECONDS));
        } else if (result instanceof MessageEmbed embed) {
            hook.editOriginalEmbeds(embed).queue(ok -> autoHide(hook, VIEW_SECONDS));
        } else if (result instanceof List<?> list) {
            hook.editOriginalEmbeds((List<MessageEmbed>) list).queue(ok -> autoHide(hook, VIEW_SECONDS));
        } else {
            hook.editOriginal(String.valueOf(result)).queue(ok -> autoHide(hook, REPLY_SECONDS));
        }
    }

    /**
     * Confirmations go to the user's DMs and the private "thinking" reply is removed, so the draft
     * channel only ever shows the board. Falls back to the private reply if their DMs are closed.
     */
    private void dmOrReply(InteractionHook hook, User user, String text) {
        user.openPrivateChannel()
                .flatMap(pc -> pc.sendMessage(text))
                .queue(ok -> hook.deleteOriginal().queue(null, err -> {}),
                        err -> hook.editOriginal(text).setComponents().queue(ok -> autoHide(hook, REPLY_SECONDS)));
    }

    /** Removes a private reply after a delay so they don't pile up in the channel. */
    private static void autoHide(InteractionHook hook, int seconds) {
        hook.deleteOriginal().queueAfter(seconds, TimeUnit.SECONDS, null, err -> {});
    }

    // ---------- confirm buttons ----------

    @Override
    public void onButtonInteraction(ButtonInteractionEvent e) {
        String[] parts = e.getComponentId().split(":");
        String owner = parts[parts.length - 1];
        if (!e.getUser().getId().equals(owner)) {
            e.reply("That button isn't for you.").setEphemeral(true).queue();
            return;
        }
        if (parts[0].equals("cancel")) {
            e.editMessage("Cancelled.").setComponents().queue(ok -> autoHide(e.getHook(), 5));
            return;
        }
        e.deferEdit().queue();
        InteractionHook hook = e.getHook();
        workers.submit(() -> {
            String reply;
            try {
                reply = switch (parts[0]) {
                    case "end" -> drafts.endDraft(e.getGuild(), e.getMember(), parts[1]);
                    case "teardown" -> drafts.teardown(e.getGuild(), e.getMember(), parts[1].equals("1"));
                    default -> "Unknown action.";
                };
            } catch (UserError err) {
                reply = "❌ " + err.getMessage();
            } catch (Exception ex) {
                log.error("Button {} failed", e.getComponentId(), ex);
                reply = "⚠️ Something went wrong — the error has been logged.";
            }
            if (reply.startsWith("❌") || reply.startsWith("⚠️")) {
                hook.editOriginal(reply).setComponents().queue(ok -> autoHide(hook, ERROR_SECONDS));
            } else {
                dmOrReply(hook, e.getUser(), reply);
            }
        });
    }

    // ---------- autocomplete ----------

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent e) {
        if (!e.getFocusedOption().getName().startsWith("player") || e.getMember() == null) {
            e.replyChoices(List.of()).queue();
            return;
        }
        long ch = channelId(e.getChannel());
        String query = e.getFocusedOption().getValue().toLowerCase(Locale.ROOT);
        OptionMapping factionOpt = e.getOption("faction");
        String factionValue = factionOpt == null ? null : factionOpt.getAsString();

        BotProperties.Faction f = switch (e.getName()) {
            case "pick", "queue" -> drafts.captainFactionOrNull(e.getUser().getIdLong(), ch);
            case "adminpick", "removeplayer" -> drafts.leaderFactionOrNull(e.getMember(), factionValue, ch);
            default -> null;
        };
        DraftManager.Snapshot snap = f == null ? null : drafts.snapshot(f.getKey());
        if (snap == null) {
            e.replyChoices(List.of()).queue();
            return;
        }

        List<Command.Choice> choices = new ArrayList<>();
        addChoices(choices, snap.eligible(), query, "");
        if (e.getName().equals("removeplayer")) addChoices(choices, snap.drafted(), query, " (drafted)");
        e.replyChoices(choices).queue(null, err -> {});
    }

    private static void addChoices(List<Command.Choice> out, List<Player> players, String query, String suffix) {
        for (Player p : players) {
            if (out.size() >= 25) return;
            if (!query.isEmpty() && !p.display.toLowerCase(Locale.ROOT).contains(query)) continue;
            String label = p.display + (p.streak != null ? " · 🔥 " + p.streak + "d streak" : "")
                    + (p.onDiscord() ? "" : " · not on Discord") + suffix;
            if (label.length() > 100) label = label.substring(0, 100);
            out.add(new Command.Choice(label, String.valueOf(p.id)));
        }
    }

    // ---------- option helpers ----------

    private static long channelId(Channel channel) {
        return channel == null ? 0 : channel.getIdLong();
    }

    private static String str(SlashCommandInteractionEvent e, String name) {
        OptionMapping o = e.getOption(name);
        return o == null ? null : o.getAsString();
    }

    private static boolean bool(SlashCommandInteractionEvent e, String name, boolean def) {
        OptionMapping o = e.getOption(name);
        return o == null ? def : o.getAsBoolean();
    }

    private static Member member(SlashCommandInteractionEvent e, String name) {
        OptionMapping o = e.getOption(name);
        if (o == null) throw new UserError("Missing `" + name + "`.");
        Member m = o.getAsMember();
        if (m == null) throw new UserError(o.getAsUser().getName() + " isn't in this server.");
        return m;
    }
}