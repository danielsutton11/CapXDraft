package com.halloween.draft.discord;

import com.halloween.draft.competition.CompetitionRepository.ApiKey;
import com.halloween.draft.competition.CompetitionService;
import com.halloween.draft.config.BotProperties.Faction;
import com.halloween.draft.core.DraftManager;
import com.halloween.draft.core.DraftManager.CaptainInfo;
import com.halloween.draft.core.DraftManager.CaptainView;
import com.halloween.draft.core.DraftManager.PanelInfo;
import com.halloween.draft.core.UserError;
import com.halloween.draft.model.DraftStatus;
import com.halloween.draft.model.Player;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The /draft panels: a private (ephemeral) message whose buttons, dropdowns and pop-up forms drive
 * the whole draft. Leaders get the control panel, captains their team &amp; queue, everyone else a
 * status view.
 *
 * Panels use Discord's Components V2 layout: one coloured container holding section headings,
 * text, dividers and button rows, so related buttons sit under their own heading. A message is
 * limited to 40 components in total (text blocks, dividers, rows and buttons all count).
 *
 * Every component id is "dp:&lt;action&gt;:&lt;faction&gt;[:args]", so the panel carries its own state.
 */
@Component
public class PanelController {
    private static final Logger log = LoggerFactory.getLogger(PanelController.class);

    static final String PREFIX = "dp:";
    private static final int PANEL_MINUTES = 14;   // ephemeral messages can only be deleted for ~15 min
    private static final int POOL_PAGE = 30;
    private static final int[] TIMER_CHOICES = {0, 1, 2, 3, 5, 10, 15, 30, 60};
    private static final int BLURPLE = 0x5865F2;
    private static final int ORANGE = 0xE67E22;
    private static final int RED = 0xED4245;

    /** A screen: the top-level components that replace the panel's contents. */
    record View(List<MessageTopLevelComponent> components) {}

    /** Builds one panel: a coloured container of headings, text, dividers and button rows. */
    private static final class Ui {
        private final List<ContainerChildComponent> parts = new ArrayList<>();
        private final int accent;

        Ui(int accent) {
            this.accent = accent;
        }

        Ui text(String markdown) {
            if (markdown != null && !markdown.isBlank()) parts.add(TextDisplay.of(markdown));
            return this;
        }

        /** The result of the last action, as a quote block so it stands apart from the panel itself. */
        Ui notice(String text) {
            if (text == null || text.isBlank()) return this;
            return text("> " + text.strip().replace("\n", "\n> "));
        }

        Ui heading(String title) {
            return text("### " + title);
        }

        Ui divider() {
            if (!parts.isEmpty()) parts.add(Separator.createDivider(Separator.Spacing.SMALL));
            return this;
        }

        /** A row of buttons; nulls (buttons that can't be used yet) are skipped, and an empty row is dropped. */
        Ui buttons(Button... buttons) {
            List<Button> usable = new ArrayList<>();
            for (Button b : buttons) if (b != null) usable.add(b);
            if (!usable.isEmpty()) parts.add(ActionRow.of(usable));
            return this;
        }

        Ui menu(StringSelectMenu menu) {
            parts.add(ActionRow.of(menu));
            return this;
        }

        Ui menu(EntitySelectMenu menu) {
            parts.add(ActionRow.of(menu));
            return this;
        }

        View build() {
            return new View(List.of(Container.of(parts).withAccentColor(accent)));
        }
    }

    private final DraftManager drafts;
    private final CompetitionService competitions;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    /** Captains chosen in the "Set Captains" picker, waiting for keep-order / shuffle. */
    private final Map<String, List<Long>> pendingCaptains = new ConcurrentHashMap<>();

    public PanelController(DraftManager drafts, CompetitionService competitions) {
        this.drafts = drafts;
        this.competitions = competitions;
    }

    // =====================================================================
    // Opening the panel (/draft)
    // =====================================================================

    /** Called after /draft has been deferred (ephemeral). */
    public void open(InteractionHook hook, Guild g, Member m, String factionOption, long channelId) {
        try {
            Faction f = drafts.resolveViewFaction(m, factionOption, channelId);
            show(hook, openingScreen(g, m, f));
            hook.deleteOriginal().queueAfter(PANEL_MINUTES, TimeUnit.MINUTES, null, err -> {});
        } catch (UserError e) {
            hook.editOriginal("❌ " + e.getMessage()).queue(ok -> hook.deleteOriginal()
                    .queueAfter(20, TimeUnit.SECONDS, null, err -> {}));
        }
    }

    // =====================================================================
    // Event routing
    // =====================================================================

    public boolean handles(String componentId) {
        return componentId.startsWith(PREFIX);
    }

