package com.halloween.draft.core;

/** A problem to show the user as-is (wrong turn, bad name, missing permission...). */
public class UserError extends RuntimeException {
    public UserError(String message) {
        super(message);
    }
}
