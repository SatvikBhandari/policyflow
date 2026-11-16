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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.policyflow.policyflow.dto.ApprovalViews.HistoryEntry;
import com.policyflow.policyflow.dto.ApprovalViews.QueueItem;
import com.policyflow.policyflow.dto.DashboardSummary;
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
import com.policyflow.policyflow.support.WorkflowFixtures;

/**
 * The whole workflow over HTTP: form token, validation, submission, approval queues, decisions, dashboard.
 * Everything is rolled back after each test, so your real data and policies are untouched.
 */
@SpringBootTest
@Transactional
class WorkflowHttpFlowTest {

    private static final String PASSWORD = "Test@12345";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ApprovalPolicyRepository policyRepository;
    @Autowired private RequestRepository requestRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;
    private Department department;
    private User employee;
    private User manager;
    private User finance;
    private MockHttpSession employeeSession;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        WorkflowFixtures.isolatePolicies(policyRepository);
        WorkflowFixtures.policy(policyRepository, "HTTP manager and finance", Role.MANAGER, Role.FINANCE_OFFICER);

        department = departmentRepository.save(new Department("Http Flow", "HTTPFLOW"));
        Department financeDepartment = departmentRepository.save(new Department("Http Finance", "HTTPFIN"));

        employee = WorkflowFixtures.user(userRepository, "http-employee", Role.EMPLOYEE, department);
        manager = WorkflowFixtures.user(userRepository, "http-manager", Role.MANAGER, department);
        finance = WorkflowFixtures.user(userRepository, "http-finance", Role.FINANCE_OFFICER, financeDepartment);
        employeeSession = new MockHttpSession();
    }

    // ---- helpers ----

    private static RequestPostProcessor as(User account) {
        return user(AuthenticatedUser.from(account));
    }

    /** Opens the new-request form and returns the one-time form token it contains. */
    private String openForm(User account, MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get("/requests/new").with(as(account)).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("new-request"))
                .andReturn();
        return (String) result.getModelAndView().getModel().get("formToken");
    }

    private ResultActions submitForm(User account, MockHttpSession session, String token,
                                     String title, String category, String amount, String description)
            throws Exception {
        return mockMvc.perform(post("/requests")
                .with(as(account)).with(csrf()).session(session)
                .param("title", title)
                .param("category", category)
                .param("amount", amount)
                .param("description", description)
                .param("formToken", token));
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> modelList(MvcResult result, String name) {
        return (List<T>) result.getModelAndView().getModel().get(name);
    }

    private List<Long> queueIds(String path, User approver) throws Exception {
        MvcResult result = mockMvc.perform(get(path).with(as(approver)))
                .andExpect(status().isOk())
                .andReturn();
        List<QueueItem> queue = modelList(result, "queue");
        return queue.stream().map(QueueItem::getRequestId).toList();
    }

    private Request onlyRequestOf(User account) {
        List<Request> requests = requestRepository.findByEmployeeIdOrderByCreatedAtDesc(account.getId());
        assertEquals(1, requests.size());
        return requests.get(0);
    }

    private User userWithPassword(String prefix, Role role) {
        User account = WorkflowFixtures.user(userRepository, prefix, role, department);
        account.setPasswordHash(passwordEncoder.encode(PASSWORD));
        return userRepository.saveAndFlush(account);
    }

    private MockHttpSession login(User account) throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .param("email", account.getEmail())
                        .param("password", PASSWORD)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session, "a successful login should create a session");
        return session;
    }

    // ---- scenarios ----

    @Test
    void fullFlowFromSubmissionToFinalApprovalOverHttp() throws Exception {
        String token = openForm(employee, employeeSession);
        submitForm(employee, employeeSession, token, "Laptop for new hire", "HARDWARE", "15000",
                "Needed for onboarding.")
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/requests"))
                .andExpect(flash().attributeExists("successMessage"));

        Request created = onlyRequestOf(employee);
        Long id = created.getId();
        assertEquals(RequestStatus.PENDING_APPROVAL, created.getStatus());
        assertEquals(2, created.getStages().size());

        MvcResult list = mockMvc.perform(get("/requests").with(as(employee)).session(employeeSession))
                .andExpect(status().isOk())
                .andReturn();
        List<Request> listed = modelList(list, "requests");
        assertEquals(1, listed.size());

        // At first only the manager has it
        assertTrue(queueIds("/manager", manager).contains(id));
        assertFalse(queueIds("/finance", finance).contains(id));

        mockMvc.perform(post("/manager/requests/" + id + "/approve")
                        .with(as(manager)).with(csrf()).param("comment", "Approved by manager"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager"))
                .andExpect(flash().attributeExists("successMessage"));

        // Then it moves to finance
        assertFalse(queueIds("/manager", manager).contains(id));
        assertTrue(queueIds("/finance", finance).contains(id));

        mockMvc.perform(post("/finance/requests/" + id + "/approve")
                        .with(as(finance)).with(csrf()).param("comment", "Budget available"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/finance"));

        // The employee sees the final result and the full history
        MvcResult details = mockMvc.perform(get("/requests/" + id).with(as(employee)).session(employeeSession))
                .andExpect(status().isOk())
                .andExpect(view().name("request-details"))
                .andReturn();
        Request shown = (Request) details.getModelAndView().getModel().get("request");
        List<HistoryEntry> history = modelList(details, "history");
        assertEquals(RequestStatus.APPROVED, shown.getStatus());
        assertEquals(2, history.size());

        MvcResult home = mockMvc.perform(get("/").with(as(employee)).session(employeeSession))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andReturn();
        DashboardSummary summary = (DashboardSummary) home.getModelAndView().getModel().get("summary");
        assertEquals(1, summary.getTotal());
        assertEquals(1, summary.getApproved());
        assertEquals(0, summary.getPendingApproval());
    }

    @Test
    void repeatedSubmissionWithTheSameTokenCreatesOnlyOneRequest() throws Exception {
        String token = openForm(employee, employeeSession);

        submitForm(employee, employeeSession, token, "Monitor", "HARDWARE", "9000", "A second screen.")
                .andExpect(redirectedUrl("/requests"))
                .andExpect(flash().attributeExists("successMessage"));
        submitForm(employee, employeeSession, token, "Monitor", "HARDWARE", "9000", "A second screen.")
                .andExpect(redirectedUrl("/requests"))
                .andExpect(flash().attributeExists("infoMessage"));

        assertEquals(1, requestRepository.findByEmployeeIdOrderByCreatedAtDesc(employee.getId()).size());
    }

    @Test
    void invalidFormIsRedisplayedWithFieldErrorsAndAFreshToken() throws Exception {
        String token = openForm(employee, employeeSession);

        MvcResult failed = submitForm(employee, employeeSession, token, "", "", "", "")
                .andExpect(status().isOk())
                .andExpect(view().name("new-request"))
                .andExpect(model().attributeHasFieldErrors("requestForm", "title", "category", "amount", "description"))
                .andReturn();
        assertTrue(requestRepository.findByEmployeeIdOrderByCreatedAtDesc(employee.getId()).isEmpty());

        // The redisplayed form carries a fresh token that works
        String freshToken = (String) failed.getModelAndView().getModel().get("formToken");
        assertNotNull(freshToken);
        submitForm(employee, employeeSession, freshToken, "Valid title", "OTHER", "100", "Valid description.")
                .andExpect(redirectedUrl("/requests"));
        assertEquals(1, requestRepository.findByEmployeeIdOrderByCreatedAtDesc(employee.getId()).size());
    }

    @Test
    void nonNumericAmountIsReportedAsAFieldError() throws Exception {
        String token = openForm(employee, employeeSession);

        submitForm(employee, employeeSession, token, "Valid title", "OTHER", "abc", "Valid description.")
                .andExpect(status().isOk())
                .andExpect(view().name("new-request"))
                .andExpect(model().attributeHasFieldErrors("requestForm", "amount"));

        assertTrue(requestRepository.findByEmployeeIdOrderByCreatedAtDesc(employee.getId()).isEmpty());
    }

    @Test
    void requestWithNoEligibleApproverShowsAFormErrorAndSavesNothing() throws Exception {
        Department emptyDepartment = departmentRepository.save(new Department("Http Empty", "HTTPEMPTY"));
        User lonely = WorkflowFixtures.user(userRepository, "http-lonely", Role.EMPLOYEE, emptyDepartment);
        MockHttpSession lonelySession = new MockHttpSession();

        String token = openForm(lonely, lonelySession);
        submitForm(lonely, lonelySession, token, "Needs a manager", "OTHER", "500", "Nobody can approve this.")
                .andExpect(status().isOk())
                .andExpect(view().name("new-request"))
                .andExpect(model().attributeHasErrors("requestForm"));

        assertTrue(requestRepository.findByEmployeeIdOrderByCreatedAtDesc(lonely.getId()).isEmpty());
    }

    @Test
    void aRealLoginSessionCarriesTheUserThroughTheFlow() throws Exception {
        User loginEmployee = userWithPassword("http-login-employee", Role.EMPLOYEE);
        User loginManager = userWithPassword("http-login-manager", Role.MANAGER);
        MockHttpSession employeeLogin = login(loginEmployee);
        MockHttpSession managerLogin = login(loginManager);

        // The employee submits using only the login session (no test shortcut for the user)
        MvcResult form = mockMvc.perform(get("/requests/new").session(employeeLogin))
                .andExpect(status().isOk())
                .andReturn();
        String token = (String) form.getModelAndView().getModel().get("formToken");
        mockMvc.perform(post("/requests").with(csrf()).session(employeeLogin)
                        .param("title", "Logged-in request")
                        .param("category", "OTHER")
                        .param("amount", "800")
                        .param("description", "Submitted through a real login session.")
                        .param("formToken", token))
                .andExpect(redirectedUrl("/requests"));

        Request created = onlyRequestOf(loginEmployee);
        assertEquals(loginEmployee.getId(), created.getEmployee().getId()); // owned by the logged-in user

        // The employee's session cannot reach the approval area
        mockMvc.perform(get("/manager").session(employeeLogin))
                .andExpect(status().isForbidden());

        // The manager's session can approve, which moves the request on to finance
        mockMvc.perform(post("/manager/requests/" + created.getId() + "/approve")
                        .with(csrf()).session(managerLogin).param("comment", "Approved via real session"))
                .andExpect(redirectedUrl("/manager"));
        assertEquals(Role.FINANCE_OFFICER, created.getCurrentStage().getApproverRole());
    }
}