    public void onButton(ButtonInteractionEvent e) {
        String[] a = parts(e.getComponentId());
        String action = a[0];
        Faction f = drafts.faction(a[1]);
        Member m = e.getMember();
        Guild g = e.getGuild();
        long ch = channelId(e.getChannel());

        // Buttons that open a pop-up form must answer straight away with the modal.
        switch (action) {
            case "addtorn" -> { e.replyModal(textModal("maddtorn:" + f.getKey(), "Add player by Torn ID",
                    "Torn ID", "2087183", null, 1, 12)).queue(); return; }
            case "rm" -> { e.replyModal(searchModal("mrmq:" + f.getKey(), "Remove players")).queue(); return; }
            case "apick" -> { e.replyModal(searchModal("mapq:" + f.getKey(), "Admin pick")).queue(); return; }
            case "qadd" -> { e.replyModal(searchModal("mqq:" + f.getKey(), "Add to your queue")).queue(); return; }
            case "pk" -> { e.replyModal(searchModal("mpkq:" + f.getKey(), "Make your pick")).queue(); return; }
            case "ctimes" -> { e.replyModal(timesModal(f)).queue(); return; }
            case "keyadd" -> { e.replyModal(textModal("mkey:" + f.getKey(), "Add a faction API key",
                    "Limited key with Faction API Access", "16 letters and numbers", null, 16, 16)).queue(); return; }
            case "name" -> {
                if (!drafts.isLeader(m, f)) { // captains name their own team directly
                    e.replyModal(teamNameModal(f, m.getIdLong(), currentTeamName(f, m.getIdLong()))).queue();
                    return;
                }
            }
            case "close" -> {
                e.deferEdit().queue(ok -> e.getHook().deleteOriginal().queue(null, err -> {}));
                return;
            }
            default -> { }
        }

        e.deferEdit().queue();
        InteractionHook hook = e.getHook();
        run(hook, g, m, f, () -> switch (action) {
            case "home" -> home(g, m, f, null);
            case "setcap" -> setCaptainsView(f);
            case "setcapok" -> {
                List<Long> ids = pendingCaptains.remove(key(m, f));
                if (ids == null) throw new UserError("Pick the captains again — that selection expired.");
                List<Member> members = new ArrayList<>();
                for (Long id : ids) {
                    Member c = g.getMemberById(id);
                    if (c == null) throw new UserError("<@" + id + "> isn't in the server.");
                    members.add(c);
                }
                yield home(g, m, f, drafts.setCaptains(g, m, f.getKey(), ch, members, a[2].equals("1")));
            }
            case "name" -> nameTeamView(g, f);
            case "repl" -> replaceOldView(g, f);
            case "pool" -> poolView(m, f, Integer.parseInt(a[2]));
            case "add" -> addView(f);
            case "rebuild" -> home(g, m, f, drafts.buildPool(g, m, f.getKey(), ch));
            case "start" -> startView(f, 1, false, false);
            case "stauto" -> startView(f, Integer.parseInt(a[2]), !a[3].equals("1"), a[4].equals("1"));
            case "strand" -> startView(f, Integer.parseInt(a[2]), a[3].equals("1"), !a[4].equals("1"));
            case "stgo" -> home(g, m, f, drafts.startDraft(g, m, f.getKey(), ch,
                    Integer.parseInt(a[2]) * 60, a[3].equals("1"), a[4].equals("1")));
            case "pause" -> home(g, m, f, drafts.pause(g, m, f.getKey(), ch));
            case "resume" -> home(g, m, f, drafts.resume(g, m, f.getKey(), ch));
            case "undo" -> home(g, m, f, drafts.undo(g, m, f.getKey(), ch));
            case "end" -> confirmView(f, "🛑 End the draft?",
                    "This ends the **" + f.getName() + "** draft now and can't be undone. Teams so far stay on the board.",
                    Button.danger(id("endok", f), "🛑 Yes, end draft"));
            case "endok" -> home(g, m, f, drafts.endDraft(g, m, f.getKey()));
            case "td" -> {
                String summary = drafts.checkTeardown(m, f);
                yield new Ui(RED)
                        .text("## 🧹 Clean Up — " + f.getName())
                        .text("This deletes **" + summary + "**: their roles, captain roles, text and voice channels, "
                                + "the participant list, removals and competition times. It also clears the bot's board "
                                + "and messages from <#" + f.getChannelId() + "> and the " + f.getName()
                                + " scoreboard from the live scores channel.\n"
                                + "-# Other factions and other people's messages aren't touched.")
                        .divider()
                        .heading("Keep the captains for the next draft?")
                        .text("-# Keeping them saves choosing them again; they'll need to name new teams.")
                        .buttons(Button.danger(id("tdok", f, "1"), "Clean up everything"),
                                Button.danger(id("tdok", f, "0"), "Clean up, keep captains"))
                        .buttons(Button.secondary(id("home", f), "Back"))
                        .build();
            }
            case "tdok" -> home(g, m, f, drafts.teardown(g, m, f, a[2].equals("1")));
            case "my" -> captainHome(g, m, f, null);
            case "keys" -> keysView(f, null);
            case "qclr" -> captainHome(g, m, f, drafts.queueClear(m, ch));
            case "qrm" -> queueRemoveView(f, m);
            default -> home(g, m, f, "❌ Unknown action.");
        });
    }

    public void onStringSelect(StringSelectInteractionEvent e) {
        String[] a = parts(e.getComponentId());
        String action = a[0];
        Faction f = drafts.faction(a[1]);
        Member m = e.getMember();
        Guild g = e.getGuild();
        long ch = channelId(e.getChannel());
        List<String> values = e.getValues();

        if (action.equals("namecap")) { // choose captain → pop-up for the name
            long captainId = Long.parseLong(values.get(0));
            e.replyModal(teamNameModal(f, captainId, currentTeamName(f, captainId))).queue();
            return;
        }

        e.deferEdit().queue();
        run(e.getHook(), g, m, f, () -> switch (action) {
            case "replold" -> replaceNewView(g, f, Long.parseLong(values.get(0)));
            case "rmsel" -> home(g, m, f, drafts.removePlayers(g, m, f.getKey(), ch, values));
            case "apsel" -> home(g, m, f, drafts.adminPick(g, m, f.getKey(), ch, values.get(0)));
            case "qaddsel" -> captainHome(g, m, f, drafts.queueAdd(g, m, ch, values.get(0)));
            // The faction's own channel, so a captain in two factions picks for the panel they're using.
            case "pksel" -> captainHome(g, m, f, drafts.pick(g, m, f.getChannelId(), values.get(0)));
            case "qrmsel" -> captainHome(g, m, f, drafts.queueRemove(f, m.getIdLong(),
                    values.stream().map(Long::parseLong).toList()));
            case "sttimer" -> startView(f, Integer.parseInt(values.get(0)), a[2].equals("1"), a[3].equals("1"));
            case "keyrm" -> keysView(f, competitions.removeKey(m, f, Long.parseLong(values.get(0))));
            default -> home(g, m, f, "❌ Unknown action.");
        });
    }

