package com.policyflow.policyflow.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.policyflow.policyflow.dto.AdminViews.AuditEntry;
import com.policyflow.policyflow.dto.AdminViews.StuckRequest;
import com.policyflow.policyflow.dto.ApprovalViews.HistoryEntry;
import com.policyflow.policyflow.entity.AuditLog;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.RequestStatus;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.repository.ApprovalPolicyRepository;
import com.policyflow.policyflow.repository.AuditLogRepository;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.RequestRepository;
import com.policyflow.policyflow.repository.UserRepository;
import com.policyflow.policyflow.security.AuthenticatedUser;
import com.policyflow.policyflow.service.RequestService;
import com.policyflow.policyflow.support.WorkflowFixtures;

@SpringBootTest
@Transactional // everything is rolled back, so your real data is untouched
class AdminOversightHttpTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ApprovalPolicyRepository policyRepository;
    @Autowired private RequestRepository requestRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private RequestService requestService;

    private MockMvc mockMvc;
    private User admin;
    private User employee;
    private User manager;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        WorkflowFixtures.isolatePolicies(policyRepository);
        WorkflowFixtures.policy(policyRepository, "HOV manager only", Role.MANAGER);

        Department department = departmentRepository.save(new Department("Http Oversight", "HTTPOV"));
        admin = WorkflowFixtures.user(userRepository, "hov-admin", Role.ADMIN, department);
        employee = WorkflowFixtures.user(userRepository, "hov-employee", Role.EMPLOYEE, department);
        manager = WorkflowFixtures.user(userRepository, "hov-manager", Role.MANAGER, department);
    }

    // ---- helpers ----

    private static RequestPostProcessor as(User account) {
        return user(AuthenticatedUser.from(account));
    }

    private Long submitStuckRequest() {
        Long id = requestService.createAndSubmit(WorkflowFixtures.form("5000"), employee).getId();
        manager.setActive(false);
        userRepository.saveAndFlush(manager);
        return id;
    }

    private RequestStatus statusOf(Long id) {
        return requestRepository.findById(id).orElseThrow().getStatus();
    }

    // ---- access control ----

    @Test
    void nonAdminsAreForbidden() throws Exception {
        for (User blocked : new User[] {employee, manager}) {
            mockMvc.perform(get("/admin").with(as(blocked))).andExpect(status().isForbidden());
            mockMvc.perform(get("/admin/audit").with(as(blocked))).andExpect(status().isForbidden());
            mockMvc.perform(get("/admin/stuck").with(as(blocked))).andExpect(status().isForbidden());
            mockMvc.perform(post("/admin/stuck/1/close").with(as(blocked)).with(csrf()).param("reason", "x"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void anonymousVisitorsAreSentToLogin() throws Exception {
        for (String path : new String[] {"/admin", "/admin/audit", "/admin/stuck"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login"));
        }
    }

    // ---- overview and audit log ----

    @Test
    void theOverviewRenders() throws Exception {
        MvcResult result = mockMvc.perform(get("/admin").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-overview"))
                .andExpect(model().attributeExists("overview"))
                .andReturn();

        assertTrue(result.getResponse().getContentAsString().contains("Recent activity"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAuditPageFiltersEntries() throws Exception {
        auditLogRepository.save(new AuditLog(1L, "zz6c.http@policyflow.local", "ZZ6C_HTTP",
                "ZZ6C_HTTP_ENTITY", 1L, "http-audit-marker-6c"));

        MvcResult result = mockMvc.perform(get("/admin/audit").with(as(admin))
                        .param("action", "ZZ6C_HTTP").param("entityType", "").param("actor", "")
                        .param("from", "").param("to", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-audit"))
                .andReturn();

        Page<AuditEntry> entries = (Page<AuditEntry>) result.getModelAndView().getModel().get("entries");
        assertEquals(1, entries.getContent().size());
        assertTrue(result.getResponse().getContentAsString().contains("http-audit-marker-6c"));
    }

    @Test
    void invalidDatesShowAnErrorMessage() throws Exception {
        mockMvc.perform(get("/admin/audit").with(as(admin)).param("from", "not-a-date"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-audit"))
                .andExpect(model().attributeExists("errorMessage"));

        mockMvc.perform(get("/admin/audit").with(as(admin)).param("from", "2026-12-31").param("to", "2026-01-01"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("errorMessage"));
    }

    // ---- stuck requests ----

    @Test
    @SuppressWarnings("unchecked")
    void theStuckPageListsBlockedRequests() throws Exception {
        Long id = submitStuckRequest();

        MvcResult result = mockMvc.perform(get("/admin/stuck").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-stuck"))
                .andReturn();

        List<StuckRequest> stuck = (List<StuckRequest>) result.getModelAndView().getModel().get("stuck");
        assertTrue(stuck.stream().anyMatch(s -> s.getRequestId().equals(id)));
        assertTrue(result.getResponse().getContentAsString().contains("Workflow test request"));
    }

    @Test
    void closingWithoutAReasonIsRefused() throws Exception {
        Long id = submitStuckRequest();

        mockMvc.perform(post("/admin/stuck/" + id + "/close").with(as(admin)).with(csrf()).param("reason", " "))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/stuck"))
                .andExpect(flash().attributeExists("errorMessage"));

        assertEquals(RequestStatus.PENDING_APPROVAL, statusOf(id));
    }

    @Test
    @SuppressWarnings("unchecked")
    void closingWithAReasonRejectsTheRequestAndTheOwnerSeesWhy() throws Exception {
        Long id = submitStuckRequest();

        mockMvc.perform(post("/admin/stuck/" + id + "/close").with(as(admin)).with(csrf())
                        .param("reason", "The department has no manager right now"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/stuck"))
                .andExpect(flash().attributeExists("successMessage"));

        assertEquals(RequestStatus.REJECTED, statusOf(id));

        MvcResult details = mockMvc.perform(get("/requests/" + id).with(as(employee)))
                .andExpect(status().isOk())
                .andExpect(view().name("request-details"))
                .andReturn();
        List<HistoryEntry> history = (List<HistoryEntry>) details.getModelAndView().getModel().get("history");
        assertEquals(1, history.size());
        assertEquals("Closed by administrator", history.get(0).getActionLabel());
        assertEquals("The department has no manager right now", history.get(0).getComment());
    }

    @Test
    void closingARequestThatIsNotStuckIsRefused() throws Exception {
        Long id = requestService.createAndSubmit(WorkflowFixtures.form("5000"), employee).getId(); // manager is active

        mockMvc.perform(post("/admin/stuck/" + id + "/close").with(as(admin)).with(csrf()).param("reason", "Trying"))
                .andExpect(redirectedUrl("/admin/stuck"))
                .andExpect(flash().attributeExists("errorMessage"));

        assertEquals(RequestStatus.PENDING_APPROVAL, statusOf(id));
    }

    @Test
    void closingAnUnknownRequestIsNotFound() throws Exception {
        mockMvc.perform(post("/admin/stuck/999999999/close").with(as(admin)).with(csrf()).param("reason", "Nothing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void closingWithoutCsrfIsForbidden() throws Exception {
        Long id = submitStuckRequest();

        mockMvc.perform(post("/admin/stuck/" + id + "/close").with(as(admin)).param("reason", "No token"))
                .andExpect(status().isForbidden());

        assertEquals(RequestStatus.PENDING_APPROVAL, statusOf(id));
    }
}