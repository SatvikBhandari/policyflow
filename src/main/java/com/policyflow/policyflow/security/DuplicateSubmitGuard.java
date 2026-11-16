package com.policyflow.policyflow.security;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

import jakarta.servlet.http.HttpSession;

/**
 * One-time form tokens that stop the same form submission from being processed twice.
 * A token is issued when the form is shown and consumed by the first valid POST.
 */
@Component
public class DuplicateSubmitGuard {

    private static final String SESSION_ATTRIBUTE = "policyflow.formTokens";
    private static final int MAX_TOKENS = 20; // enough for several open tabs

    public String issue(HttpSession session) {
        String token = UUID.randomUUID().toString();
        synchronized (WebUtils.getSessionMutex(session)) {
            Set<String> tokens = tokensOf(session);
            tokens.add(token);
            if (tokens.size() > MAX_TOKENS) {
                Iterator<String> oldest = tokens.iterator();
                oldest.next();
                oldest.remove(); // forget the oldest token
            }
            session.setAttribute(SESSION_ATTRIBUTE, tokens);
        }
        return token;
    }

    /** Returns true only the first time a valid token is presented. */
    public boolean consume(HttpSession session, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        synchronized (WebUtils.getSessionMutex(session)) {
            Set<String> tokens = tokensOf(session);
            boolean wasValid = tokens.remove(token);
            session.setAttribute(SESSION_ATTRIBUTE, tokens);
            return wasValid;
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> tokensOf(HttpSession session) {
        Object existing = session.getAttribute(SESSION_ATTRIBUTE);
        if (existing instanceof Set<?>) {
            return (Set<String>) existing;
        }
        return new LinkedHashSet<>();
    }
}