    public void onEntitySelect(EntitySelectInteractionEvent e) {
        String[] a = parts(e.getComponentId());
        String action = a[0];
        Faction f = drafts.faction(a[1]);
        Member m = e.getMember();
        Guild g = e.getGuild();
        long ch = channelId(e.getChannel());
        List<Member> picked = e.getMentions().getMembers();

        e.deferEdit().queue();
        run(e.getHook(), g, m, f, () -> switch (action) {
            case "setcapusers" -> {
                if (picked.stream().anyMatch(x -> x.getUser().isBot())) throw new UserError("Bots can't be captains.");
                pendingCaptains.put(key(m, f), picked.stream().map(Member::getIdLong).toList());
                yield setCaptainsConfirmView(f, picked);
            }
            case "addusr" -> home(g, m, f, drafts.addPlayer(g, m, f.getKey(), ch, picked.get(0), null));
            case "replnew" -> {
                Member old = g.getMemberById(Long.parseLong(a[2]));
                if (old == null) throw new UserError("That captain isn't in the server any more.");
                yield home(g, m, f, drafts.replaceCaptain(g, m, f.getKey(), ch, old, picked.get(0)));
            }
            default -> home(g, m, f, "❌ Unknown action.");
        });
    }

    public void onModal(ModalInteractionEvent e) {
        String[] a = parts(e.getModalId());
        String action = a[0];
        Faction f = drafts.faction(a[1]);
        Member m = e.getMember();
        Guild g = e.getGuild();
        long ch = channelId(e.getChannel());
        String text = modalValue(e, "text");

        e.deferEdit().queue();
        run(e.getHook(), g, m, f, () -> switch (action) {
            case "mname" -> {
                long captainId = Long.parseLong(a[2]);
                Member captain = g.getMemberById(captainId);
                if (captain == null) throw new UserError("That captain isn't in the server any more.");
                String result = drafts.nameTeam(g, m, ch, text, captainId == m.getIdLong() ? null : captain);
                yield drafts.isLeader(m, f) ? home(g, m, f, result) : captainHome(g, m, f, result);
            }
            case "maddtorn" -> {
                if (!text.matches("\\d{1,10}")) throw new UserError("A Torn ID is just the number, like 2087183.");
                yield home(g, m, f, drafts.addPlayer(g, m, f.getKey(), ch, null, Long.parseLong(text)));
            }
            case "mrmq" -> resultsView(f, text, "rmsel", "Tick the players to remove", true, 25);
            case "mtimes" -> {
                String result = drafts.setCompetitionTimes(m, f.getKey(), ch,
                        parseTct(modalValue(e, "start")), parseTct(modalValue(e, "end")));
                workers.submit(() -> competitions.refreshScoreboard(f));
                yield home(g, m, f, result);
            }
            case "mkey" -> keysView(f, competitions.addKey(m, f, text));
            case "mapq" -> resultsView(f, text, "apsel", "Choose the player to pick", false, 1);
            case "mqq" -> resultsView(f, text, "qaddsel", "Choose a player to queue", false, 1);
            case "mpkq" -> resultsView(f, text, "pksel", "Choose your pick", false, 1);
            default -> home(g, m, f, "❌ Unknown action.");
        });
    }

    // =====================================================================
    // Home screens
    // =====================================================================

    /**
     * What /draft opens on. Same as {@link #home}, except a leader who's also a captain lands on
     * their captain panel while it's their pick (it has a 🎛️ Control Panel button back).
     */
    private View openingScreen(Guild g, Member m, Faction f) {
        if (drafts.isLeader(m, f) && isCaptain(m, f) && drafts.captainView(f, m.getIdLong()).onClock()) {
            return captainHome(g, m, f, null);
        }
        return home(g, m, f, null);
    }

    /** The right home screen for this member: leaders always get the control panel, then captains, then status. */
    View home(Guild g, Member m, Faction f, String notice) {
        if (drafts.isLeader(m, f)) return leaderHome(g, m, f, notice);
        if (isCaptain(m, f)) return captainHome(g, m, f, notice);
        return viewerHome(g, f, notice);
    }

    private boolean isCaptain(Member m, Faction f) {
        DraftManager.Snapshot snap = drafts.snapshot(f.getKey());
        return snap != null && snap.captains().contains(m.getIdLong());
    }

    private View leaderHome(Guild g, Member m, Faction f, String notice) {
        PanelInfo p = drafts.panelInfo(g, f);
        CompetitionService.Status comp = competitions.status(f);
        return p.running() ? leaderRunning(g, m, f, notice, p, comp) : leaderSetup(m, f, notice, p, comp);
    }

