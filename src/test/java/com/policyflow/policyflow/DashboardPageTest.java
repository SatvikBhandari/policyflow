package com.policyflow.policyflow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.policyflow.policyflow.dto.RequestForm;
import com.policyflow.policyflow.support.ApproverFixtures;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.UserRepository;
import com.policyflow.policyflow.security.AuthenticatedUser;
import com.policyflow.policyflow.service.RequestService;

@SpringBootTest
@Transactional // test data is rolled back, so nothing stays in MySQL
class DashboardPageTest {

    private static final String UNIQUE_TITLE = "Dashboard visibility check 7731";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RequestService requestService;

    private MockMvc mockMvc;
    private User alice;
    private User bob;
    private User admin;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Department department = departmentRepository.save(new Department("Dashboard Test", "DASHTST"));
        ApproverFixtures.createApprovers(userRepository, department, "dash");
        alice = createUser("alice-dash@policyflow.local", "Alice Dash", Role.EMPLOYEE, department);
        bob = createUser("bob-dash@policyflow.local", "Bob Dash", Role.EMPLOYEE, department);
        admin = createUser("admin-dash@policyflow.local", "Admin Dash", Role.ADMIN, department);

        RequestForm form = new RequestForm();
        form.setTitle(UNIQUE_TITLE);
        form.setCategory(RequestCategory.HARDWARE);
        form.setAmount(new BigDecimal("85000.00"));
        form.setDescription("Created by an automated test.");
        requestService.createAndSubmit(form, alice);
    }

    private User createUser(String email, String name, Role role, Department department) {
        User user = new User(name, email, role, department);
        user.setPasswordHash(passwordEncoder.encode("Test@12345"));
        return userRepository.save(user);
    }

    private String dashboardFor(User user) throws Exception {
        return mockMvc.perform(get("/").with(user(AuthenticatedUser.from(user))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void employeeSeesTheirOwnRequestOnTheDashboard() throws Exception {
        String html = dashboardFor(alice);

        assertTrue(html.contains("Request lifecycle"));
        assertTrue(html.contains(UNIQUE_TITLE));
        assertTrue(html.contains("Alice"), "greeting should use the first name");
    }

    @Test
    void employeeDoesNotSeeAnotherEmployeesRequestAndGetsAnEmptyState() throws Exception {
        String html = dashboardFor(bob);

        assertFalse(html.contains(UNIQUE_TITLE));
        assertTrue(html.contains("No requests yet"));
    }

    @Test
    void adminGetsTheAdministratorViewWithoutRequestWidgets() throws Exception {
        String html = dashboardFor(admin);

        assertTrue(html.contains("Administrator workspace"));
        assertFalse(html.contains("Request lifecycle"));
        assertFalse(html.contains(UNIQUE_TITLE));
    }
}