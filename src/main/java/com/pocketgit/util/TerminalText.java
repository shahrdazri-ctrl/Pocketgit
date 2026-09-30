package com.pocketgit.util;

import java.io.IOException;

/** Render untrusted text without interpreting terminal controls or allocating per character. */
public final class TerminalText {
    private static final String HEX = "0123456789abcdef";

    private TerminalText() {}

    public static String escape(String value) {
        var result = new StringBuilder(value.length());
        try {
            appendEscaped(value, result);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return result.toString();
    }

    public static void appendEscaped(String value, Appendable output) throws IOException {
        var chunk = new StringBuilder(8192);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) && c != '\t' && c != '\n') {
                chunk.append("\\u")
                        .append(HEX.charAt((c >>> 12) & 15))
                        .append(HEX.charAt((c >>> 8) & 15))
                        .append(HEX.charAt((c >>> 4) & 15))
                        .append(HEX.charAt(c & 15));
            } else chunk.append(c);
            if (chunk.length() >= 8192) {
                output.append(chunk);
                chunk.setLength(0);
            }
        }
        if (!chunk.isEmpty()) output.append(chunk);
    }
}
