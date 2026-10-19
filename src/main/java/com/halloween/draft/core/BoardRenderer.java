package com.halloween.draft.core;

import com.halloween.draft.config.BotProperties;
import com.halloween.draft.model.Draft;
import com.halloween.draft.model.FactionState;
import com.halloween.draft.model.Pick;
import com.halloween.draft.model.Team;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Builds the live draft board: one header embed plus one embed per team (max 10 per message). */
@Component
public class BoardRenderer {

    private final BotProperties props;

    public BoardRenderer(BotProperties props) {
        this.props = props;
    }

    static final int MAX_TOTAL = 5800; // Discord caps a message's embeds at 6000 characters
    private static final int[] COLORS = {
            0x3498DB, 0x2ECC71, 0xE67E22, 0x9B59B6, 0xE74C3C, 0xF1C40F, 0x1ABC9C, 0xAD1457, 0x95A5A6
    };

    public static int color(int i) {
        return COLORS[Math.floorMod(i, COLORS.length)];
    }

    /** {@code attached}: the team image files attached to the board message, which team embeds can show. */
    public List<MessageEmbed> render(BotProperties.Faction faction, FactionState state, java.util.Set<String> attached) {
        Draft d = state.draft;
        List<MessageEmbed> embeds = new ArrayList<>();
        embeds.add(header(faction, state));

        for (int mode = 0; mode < 3; mode++) {
            List<MessageEmbed> teams = new ArrayList<>();
            for (int i = 0; i < d.captainOrder.size(); i++) {
                teams.add(teamEmbed(state, d.captainOrder.get(i), i, mode, attached));
            }
            int total = embeds.get(0).getLength() + teams.stream().mapToInt(MessageEmbed::getLength).sum();
            if (total <= MAX_TOTAL || mode == 2) {
                embeds.addAll(teams);
                break;
            }
        }
        return embeds;
    }

    /** e.g. "🏆 Capacity X Halloween Draft 2026" - the year is the current year in UK time. */
    public String title(BotProperties.Faction faction) {
        int year = java.time.LocalDate.now(java.time.ZoneId.of("Europe/London")).getYear();
        String event = props.getEventName() == null || props.getEventName().isBlank() ? "" : props.getEventName().trim() + " ";
        return "🏆 " + faction.getName() + " " + event + "Draft " + year;
    }

    /** 120 → "2 min", 90 → "1 min 30s", 45 → "45s". */
    static String duration(int seconds) {
        int m = seconds / 60, s = seconds % 60;
        if (m == 0) return s + "s";
        return s == 0 ? m + " min" : m + " min " + s + "s";
    }

    public MessageEmbed header(BotProperties.Faction faction, FactionState state) {
        Draft d = state.draft;
        StringBuilder sb = new StringBuilder();
        Long current = d.currentCaptain();

        switch (d.status) {
            case ACTIVE -> {
                int idx = d.nextPickIndex();
                sb.append("**On the clock:** <@").append(current).append("> — ")
                        .append(state.teamName(current, d.captainName(current))).append('\n')
                        .append("Pick #").append(idx + 1).append(" · Round ").append(d.roundForPick(idx));
                if (d.turnDeadlineMs != null) {
                    sb.append(" · ⏱ <t:").append(d.turnDeadlineMs / 1000).append(":R>");
                } else if (d.timerSeconds > 0) {
                    sb.append(" · ⏰ **out of time**");
                }
                sb.append('\n');
            }
            case PAUSED -> sb.append("⏸️ **Paused** — <@").append(current).append("> is up next")
                    .append(d.pausedRemainingMs != null ? " (" + duration((int) (d.pausedRemainingMs / 1000)) + " left on their clock)" : "")
                    .append('\n');
            case COMPLETE -> sb.append("✅ **Draft complete** — final teams below\n");
            case ENDED -> sb.append("🛑 **Draft ended early** — teams below\n");
        }

        if (d.status.running()) {
            List<Long> next = d.upcomingCaptains(3);
            if (!next.isEmpty()) {
                sb.append("**Up next:** ").append(next.stream()
                        .map(d::captainName).collect(Collectors.joining(" → "))).append('\n');
            }
        }
        if (!d.picks.isEmpty()) {
            Pick last = d.picks.get(d.picks.size() - 1);
            sb.append("**Last pick:** #").append(last.number).append(' ')
                    .append(last.player.display).append(" → ")
                    .append(state.teamName(last.captainId, d.captainName(last.captainId)))
                    .append(" (").append(last.method.verb).append(")\n");
        }

        String footer = d.eligible.size() + " participants left · "
                + (d.timerSeconds > 0 ? "Timer " + duration(d.timerSeconds) : "No timer")
                + " · Auto-pick " + (d.autopick ? "on" : "off");

        return new EmbedBuilder()
                .setTitle(title(faction))
                .setDescription(sb.toString())
                .setFooter(footer)
                .setColor(0x5865F2)
                .build();
    }

    /** mode 0 = full, 1 = compact, 2 = compact and truncated. */
    private MessageEmbed teamEmbed(FactionState state, long captainId, int index, int mode, java.util.Set<String> attached) {
        Draft d = state.draft;
        Team team = state.teams.get(captainId);
        StringBuilder sb = new StringBuilder("Captain: <@").append(captainId).append(">");
        if (team != null && team.textChannelId != null && mode == 0) sb.append(" · <#").append(team.textChannelId).append(">");
        sb.append('\n');

        List<Pick> picks = d.picksFor(captainId);
        int limit = mode == 2 ? 12 : Integer.MAX_VALUE;
        int shown = 0;
        for (Pick p : picks) {
            if (shown++ >= limit) {
                sb.append("…and ").append(picks.size() - limit).append(" more\n");
                break;
            }
            String name = (p.dropped ? "~~" + p.player.display + "~~" : p.player.display)
                    + (p.player.onDiscord() || mode != 0 ? "" : " *(no Discord)*");
            if (mode == 0) {
                sb.append('`').append(String.format("#%-3d", p.number)).append("` ").append(name).append('\n');
            } else {
                sb.append("• ").append(name).append('\n');
            }
        }
        if (picks.isEmpty()) sb.append("*No picks yet*");

        EmbedBuilder eb = new EmbedBuilder()
                .setTitle(state.teamName(captainId, d.captainName(captainId)))
                .setDescription(sb.toString())
                .setColor(color(index));
        String file = TeamImageService.fileName(team);
        if (file != null && attached != null && attached.contains(file)) eb.setThumbnail("attachment://" + file);
        return eb.build();
    }
}
