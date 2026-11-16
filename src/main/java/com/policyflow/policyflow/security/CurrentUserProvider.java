package com.policyflow.policyflow.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.repository.UserRepository;

/** Returns the database User for the currently logged-in session. */
@Component
public class CurrentUserProvider {

    private final UserRepository userRepository;

    public CurrentUserProvider(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser principal)) {
            throw new AuthenticationCredentialsNotFoundException("No authenticated user");
        }

        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Account no longer exists"));

        if (!user.isActive()) {
            throw new AccessDeniedException("Account is deactivated");
        }
        if (user.getRole() != principal.getRole()) {
            // The role was changed after this session started, so its permissions are out of date
            throw new AccessDeniedException("Role changed; please sign in again");
        }
        return user;
    }
}