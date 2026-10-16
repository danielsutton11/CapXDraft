package com.halloween.draft.model;

public enum PickMethod {
    MANUAL("picked"),
    QUEUE("auto-picked from queue"),
    TIMEOUT("auto-picked on timeout"),
    ADMIN("picked by a leader");

    public final String verb;

    PickMethod(String verb) {
        this.verb = verb;
    }
}
