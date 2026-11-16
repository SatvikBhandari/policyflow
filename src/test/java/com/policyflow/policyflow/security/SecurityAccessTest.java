package com.policyflow.policyflow.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.policyflow.policyflow.dto.RequestForm;
import com.policyflow.policyflow.support.ApproverFixtures;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.Request;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.UserRepository;
import com.policyflow.policyflow.service.RequestService;

@SpringBootTest
@Transactional // test data is rolled back, so nothing stays in MySQL
class SecurityAccessTest {

    private static final String PASSWORD = "Test@12345";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RequestService requestService;
    @Autowired private CurrentUserProvider currentUserProvider;

    private MockMvc mockMvc;
    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Department department = departmentRepository.save(new Department("Security Test", "SECTST"));
        ApproverFixtures.createApprovers(userRepository, department, "sec");
        alice = createUser("alice-sec@policyflow.local", "Alice Test", Role.EMPLOYEE, department);
        bob = createUser("bob-sec@policyflow.local", "Bob Test", Role.EMPLOYEE, department);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private User createUser(String email, String name, Role role, Department department) {
        User user = new User(name, email, role, department);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        return userRepository.save(user);
    }

    private RequestForm sampleForm() {
        RequestForm form = new RequestForm();
        form.setTitle("Security test request");
        form.setCategory(RequestCategory.SOFTWARE);
        form.setAmount(new BigDecimal("1500.00"));
        form.setDescription("Created by an automated test.");
        return form;
    }

    // ---- Authentication and redirects ----

    @Test
    void unauthenticatedUserIsRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/requests"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void loginPageIsPublic() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"));
    }

    @Test
    void validLoginAuthenticatesAndRedirectsHome() throws Exception {
        mockMvc.perform(post("/login")
                        .param("email", alice.getEmail())
                        .param("password", PASSWORD)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withUsername(alice.getEmail()));
    }

    @Test
    void wrongPasswordRedirectsBackToLoginWithError() throws Exception {
        mockMvc.perform(post("/login")
                        .param("email", alice.getEmail())
                        .param("password", "wrong-password")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());
    }

    @Test
    void loginWithoutCsrfTokenIsRejected() throws Exception {
        mockMvc.perform(post("/login")
                        .param("email", alice.getEmail())
                        .param("password", PASSWORD))
                .andExpect(status().isForbidden());
    }

    // ---- Existing Module 3 behaviour under an authenticated principal ----

    @Test
    void authenticatedEmployeeCanOpenMyRequests() throws Exception {
        mockMvc.perform(get("/requests").with(user(AuthenticatedUser.from(alice))))
                .andExpect(status().isOk())
                .andExpect(view().name("my-requests"));
    }

    @Test
    void employeeCannotOpenAnotherEmployeesRequest() throws Exception {
        Request saved = requestService.createAndSubmit(sampleForm(), alice);

        mockMvc.perform(get("/requests/" + saved.getId()).with(user(AuthenticatedUser.from(bob))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/requests/" + saved.getId()).with(user(AuthenticatedUser.from(alice))))
                .andExpect(status().isOk());
    }

    @Test
    void requestListShowsOnlyTheLoggedInEmployeesRequests() throws Exception {
        requestService.createAndSubmit(sampleForm(), alice);

        MvcResult aliceResult = mockMvc.perform(get("/requests").with(user(AuthenticatedUser.from(alice))))
                .andExpect(status().isOk()).andReturn();
        MvcResult bobResult = mockMvc.perform(get("/requests").with(user(AuthenticatedUser.from(bob))))
                .andExpect(status().isOk()).andReturn();

        List<?> aliceRequests = (List<?>) aliceResult.getModelAndView().getModel().get("requests");
        List<?> bobRequests = (List<?>) bobResult.getModelAndView().getModel().get("requests");
        assertEquals(1, aliceRequests.size());
        assertTrue(bobRequests.isEmpty());
    }

    // ---- Logout ----

    @Test
    void logoutRedirectsToLoginWithMessage() throws Exception {
        mockMvc.perform(post("/logout").with(user(AuthenticatedUser.from(alice))).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));
    }

    @Test
    void logoutWithoutCsrfTokenIsRejected() throws Exception {
        mockMvc.perform(post("/logout").with(user(AuthenticatedUser.from(alice))))
                .andExpect(status().isForbidden());
    }

    // ---- Principal linked to the database user ----

    @Test
    void currentUserProviderReturnsTheDatabaseUserOfThePrincipal() {
        AuthenticatedUser principal = AuthenticatedUser.from(bob);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));

        assertEquals(bob.getId(), currentUserProvider.getCurrentUser().getId());
    }

    @Test
    void currentUserProviderRefusesWhenNobodyIsLoggedIn() {
        SecurityContextHolder.clearContext();

        assertThrows(AuthenticationCredentialsNotFoundException.class,
                () -> currentUserProvider.getCurrentUser());
    }
}