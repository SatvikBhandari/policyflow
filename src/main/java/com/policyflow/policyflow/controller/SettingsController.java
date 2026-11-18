package com.policyflow.policyflow.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.repository.UserRepository;
import com.policyflow.policyflow.security.CurrentUserProvider;

/** The logged-in user's Settings page (profile, security, preferences). Open to every role. */
@Controller
public class SettingsController {

    private final CurrentUserProvider currentUserProvider;
    private final UserRepository userRepository;

    public SettingsController(CurrentUserProvider currentUserProvider, UserRepository userRepository) {
        this.currentUserProvider = currentUserProvider;
        this.userRepository = userRepository;
    }

    @GetMapping("/settings")
    public String settings(Model model) {
        User current = currentUserProvider.getCurrentUser();
        // Re-load with the department fetched, because open-in-view is off
        User user = userRepository.findWithDepartmentById(current.getId()).orElse(current);

        model.addAttribute("profileName", user.getFullName());
        model.addAttribute("profileEmail", user.getEmail());
        model.addAttribute("profileRole", user.getRole().getLabel());
        model.addAttribute("profileDepartment", user.getDepartment().getName());
        model.addAttribute("memberSince", user.getCreatedAt());
        return "settings";
    }
}