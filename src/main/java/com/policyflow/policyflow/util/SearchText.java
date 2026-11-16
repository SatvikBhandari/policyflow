package com.policyflow.policyflow.util;

import java.util.Locale;

/** Builds safe LIKE patterns from text typed into a search box. */
public final class SearchText {

    public static final int MAX_LENGTH = 100;

    private SearchText() {
    }

    /**
     * "%" (match everything) for blank text, otherwise a lowercase "%text%" pattern.
     * The pattern uses '!' as its escape character, so '%' and '_' typed by the user are matched literally.
     */
    public static String likePattern(String text) {
        if (text == null || text.isBlank()) {
            return "%";
        }
        String trimmed = text.trim().toLowerCase(Locale.ROOT);
        if (trimmed.length() > MAX_LENGTH) {
            trimmed = trimmed.substring(0, MAX_LENGTH);
        }
        String escaped = trimmed.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }
}