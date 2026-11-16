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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.policyflow.policyflow.dto.PolicyEvaluation;
import com.policyflow.policyflow.dto.PolicyForm;
import com.policyflow.policyflow.entity.ApprovalPolicy;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.repository.ApprovalPolicyRepository;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.UserRepository;
import com.policyflow.policyflow.security.AuthenticatedUser;
import com.policyflow.policyflow.service.PolicyAdminService;
import com.policyflow.policyflow.support.WorkflowFixtures;

@SpringBootTest
@Transactional // everything is rolled back, so your real policies and users are untouched
class AdminPolicyHttpTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ApprovalPolicyRepository policyRepository;
    @Autowired private PolicyAdminService policyAdminService;

    private MockMvc mockMvc;
    private User admin;
    private User employee;
    private User manager;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        WorkflowFixtures.isolatePolicies(policyRepository); // only policies created by each test apply

        Department department = departmentRepository.save(new Department("Http Policy Test", "HTTPPOL"));
        admin = userRepository.save(new User("Policy Admin", "hpol-admin@policyflow.local", Role.ADMIN, department));
        employee = userRepository.save(new User("Policy Employee", "hpol-employee@policyflow.local", Role.EMPLOYEE, department));
        manager = userRepository.save(new User("Policy Manager", "hpol-manager@policyflow.local", Role.MANAGER, department));
    }

    // ---- helpers ----

    private static RequestPostProcessor as(User account) {
        return user(AuthenticatedUser.from(account));
    }

    private ApprovalPolicy createPolicy(String name, String min, String max, Role... roles) {
        PolicyForm form = new PolicyForm();
        form.setName(name);
        form.setMinAmount(new BigDecimal(min));
        form.setMaxAmount(max == null ? null : new BigDecimal(max));
        form.setApproverRoles(new LinkedHashSet<>(List.of(roles)));
        form.setActive(true);
        return policyAdminService.create(admin, form);
    }

    // ---- access control ----

    @Test
    void nonAdminsCannotUsePolicyManagement() throws Exception {
        ApprovalPolicy policy = createPolicy("HTTP Guarded", "0", null, Role.MANAGER);

        for (User blocked : new User[] {employee, manager}) {
            mockMvc.perform(get("/admin/policies").with(as(blocked))).andExpect(status().isForbidden());
            mockMvc.perform(get("/admin/policies/new").with(as(blocked))).andExpect(status().isForbidden());
            mockMvc.perform(get("/admin/policies/" + policy.getId() + "/edit").with(as(blocked)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/admin/policies").with(as(blocked)).with(csrf())).andExpect(status().isForbidden());
            mockMvc.perform(post("/admin/policies/" + policy.getId() + "/deactivate").with(as(blocked)).with(csrf())
                    .param("version", String.valueOf(policy.getVersion()))).andExpect(status().isForbidden());
        }
        assertTrue(policyRepository.findById(policy.getId()).orElseThrow().isActive());
    }

    @Test
    void anonymousVisitorsAreSentToLogin() throws Exception {
        mockMvc.perform(get("/admin/policies"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // ---- list and pages ----

    @Test
    void adminSeesThePolicyList() throws Exception {
        createPolicy("HTTP Listed", "0", null, Role.MANAGER);

        mockMvc.perform(get("/admin/policies").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-policies"))
                .andExpect(model().attributeExists("policies", "coverage", "categories", "departments"));
    }

    @Test
    void theEditPageRenders() throws Exception {
        ApprovalPolicy policy = createPolicy("HTTP Editable", "0", "10000", Role.MANAGER);

        mockMvc.perform(get("/admin/policies/" + policy.getId() + "/edit").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-policy-form"))
                .andExpect(model().attribute("editing", true))
                .andExpect(model().attributeExists("policyForm", "target"));
    }

    @Test
    void anUnknownPolicyIsNotFound() throws Exception {
        mockMvc.perform(get("/admin/policies/999999999/edit").with(as(admin)))
                .andExpect(status().isNotFound());
    }

    // ---- creating ----

    @Test
    void adminCreatesAPolicyOverHttp() throws Exception {
        mockMvc.perform(post("/admin/policies").with(as(admin)).with(csrf())
                        .param("name", "HTTP Created")
                        .param("description", "")
                        .param("minAmount", "0")
                        .param("maxAmount", "10000")
                        .param("category", "")
                        .param("departmentId", "")
                        .param("approverRoles", "MANAGER", "FINANCE_OFFICER")
                        .param("active", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/policies"))
                .andExpect(flash().attributeExists("successMessage"));

        ApprovalPolicy created = policyRepository.findByNameIgnoreCase("HTTP Created").orElseThrow();
        assertEquals(List.of(Role.MANAGER, Role.FINANCE_OFFICER), created.getApproverRolesInOrder());
        assertTrue(created.isActive());
        assertEquals(0, new BigDecimal("10000").compareTo(created.getMaxAmount()));
    }

    @Test
    void anEmptyFormShowsFieldErrorsAndSavesNothing() throws Exception {
        long before = policyRepository.count();

        mockMvc.perform(post("/admin/policies").with(as(admin)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-policy-form"))
                .andExpect(model().attributeHasFieldErrors("policyForm", "name", "minAmount", "approverRoles"));

        assertEquals(before, policyRepository.count());
    }

    @Test
    void aMaximumNotAboveTheMinimumIsReportedOnItsField() throws Exception {
        mockMvc.perform(post("/admin/policies").with(as(admin)).with(csrf())
                        .param("name", "HTTP Bad Range")
                        .param("minAmount", "5000")
                        .param("maxAmount", "1000")
                        .param("approverRoles", "MANAGER"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-policy-form"))
                .andExpect(model().attributeHasFieldErrors("policyForm", "maxAmount"));

        assertTrue(policyRepository.findByNameIgnoreCase("HTTP Bad Range").isEmpty());
    }

    // ---- editing ----

    @Test
    void adminEditsAPolicy() throws Exception {
        ApprovalPolicy policy = createPolicy("HTTP Edit Me", "0", "10000", Role.MANAGER);

        mockMvc.perform(post("/admin/policies/" + policy.getId() + "/edit").with(as(admin)).with(csrf())
                        .param("version", String.valueOf(policy.getVersion()))
                        .param("name", "HTTP Edited")
                        .param("description", "")
                        .param("minAmount", "0")
                        .param("maxAmount", "20000")
                        .param("category", "SOFTWARE")
                        .param("departmentId", "")
                        .param("approverRoles", "MANAGER", "FINANCE_OFFICER"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/policies"))
                .andExpect(flash().attributeExists("successMessage"));

        ApprovalPolicy updated = policyRepository.findById(policy.getId()).orElseThrow();
        assertEquals("HTTP Edited", updated.getName());
        assertEquals(RequestCategory.SOFTWARE, updated.getCategory());
        assertEquals(0, new BigDecimal("20000").compareTo(updated.getMaxAmount()));
        assertEquals(List.of(Role.MANAGER, Role.FINANCE_OFFICER), updated.getApproverRolesInOrder());
    }

    @Test
    void editingWithAnOutdatedVersionSavesNothingAndExplainsWhy() throws Exception {
        ApprovalPolicy policy = createPolicy("HTTP Conflict", "0", "10000", Role.MANAGER);

        mockMvc.perform(post("/admin/policies/" + policy.getId() + "/edit").with(as(admin)).with(csrf())
                        .param("version", String.valueOf(policy.getVersion() + 99))
                        .param("name", "HTTP Conflict Edited")
                        .param("minAmount", "0")
                        .param("maxAmount", "10000")
                        .param("approverRoles", "MANAGER"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/policies/" + policy.getId() + "/edit"))
                .andExpect(flash().attributeExists("errorMessage"));

        assertEquals("HTTP Conflict", policyRepository.findById(policy.getId()).orElseThrow().getName());
    }

    // ---- enabling and disabling ----

    @Test
    void adminDisablesAndEnablesAPolicy() throws Exception {
        ApprovalPolicy policy = createPolicy("HTTP Toggle", "0", null, Role.MANAGER);

        mockMvc.perform(post("/admin/policies/" + policy.getId() + "/deactivate").with(as(admin)).with(csrf())
                        .param("version", String.valueOf(policy.getVersion())))
                .andExpect(redirectedUrl("/admin/policies"))
                .andExpect(flash().attributeExists("successMessage"));
        assertFalse(policyRepository.findById(policy.getId()).orElseThrow().isActive());

        Long currentVersion = policyRepository.findById(policy.getId()).orElseThrow().getVersion();
        mockMvc.perform(post("/admin/policies/" + policy.getId() + "/activate").with(as(admin)).with(csrf())
                        .param("version", String.valueOf(currentVersion)))
                .andExpect(redirectedUrl("/admin/policies"))
                .andExpect(flash().attributeExists("successMessage"));
        assertTrue(policyRepository.findById(policy.getId()).orElseThrow().isActive());
    }

    // ---- preview ----

    @Test
    void thePreviewShowsTheApproversForAnAmount() throws Exception {
        createPolicy("HTTP Low", "0", "10000", Role.MANAGER);
        createPolicy("HTTP High", "10000", null, Role.MANAGER, Role.FINANCE_OFFICER);

        MvcResult result = mockMvc.perform(get("/admin/policies").with(as(admin))
                        .param("previewAmount", "20000")
                        .param("previewCategory", "HARDWARE")
                        .param("previewDepartmentId", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-policies"))
                .andExpect(model().attributeExists("preview"))
                .andReturn();

        PolicyEvaluation preview = (PolicyEvaluation) result.getModelAndView().getModel().get("preview");
        assertEquals(List.of(Role.MANAGER, Role.FINANCE_OFFICER), preview.getApproverRoles());
    }

    @Test
    void thePreviewRejectsAnInvalidAmount() throws Exception {
        mockMvc.perform(get("/admin/policies").with(as(admin))
                        .param("previewAmount", "abc")
                        .param("previewCategory", "HARDWARE")
                        .param("previewDepartmentId", ""))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("previewError"))
                .andExpect(model().attributeDoesNotExist("preview"));
    }
}