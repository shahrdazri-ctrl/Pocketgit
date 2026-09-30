package com.pocketgit.util;

/** Render untrusted textual content without interpreting terminal escape/control sequences. */
public final class TerminalText {
    private TerminalText() {}

    public static String escape(String value) {
        var result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) && c != '\t' && c != '\n')
                result.append("\\u%04x".formatted((int) c));
            else result.append(c);
        }
        return result.toString();
    }
}
