package com.policyflow.policyflow.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.EnumMap;
import java.util.Map;

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
@Transactional // test users are rolled back, so nothing stays in MySQL
class RoleAccessTest {

    private static final String PASSWORD = "Test@12345";

    /** The page that belongs to each role (EMPLOYEE has none). */
    private static final Map<Role, String> AREA_PATH = new EnumMap<>(Role.class);
    /** Where each role must land after signing in. */
    private static final Map<Role, String> LANDING = new EnumMap<>(Role.class);

    static {
        AREA_PATH.put(Role.MANAGER, "/manager");
        AREA_PATH.put(Role.FINANCE_OFFICER, "/finance");
        AREA_PATH.put(Role.DEPARTMENT_HEAD, "/department");
        AREA_PATH.put(Role.ADMIN, "/admin");

        LANDING.put(Role.EMPLOYEE, "/");
        LANDING.putAll(AREA_PATH);
    }

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;
    private final Map<Role, User> users = new EnumMap<>(Role.class);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Department department = departmentRepository.save(new Department("RBAC Test", "RBACTST"));
        for (Role role : Role.values()) {
            User user = new User("Rbac " + role.getLabel(),
                    "rbac-" + role.name().toLowerCase() + "@policyflow.local", role, department);
            user.setPasswordHash(passwordEncoder.encode(PASSWORD));
            users.put(role, userRepository.save(user));
        }
    }

    private int statusOf(String path, Role loggedInAs) throws Exception {
        return mockMvc.perform(get(path).with(user(AuthenticatedUser.from(users.get(loggedInAs)))))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void eachAreaIsOpenOnlyToItsOwnRole() throws Exception {
        for (Map.Entry<Role, String> area : AREA_PATH.entrySet()) {
            for (Role role : Role.values()) {
                int expected = role == area.getKey() ? 200 : 403;
                assertEquals(expected, statusOf(area.getValue(), role),
                        role + " opening " + area.getValue());
            }
        }
    }

    @Test
    void nonExistentSubPathsStayProtected() throws Exception {
        // Future pages under these prefixes are protected before they even exist.
        for (String path : new String[] {"/admin/users", "/manager/queue", "/finance/review", "/department/escalated"}) {
            assertEquals(403, statusOf(path, Role.EMPLOYEE), "EMPLOYEE opening " + path);
        }
    }

    @Test
    void requestsAreOpenToStaffButNotToAdmin() throws Exception {
        for (Role role : Role.values()) {
            int expected = role == Role.ADMIN ? 403 : 200;
            assertEquals(expected, statusOf("/requests", role), role + " opening /requests");
        }
    }

    @Test
    void rootIsOpenToEveryLoggedInRole() throws Exception {
        for (Role role : Role.values()) {
            assertEquals(200, statusOf("/", role), role + " opening /");
        }
    }

    @Test
    void managerAreaIsTheApprovalQueue() throws Exception {
        mockMvc.perform(get("/manager").with(user(AuthenticatedUser.from(users.get(Role.MANAGER)))))
                .andExpect(status().isOk())
                .andExpect(view().name("approval-queue"));
    }

    @Test
    void adminAreaIsTheOverviewPage() throws Exception {
        mockMvc.perform(get("/admin").with(user(AuthenticatedUser.from(users.get(Role.ADMIN)))))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-overview"));
    }

    @Test
    void unauthenticatedUsersAreRedirectedFromEveryProtectedArea() throws Exception {
        for (String path : new String[] {"/", "/requests", "/manager", "/finance", "/department", "/admin"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login"));
        }
    }

    @Test
    void loginRedirectsEachRoleToItsLandingPage() throws Exception {
        for (Role role : Role.values()) {
            mockMvc.perform(post("/login")
                            .param("email", users.get(role).getEmail())
                            .param("password", PASSWORD)
                            .with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl(LANDING.get(role)));
        }
    }

    @Test
    void loggedInUserVisitingLoginIsSentToTheirLandingPage() throws Exception {
        for (Role role : Role.values()) {
            mockMvc.perform(get("/login").with(user(AuthenticatedUser.from(users.get(role)))))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl(LANDING.get(role)));
        }
    }

    @Test
    void adminCannotSubmitRequests() throws Exception {
        mockMvc.perform(post("/requests")
                        .with(user(AuthenticatedUser.from(users.get(Role.ADMIN))))
                        .with(csrf())
                        .param("title", "Should not be allowed")
                        .param("category", "OTHER")
                        .param("amount", "100")
                        .param("description", "Admin is not a requester."))
                .andExpect(status().isForbidden());
    }
}