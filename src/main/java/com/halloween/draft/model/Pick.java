package com.halloween.draft.model;

public class Pick {
    public int number;      // 1-based overall pick number
    public int round;       // 1-based
    public long captainId;
    public Player player;
    public PickMethod method;
    public boolean dropped; // player removed after being drafted; slot stays used

    public Pick() {}

    public Pick(int number, int round, long captainId, Player player, PickMethod method) {
        this.number = number;
        this.round = round;
        this.captainId = captainId;
        this.player = player;
        this.method = method;
    }
}
