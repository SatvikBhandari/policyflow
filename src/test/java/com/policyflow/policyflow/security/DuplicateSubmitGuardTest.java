package com.policyflow.policyflow.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class DuplicateSubmitGuardTest {

    private final DuplicateSubmitGuard guard = new DuplicateSubmitGuard();

    @Test
    void issuedTokenCanBeConsumedOnlyOnce() {
        MockHttpSession session = new MockHttpSession();
        String token = guard.issue(session);

        assertTrue(guard.consume(session, token));
        assertFalse(guard.consume(session, token)); // the duplicate is refused
    }

    @Test
    void unknownMissingOrBlankTokensAreRejected() {
        MockHttpSession session = new MockHttpSession();

        assertFalse(guard.consume(session, "not-a-real-token"));
        assertFalse(guard.consume(session, null));
        assertFalse(guard.consume(session, "   "));
    }

    @Test
    void tokensFromDifferentTabsDoNotInvalidateEachOther() {
        MockHttpSession session = new MockHttpSession();
        String tabA = guard.issue(session);
        String tabB = guard.issue(session);

        assertTrue(guard.consume(session, tabA));
        assertTrue(guard.consume(session, tabB));
    }

    @Test
    void oldestTokenIsForgottenOnceTheLimitIsExceeded() {
        MockHttpSession session = new MockHttpSession();
        String first = guard.issue(session);
        String last = first;
        for (int i = 0; i < 20; i++) {
            last = guard.issue(session);
        }

        assertFalse(guard.consume(session, first));
        assertTrue(guard.consume(session, last));
    }
}