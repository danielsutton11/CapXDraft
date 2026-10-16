package com.halloween.draft.model;

public enum DraftStatus {
    ACTIVE, PAUSED, COMPLETE, ENDED;

    public boolean running() {
        return this == ACTIVE || this == PAUSED;
    }
}
