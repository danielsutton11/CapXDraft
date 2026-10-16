package com.halloween.draft.discord;

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
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    /** Captains chosen in the "Set Captains" picker, waiting for keep-order / shuffle. */
    private final Map<String, List<Long>> pendingCaptains = new ConcurrentHashMap<>();

    public PanelController(DraftManager drafts) {
        this.drafts = drafts;
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
                                + "the prepared pools and removals.\nRemove the Halloween Captain role from everyone too?")),
                        List.of(ActionRow.of(
                                Button.danger(id("tdok", f, "1"), "Delete all + clear captains"),
                                Button.danger(id("tdok", f, "0"), "Delete all, keep captains"),
                                Button.secondary(id("home", f), "Back"))));
            }
            case "tdok" -> home(g, m, f, drafts.teardown(g, m, a[2].equals("1")));
            case "my" -> captainHome(g, m, f, null);
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
        String text = e.getValue("text") == null ? "" : e.getValue("text").getAsString().trim();

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
        if (drafts.isLeader(m, f)) return leaderHome(g, m, f, notice);
        DraftManager.Snapshot snap = drafts.snapshot(f.getKey());
        if (snap != null && snap.captains().contains(m.getIdLong())) return captainHome(g, m, f, notice);
        return viewerHome(g, f, notice);
    }

    private View leaderHome(Guild g, Member m, Faction f, String notice) {
        PanelInfo p = drafts.panelInfo(g, f);
        boolean isCaptain = p.captains().stream().anyMatch(c -> c.id() == m.getIdLong());
        boolean admin = m.hasPermission(Permission.ADMINISTRATOR);
        List<LayoutComponent> rows = new ArrayList<>();

        if (!p.running()) {
            rows.add(ActionRow.of(
                    Button.primary(id("setcap", f), "👑 Set Captains"),
                    Button.primary(id("name", f), "🏷️ Name Team").withDisabled(p.captains().isEmpty()),
                    Button.secondary(id("repl", f), "🔁 Replace Captain").withDisabled(p.captains().isEmpty())));
            rows.add(ActionRow.of(
                    Button.secondary(id("pool", f, "0"), "📋 View Pool").withDisabled(!p.poolPrepared()),
                    Button.secondary(id("add", f), "➕ Add Player"),
                    Button.secondary(id("rm", f), "➖ Remove Players").withDisabled(!p.poolPrepared()),
                    Button.secondary(id("rebuild", f), "🔄 Rebuild Pool")));
            boolean ready = p.captains().size() >= 2 && p.allTeamsNamed();
            rows.add(ActionRow.of(Button.success(id("start", f), "▶️ Start Draft").withDisabled(!ready)));
        } else {
            boolean paused = p.status() == DraftStatus.PAUSED;
            rows.add(ActionRow.of(
                    paused ? Button.success(id("resume", f), "▶️ Resume") : Button.secondary(id("pause", f), "⏸️ Pause"),
                    Button.secondary(id("undo", f), "↩️ Undo").withDisabled(p.picksMade() == 0),
                    Button.primary(id("apick", f), "🛡️ Admin Pick"),
                    Button.danger(id("end", f), "🛑 End Draft")));
            rows.add(ActionRow.of(
                    Button.secondary(id("pool", f, "0"), "📋 View Pool"),
                    Button.secondary(id("add", f), "➕ Add Player"),
                    Button.secondary(id("rm", f), "➖ Remove Players"),
                    Button.secondary(id("repl", f), "🔁 Replace Captain"),
                    Button.secondary(id("name", f), "🏷️ Rename Team")));
        }
        List<Button> last = new ArrayList<>();
        if (isCaptain) last.add(Button.primary(id("my", f), "🎯 My Team & Queue"));
        if (admin && !p.running()) last.add(Button.danger(id("td", f), "🧹 Teardown"));
        last.add(Button.secondary(id("close", f), "✖ Close"));
        rows.add(ActionRow.of(last));

        return View.of(statusEmbed(p, "🎛️ " + f.getName() + " — Draft Control", notice), rows);
    }

    private View viewerHome(Guild g, Faction f, String notice) {
        PanelInfo p = drafts.panelInfo(g, f);
        return View.of(statusEmbed(p, "🏆 " + f.getName() + " — Draft", notice), List.of(ActionRow.of(
                Button.secondary(id("pool", f, "0"), "📋 View Pool").withDisabled(!p.poolPrepared()),
                Button.secondary(id("close", f), "✖ Close"))));
    }

    View captainHome(Guild g, Member m, Faction f, String notice) {
        CaptainView c = drafts.captainView(f, m.getIdLong());
        StringBuilder sb = new StringBuilder();
        if (notice != null) sb.append(notice).append("\n\n");
        sb.append("**Team:** ").append(c.team() == null ? "⚠️ not named yet — use **Name Team**" : c.team()).append('\n');
        if (c.running()) {
            sb.append(c.onClock() ? "🎯 **You're on the clock** — use `/pick`\n" : "Waiting for your turn — use `/pick` when it comes.\n");
            sb.append("\n**Your picks (").append(c.picks().size()).append("):**\n");
            sb.append(c.picks().isEmpty() ? "*none yet*\n" : bullets(c.picks(), 20));
            sb.append("\n**Your queue (").append(c.queue().size()).append("/5):**\n");
            sb.append(c.queue().isEmpty() ? "*empty — queued players are picked for you automatically when your turn starts*\n"
                    : numbered(c.queue()));
        } else {
            sb.append("\nThe draft hasn't started yet. Once it does, your picks and queue show here.");
        }
        MessageEmbed embed = embed("🎯 " + f.getName() + " — Your Team", sb.toString());

        List<LayoutComponent> rows = new ArrayList<>();
        if (c.running()) {
            rows.add(ActionRow.of(
                    Button.primary(id("qadd", f), "➕ Add to Queue").withDisabled(c.queue().size() >= 5),
                    Button.secondary(id("qrm", f), "➖ Remove from Queue").withDisabled(c.queue().isEmpty()),
                    Button.danger(id("qclr", f), "🗑️ Clear Queue").withDisabled(c.queue().isEmpty())));
        }
        List<Button> last = new ArrayList<>();
        last.add(Button.secondary(id("name", f), c.team() == null ? "🏷️ Name Team" : "🏷️ Rename Team"));
        last.add(Button.secondary(id("pool", f, "0"), "📋 View Pool"));
        if (drafts.isLeader(m, f)) last.add(Button.secondary(id("home", f), "🎛️ Control Panel"));
        else last.add(Button.secondary(id("my", f), "🔄 Refresh"));
        last.add(Button.secondary(id("close", f), "✖ Close"));
        rows.add(ActionRow.of(last));
        return View.of(embed, rows);
    }

    private View setCaptainsView(Faction f) {
        return new View(List.of(embed("👑 Set Captains",
                "Choose 2–9 captains. Setting captains also builds the draft pool.\n"
                        + "Anyone removed from the pool earlier stays out.")),
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
        String body = pool.isEmpty() ? "*The pool is empty.*"
                : bullets(pool.subList(pg * POOL_PAGE, Math.min(pool.size(), (pg + 1) * POOL_PAGE)), POOL_PAGE);
        MessageEmbed e = new EmbedBuilder()
                .setTitle("📋 " + f.getName() + " pool — " + pool.size() + " players")
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
    // Embeds
    // =====================================================================

    private MessageEmbed statusEmbed(PanelInfo p, String title, String notice) {
        StringBuilder sb = new StringBuilder();
        if (notice != null) sb.append(notice).append("\n\n");

        if (p.running()) {
            if (p.status() == DraftStatus.PAUSED) {
                sb.append("⏸️ **Paused** — <@").append(p.onClock()).append("> is up next\n");
            } else {
                sb.append("🎯 **On the clock:** <@").append(p.onClock()).append(">");
                if (p.deadlineMs() != null) sb.append(" · ⏱ <t:").append(p.deadlineMs() / 1000).append(":R>");
                sb.append('\n');
            }
            sb.append(p.picksMade()).append(" picks made · ").append(p.poolSize()).append(" left · ")
                    .append(p.timerSeconds() > 0 ? p.timerSeconds() / 60 + " min timer" : "no timer")
                    .append(" · auto-pick ").append(p.autopick() ? "on" : "off").append('\n');
        } else if (p.status() == DraftStatus.COMPLETE) {
            sb.append("✅ The last draft is complete. Set captains to prepare the next one.\n");
        } else if (p.status() == DraftStatus.ENDED) {
            sb.append("🛑 The last draft was ended early. Set captains to prepare the next one.\n");
        } else {
            sb.append("**Not started.** ");
            if (p.captains().size() < 2) sb.append("Set at least 2 captains to begin.\n");
            else if (!p.allTeamsNamed()) sb.append("Every captain needs a team name before the draft can start.\n");
            else sb.append("Ready to start!\n");
        }

        sb.append("\n**Captains**\n");
        if (p.captains().isEmpty()) sb.append("*none set*\n");
        for (int i = 0; i < p.captains().size(); i++) {
            CaptainInfo c = p.captains().get(i);
            sb.append(i + 1).append(". <@").append(c.id()).append("> — ")
                    .append(c.team() == null ? "⚠️ *no team yet*" : "**" + c.team() + "**");
            if (p.running()) sb.append(" · ").append(c.picks()).append(" picked");
            sb.append('\n');
        }

        if (!p.running()) {
            sb.append("\n**Pool:** ").append(p.poolPrepared()
                    ? p.poolSize() + " players (" + p.poolOffDiscord() + " not on Discord)"
                    : "not built yet — it's built when captains are set");
        }
        return new EmbedBuilder().setTitle(title).setDescription(sb.toString()).setColor(BLURPLE).build();
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