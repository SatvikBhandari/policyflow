package com.policyflow.policyflow.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.policyflow.policyflow.dto.ApprovalViews.Review;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.Notification;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.RequestStatus;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.repository.ApprovalPolicyRepository;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.NotificationRepository;
import com.policyflow.policyflow.repository.RequestRepository;
import com.policyflow.policyflow.repository.UserRepository;
import com.policyflow.policyflow.security.AuthenticatedUser;
import com.policyflow.policyflow.service.ApprovalService;
import com.policyflow.policyflow.service.RequestService;
import com.policyflow.policyflow.support.WorkflowFixtures;

@SpringBootTest
@Transactional // everything is rolled back, so your real data is untouched
class LateApproverHttpTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ApprovalPolicyRepository policyRepository;
    @Autowired private RequestRepository requestRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private RequestService requestService;
    @Autowired private ApprovalService approvalService;

    private MockMvc mockMvc;
    private User employee;
    private User manager;
    private User secondManager;
    private User outsiderManager;
    private Long requestId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        WorkflowFixtures.isolatePolicies(policyRepository);
        WorkflowFixtures.policy(policyRepository, "LH manager only", Role.MANAGER);

        Department department = departmentRepository.save(new Department("Http Late", "HTTPLATE"));
        Department other = departmentRepository.save(new Department("Http Late Other", "HTTPLOTH"));
        employee = WorkflowFixtures.user(userRepository, "lh-employee", Role.EMPLOYEE, department);
        manager = WorkflowFixtures.user(userRepository, "lh-manager", Role.MANAGER, department);
        secondManager = WorkflowFixtures.user(userRepository, "lh-manager2", Role.MANAGER, department);
        outsiderManager = WorkflowFixtures.user(userRepository, "lh-outsider", Role.MANAGER, other);

        // Both managers are told; the first one approves; the second one arrives late
        requestId = requestService.createAndSubmit(WorkflowFixtures.form("5000"), employee).getId();
        approvalService.approve(requestId, manager, "Approved by the first manager");
    }

    private static RequestPostProcessor as(User account) {
        return user(AuthenticatedUser.from(account));
    }

    @Test
    void aLateApproverOpeningTheRequestSeesAReadOnlyPageNotA404() throws Exception {
        MvcResult result = mockMvc.perform(get("/manager/requests/" + requestId).with(as(secondManager)))
                .andExpect(status().isOk())
                .andExpect(view().name("approval-review"))
                .andReturn();

        Review review = (Review) result.getModelAndView().getModel().get("review");
        assertFalse(review.isCanDecide());

        String html = result.getResponse().getContentAsString();
        assertTrue(html.contains("Read only"));
        assertTrue(html.contains("already approved"));
        assertTrue(html.contains(manager.getFullName()));
        assertFalse(html.contains("Your decision"), "the decision form must be hidden");
    }

    @Test
    void aLateApproveClickRedirectsWithAFriendlyMessageAndThatPageLoads() throws Exception {
        MvcResult result = mockMvc.perform(post("/manager/requests/" + requestId + "/approve")
                        .with(as(secondManager)).with(csrf()).param("comment", "too late"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/requests/" + requestId))
                .andExpect(flash().attributeExists("errorMessage"))
                .andReturn();

        String message = (String) result.getFlashMap().get("errorMessage");
        assertTrue(message.contains(manager.getFullName()));
        assertTrue(message.contains("not recorded"));

        mockMvc.perform(get("/manager/requests/" + requestId).with(as(secondManager)))
                .andExpect(status().isOk());
    }

    @Test
    void aLateRejectDoesNotChangeTheOutcome() throws Exception {
        mockMvc.perform(post("/manager/requests/" + requestId + "/reject")
                        .with(as(secondManager)).with(csrf()).param("comment", "I disagree"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/requests/" + requestId))
                .andExpect(flash().attributeExists("errorMessage"));

        assertEquals(RequestStatus.APPROVED, requestRepository.findById(requestId).orElseThrow().getStatus());
    }

    @Test
    void openingTheOldNotificationLeadsToTheReadOnlyPage() throws Exception {
        Notification notification = notificationRepository
                .findByRecipientIdOrderByIdDesc(secondManager.getId(), PageRequest.of(0, 10))
                .getContent().get(0);

        mockMvc.perform(post("/notifications/" + notification.getId() + "/open").with(as(secondManager)).with(csrf()))
                .andExpect(redirectedUrl("/manager/requests/" + requestId));
        mockMvc.perform(get("/manager/requests/" + requestId).with(as(secondManager)))
                .andExpect(status().isOk());
    }

    @Test
    void aManagerFromAnotherDepartmentStillGetsNotFound() throws Exception {
        mockMvc.perform(get("/manager/requests/" + requestId).with(as(outsiderManager)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/manager/requests/" + requestId + "/approve").with(as(outsiderManager)).with(csrf()))
                .andExpect(status().isNotFound());
    }
}