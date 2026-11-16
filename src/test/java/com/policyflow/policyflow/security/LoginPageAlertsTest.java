package com.policyflow.policyflow.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class LoginPageAlertsTest {

    private static final String ERROR_TEXT = "Sign in failed";
    private static final String LOGOUT_TEXT = "Signed out";

    @Autowired private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private String page(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void aPlainLoginPageShowsNoAlert() throws Exception {
        String html = page(get("/login"));

        assertFalse(html.contains(ERROR_TEXT));
        assertFalse(html.contains(LOGOUT_TEXT));
        assertTrue(html.contains("id=\"togglePassword\""));
    }

    @Test
    void aFailedLoginShowsOnlyTheErrorAlert() throws Exception {
        String html = page(get("/login").param("error", ""));

        assertTrue(html.contains(ERROR_TEXT));
        assertFalse(html.contains(LOGOUT_TEXT));
    }

    @Test
    void aLogoutShowsOnlyTheSignedOutAlert() throws Exception {
        String html = page(get("/login").param("logout", ""));

        assertTrue(html.contains(LOGOUT_TEXT));
        assertFalse(html.contains(ERROR_TEXT));
    }

    @Test
    void errorWinsWhenBothParametersArePresent() throws Exception {
        String html = page(get("/login").param("error", "").param("logout", ""));

        assertTrue(html.contains(ERROR_TEXT));
        assertFalse(html.contains(LOGOUT_TEXT));
    }

    @Test
    void theRedesignedPageKeepsTheFormSecurityFeatures() throws Exception {
        String html = page(get("/login"));

        assertTrue(html.contains("name=\"_csrf\""), "the login form must carry a CSRF token");
        assertTrue(html.contains("aria-label=\"Show password\""));
        assertFalse(html.toLowerCase().contains("remember"), "remember-me is intentionally not offered");
    }
}