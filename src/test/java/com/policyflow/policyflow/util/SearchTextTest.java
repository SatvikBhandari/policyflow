package com.policyflow.policyflow.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SearchTextTest {

    @Test
    void blankOrMissingTextMatchesEverything() {
        assertEquals("%", SearchText.likePattern(null));
        assertEquals("%", SearchText.likePattern(""));
        assertEquals("%", SearchText.likePattern("    "));
    }

    @Test
    void textIsTrimmedLowercasedAndWrapped() {
        assertEquals("%pf-00012%", SearchText.likePattern("  PF-00012 "));
    }

    @Test
    void wildcardsAndTheEscapeCharacterAreEscaped() {
        assertEquals("%100!%!_x!!%", SearchText.likePattern("100%_x!"));
    }

    @Test
    void veryLongTextIsCutToTheMaximumLength() {
        String pattern = SearchText.likePattern("a".repeat(300));

        assertEquals(SearchText.MAX_LENGTH + 2, pattern.length()); // the two '%' signs
    }
}