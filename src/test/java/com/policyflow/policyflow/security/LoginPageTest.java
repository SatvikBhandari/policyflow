package com.policyflow.policyflow.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.UserRepository;

@SpringBootTest
@Transactional // test data is rolled back, so nothing stays in MySQL
class LoginPageTest {

    private static final String PASSWORD = "Test@12345";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;
    private User user;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Department department = departmentRepository.save(new Department("Login Test", "LOGINTST"));
        user = new User("Login Tester", "login-test@policyflow.local", Role.EMPLOYEE, department);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user = userRepository.save(user);
    }

    private String loginPageHtml() throws Exception {
        return mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void loginPageHasPasswordToggleAndCsrfToken() throws Exception {
        String html = loginPageHtml();

        assertTrue(html.contains("id=\"togglePassword\""));
        assertTrue(html.contains("aria-label=\"Show password\""));
        assertTrue(html.contains("name=\"_csrf\""), "login form must carry a CSRF token");
    }

    @Test
    void loginPageHasNoRememberMeOption() throws Exception {
        assertFalse(loginPageHtml().toLowerCase().contains("remember"));
    }

    @Test
    void rememberMeParameterDoesNotIssueALongLivedCookie() throws Exception {
        mockMvc.perform(post("/login")
                        .param("email", user.getEmail())
                        .param("password", PASSWORD)
                        .param("remember-me", "on")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(cookie().doesNotExist("remember-me"));
    }
}