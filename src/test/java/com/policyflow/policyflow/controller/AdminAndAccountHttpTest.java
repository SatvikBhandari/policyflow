package com.policyflow.policyflow.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.UserRepository;
import com.policyflow.policyflow.security.AuthenticatedUser;

@SpringBootTest
@Transactional // everything is rolled back, so your real users are untouched
class AdminAndAccountHttpTest {

    private static final String PASSWORD = "Test@12345";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;
    private Department department;
    private User admin;
    private User employee;
    private User manager;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        department = departmentRepository.save(new Department("Http Admin Test", "HTTPADM"));
        admin = account("hadm-admin", Role.ADMIN);
        employee = account("hadm-employee", Role.EMPLOYEE);
        manager = account("hadm-manager", Role.MANAGER);
    }

    // ---- helpers ----

    private User account(String prefix, Role role) {
        User account = new User(role.getLabel() + " " + prefix, prefix + "@policyflow.local", role, department);
        account.setPasswordHash(passwordEncoder.encode(PASSWORD));
        return userRepository.saveAndFlush(account);
    }

    private static RequestPostProcessor as(User account) {
        return user(AuthenticatedUser.from(account));
    }

    private MockHttpSession login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .param("email", email)
                        .param("password", password)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session, "a successful login should create a session");
        return session;
    }

    private User reload(User account) {
        return userRepository.findById(account.getId()).orElseThrow();
    }

    // ---- access control ----

    @Test
    void nonAdminsCannotUseUserManagement() throws Exception {
        for (User blocked : new User[] {employee, manager}) {
            mockMvc.perform(get("/admin/users").with(as(blocked))).andExpect(status().isForbidden());
            mockMvc.perform(get("/admin/users/new").with(as(blocked))).andExpect(status().isForbidden());
            mockMvc.perform(post("/admin/users").with(as(blocked)).with(csrf())).andExpect(status().isForbidden());
            mockMvc.perform(post("/admin/users/" + employee.getId() + "/deactivate").with(as(blocked)).with(csrf()))
                    .andExpect(status().isForbidden());
        }
        assertTrue(reload(employee).isActive());
    }

    @Test
    void anonymousVisitorsAreSentToLogin() throws Exception {
        mockMvc.perform(get("/admin/users"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // ---- listing and creating ----

    @Test
    void adminSeesTheUserList() throws Exception {
        mockMvc.perform(get("/admin/users").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-users"))
                .andExpect(model().attributeExists("users", "roles"));
    }

    @Test
    void adminCreatesAUserOverHttp() throws Exception {
        mockMvc.perform(post("/admin/users").with(as(admin)).with(csrf())
                        .param("fullName", "Priya Nair")
                        .param("email", "Priya.Nair@policyflow.local")
                        .param("role", "MANAGER")
                        .param("departmentId", String.valueOf(department.getId()))
                        .param("temporaryPassword", "Temp@12345"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"))
                .andExpect(flash().attributeExists("successMessage"));

        User created = userRepository.findByEmail("priya.nair@policyflow.local").orElseThrow();
        assertEquals(Role.MANAGER, created.getRole());
        assertTrue(created.isMustChangePassword());
        assertTrue(passwordEncoder.matches("Temp@12345", created.getPasswordHash()));
    }

    @Test
    void emptyCreateFormShowsFieldErrorsAndSavesNothing() throws Exception {
        long before = userRepository.count();

        mockMvc.perform(post("/admin/users").with(as(admin)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-user-form"))
                .andExpect(model().attributeHasFieldErrors("userForm",
                        "fullName", "email", "role", "departmentId", "temporaryPassword"));

        assertEquals(before, userRepository.count());
    }

    @Test
    void weakTemporaryPasswordIsReportedOnItsField() throws Exception {
        mockMvc.perform(post("/admin/users").with(as(admin)).with(csrf())
                        .param("fullName", "Weak Password")
                        .param("email", "weak.password@policyflow.local")
                        .param("role", "EMPLOYEE")
                        .param("departmentId", String.valueOf(department.getId()))
                        .param("temporaryPassword", "abc"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-user-form"))
                .andExpect(model().attributeHasFieldErrors("userForm", "temporaryPassword"));

        assertFalse(userRepository.existsByEmail("weak.password@policyflow.local"));
    }

    @Test
    void duplicateEmailIsReportedOnItsField() throws Exception {
        mockMvc.perform(post("/admin/users").with(as(admin)).with(csrf())
                        .param("fullName", "Copy Cat")
                        .param("email", employee.getEmail())
                        .param("role", "EMPLOYEE")
                        .param("departmentId", String.valueOf(department.getId()))
                        .param("temporaryPassword", "Temp@12345"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-user-form"))
                .andExpect(model().attributeHasFieldErrors("userForm", "email"));
    }

    // ---- editing, activating, deactivating ----

    @Test
    void adminEditsAUser() throws Exception {
        mockMvc.perform(post("/admin/users/" + employee.getId() + "/edit").with(as(admin)).with(csrf())
                        .param("fullName", "Promoted Employee")
                        .param("role", "FINANCE_OFFICER")
                        .param("departmentId", String.valueOf(department.getId())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"))
                .andExpect(flash().attributeExists("successMessage"));

        User updated = reload(employee);
        assertEquals("Promoted Employee", updated.getFullName());
        assertEquals(Role.FINANCE_OFFICER, updated.getRole());
    }

    @Test
    void deactivatedUserCannotSignInAndReactivationRestoresAccess() throws Exception {
        mockMvc.perform(post("/admin/users/" + employee.getId() + "/deactivate").with(as(admin)).with(csrf()))
                .andExpect(redirectedUrl("/admin/users"))
                .andExpect(flash().attributeExists("successMessage"));
        assertFalse(reload(employee).isActive());

        mockMvc.perform(post("/login").param("email", employee.getEmail()).param("password", PASSWORD).with(csrf()))
                .andExpect(redirectedUrl("/login?error"));

        mockMvc.perform(post("/admin/users/" + employee.getId() + "/activate").with(as(admin)).with(csrf()))
                .andExpect(redirectedUrl("/admin/users"));
        assertTrue(reload(employee).isActive());
        login(employee.getEmail(), PASSWORD); // can sign in again
    }

    @Test
    void adminCannotDeactivateTheirOwnAccount() throws Exception {
        mockMvc.perform(post("/admin/users/" + admin.getId() + "/deactivate").with(as(admin)).with(csrf()))
                .andExpect(redirectedUrl("/admin/users"))
                .andExpect(flash().attributeExists("errorMessage"));

        assertTrue(reload(admin).isActive());
    }

    // ---- passwords ----

    @Test
    void resetPasswordForcesTheUserToChooseANewOneBeforeContinuing() throws Exception {
        mockMvc.perform(post("/admin/users/" + employee.getId() + "/reset-password").with(as(admin)).with(csrf())
                        .param("temporaryPassword", "Temp@12345"))
                .andExpect(redirectedUrl("/admin/users"))
                .andExpect(flash().attributeExists("successMessage"));

        MockHttpSession session = login(employee.getEmail(), "Temp@12345");

        // Every ordinary page is redirected to the change-password page
        mockMvc.perform(get("/requests").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/account/password"));
        mockMvc.perform(get("/").session(session))
                .andExpect(redirectedUrl("/account/password"));

        // The change-password page itself is reachable and says the change is required
        mockMvc.perform(get("/account/password").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("change-password"))
                .andExpect(model().attribute("forced", true));

        mockMvc.perform(post("/account/password").session(session).with(csrf())
                        .param("currentPassword", "Temp@12345")
                        .param("newPassword", "Fresh@12345")
                        .param("confirmPassword", "Fresh@12345"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/account/password"))
                .andExpect(flash().attributeExists("successMessage"));

        // Afterwards the normal pages work again
        mockMvc.perform(get("/requests").session(session)).andExpect(status().isOk());
        assertFalse(reload(employee).isMustChangePassword());
    }

    @Test
    void wrongCurrentPasswordIsReportedOnItsField() throws Exception {
        mockMvc.perform(post("/account/password").with(as(employee)).with(csrf())
                        .param("currentPassword", "Wrong@12345")
                        .param("newPassword", "Fresh@12345")
                        .param("confirmPassword", "Fresh@12345"))
                .andExpect(status().isOk())
                .andExpect(view().name("change-password"))
                .andExpect(model().attributeHasFieldErrors("passwordForm", "currentPassword"));

        assertTrue(passwordEncoder.matches(PASSWORD, reload(employee).getPasswordHash()));
    }

    // ---- sessions that are out of date ----

    @Test
    void aRoleChangeMidSessionIsRefusedUntilTheUserSignsInAgain() throws Exception {
        AuthenticatedUser sessionPrincipal = AuthenticatedUser.from(employee); // signed in as an Employee
        employee.setRole(Role.MANAGER);
        userRepository.saveAndFlush(employee); // an administrator changes the role afterwards

        mockMvc.perform(get("/requests").with(user(sessionPrincipal)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aDeactivatedUsersLiveSessionIsRefused() throws Exception {
        AuthenticatedUser sessionPrincipal = AuthenticatedUser.from(employee);
        employee.setActive(false);
        userRepository.saveAndFlush(employee);

        mockMvc.perform(get("/requests").with(user(sessionPrincipal)))
                .andExpect(status().isForbidden());
    }
}