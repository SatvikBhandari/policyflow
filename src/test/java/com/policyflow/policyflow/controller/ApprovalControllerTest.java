package com.policyflow.policyflow.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.Request;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.RequestStatus;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.repository.ApprovalPolicyRepository;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.RequestRepository;
import com.policyflow.policyflow.repository.UserRepository;
import com.policyflow.policyflow.security.AuthenticatedUser;
import com.policyflow.policyflow.service.RequestService;
import com.policyflow.policyflow.support.WorkflowFixtures;

@SpringBootTest
@Transactional // everything is rolled back, so your real data and policies are untouched
class ApprovalControllerTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ApprovalPolicyRepository policyRepository;
    @Autowired private RequestRepository requestRepository;
    @Autowired private RequestService requestService;

    private MockMvc mockMvc;
    private User employee;
    private User colleague;
    private User manager;
    private User finance;
    private User outsiderManager;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        WorkflowFixtures.isolatePolicies(policyRepository);
        WorkflowFixtures.policy(policyRepository, "WFC manager only", Role.MANAGER);

        Department department = departmentRepository.save(new Department("Controller Test", "WFCTEST"));
        Department other = departmentRepository.save(new Department("Controller Other", "WFCOTHER"));
        employee = WorkflowFixtures.user(userRepository, "wfc-employee", Role.EMPLOYEE, department);
        colleague = WorkflowFixtures.user(userRepository, "wfc-colleague", Role.EMPLOYEE, department);
        manager = WorkflowFixtures.user(userRepository, "wfc-manager", Role.MANAGER, department);
        finance = WorkflowFixtures.user(userRepository, "wfc-finance", Role.FINANCE_OFFICER, other);
        outsiderManager = WorkflowFixtures.user(userRepository, "wfc-outsider", Role.MANAGER, other);
    }

    private static RequestPostProcessor as(User account) {
        return user(AuthenticatedUser.from(account));
    }

    private Request submit() {
        return requestService.createAndSubmit(WorkflowFixtures.form("5000"), employee);
    }

    private RequestStatus statusOf(Long id) {
        return requestRepository.findById(id).orElseThrow().getStatus();
    }

    @Test
    void managerSeesTheQueuePage() throws Exception {
        submit();

        mockMvc.perform(get("/manager").with(as(manager)))
                .andExpect(status().isOk())
                .andExpect(view().name("approval-queue"))
                .andExpect(model().attributeExists("queue", "decided", "areaPath"));
    }

    @Test
    void managerCanOpenTheReviewPage() throws Exception {
        Long id = submit().getId();

        mockMvc.perform(get("/manager/requests/" + id).with(as(manager)))
                .andExpect(status().isOk())
                .andExpect(view().name("approval-review"))
                .andExpect(model().attributeExists("review", "approveUrl", "rejectUrl"));
    }

    @Test
    void managerApprovesThroughTheEndpoint() throws Exception {
        Long id = submit().getId();

        mockMvc.perform(post("/manager/requests/" + id + "/approve")
                        .with(as(manager)).with(csrf()).param("comment", "Fine"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager"))
                .andExpect(flash().attributeExists("successMessage"));

        assertEquals(RequestStatus.APPROVED, statusOf(id));
    }

    @Test
    void rejectingWithoutACommentIsRefused() throws Exception {
        Long id = submit().getId();

        mockMvc.perform(post("/manager/requests/" + id + "/reject")
                        .with(as(manager)).with(csrf()).param("comment", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/requests/" + id))
                .andExpect(flash().attributeExists("errorMessage"));

        assertEquals(RequestStatus.PENDING_APPROVAL, statusOf(id));
    }

    @Test
    void rejectingWithACommentRejectsTheRequest() throws Exception {
        Long id = submit().getId();

        mockMvc.perform(post("/manager/requests/" + id + "/reject")
                        .with(as(manager)).with(csrf()).param("comment", "Not justified"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager"))
                .andExpect(flash().attributeExists("successMessage"));

        assertEquals(RequestStatus.REJECTED, statusOf(id));
    }

    @Test
    void employeeCannotUseTheApprovalEndpoints() throws Exception {
        Long id = submit().getId();

        mockMvc.perform(post("/manager/requests/" + id + "/approve").with(as(employee)).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/manager").with(as(employee)))
                .andExpect(status().isForbidden());
        assertEquals(RequestStatus.PENDING_APPROVAL, statusOf(id));
    }

    @Test
    void financeOfficerCannotUseTheManagerArea() throws Exception {
        Long id = submit().getId();

        mockMvc.perform(post("/manager/requests/" + id + "/approve").with(as(finance)).with(csrf()))
                .andExpect(status().isForbidden());
        assertEquals(RequestStatus.PENDING_APPROVAL, statusOf(id));
    }

    @Test
    void approvalWithoutCsrfTokenIsForbidden() throws Exception {
        Long id = submit().getId();

        mockMvc.perform(post("/manager/requests/" + id + "/approve").with(as(manager)))
                .andExpect(status().isForbidden());
        assertEquals(RequestStatus.PENDING_APPROVAL, statusOf(id));
    }

    @Test
    void managerFromAnotherDepartmentGetsNotFound() throws Exception {
        Long id = submit().getId();

        mockMvc.perform(post("/manager/requests/" + id + "/approve").with(as(outsiderManager)).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/manager/requests/" + id).with(as(outsiderManager)))
                .andExpect(status().isNotFound());
        assertEquals(RequestStatus.PENDING_APPROVAL, statusOf(id));
    }

    @Test
    void employeeCancelsOwnRequest() throws Exception {
        Long id = submit().getId();

        mockMvc.perform(post("/requests/" + id + "/cancel")
                        .with(as(employee)).with(csrf()).param("reason", "Changed plans"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/requests"))
                .andExpect(flash().attributeExists("successMessage"));

        assertEquals(RequestStatus.CANCELLED, statusOf(id));
    }

    @Test
    void otherEmployeeCannotCancel() throws Exception {
        Long id = submit().getId();

        mockMvc.perform(post("/requests/" + id + "/cancel").with(as(colleague)).with(csrf()))
                .andExpect(status().isNotFound());
        assertEquals(RequestStatus.PENDING_APPROVAL, statusOf(id));
    }

    @Test
    void ownerSeesApprovalHistoryOnTheDetailsPage() throws Exception {
        Long id = submit().getId();

        mockMvc.perform(get("/requests/" + id).with(as(employee)))
                .andExpect(status().isOk())
                .andExpect(view().name("request-details"))
                .andExpect(model().attributeExists("request", "history"));
    }
}