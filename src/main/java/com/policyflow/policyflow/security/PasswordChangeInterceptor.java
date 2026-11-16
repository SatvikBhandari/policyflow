package com.policyflow.policyflow.security;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.repository.UserRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Sends users who still have a temporary password to the change-password page. */
@Component
public class PasswordChangeInterceptor implements HandlerInterceptor {

    private final UserRepository userRepository;

    public PasswordChangeInterceptor(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser principal)) {
            return true; // not logged in: nothing to enforce here
        }

        boolean mustChange = userRepository.findById(principal.getId())
                .map(User::isMustChangePassword)
                .orElse(false);
        if (mustChange) {
            response.sendRedirect(request.getContextPath() + "/account/password");
            return false;
        }
        return true;
    }
}