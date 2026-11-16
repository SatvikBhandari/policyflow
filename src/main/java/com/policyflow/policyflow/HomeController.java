package com.policyflow.policyflow;

import java.time.LocalTime;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.security.CurrentUserProvider;
import com.policyflow.policyflow.service.DashboardService;

@Controller
public class HomeController {

    private final DashboardService dashboardService;
    private final CurrentUserProvider currentUserProvider;

    public HomeController(DashboardService dashboardService, CurrentUserProvider currentUserProvider) {
        this.dashboardService = dashboardService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/")
    public String home(Model model) {
        User user = currentUserProvider.getCurrentUser();

        model.addAttribute("greeting", greetingFor(LocalTime.now()));
        model.addAttribute("firstName", firstNameOf(user.getFullName()));

        // Administrators do not raise requests, so they get a different dashboard.
        boolean requesterView = user.getRole() != Role.ADMIN;
        model.addAttribute("requesterView", requesterView);
        if (requesterView) {
            model.addAttribute("summary", dashboardService.summaryFor(user));
        }
        return "index";
    }

    private static String greetingFor(LocalTime time) {
        int hour = time.getHour();
        if (hour < 12) {
            return "Good morning";
        }
        return hour < 17 ? "Good afternoon" : "Good evening";
    }

    private static String firstNameOf(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "there";
        }
        return fullName.trim().split("\\s+")[0];
    }
}