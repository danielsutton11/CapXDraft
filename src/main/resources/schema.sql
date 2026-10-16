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
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.LayoutComponent;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.interactions.components.selections.SelectOption;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
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
 * status view. Every component id is "dp:&lt;action&gt;:&lt;faction&gt;[:args]", so the panel itself
 * carries all the state it needs.
 */
@Component
public class PanelController {
    private static final Logger log = LoggerFactory.getLogger(PanelController.class);

    static final String PREFIX = "dp:";
    private static final int PANEL_MINUTES = 14;   // ephemeral messages can only be deleted for ~15 min
    private static final int POOL_PAGE = 30;
    private static final int[] TIMER_CHOICES = {0, 1, 2, 3, 5, 10, 15, 30, 60};
    private static final int BLURPLE = 0x5865F2;

    /** A screen: the embeds and component rows that replace the panel's contents. */
    record View(List<MessageEmbed> embeds, List<LayoutComponent> rows) {
        static View of(MessageEmbed embed, List<LayoutComponent> rows) {
            return new View(List.of(embed), rows);
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
            show(hook, home(g, m, f, null));
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
                    "Torn ID", "e.g. 2087183", null, 1, 12)).queue(); return; }
            case "rm" -> { e.replyModal(searchModal("mrmq:" + f.getKey(), "Remove players")).queue(); return; }
            case "apick" -> { e.replyModal(searchModal("mapq:" + f.getKey(), "Admin pick")).queue(); return; }
            case "qadd" -> { e.replyModal(searchModal("mqq:" + f.getKey(), "Add to your queue")).queue(); return; }
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
            case "pool" -> poolView(g, m, f, Integer.parseInt(a[2]));
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
            case "end" -> confirmView(f, "🛑 End the **" + f.getName() + "** draft now? This can't be undone.",
                    Button.danger(id("endok", f), "Yes, end draft"));
            case "endok" -> home(g, m, f, drafts.endDraft(g, m, f.getKey()));
            case "td" -> {
                String summary = drafts.checkTeardown(m);
                yield new View(List.of(embed("🧹 Teardown — all factions",
                        "This deletes **" + summary + "**: their roles, captain roles, text and voice channels, "
                                + "the prepared participant lists and removals.\nRemove the Halloween Captain role from everyone too?")),
                        List.of(ActionRow.of(
                                Button.danger(id("tdok", f, "1"), "Delete all + clear captains"),
                                Button.danger(id("tdok", f, "0"), "Delete all, keep captains"),
                                Button.secondary(id("home", f), "Back"))));
            }
            case "tdok" -> home(g, m, f, drafts.teardown(g, m, a[2].equals("1")));
            case "my" -> captainHome(g, m, f, null);
            case "keys" -> keysView(m, f, null);
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
            case "qrmsel" -> captainHome(g, m, f, drafts.queueRemove(f, m.getIdLong(),
                    values.stream().map(Long::parseLong).toList()));
            case "sttimer" -> startView(f, Integer.parseInt(values.get(0)), a[2].equals("1"), a[3].equals("1"));
            case "keyrm" -> keysView(m, f, competitions.removeKey(m, f, Long.parseLong(values.get(0))));
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
                if (!text.matches("\\d{1,10}")) throw new UserError("A Torn ID is just the number, e.g. 2087183.");
                yield home(g, m, f, drafts.addPlayer(g, m, f.getKey(), ch, null, Long.parseLong(text)));
            }
            case "mrmq" -> resultsView(f, text, "rmsel", "Tick the players to remove", true, 25);
            case "mtimes" -> {
                String result = drafts.setCompetitionTimes(m, f.getKey(), ch,
                        parseTct(modalValue(e, "start")), parseTct(modalValue(e, "end")));
                workers.submit(() -> competitions.refreshScoreboard(f));
                yield home(g, m, f, result);
            }
            case "mkey" -> keysView(m, f, competitions.addKey(m, f, text));
            case "mapq" -> resultsView(f, text, "apsel", "Choose the player to pick", false, 1);
            case "mqq" -> resultsView(f, text, "qaddsel", "Choose a player to queue", false, 1);
            default -> home(g, m, f, "❌ Unknown action.");
        });
}

    // =====================================================================
    // Screens
    // =====================================================================

    /** The right home screen for this member: leader control panel, captain panel, or status. */
    View home(Guild g, Member m, Faction f, String notice) {
        DraftManager.Snapshot snap = drafts.snapshot(f.getKey());
        boolean captain = snap != null && snap.captains().contains(m.getIdLong());
        if (drafts.isLeader(m, f)) {
            // A leader who's also a captain gets their own panel while it's their pick.
            if (captain && drafts.captainView(f, m.getIdLong()).onClock()) return captainHome(g, m, f, notice);
return leaderHome(g, m, f, notice);
}
        if (captain) return captainHome(g, m, f, notice);
return viewerHome(g, f, notice);
}

    /**
     * Leader control panel. Buttons are grouped by job (setup / pool / go), grey by default, with
     * the single recommended next step coloured so it's obvious what to do.
     */
    private View leaderHome(Guild g, Member m, Faction f, String notice) {
        PanelInfo p = drafts.panelInfo(g, f);
        boolean isCaptain = p.captains().stream().anyMatch(c -> c.id() == m.getIdLong());
        boolean admin = m.hasPermission(Permission.ADMINISTRATOR);
        List<LayoutComponent> rows = new ArrayList<>();

        if (!p.running()) {
            boolean needCaptains = p.captains().size() < 2;
            boolean needNames = !needCaptains && !p.allTeamsNamed();
            boolean ready = !needCaptains && !needNames;

rows.add(ActionRow.of(
                    style(needCaptains, id("setcap", f), "👑 Captains"),
                    style(needNames, id("name", f), "🏷️ Team Names").withDisabled(p.captains().isEmpty()),
                    Button.secondary(id("repl", f), "🔁 Replace Captain").withDisabled(p.captains().isEmpty())));
rows.add(ActionRow.of(
                    Button.secondary(id("pool", f, "0"), "📋 Participants").withDisabled(!p.poolPrepared()),
                    Button.secondary(id("add", f), "➕ Add"),
                    Button.secondary(id("rm", f), "➖ Remove").withDisabled(!p.poolPrepared()),
                    Button.secondary(id("rebuild", f), "🔄 Rebuild List")));
            List<Button> go = new ArrayList<>();
go.add(ready ? Button.success(id("start", f), "▶️ Start Draft")
                         : Button.secondary(id("start", f), "▶️ Start Draft").asDisabled());
            if (isCaptain) go.add(Button.secondary(id("my", f), "🎯 My Team"));
rows.add(ActionRow.of(go));
} else {
            boolean paused = p.status() == DraftStatus.PAUSED;
rows.add(ActionRow.of(
                    paused ? Button.success(id("resume", f), "▶️ Resume") : Button.primary(id("pause", f), "⏸️ Pause"),
                    Button.secondary(id("undo", f), "↩️ Undo").withDisabled(p.picksMade() == 0),
                    Button.secondary(id("apick", f), "🛡️ Admin Pick")));
rows.add(ActionRow.of(
                    Button.secondary(id("pool", f, "0"), "📋 Participants"),
                    Button.secondary(id("add", f), "➕ Add"),
                    Button.secondary(id("rm", f), "➖ Remove")));
            List<Button> team = new ArrayList<>();
            team.add(Button.secondary(id("repl", f), "🔁 Replace Captain"));
            team.add(Button.secondary(id("name", f), "🏷️ Team Names"));
            if (isCaptain) team.add(Button.secondary(id("my", f), "🎯 My Team"));
rows.add(ActionRow.of(team));
}

        int keyCount = competitions.keys(f).size();
rows.add(ActionRow.of(
                Button.secondary(id("ctimes", f), "🗓️ Competition Times"),
                Button.secondary(id("keys", f), "🔑 API Keys (" + keyCount + ")")));

// Last row: anything destructive, kept away from the everyday buttons, then Close.
        List<Button> last = new ArrayList<>();
        if (p.running()) last.add(Button.danger(id("end", f), "🛑 End Draft"));
else if (admin) last.add(Button.danger(id("td", f), "🧹 Teardown"));
last.add(Button.secondary(id("close", f), "✖ Close"));
rows.add(ActionRow.of(last));

        MessageEmbed status = statusEmbed(p, "🎛️ " + f.getName() + " · Draft Control", notice, nextStep(p));
return View.of(new EmbedBuilder(status).addField("🏁 Competition", competitionSummary(f), false).build(), rows);
}

    private View viewerHome(Guild g, Faction f, String notice) {
        PanelInfo p = drafts.panelInfo(g, f);
return View.of(statusEmbed(p, "🏆 " + f.getName() + " · Draft", notice, null), List.of(ActionRow.of(
        Button.secondary(id("pool", f, "0"), "📋 Participants").withDisabled(!p.poolPrepared()),
        Button.secondary(id("close", f), "✖ Close"))));
}

    /** The one thing a leader should do next, shown under the status. */
    private static String nextStep(PanelInfo p) {
        if (p.running()) return null;
        if (p.captains().size() < 2) return "Choose at least 2 captains with **👑 Captains** — that also builds the participant list.";
        if (!p.allTeamsNamed()) return "Every team needs a name — use **🏷️ Team Names** (or let captains name their own).";
return "All set — check the participants, then **▶️ Start Draft**.";
}

    /** Coloured (primary) when it's the recommended next step, grey otherwise. */
    private static Button style(boolean highlight, String id, String label) {
        return highlight ? Button.primary(id, label) : Button.secondary(id, label);
}

    View captainHome(Guild g, Member m, Faction f, String notice) {
        CaptainView c = drafts.captainView(f, m.getIdLong());
        EmbedBuilder eb = new EmbedBuilder().setTitle("🎯 " + f.getName() + " · " + (c.team() == null ? "Your Team" : c.team()))
                .setColor(BLURPLE);
        StringBuilder desc = new StringBuilder();
        if (notice != null) desc.append(notice).append("\n\n");
        if (c.team() == null) desc.append("⚠️ **Your team needs a name** — use **🏷️ Name Team**.\n");
        if (!c.running()) desc.append("The draft hasn't started yet. Your picks and queue will show here once it does.");
else desc.append(c.onClock() ? "🎯 **You're on the clock!** Use `/pick`." : "⏳ Waiting for your turn — use `/pick` when it comes.");
        eb.setDescription(desc.toString());

        List<LayoutComponent> rows = new ArrayList<>();
        if (c.running()) {
            eb.addField("Picks (" + c.picks().size() + ")", c.picks().isEmpty() ? "*none yet*" : bullets(c.picks(), 20), true);
            eb.addField("Queue (" + c.queue().size() + "/5)", c.queue().isEmpty()
                    ? "*empty*\nQueued players are picked for you automatically when your turn starts." : numbered(c.queue()), true);
rows.add(ActionRow.of(
                    Button.primary(id("qadd", f), "➕ Queue Player").withDisabled(c.queue().size() >= 5),
                    Button.secondary(id("qrm", f), "➖ Unqueue").withDisabled(c.queue().isEmpty()),
                    Button.secondary(id("qclr", f), "🗑️ Clear").withDisabled(c.queue().isEmpty())));
}
        rows.add(ActionRow.of(
                style(c.team() == null, id("name", f), c.team() == null ? "🏷️ Name Team" : "🏷️ Rename Team"),
                Button.secondary(id("pool", f, "0"), "📋 Participants"),
                drafts.isLeader(m, f) ? Button.secondary(id("home", f), "🎛️ Control Panel")
                                      : Button.secondary(id("my", f), "🔄 Refresh")));
rows.add(ActionRow.of(Button.secondary(id("close", f), "✖ Close")));
return View.of(eb.build(), rows);
}

    private View setCaptainsView(Faction f) {
        return new View(List.of(embed("👑 Set Captains",
                "Choose 2–9 captains. Setting captains also builds the participant list.\n"
                        + "Anyone removed from the participants earlier stays out.")),
                List.of(ActionRow.of(EntitySelectMenu.create(id("setcapusers", f), EntitySelectMenu.SelectTarget.USER)
                                .setPlaceholder("Choose captains").setRequiredRange(2, 9).build()),
                        ActionRow.of(Button.secondary(id("home", f), "Back"))));
}

    private View setCaptainsConfirmView(Faction f, List<Member> picked) {
        StringBuilder sb = new StringBuilder("Pick order:\n");
for (int i = 0; i < picked.size(); i++) {
            sb.append(i + 1).append(". ").append(picked.get(i).getAsMention()).append('\n');
}
        return new View(List.of(embed("👑 Confirm Captains", sb.toString())), List.of(ActionRow.of(
                Button.success(id("setcapok", f, "0"), "✅ Use this order"),
                Button.primary(id("setcapok", f, "1"), "🔀 Shuffle order"),
                Button.secondary(id("setcap", f), "Choose again"),
                Button.secondary(id("home", f), "Back"))));
}

    private View nameTeamView(Guild g, Faction f) {
        PanelInfo p = drafts.panelInfo(g, f);
        StringSelectMenu.Builder menu = StringSelectMenu.create(id("namecap", f)).setPlaceholder("Choose a captain");
for (CaptainInfo c : p.captains()) {
            menu.addOptions(SelectOption.of(trim(c.name()), String.valueOf(c.id()))
                    .withDescription(trim(c.team() == null ? "No team yet" : "Team: " + c.team())));
}
        return new View(List.of(embed("🏷️ Name Team", "Choose the captain whose team you're naming.")),
                List.of(ActionRow.of(menu.build()), ActionRow.of(Button.secondary(id("home", f), "Back"))));
}

    private View replaceOldView(Guild g, Faction f) {
        PanelInfo p = drafts.panelInfo(g, f);
        StringSelectMenu.Builder menu = StringSelectMenu.create(id("replold", f)).setPlaceholder("Captain to replace");
for (CaptainInfo c : p.captains()) {
            menu.addOptions(SelectOption.of(trim(c.name()), String.valueOf(c.id()))
                    .withDescription(trim(c.team() == null ? "No team yet" : "Team: " + c.team())));
}
        return new View(List.of(embed("🔁 Replace Captain", "Who's being replaced? The new captain takes over their team, queue and turn.")),
                List.of(ActionRow.of(menu.build()), ActionRow.of(Button.secondary(id("home", f), "Back"))));
}

    private View replaceNewView(Guild g, Faction f, long oldId) {
        Member old = g.getMemberById(oldId);
return new View(List.of(embed("🔁 Replace Captain",
                "Replacing **" + (old == null ? oldId : old.getEffectiveName()) + "**. Choose the new captain.")),
                List.of(ActionRow.of(EntitySelectMenu.create(id("replnew", f, String.valueOf(oldId)),
                                EntitySelectMenu.SelectTarget.USER).setPlaceholder("New captain").setRequiredRange(1, 1).build()),
                        ActionRow.of(Button.secondary(id("home", f), "Back"))));
}

    private View poolView(Guild g, Member m, Faction f, int page) {
        List<Player> pool = drafts.poolList(f);
int pages = Math.max(1, (pool.size() + POOL_PAGE - 1) / POOL_PAGE);
int pg = Math.max(0, Math.min(page, pages - 1));
        long off = pool.stream().filter(p -> !p.onDiscord()).count();
        String body = pool.isEmpty() ? "*No participants.*"
                : bullets(pool.subList(pg * POOL_PAGE, Math.min(pool.size(), (pg + 1) * POOL_PAGE)), POOL_PAGE);
        MessageEmbed e = new EmbedBuilder()
                .setTitle("📋 " + f.getName() + " participants — " + pool.size())
                .setDescription(body)
                .setFooter("Page " + (pg + 1) + "/" + pages + " · " + off + " not on Discord")
                .setColor(BLURPLE).build();
return View.of(e, List.of(ActionRow.of(
        Button.secondary(id("pool", f, String.valueOf(pg - 1)), "◀").withDisabled(pg == 0),
        Button.secondary(id("pool", f, String.valueOf(pg + 1)), "▶").withDisabled(pg >= pages - 1),
        Button.secondary(backId(m, f), "Back"))));
}

    private View addView(Faction f) {
        return new View(List.of(embed("➕ Add Player",
                "Choose a Discord member below, or add a faction member who isn't on Discord by their Torn ID.")),
                List.of(ActionRow.of(EntitySelectMenu.create(id("addusr", f), EntitySelectMenu.SelectTarget.USER)
                                .setPlaceholder("Discord member").setRequiredRange(1, 1).build()),
                        ActionRow.of(Button.primary(id("addtorn", f), "🔢 By Torn ID"),
                                Button.secondary(id("home", f), "Back"))));
}

    /** Search results as a dropdown. {@code action} is the select's id, {@code max} how many can be ticked. */
    private View resultsView(Faction f, String query, String action, String placeholder, boolean includeDrafted, int max) {
        List<Player> found = drafts.search(f, query, includeDrafted);
        String back = action.startsWith("q") ? id("my", f) : id("home", f);
        if (found.isEmpty()) {
            return new View(List.of(embed("🔎 No matches", "Nobody matches `" + query + "`. Try part of the name or a Torn ID.")),
                    List.of(ActionRow.of(Button.secondary(back, "Back"))));
}
        StringSelectMenu.Builder menu = StringSelectMenu.create(id(action, f)).setPlaceholder(placeholder)
                .setRequiredRange(1, Math.min(max, found.size()));
for (Player p : found) {
            String desc = (p.streak != null ? "🔥 " + p.streak + "d streak" : "Streak loading…")
                    + (p.onDiscord() ? "" : " · not on Discord");
            menu.addOptions(SelectOption.of(trim(p.display), String.valueOf(p.id)).withDescription(desc));
}
        String more = found.size() >= 25 ? "\nShowing the first 25 — search more specifically to narrow it down." : "";
return new View(List.of(embed("🔎 Results for `" + (query.isBlank() ? "everyone" : query) + "`",
                found.size() + " match" + (found.size() == 1 ? "" : "es") + "." + more)),
                List.of(ActionRow.of(menu.build()), ActionRow.of(Button.secondary(back, "Back"))));
}

    private View queueRemoveView(Faction f, Member m) {
        CaptainView c = drafts.captainView(f, m.getIdLong());
        if (c.queue().isEmpty()) return captainView(m, f, "Your queue is already empty.");
        StringSelectMenu.Builder menu = StringSelectMenu.create(id("qrmsel", f)).setPlaceholder("Tick players to remove")
                .setRequiredRange(1, c.queue().size());
for (Player p : c.queue()) menu.addOptions(SelectOption.of(trim(p.display), String.valueOf(p.id)));
return new View(List.of(embed("➖ Remove from Queue", "Tick the players to take out of your queue.")),
                List.of(ActionRow.of(menu.build()), ActionRow.of(Button.secondary(id("my", f), "Back"))));
}

    private View captainView(Member m, Faction f, String notice) {
        return captainHome(m.getGuild(), m, f, notice);
}

    private View startView(Faction f, int minutes, boolean autopick, boolean random) {
        String a = autopick ? "1" : "0", r = random ? "1" : "0";
        StringSelectMenu.Builder timer = StringSelectMenu.create(id("sttimer", f, a, r)).setPlaceholder("Pick timer");
for (int t : TIMER_CHOICES) {
            String label = t == 0 ? "No timer" : t + " minute" + (t == 1 ? "" : "s") + " per pick";
            timer.addOptions(SelectOption.of(label, String.valueOf(t)).withDefault(t == minutes));
}
        String desc = "**Timer:** " + (minutes == 0 ? "off" : minutes + " min per pick") + "\n"
                + "**Auto-pick on timeout:** " + (autopick ? "on — queue first, then a random player" : "off — the draft waits") + "\n"
                + "**Captain order:** " + (random ? "shuffled at start" : "as set");
        String m = String.valueOf(minutes);
return new View(List.of(embed("▶️ Start " + f.getName() + " Draft", desc)), List.of(
                ActionRow.of(timer.build()),
                ActionRow.of(
                        Button.secondary(id("stauto", f, m, a, r), autopick ? "🤖 Auto-pick: On" : "🤖 Auto-pick: Off"),
                        Button.secondary(id("strand", f, m, a, r), random ? "🔀 Order: Shuffle" : "📋 Order: As set")),
                ActionRow.of(
                        Button.success(id("stgo", f, m, a, r), "✅ Start Draft"),
                        Button.secondary(id("home", f), "Back"))));
}

    private View confirmView(Faction f, String text, Button yes) {
        return new View(List.of(embed("Are you sure?", text)),
                List.of(ActionRow.of(yes, Button.secondary(id("home", f), "Back"))));
}

    // =====================================================================
    // Competition
    // =====================================================================

    private String competitionSummary(Faction f) {
        CompetitionService.Status st = competitions.status(f);
        StringBuilder sb = new StringBuilder();
        if (st.start() == null) {
            sb.append("*Not scheduled* — set the start and end with **🗓️ Competition Times**.\n");
} else {
            sb.append("🗓️ ").append(DraftManager.tct(st.start())).append(" → ")
                    .append(DraftManager.tct(st.end())).append(" TCT\n");
            if (st.finalPosted()) sb.append("🏁 Finished — final scoreboard posted\n");
else if (st.live()) sb.append("🔴 **Live** — ends <t:").append(st.end()).append(":R>\n");
else if (st.over()) sb.append("⏱️ Finished — collecting the last hits\n");
else sb.append("⏳ Starts <t:").append(st.start()).append(":R>\n");
}
        sb.append("🔑 ").append(st.keysWorking()).append('/').append(st.keys()).append(" keys working");
        if (st.lastCheckedMs() != null) sb.append(" · checked <t:").append(st.lastCheckedMs() / 1000).append(":R>");
        if (st.keys() == 0) sb.append(" — add one under **🔑 API Keys**");
        if (st.lastError() != null) sb.append("\n⚠️ ").append(st.lastError());
        if (st.missingTornIds() > 0) {
            sb.append("\n⚠️ ").append(st.missingTornIds()).append(" drafted player")
                    .append(st.missingTornIds() == 1 ? " has" : "s have")
                    .append(" no `[ID]` in their nickname, so their hits can't be counted.");
}
        return sb.toString();
}

    private View keysView(Member m, Faction f, String notice) {
        List<ApiKey> keys = competitions.keys(f);
        StringBuilder sb = new StringBuilder();
        if (notice != null) sb.append(notice).append("\n\n");
        sb.append("Keys are used to read **outgoing faction attacks** during the competition. Each must be a ")
                .append("**Limited** key from a ").append(f.getName()).append(" member with **Faction API Access**. ")
                .append("Keys are stored encrypted and never shown again.\n\n");
        if (keys.isEmpty()) sb.append("*No keys yet.*");
for (ApiKey k : keys) {
            sb.append("• **").append(k.ownerName()).append("** [").append(k.ownerTornId()).append("] — ");
            if (k.lastError() != null) sb.append("⚠️ ").append(k.lastError());
else sb.append("✅ working").append(k.lastOkAt() == null ? ""
                    : " · last used <t:" + k.lastOkAt().getEpochSecond() + ":R>");
            sb.append('\n');
}
        List<LayoutComponent> rows = new ArrayList<>();
        if (!keys.isEmpty()) {
            StringSelectMenu.Builder menu = StringSelectMenu.create(id("keyrm", f)).setPlaceholder("Remove a key…");
for (ApiKey k : keys) {
                menu.addOptions(SelectOption.of(trim(k.ownerName() + " [" + k.ownerTornId() + "]"), String.valueOf(k.id())));
}
            rows.add(ActionRow.of(menu.build()));
}
        rows.add(ActionRow.of(
                Button.primary(id("keyadd", f), "➕ Add Key"),
                Button.secondary(id("home", f), "Back")));
return View.of(embed("🔑 " + f.getName() + " · API Keys", sb.toString()), rows);
}

    private Modal timesModal(Faction f) {
        DraftManager.Snapshot snap = drafts.snapshot(f.getKey());
        Long start = snap == null ? null : snap.compStart();
        Long end = snap == null ? null : snap.compEnd();
        TextInput.Builder s = TextInput.create("start", "Start (TCT)", TextInputStyle.SHORT)
                .setPlaceholder("2026-10-25 18:00").setRequired(false).setRequiredRange(0, 16);
        TextInput.Builder e = TextInput.create("end", "End (TCT)", TextInputStyle.SHORT)
                .setPlaceholder("2026-10-27 18:00").setRequired(false).setRequiredRange(0, 16);
        if (start != null) s.setValue(DraftManager.tct(start));
        if (end != null) e.setValue(DraftManager.tct(end));
return Modal.create(PREFIX + "mtimes:" + f.getKey(), "Competition times (TCT)")
           .addActionRow(s.build()).addActionRow(e.build()).build();
}

    /** "2026-10-25 18:00" (TCT = UTC) → epoch seconds; blank → null. */
    private static Long parseTct(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return java.time.LocalDateTime.parse(text.trim().replace('T', ' '),
                            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    .toEpochSecond(java.time.ZoneOffset.UTC);
} catch (Exception ex) {
            throw new UserError("Use the format `2026-10-25 18:00` for times (TCT). Got: `" + text + "`.");
}
    }

    private static String modalValue(ModalInteractionEvent e, String id) {
        var v = e.getValue(id);
return v == null ? "" : v.getAsString().trim();
}

    // =====================================================================
    // Embeds
    // =====================================================================

    private MessageEmbed statusEmbed(PanelInfo p, String title, String notice, String next) {
        EmbedBuilder eb = new EmbedBuilder().setTitle(title).setColor(BLURPLE);

        StringBuilder desc = new StringBuilder();
        if (notice != null) desc.append(notice).append("\n\n");
        if (p.running()) {
            if (p.status() == DraftStatus.PAUSED) {
                desc.append("⏸️ **Paused** — <@").append(p.onClock()).append("> is up next");
} else {
                desc.append("🎯 **On the clock:** <@").append(p.onClock()).append(">");
                if (p.deadlineMs() != null) desc.append(" · ⏱ <t:").append(p.deadlineMs() / 1000).append(":R>");
}
        } else if (p.status() == DraftStatus.COMPLETE) {
            desc.append("✅ **Last draft complete.** Choose captains to prepare the next one.");
} else if (p.status() == DraftStatus.ENDED) {
            desc.append("🛑 **Last draft ended early.** Choose captains to prepare the next one.");
} else {
            desc.append("⚪ **Not started**");
}
        if (next != null) desc.append("\n**Next:** ").append(next);
        eb.setDescription(desc.toString());

        StringBuilder caps = new StringBuilder();
for (int i = 0; i < p.captains().size(); i++) {
            CaptainInfo c = p.captains().get(i);
            caps.append('`').append(i + 1).append("` <@").append(c.id()).append("> — ")
                    .append(c.team() == null ? "⚠️ *no team name*" : "**" + c.team() + "**");
            if (p.running()) caps.append(" · ").append(c.picks());
            caps.append('\n');
}
        eb.addField("👑 Captains" + (p.running() ? " · picks" : ""), caps.length() == 0 ? "*none yet*" : caps.toString(), false);

        eb.addField("📋 Participants", p.poolPrepared()
                ? "**" + p.poolSize() + "** " + (p.running() ? "left to pick" : "players") + "\n" + p.poolOffDiscord() + " not on Discord"
                        + "\n" + p.excluded() + " excluded"
                : "*built when captains are set*", true);
        if (p.running()) {
            eb.addField("⚙️ Settings", (p.timerSeconds() > 0 ? p.timerSeconds() / 60 + " min timer" : "No timer")
                    + "\nAuto-pick " + (p.autopick() ? "on" : "off"), true);
            eb.addField("📈 Progress", "**" + p.picksMade() + "** picks made", true);
}
        eb.setFooter("Only you can see this panel · it closes after " + PANEL_MINUTES + " minutes");
return eb.build();
}

    private static MessageEmbed embed(String title, String body) {
        return new EmbedBuilder().setTitle(title).setDescription(body).setColor(BLURPLE).build();
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
        TextInput.Builder input = TextInput.create("text", label, TextInputStyle.SHORT)
                .setPlaceholder(placeholder)
                .setRequired(min > 0)
                .setRequiredRange(min, max);
        if (value != null && !value.isBlank()) input.setValue(value);
return Modal.create(PREFIX + id, title).addActionRow(input.build()).build();
}

    /** From the lock-free snapshot: this runs on the event thread, which must answer within 3 seconds. */
    private String currentTeamName(Faction f, long captainId) {
        DraftManager.Snapshot snap = drafts.snapshot(f.getKey());
return snap == null ? null : snap.teamNames().get(captainId);
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
            return View.of(embed("Draft", notice), List.of(ActionRow.of(Button.secondary(id("close", f), "✖ Close"))));
}
    }

    private static void show(InteractionHook hook, View v) {
        hook.editOriginalEmbeds(v.embeds()).setContent("").setComponents(v.rows()).queue(null,
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