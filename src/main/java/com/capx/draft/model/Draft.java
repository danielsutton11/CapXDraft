package com.capx.draft.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure draft state + rules (no Discord calls), so it can be unit tested on its own.
 *
 * The snake order is computed from the pick index rather than stored as a list, which
 * means adding or removing players mid-draft never breaks the turn order.
 */
public class Draft {
    public DraftStatus status = DraftStatus.ACTIVE;
    public List<Long> captainOrder = new ArrayList<>();
    public Map<Long, String> captainNames = new HashMap<>();
    public List<Player> eligible = new ArrayList<>();
    public List<Pick> picks = new ArrayList<>();
    public Map<Long, List<Long>> queues = new HashMap<>();

    public int timerSeconds;          // 0 = no timer
    public boolean autopick;          // auto-pick when the timer runs out
    public Long turnDeadlineMs;       // null when no timer is running
    public Long pausedRemainingMs;    // time left on the clock when paused
    public boolean warningSent;
    public boolean suppressQueueOnce; // after an undo, don't instantly re-pick from the queue
    public long turnToken;            // bumps every time the clock (re)starts; stale timers ignore themselves

    public long channelId;
    public Long boardMessageId;
    public Long lastPingMessageId;

    // ---------- turn order ----------

    public Long captainForPick(int index) {
        int n = captainOrder.size();
        if (n == 0) return null;
        int round = index / n;
        int pos = index % n;
        return round % 2 == 0 ? captainOrder.get(pos) : captainOrder.get(n - 1 - pos);
    }

    public int roundForPick(int index) {
        return captainOrder.isEmpty() ? 1 : index / captainOrder.size() + 1;
    }

    public int nextPickIndex() {
        return picks.size();
    }

    /** Captain on the clock, or null when no players are left. */
    public Long currentCaptain() {
        return eligible.isEmpty() ? null : captainForPick(picks.size());
    }

    /** The captains due to pick after the current one, limited by players remaining. */
    public List<Long> upcomingCaptains(int count) {
        List<Long> out = new ArrayList<>();
        int remainingAfterCurrent = eligible.size() - 1;
        for (int i = 1; i <= count && i <= remainingAfterCurrent; i++) {
            out.add(captainForPick(picks.size() + i));
        }
        return out;
    }

    public String captainName(long id) {
        return captainNames.getOrDefault(id, String.valueOf(id));
    }

    // ---------- players ----------

    public Optional<Player> findEligible(long playerId) {
        return eligible.stream().filter(p -> p.id == playerId).findFirst();
    }

    public boolean isDrafted(long playerId) {
        return picks.stream().anyMatch(p -> !p.dropped && p.player.id == playerId);
    }

    public List<Pick> picksFor(long captainId) {
        return picks.stream().filter(p -> p.captainId == captainId).toList();
    }

    public void sortEligible() {
        eligible.sort(Comparator.comparing(p -> p.display.toLowerCase()));
    }

    // ---------- queues ----------

    public List<Long> queueFor(long captainId) {
        return queues.computeIfAbsent(captainId, k -> new ArrayList<>());
    }

    public void removeFromAllQueues(long playerId) {
        queues.values().forEach(q -> q.remove(playerId));
    }

    /** First queued player that is still available, dropping anything stale from the queue. */
    public Optional<Player> firstQueued(long captainId) {
        List<Long> q = queueFor(captainId);
        q.removeIf(id -> findEligible(id).isEmpty());
        return q.isEmpty() ? Optional.empty() : findEligible(q.get(0));
    }

    // ---------- mutations ----------

    /** Records a pick for the captain currently on the clock. Caller checks it's that captain's turn. */
    public Pick recordPick(Player player, PickMethod method) {
        int idx = picks.size();
        Long captain = captainForPick(idx);
        if (captain == null) throw new IllegalStateException("No captains");
        eligible.removeIf(p -> p.id == player.id);
        removeFromAllQueues(player.id);
        Pick pick = new Pick(idx + 1, roundForPick(idx), captain, player, method);
        picks.add(pick);
        suppressQueueOnce = false;
        if (eligible.isEmpty()) status = DraftStatus.COMPLETE;
        return pick;
    }

    /** Removes the last pick and puts that player back in the pool. */
    public Optional<Pick> undoLast() {
        if (picks.isEmpty()) return Optional.empty();
        Pick last = picks.remove(picks.size() - 1);
        if (!last.dropped) {
            eligible.add(last.player);
            sortEligible();
        }
        if (status == DraftStatus.COMPLETE) status = DraftStatus.ACTIVE;
        suppressQueueOnce = true;
        return Optional.of(last);
    }

    /** Swaps one captain for another everywhere: turn order, names, queue and existing picks. */
    public void replaceCaptain(long oldId, long newId, String newName) {
        captainOrder.replaceAll(id -> id == oldId ? newId : id);
        captainNames.remove(oldId);
        captainNames.put(newId, newName);
        List<Long> q = queues.remove(oldId);
        queues.put(newId, q != null ? q : new ArrayList<>());
        picks.forEach(p -> { if (p.captainId == oldId) p.captainId = newId; });
        eligible.removeIf(p -> p.id == newId);
        removeFromAllQueues(newId);
    }
}