    /**
     * Before the draft. The four required steps sit together under "Setup", in order: done steps
     * get a ✅, the next one is blue. Start Draft appears under them once all four are done.
     */
    private View leaderSetup(Member m, Faction f, String notice, PanelInfo p, CompetitionService.Status comp) {
        boolean admin = m.hasPermission(Permission.ADMINISTRATOR);
        boolean isCaptain = p.captains().stream().anyMatch(c -> c.id() == m.getIdLong());
        int named = (int) p.captains().stream().filter(c -> c.team() != null).count();

        boolean captainsDone = p.captains().size() >= 2;
        boolean namesDone = captainsDone && p.allTeamsNamed();
        boolean timesDone = comp.start() != null;
        boolean keysDone = comp.keys() >= CompetitionService.MIN_KEYS;
        int done = (captainsDone ? 1 : 0) + (namesDone ? 1 : 0) + (timesDone ? 1 : 0) + (keysDone ? 1 : 0);
        // The first unfinished step, in order, is the one to highlight.
        int next = !captainsDone ? 0 : !namesDone ? 1 : !timesDone ? 2 : !keysDone ? 3 : -1;

        Ui ui = new Ui(BLURPLE).text("## 🎛️ " + f.getName() + " · Draft Control\n"
                + lastDraftLine(p) + "-# Not started · " + done + " of 4 setup steps done");
        ui.notice(notice);

        ui.divider().heading("📋 Setup — required, in order");
        ui.text(check(captainsDone, next == 0, "Captains", p.captains().isEmpty() ? "none yet"
                : String.join(", ", p.captains().stream().map(c -> "<@" + c.id() + ">").toList())) + "\n"
                + check(namesDone, next == 1, "Team names", p.captains().isEmpty() ? "after captains"
                : named + " of " + p.captains().size()) + "\n"
                + check(timesDone, next == 2, "Competition times", timesDone
                ? DraftManager.tct(comp.start()) + " → " + DraftManager.tct(comp.end()) + " TCT" : "not set") + "\n"
                + check(keysDone, next == 3, "API keys", comp.keys() + " of " + CompetitionService.MIN_KEYS));
        ui.buttons(
                step(captainsDone, next == 0, id("setcap", f), "👑", "Captains"),
                step(namesDone, next == 1, id("name", f), "🏷️", "Team Names"),
                step(timesDone, next == 2, id("ctimes", f), "🗓️", "Competition Times"),
                step(keysDone, next == 3, id("keys", f), "🔑",
                        "API Keys (" + comp.keys() + "/" + CompetitionService.MIN_KEYS + ")"));
        ui.buttons(next == -1 ? Button.success(id("start", f), "▶️ Start Draft") : null);

        ui.divider().heading("👥 Participants");
        if (p.poolPrepared()) {
            ui.text("**" + p.poolSize() + "** players · " + p.poolOffDiscord() + " not on Discord · "
                    + p.excluded() + " excluded");
            ui.buttons(Button.secondary(id("pool", f, "0"), "📋 View"),
                    Button.secondary(id("add", f), "➕ Add"),
                    Button.secondary(id("rm", f), "➖ Remove"),
                    Button.secondary(id("rebuild", f), "🔄 Rebuild List"));
        } else {
            ui.text("-# The list is built when captains are set.");
        }

        if (!p.captains().isEmpty()) {
            ui.divider().heading("👑 Captains");
            ui.text(captainLines(p, false));
            ui.buttons(Button.secondary(id("repl", f), "🔁 Replace Captain"),
                    isCaptain ? Button.secondary(id("my", f), "🎯 My Team") : null);
        }

        if (comp.start() != null) { // before that, the setup checklist already says what's missing
            ui.divider().heading("🏁 Competition");
            ui.text(competitionLines(comp, false));
        }

        ui.divider();
        ui.buttons(admin ? Button.danger(id("td", f), "🧹 Clean Up") : null,
                Button.secondary(id("close", f), "✖ Close"));
        ui.text("-# Only you can see this panel · it closes after " + PANEL_MINUTES + " minutes");
        return ui.build();
    }

    /** During the draft: draft controls first, then participants, teams and the competition. */
    private View leaderRunning(Guild g, Member m, Faction f, String notice, PanelInfo p, CompetitionService.Status comp) {
        boolean isCaptain = p.captains().stream().anyMatch(c -> c.id() == m.getIdLong());
        boolean paused = p.status() == DraftStatus.PAUSED;

        Ui ui = new Ui(BLURPLE).text("## 🎛️ " + f.getName() + " · Draft Control");
        ui.notice(notice);
        ui.text(clockLine(p) + "\n-# " + p.picksMade() + " picks made · "
                + (p.timerSeconds() > 0 ? p.timerSeconds() / 60 + " min timer" : "no timer")
                + " · auto-pick " + (p.autopick() ? "on" : "off"));
        boolean myPick = !paused && p.onClock() != null && p.onClock() == m.getIdLong();
        ui.buttons(myPick ? Button.success(id("pk", f), "🎯 Pick Player") : null,
                paused ? Button.success(id("resume", f), "▶️ Resume") : Button.primary(id("pause", f), "⏸️ Pause"),
                p.picksMade() > 0 ? Button.secondary(id("undo", f), "↩️ Undo") : null,
                p.poolSize() > 0 ? Button.secondary(id("apick", f), "🛡️ Admin Pick") : null);

        ui.divider().heading("👥 Participants");
        ui.text("**" + p.poolSize() + "** left to pick · " + p.poolOffDiscord() + " not on Discord");
        ui.buttons(Button.secondary(id("pool", f, "0"), "📋 View"),
                Button.secondary(id("add", f), "➕ Add"),
                p.poolSize() > 0 || p.picksMade() > 0 ? Button.secondary(id("rm", f), "➖ Remove") : null);

        ui.divider().heading("👑 Teams");
        ui.text(captainLines(p, true));
        ui.buttons(Button.secondary(id("repl", f), "🔁 Replace Captain"),
                Button.secondary(id("name", f), "🏷️ Team Names"),
                isCaptain ? Button.secondary(id("my", f), "🎯 My Team") : null);

        ui.divider().heading("🏁 Competition");
        ui.text(competitionLines(comp, true));
        ui.buttons(Button.secondary(id("ctimes", f), "🗓️ Competition Times"),
                Button.secondary(id("keys", f), "🔑 API Keys (" + comp.keys() + ")"));

        ui.divider();
        ui.buttons(Button.danger(id("end", f), "🛑 End Draft"), Button.secondary(id("close", f), "✖ Close"));
        ui.text("-# Only you can see this panel · it closes after " + PANEL_MINUTES + " minutes");
        return ui.build();
    }

    private View viewerHome(Guild g, Faction f, String notice) {
        PanelInfo p = drafts.panelInfo(g, f);
        Ui ui = new Ui(BLURPLE).text("## 🏆 " + f.getName() + " · Draft");
        ui.notice(notice);
        ui.text(p.running() ? clockLine(p) : lastDraftLine(p) + "**Not started**");
        if (!p.captains().isEmpty()) ui.divider().heading("👑 Teams").text(captainLines(p, p.running()));
        if (p.poolPrepared()) {
            ui.divider().heading("👥 Participants")
                    .text("**" + p.poolSize() + "** " + (p.running() ? "left to pick" : "players"));
        }
        ui.divider().buttons(p.poolPrepared() ? Button.secondary(id("pool", f, "0"), "📋 View Participants") : null,
                Button.secondary(id("close", f), "✖ Close"));
        return ui.build();
    }

