package com.policyflow.policyflow.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.policyflow.policyflow.entity.Notification;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.security.CurrentUserProvider;
import com.policyflow.policyflow.service.NotificationService;

/** The logged-in user's own notifications. Open to every role. */
@Controller
@RequestMapping("/notifications")
public class NotificationController {

    private static final String LIST_REDIRECT = "redirect:/notifications";

    private final NotificationService notificationService;
    private final CurrentUserProvider currentUserProvider;

    public NotificationController(NotificationService notificationService, CurrentUserProvider currentUserProvider) {
        this.notificationService = notificationService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public String list(@RequestParam(name = "page", defaultValue = "0") int page, Model model) {
        User user = currentUserProvider.getCurrentUser();
        model.addAttribute("notifications", notificationService.list(user, page));
        model.addAttribute("unread", notificationService.unreadCount(user.getId()));
        return "notifications";
    }

    /** Marks the notification as read and goes to the page it points to (the link is stored by the server). */
    @PostMapping("/{id}/open")
    public String open(@PathVariable Long id) {
        User user = currentUserProvider.getCurrentUser();
        Notification notification = notificationService.markRead(user, id);
        return notification.getLink() == null ? LIST_REDIRECT : "redirect:" + notification.getLink();
    }

    @PostMapping("/{id}/read")
    public String markRead(@PathVariable Long id) {
        User user = currentUserProvider.getCurrentUser();
        notificationService.markRead(user, id);
        return LIST_REDIRECT;
    }

    @PostMapping("/read-all")
    public String markAllRead(RedirectAttributes redirectAttributes) {
        User user = currentUserProvider.getCurrentUser();
        int count = notificationService.markAllRead(user);
        redirectAttributes.addFlashAttribute("successMessage",
                count == 1 ? "1 notification marked as read." : count + " notifications marked as read.");
        return LIST_REDIRECT;
    }
}