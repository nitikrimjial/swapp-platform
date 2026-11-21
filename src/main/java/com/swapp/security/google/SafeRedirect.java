package com.swapp.security.google;

/** Server-side twin of {@code safeNext} in ui.js: only same-origin relative paths are allowed as redirect targets. */
public final class SafeRedirect {

    private SafeRedirect() {
    }

    /** Returns the path if it is a safe same-origin target, otherwise null. */
    public static String path(String value) {
        if (value == null || value.length() > 500 || !value.startsWith("/") || value.startsWith("//")
                || value.contains("\\")) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                return null;
            }
        }
        return value;
    }
}