    View captainHome(Guild g, Member m, Faction f, String notice) {
        CaptainView c = drafts.captainView(f, m.getIdLong());
        Ui ui = new Ui(ORANGE).text("## 🎯 " + f.getName() + " · " + (c.team() == null ? "Your Team" : c.team()));
        ui.notice(notice);

        ui.heading("📍 Status");
        if (c.team() == null) ui.text("⚠️ **Your team needs a name** before the draft can start.");
        ui.text(c.running() ? turnLine(c) : "The draft hasn't started yet. Your queue and picks show here once it does.");
        if (c.onClock() && !c.paused()) ui.buttons(Button.success(id("pk", f), "🎯 Pick Player"));

        if (c.running()) {
            // Queue first: it's what captains act on, and it stays put as the picks list grows.
            ui.divider().heading("📋 Your queue (" + c.queue().size() + "/5)");
            ui.text(c.queue().isEmpty()
                    ? "-# Empty. Queued players are picked for you automatically when your turn starts."
                    : numbered(c.queue()));
            ui.buttons(c.queue().size() < 5 ? style(!c.onClock(), id("qadd", f), "➕ Queue Player") : null,
                    c.queue().isEmpty() ? null : Button.secondary(id("qrm", f), "➖ Unqueue"),
                    c.queue().isEmpty() ? null : Button.secondary(id("qclr", f), "🗑️ Clear"));

            ui.divider().heading("🧾 Your picks (" + c.picks().size() + ")");
            ui.text(c.picks().isEmpty() ? "-# None yet." : bullets(c.picks(), 20)
                    + (c.picks().size() > 20 ? "-# …and " + (c.picks().size() - 20) + " more" : ""));
        }

        boolean poolReady = !drafts.poolList(f).isEmpty();
        ui.divider();
        ui.buttons(style(c.team() == null, id("name", f), c.team() == null ? "🏷️ Name Team" : "🏷️ Rename Team"),
                poolReady ? Button.secondary(id("pool", f, "0"), "📋 Participants") : null,
                drafts.isLeader(m, f) ? Button.secondary(id("home", f), "🎛️ Control Panel")
                        : Button.secondary(id("my", f), "🔄 Refresh"),
                Button.secondary(id("close", f), "✖ Close"));
        return ui.build();
    }

    // =====================================================================
    // Sub-screens
    // =====================================================================

    private View setCaptainsView(Faction f) {
        return new Ui(BLURPLE)
                .text("## 👑 Set Captains")
                .text("Choose 2–9 captains. Setting captains also builds the participant list — "
                        + "anyone removed from it earlier stays out.")
                .menu(EntitySelectMenu.create(id("setcapusers", f), EntitySelectMenu.SelectTarget.USER)
                        .setPlaceholder("Choose captains").setRequiredRange(2, 9).build())
                .buttons(Button.secondary(id("home", f), "Back"))
                .build();
    }

