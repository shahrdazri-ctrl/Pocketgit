package com.pocketgit.util;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;

public final class JsonUtils {
    private JsonUtils() {}

    public static DefaultPrettyPrinter prettyPrinter() {
        var printer = new DefaultPrettyPrinter();
        var indent = new DefaultIndenter("  ", "\n");
        printer.indentObjectsWith(indent);
        printer.indentArraysWith(indent);
        return printer;
    }
}
