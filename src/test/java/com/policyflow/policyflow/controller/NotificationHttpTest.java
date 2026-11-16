package com.policyflow.policyflow.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.Notification;
import com.policyflow.policyflow.entity.Request;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.NotificationType;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.repository.ApprovalPolicyRepository;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.NotificationRepository;
import com.policyflow.policyflow.repository.UserRepository;
import com.policyflow.policyflow.security.AuthenticatedUser;
import com.policyflow.policyflow.service.RequestService;
import com.policyflow.policyflow.support.WorkflowFixtures;

@SpringBootTest
@Transactional // everything is rolled back, so your real data is untouched
class NotificationHttpTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ApprovalPolicyRepository policyRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private RequestService requestService;

    private MockMvc mockMvc;
    private User employee;
    private User manager;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        WorkflowFixtures.isolatePolicies(policyRepository);
        WorkflowFixtures.policy(policyRepository, "HN manager only", Role.MANAGER);

        Department department = departmentRepository.save(new Department("Http Notif", "HTTPNTF"));
        employee = WorkflowFixtures.user(userRepository, "hn-employee", Role.EMPLOYEE, department);
        manager = WorkflowFixtures.user(userRepository, "hn-manager", Role.MANAGER, department);
    }

    // ---- helpers ----

    private static RequestPostProcessor as(User account) {
        return user(AuthenticatedUser.from(account));
    }

    private Request submit() {
        return requestService.createAndSubmit(WorkflowFixtures.form("5000"), employee);
    }

    private Notification newestFor(User recipient) {
        return notificationRepository
                .findByRecipientIdOrderByIdDesc(recipient.getId(), PageRequest.of(0, 10))
                .getContent().get(0);
    }

    // ---- access ----

    @Test
    void anonymousVisitorsAreSentToLogin() throws Exception {
        mockMvc.perform(get("/notifications"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // ---- the page and the bell ----

    @Test
    @SuppressWarnings("unchecked")
    void thePageShowsOnlyTheCallersNotifications() throws Exception {
        submit(); // notifies the manager, not the employee

        MvcResult managerPage = mockMvc.perform(get("/notifications").with(as(manager)))
                .andExpect(status().isOk())
                .andExpect(view().name("notifications"))
                .andExpect(model().attribute("unread", 1L))
                .andReturn();
        Page<Notification> managerItems = (Page<Notification>) managerPage.getModelAndView().getModel().get("notifications");
        assertTrue(managerItems.getTotalElements() == 1);
        assertTrue(managerPage.getResponse().getContentAsString().contains("needs your decision"));

        MvcResult employeePage = mockMvc.perform(get("/notifications").with(as(employee)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("unread", 0L))
                .andReturn();
        assertTrue(employeePage.getResponse().getContentAsString().contains("No notifications yet"));
    }

    @Test
    void theBellShowsAnUnreadBadgeOnlyWhenThereIsSomethingUnread() throws Exception {
        submit();

        String managerHtml = mockMvc.perform(get("/").with(as(manager)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String employeeHtml = mockMvc.perform(get("/").with(as(employee)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(managerHtml.contains("count-badge"));
        assertFalse(employeeHtml.contains("count-badge"));
    }

    // ---- opening and marking ----

    @Test
    void openingMarksItReadAndGoesToTheRequest() throws Exception {
        Request request = submit();
        Notification notification = newestFor(manager);

        mockMvc.perform(post("/notifications/" + notification.getId() + "/open").with(as(manager)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/requests/" + request.getId()));

        assertTrue(notificationRepository.findById(notification.getId()).orElseThrow().isRead());
    }

    @Test
    void aNotificationWithoutALinkReturnsToTheList() throws Exception {
        Notification notification = notificationRepository.save(new Notification(
                manager, NotificationType.REQUEST_WITHDRAWN, null, null, "PF-00001 was withdrawn."));

        mockMvc.perform(post("/notifications/" + notification.getId() + "/open").with(as(manager)).with(csrf()))
                .andExpect(redirectedUrl("/notifications"));

        assertTrue(notificationRepository.findById(notification.getId()).orElseThrow().isRead());
    }

    @Test
    void someoneElsesNotificationIsNotFoundAndStaysUnread() throws Exception {
        submit();
        Notification notification = newestFor(manager);

        mockMvc.perform(post("/notifications/" + notification.getId() + "/open").with(as(employee)).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/notifications/" + notification.getId() + "/read").with(as(employee)).with(csrf()))
                .andExpect(status().isNotFound());

        assertFalse(notificationRepository.findById(notification.getId()).orElseThrow().isRead());
    }

    @Test
    void markReadAndMarkAllReadWork() throws Exception {
        submit();
        submit();
        Notification first = newestFor(manager);

        mockMvc.perform(post("/notifications/" + first.getId() + "/read").with(as(manager)).with(csrf()))
                .andExpect(redirectedUrl("/notifications"));
        assertTrue(notificationRepository.findById(first.getId()).orElseThrow().isRead());
        assertTrue(notificationRepository.countByRecipientIdAndReadAtIsNull(manager.getId()) == 1);

        mockMvc.perform(post("/notifications/read-all").with(as(manager)).with(csrf()))
                .andExpect(redirectedUrl("/notifications"))
                .andExpect(flash().attributeExists("successMessage"));
        assertTrue(notificationRepository.countByRecipientIdAndReadAtIsNull(manager.getId()) == 0);
    }

    @Test
    void changingANotificationNeedsACsrfToken() throws Exception {
        submit();
        Notification notification = newestFor(manager);

        mockMvc.perform(post("/notifications/" + notification.getId() + "/open").with(as(manager)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/notifications/" + notification.getId() + "/read").with(as(manager)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/notifications/read-all").with(as(manager)))
                .andExpect(status().isForbidden());

        assertFalse(notificationRepository.findById(notification.getId()).orElseThrow().isRead());
    }
}