    private View setCaptainsConfirmView(Faction f, List<Member> picked) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < picked.size(); i++) {
            sb.append(i + 1).append(". ").append(picked.get(i).getAsMention()).append('\n');
        }
        return new Ui(BLURPLE)
                .text("## 👑 Confirm Captains")
                .heading("Pick order")
                .text(sb.toString())
                .buttons(Button.success(id("setcapok", f, "0"), "✅ Use this order"),
                        Button.primary(id("setcapok", f, "1"), "🔀 Shuffle order"))
                .buttons(Button.secondary(id("setcap", f), "Choose again"), Button.secondary(id("home", f), "Back"))
                .build();
    }

    private View nameTeamView(Guild g, Faction f) {
        PanelInfo p = drafts.panelInfo(g, f);
        if (p.captains().isEmpty()) throw new UserError("Choose captains first — 👑 Captains.");
        StringSelectMenu.Builder menu = StringSelectMenu.create(id("namecap", f)).setPlaceholder("Choose a captain");
        for (CaptainInfo c : p.captains()) {
            menu.addOptions(SelectOption.of(trim(c.name()), String.valueOf(c.id()))
                    .withDescription(trim(c.team() == null ? "No team yet" : "Team: " + c.team())));
        }
        return new Ui(BLURPLE)
                .text("## 🏷️ Team Names")
                .text("Choose the captain whose team you're naming. Captains can also name their own team from `/draft`.")
                .menu(menu.build())
                .buttons(Button.secondary(id("home", f), "Back"))
                .build();
    }

    private View replaceOldView(Guild g, Faction f) {
        PanelInfo p = drafts.panelInfo(g, f);
        if (p.captains().isEmpty()) throw new UserError("There are no captains to replace.");
        StringSelectMenu.Builder menu = StringSelectMenu.create(id("replold", f)).setPlaceholder("Captain to replace");
        for (CaptainInfo c : p.captains()) {
            menu.addOptions(SelectOption.of(trim(c.name()), String.valueOf(c.id()))
                    .withDescription(trim(c.team() == null ? "No team yet" : "Team: " + c.team())));
        }
        return new Ui(BLURPLE)
                .text("## 🔁 Replace Captain")
                .text("Who's being replaced? The new captain takes over their team, queue and turn.")
                .menu(menu.build())
                .buttons(Button.secondary(id("home", f), "Back"))
                .build();
    }

    private View replaceNewView(Guild g, Faction f, long oldId) {
        Member old = g.getMemberById(oldId);
        return new Ui(BLURPLE)
                .text("## 🔁 Replace Captain")
                .text("Replacing **" + (old == null ? oldId : old.getEffectiveName()) + "**. Choose the new captain.")
                .menu(EntitySelectMenu.create(id("replnew", f, String.valueOf(oldId)), EntitySelectMenu.SelectTarget.USER)
                        .setPlaceholder("New captain").setRequiredRange(1, 1).build())
                .buttons(Button.secondary(id("home", f), "Back"))
                .build();
    }

    private View poolView(Member m, Faction f, int page) {
        List<Player> pool = drafts.poolList(f);
        int pages = Math.max(1, (pool.size() + POOL_PAGE - 1) / POOL_PAGE);
        int pg = Math.max(0, Math.min(page, pages - 1));
        long off = pool.stream().filter(p -> !p.onDiscord()).count();
        return new Ui(BLURPLE)
                .text("## 📋 " + f.getName() + " participants — " + pool.size())
                .text(pool.isEmpty() ? "-# No participants."
                        : bullets(pool.subList(pg * POOL_PAGE, Math.min(pool.size(), (pg + 1) * POOL_PAGE)), POOL_PAGE))
                .text("-# Page " + (pg + 1) + " of " + pages + " · " + off + " not on Discord")
                .buttons(pg > 0 ? Button.secondary(id("pool", f, String.valueOf(pg - 1)), "◀ Previous") : null,
                        pg < pages - 1 ? Button.secondary(id("pool", f, String.valueOf(pg + 1)), "Next ▶") : null,
                        Button.secondary(backId(m, f), "Back"))
                .build();
    }

    private View addView(Faction f) {
        return new Ui(BLURPLE)
                .text("## ➕ Add Participant")
                .heading("Discord member")
                .menu(EntitySelectMenu.create(id("addusr", f), EntitySelectMenu.SelectTarget.USER)
                        .setPlaceholder("Choose a member").setRequiredRange(1, 1).build())
                .heading("Not on Discord")
                .buttons(Button.primary(id("addtorn", f), "🔢 Add by Torn ID"))
                .divider()
                .buttons(Button.secondary(id("home", f), "Back"))
                .build();
    }

    /** Search results as a dropdown. {@code action} is the select's id, {@code max} how many can be ticked. */
    private View resultsView(Faction f, String query, String action, String placeholder, boolean includeDrafted, int max) {
        List<Player> found = drafts.search(f, query, includeDrafted);
        String back = action.startsWith("q") || action.startsWith("pk") ? id("my", f) : id("home", f);
        if (found.isEmpty()) {
            return new Ui(BLURPLE)
                    .text("## 🔎 No matches")
                    .text("Nobody matches `" + query + "`. Try part of the name or a Torn ID.")
                    .buttons(Button.secondary(back, "Back"))
                    .build();
        }
        StringSelectMenu.Builder menu = StringSelectMenu.create(id(action, f)).setPlaceholder(placeholder)
                .setRequiredRange(1, Math.min(max, found.size()));
        for (Player p : found) {
            String desc = (p.streak != null ? "🔥 " + p.streak + "d streak" : "Streak loading…")
                    + (p.onDiscord() ? "" : " · not on Discord");
            menu.addOptions(SelectOption.of(trim(p.display), String.valueOf(p.id)).withDescription(desc));
        }
        return new Ui(BLURPLE)
                .text("## 🔎 Results for `" + (query.isBlank() ? "everyone" : query) + "`")
                .text(found.size() + " match" + (found.size() == 1 ? "" : "es") + "."
                        + (found.size() >= 25 ? "\n-# Showing the first 25 — search more specifically to narrow it down." : ""))
                .menu(menu.build())
                .buttons(Button.secondary(back, "Back"))
                .build();
    }

    private View queueRemoveView(Faction f, Member m) {
        CaptainView c = drafts.captainView(f, m.getIdLong());
        if (c.queue().isEmpty()) return captainHome(m.getGuild(), m, f, "Your queue is already empty.");
        StringSelectMenu.Builder menu = StringSelectMenu.create(id("qrmsel", f)).setPlaceholder("Tick players to remove")
                .setRequiredRange(1, c.queue().size());
        for (Player p : c.queue()) menu.addOptions(SelectOption.of(trim(p.display), String.valueOf(p.id)));
        return new Ui(ORANGE)
                .text("## ➖ Unqueue")
                .text("Tick the players to take out of your queue.")
                .menu(menu.build())
                .buttons(Button.secondary(id("my", f), "Back"))
                .build();
    }

    private View startView(Faction f, int minutes, boolean autopick, boolean random) {
        String a = autopick ? "1" : "0", r = random ? "1" : "0";
        StringSelectMenu.Builder timer = StringSelectMenu.create(id("sttimer", f, a, r)).setPlaceholder("Pick timer");
        for (int t : TIMER_CHOICES) {
            String label = t == 0 ? "No timer" : t + " minute" + (t == 1 ? "" : "s") + " per pick";
            timer.addOptions(SelectOption.of(label, String.valueOf(t)).withDefault(t == minutes));
        }
        String m = String.valueOf(minutes);
        return new Ui(BLURPLE)
                .text("## ▶️ Start " + f.getName() + " Draft")
                .heading("⏱️ Pick timer")
                .menu(timer.build())
                .heading("⚙️ Options")
                .text("**Auto-pick on timeout:** " + (autopick ? "on — queue first, then a random player" : "off — the draft waits") + "\n"
                        + "**Captain order:** " + (random ? "shuffled at start" : "as set"))
                .buttons(Button.secondary(id("stauto", f, m, a, r), autopick ? "🤖 Auto-pick: On" : "🤖 Auto-pick: Off"),
                        Button.secondary(id("strand", f, m, a, r), random ? "🔀 Order: Shuffle" : "📋 Order: As set"))
                .divider()
                .buttons(Button.success(id("stgo", f, m, a, r), "✅ Start Draft"), Button.secondary(id("home", f), "Back"))
                .build();
    }

    private View confirmView(Faction f, String title, String text, Button yes) {
        return new Ui(RED)
                .text("## " + title)
                .text(text)
                .buttons(yes, Button.secondary(id("home", f), "Back"))
                .build();
    }

    private View keysView(Faction f, String notice) {
        List<ApiKey> keys = competitions.keys(f);
        StringBuilder sb = new StringBuilder();
        if (keys.isEmpty()) sb.append("-# No keys yet.");
        for (ApiKey k : keys) {
            sb.append("• **").append(k.ownerName()).append("** [").append(k.ownerTornId()).append("] — ");
            if (k.lastError() != null) sb.append("⚠️ ").append(k.lastError());
            else sb.append("✅ working").append(k.lastOkAt() == null ? ""
                    : " · last used <t:" + k.lastOkAt().getEpochSecond() + ":R>");
            sb.append('\n');
        }
        Ui ui = new Ui(BLURPLE)
                .text("## 🔑 " + f.getName() + " · API Keys")
                .notice(notice)
                .text("Keys read **outgoing faction attacks** during the competition. Each must be a **Limited** key from a "
                        + f.getName() + " member with **Faction API Access**, and at least " + CompetitionService.MIN_KEYS
                        + " are needed. Keys are stored encrypted and never shown again.")
                .divider()
                .heading("Keys (" + keys.size() + ")")
                .text(sb.toString());
        if (!keys.isEmpty()) {
            StringSelectMenu.Builder menu = StringSelectMenu.create(id("keyrm", f)).setPlaceholder("Remove a key…");
            for (ApiKey k : keys) {
                menu.addOptions(SelectOption.of(trim(k.ownerName() + " [" + k.ownerTornId() + "]"), String.valueOf(k.id())));
            }
            ui.menu(menu.build());
        }
        return ui.buttons(Button.primary(id("keyadd", f), "➕ Add Key"), Button.secondary(id("home", f), "Back")).build();
    }

    // =====================================================================
    // Text pieces
    // =====================================================================

    /** Where a captain stands: on the clock (with countdown), paused, or how many picks away. */
    private static String turnLine(CaptainView c) {
        String timer = c.deadlineMs() == null ? "" : " · ⏱ <t:" + c.deadlineMs() / 1000 + ":R>";
        if (c.paused()) {
            String left = c.pausedRemainingMs() == null ? "" : " · " + Math.max(1, c.pausedRemainingMs() / 1000) + "s left on the clock";
            return c.onClock() ? "⏸️ **Draft paused** — you're on the clock when it resumes" + left
                    : "⏸️ **Draft paused** — <@" + c.clockCaptain() + "> is up next" + left;
        }
        if (c.onClock()) return "🎯 **You're on the clock!** This is pick #" + c.nextPick() + timer;
        if (c.picksAway() == null) return "✅ You've no picks left — there aren't enough players for another round.";
        String away = c.picksAway() == 1 ? "**You're next**" : "**" + c.picksAway() + " picks away**";
        return "⏳ " + away + " — your next pick is #" + c.nextPick()
                + "\n-# On the clock: <@" + c.clockCaptain() + ">" + timer;
    }

    private static String clockLine(PanelInfo p) {
        if (p.status() == DraftStatus.PAUSED) return "⏸️ **Paused** — <@" + p.onClock() + "> is up next";
        String s = "🎯 **On the clock:** <@" + p.onClock() + ">";
        if (p.deadlineMs() != null) s += " · ⏱ <t:" + p.deadlineMs() / 1000 + ":R>";
        return s;
    }

    private static String lastDraftLine(PanelInfo p) {
        if (p.status() == DraftStatus.COMPLETE) return "-# ✅ The last draft is complete.\n";
        if (p.status() == DraftStatus.ENDED) return "-# 🛑 The last draft was ended early.\n";
        return "";
    }

    private static String captainLines(PanelInfo p, boolean withPicks) {
        if (p.captains().isEmpty()) return "-# None yet.";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p.captains().size(); i++) {
            CaptainInfo c = p.captains().get(i);
            sb.append(i + 1).append(". <@").append(c.id()).append("> — ")
                    .append(c.team() == null ? "⚠️ *no team name*" : "**" + c.team() + "**");
            if (withPicks) sb.append(" · ").append(c.picks()).append(" picks");
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Competition status. {@code withTimes}: include the window (it's in the setup checklist before the draft). */
    private static String competitionLines(CompetitionService.Status st, boolean withTimes) {
        StringBuilder sb = new StringBuilder();
        if (st.start() == null) {
            sb.append("Not scheduled\n");
        } else {
            if (withTimes) {
                sb.append("🗓️ ").append(DraftManager.tct(st.start())).append(" → ")
                        .append(DraftManager.tct(st.end())).append(" TCT\n");
            }
            if (st.finalPosted()) sb.append("🏁 Finished — final scoreboard posted\n");
            else if (st.live()) sb.append("🟢 **Live** — ends <t:").append(st.end()).append(":R>\n");
            else if (st.over()) sb.append("⏱️ Finished — collecting the last hits\n");
            else sb.append("⏳ Starts <t:").append(st.start()).append(":R>\n");
        }
        sb.append("🔑 ").append(st.keysWorking()).append('/').append(st.keys()).append(" keys working");
        if (st.lastCheckedMs() != null) sb.append(" · checked <t:").append(st.lastCheckedMs() / 1000).append(":R>");
        if (st.lastError() != null) sb.append("\n⚠️ ").append(st.lastError());
        if (st.missingTornIds() > 0) {
            sb.append("\n⚠️ ").append(st.missingTornIds()).append(" drafted player")
                    .append(st.missingTornIds() == 1 ? " has" : "s have")
                    .append(" no `[ID]` in their nickname, so their hits can't be counted.");
        }
        return sb.toString();
    }

    /** A checklist line: ✅ when done, ▫️ when not; only the next step is bold. */
    private static String check(boolean done, boolean next, String step, String detail) {
        if (done) return "✅ " + step + " · " + detail;
        return next ? "▫️ **" + step + "** · " + detail : "▫️ " + step + " · " + detail;
    }

    /** A required-step button: ✅ once done (grey), blue when it's the next step, grey otherwise. */
    private static Button step(boolean done, boolean next, String id, String emoji, String label) {
        if (done) return Button.secondary(id, "✅ " + label);
        return next ? Button.primary(id, emoji + " " + label) : Button.secondary(id, emoji + " " + label);
    }

    /** Coloured (primary) when it's the recommended next step, grey otherwise. */
    private static Button style(boolean highlight, String id, String label) {
        return highlight ? Button.primary(id, label) : Button.secondary(id, label);
    }

    // =====================================================================
    // Modals
    // =====================================================================

    private Modal teamNameModal(Faction f, long captainId, String current) {
        return textModal("mname:" + f.getKey() + ":" + captainId, current == null ? "Name Team" : "Rename Team",
                "Team name", "2–40 characters", current, 2, 40);
    }

    private static Modal searchModal(String id, String title) {
        return textModal(id, title, "Search", "Part of a name or a Torn ID (blank = everyone)", null, 0, 40);
    }

    private static Modal textModal(String id, String title, String label, String placeholder, String value, int min, int max) {
        TextInput.Builder input = TextInput.create("text", TextInputStyle.SHORT)
                .setPlaceholder(placeholder)
                .setRequired(min > 0)
                .setRequiredRange(min, max);
        if (value != null && !value.isBlank()) input.setValue(value);
        return Modal.create(PREFIX + id, title).addComponents(Label.of(label, input.build())).build();
    }

    private Modal timesModal(Faction f) {
        DraftManager.Snapshot snap = drafts.snapshot(f.getKey());
        Long start = snap == null ? null : snap.compStart();
        Long end = snap == null ? null : snap.compEnd();
        TextInput.Builder s = TextInput.create("start", TextInputStyle.SHORT)
                .setPlaceholder("25-10-2026 18:00").setRequired(false).setRequiredRange(0, 16);
        TextInput.Builder e = TextInput.create("end", TextInputStyle.SHORT)
                .setPlaceholder("27-10-2026 18:00").setRequired(false).setRequiredRange(0, 16);
        if (start != null) s.setValue(DraftManager.tct(start));
        if (end != null) e.setValue(DraftManager.tct(end));
        return Modal.create(PREFIX + "mtimes:" + f.getKey(), "Competition times (TCT)")
                .addComponents(Label.of("Start (TCT)", s.build()), Label.of("End (TCT)", e.build()))
                .build();
    }

    /** From the lock-free snapshot: this runs on the event thread, which must answer within 3 seconds. */
    private String currentTeamName(Faction f, long captainId) {
        DraftManager.Snapshot snap = drafts.snapshot(f.getKey());
        return snap == null ? null : snap.teamNames().get(captainId);
    }

    /** "25-10-2026 18:00" (TCT = UTC) → epoch seconds; blank → null. */
    private static Long parseTct(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return java.time.LocalDateTime.parse(text.trim().replace('T', ' '),
                            java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"))
                    .toEpochSecond(java.time.ZoneOffset.UTC);
        } catch (Exception ex) {
            throw new UserError("Use the format `25-10-2026 18:00` for times (TCT). Got: `" + text + "`.");
        }
    }

    private static String modalValue(ModalInteractionEvent e, String id) {
        var v = e.getValue(id);
        return v == null ? "" : v.getAsString().trim();
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /** Runs an action off the event thread and puts its screen (or the error) on the panel. */
    private void run(InteractionHook hook, Guild g, Member m, Faction f, Supplier<View> action) {
        workers.submit(() -> {
            View view;
            try {
                view = action.get();
            } catch (UserError e) {
                view = safeHome(g, m, f, "❌ " + e.getMessage());
            } catch (Exception e) {
                log.error("Panel action failed", e);
                view = safeHome(g, m, f, "⚠️ Something went wrong — the error has been logged.");
            }
            show(hook, view);
        });
    }

    private View safeHome(Guild g, Member m, Faction f, String notice) {
        try {
            return home(g, m, f, notice);
        } catch (Exception e) {
            return new Ui(RED).text(notice).buttons(Button.secondary(id("close", f), "✖ Close")).build();
        }
    }

    private static void show(InteractionHook hook, View v) {
        hook.editOriginalComponents(v.components()).useComponentsV2().queue(null,
                err -> log.warn("Couldn't update panel: {}", err.getMessage()));
    }

    private String backId(Member m, Faction f) {
        if (drafts.isLeader(m, f)) return id("home", f);
        DraftManager.Snapshot snap = drafts.snapshot(f.getKey());
        return snap != null && snap.captains().contains(m.getIdLong()) ? id("my", f) : id("home", f);
    }

    private static String id(String action, Faction f, String... args) {
        StringBuilder sb = new StringBuilder(PREFIX).append(action).append(':').append(f.getKey());
        for (String a : args) sb.append(':').append(a);
        return sb.toString();
    }

    /** "dp:action:faction:x:y" → ["action", "faction", "x", "y"] */
    private static String[] parts(String componentId) {
        return componentId.substring(PREFIX.length()).split(":");
    }

    private static String key(Member m, Faction f) {
        return m.getId() + ":" + f.getKey();
    }

    private static long channelId(Channel channel) {
        return channel == null ? 0 : channel.getIdLong();
    }

    private static String trim(String s) {
        return s.length() > 100 ? s.substring(0, 100) : s;
    }

    private static String bullets(List<Player> players, int limit) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Player p : players) {
            if (n++ >= limit) break;
            sb.append("• ").append(p.display).append(p.streakLabel())
                    .append(p.onDiscord() ? "" : " *(not on Discord)*").append('\n');
        }
        return sb.toString();
    }

    private static String numbered(List<Player> players) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            sb.append(i + 1).append(". ").append(p.display).append(p.streakLabel())
                    .append(p.onDiscord() ? "" : " *(not on Discord)*").append('\n');
        }
        return sb.toString();
    }
}