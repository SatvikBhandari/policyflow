package com.policyflow.policyflow.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.policyflow.policyflow.dto.ApprovalViews.QueueItem;
import com.policyflow.policyflow.dto.PolicyEvaluation;
import com.policyflow.policyflow.dto.RequestForm;
import com.policyflow.policyflow.entity.ApprovalPolicy;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.Request;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.RequestPriority;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.repository.ApprovalPolicyRepository;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.RequestRepository;
import com.policyflow.policyflow.repository.UserRepository;
import com.policyflow.policyflow.security.AuthenticatedUser;
import com.policyflow.policyflow.service.RequestService;
import com.policyflow.policyflow.support.WorkflowFixtures;

@SpringBootTest
@Transactional // everything is rolled back, so your real data is untouched
class PriorityHttpTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ApprovalPolicyRepository policyRepository;
    @Autowired private RequestRepository requestRepository;
    @Autowired private RequestService requestService;

    private MockMvc mockMvc;
    private User admin;
    private User employee;
    private User manager;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        WorkflowFixtures.isolatePolicies(policyRepository);
        WorkflowFixtures.policy(policyRepository, "HP base manager", Role.MANAGER);
        ApprovalPolicy urgentFinance = new ApprovalPolicy("HP urgent finance", BigDecimal.ZERO, null, null, null);
        urgentFinance.setPriority(RequestPriority.URGENT);
        urgentFinance.addApprover(Role.FINANCE_OFFICER);
        policyRepository.saveAndFlush(urgentFinance);

        Department department = departmentRepository.save(new Department("Http Priority", "HTTPPRIO"));
        Department financeDepartment = departmentRepository.save(new Department("Http Priority Fin", "HTTPPFIN"));
        admin = WorkflowFixtures.user(userRepository, "hp-admin", Role.ADMIN, department);
        employee = WorkflowFixtures.user(userRepository, "hp-employee", Role.EMPLOYEE, department);
        manager = WorkflowFixtures.user(userRepository, "hp-manager", Role.MANAGER, department);
        WorkflowFixtures.user(userRepository, "hp-finance", Role.FINANCE_OFFICER, financeDepartment);
        session = new MockHttpSession();
    }

    // ---- helpers ----

    private static RequestPostProcessor as(User account) {
        return user(AuthenticatedUser.from(account));
    }

    private String openForm() throws Exception {
        MvcResult result = mockMvc.perform(get("/requests/new").with(as(employee)).session(session))
                .andExpect(status().isOk())
                .andReturn();
        return (String) result.getModelAndView().getModel().get("formToken");
    }

    private ResultActions submit(String token, String priority) throws Exception {
        var request = post("/requests").with(as(employee)).with(csrf()).session(session)
                .param("title", "Priority test request")
                .param("category", "OTHER")
                .param("amount", "5000")
                .param("description", "Created by an automated test.")
                .param("formToken", token);
        if (priority != null) {
            request.param("priority", priority);
        }
        return mockMvc.perform(request);
    }

    private Request onlyRequestOfEmployee() {
        List<Request> requests = requestRepository.findByEmployeeIdOrderByCreatedAtDesc(employee.getId());
        assertEquals(1, requests.size());
        return requests.get(0);
    }

    private Request createDirectly(RequestPriority priority) {
        RequestForm form = WorkflowFixtures.form("5000");
        form.setPriority(priority);
        return requestService.createAndSubmit(form, employee);
    }

    // ---- the form ----

    @Test
    void theNewRequestFormOffersAllThreePriorities() throws Exception {
        String html = mockMvc.perform(get("/requests/new").with(as(employee)))
                .andExpect(status().isOk())
                .andExpect(view().name("new-request"))
                .andExpect(model().attributeExists("priorities"))
                .andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("Low"));
        assertTrue(html.contains("Normal"));
        assertTrue(html.contains("Urgent"));
    }

    @Test
    void anUrgentSubmissionIsStoredAsUrgentAndRoutedThroughThePriorityPolicy() throws Exception {
        submit(openForm(), "URGENT").andExpect(redirectedUrl("/requests"));

        Request saved = onlyRequestOfEmployee();
        assertEquals(RequestPriority.URGENT, saved.getPriority());
        assertEquals(2, saved.getStages().size());
    }

    @Test
    void omittingThePriorityDefaultsToNormal() throws Exception {
        submit(openForm(), null).andExpect(redirectedUrl("/requests"));

        Request saved = onlyRequestOfEmployee();
        assertEquals(RequestPriority.NORMAL, saved.getPriority());
        assertEquals(1, saved.getStages().size());
    }

    @Test
    void anUnknownOrEmptyPriorityIsAFieldErrorAndNothingIsSaved() throws Exception {
        for (String bad : new String[] {"BOGUS", ""}) {
            submit(openForm(), bad)
                    .andExpect(status().isOk())
                    .andExpect(view().name("new-request"))
                    .andExpect(model().attributeHasFieldErrors("requestForm", "priority"));
        }
        assertTrue(requestRepository.findByEmployeeIdOrderByCreatedAtDesc(employee.getId()).isEmpty());
    }

    // ---- what people see ----

    @Test
    void theDetailsPageShowsAnUrgentBadgeOnlyForUrgentRequests() throws Exception {
        Request urgent = createDirectly(RequestPriority.URGENT);
        Request normal = createDirectly(RequestPriority.NORMAL);

        String urgentHtml = mockMvc.perform(get("/requests/" + urgent.getId()).with(as(employee)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String normalHtml = mockMvc.perform(get("/requests/" + normal.getId()).with(as(employee)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertTrue(urgentHtml.contains("Urgent"));
        assertFalse(normalHtml.contains("Urgent"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theManagerQueueShowsUrgentRequestsFirstWithABadge() throws Exception {
        createDirectly(RequestPriority.NORMAL);
        Request urgent = createDirectly(RequestPriority.URGENT);

        MvcResult result = mockMvc.perform(get("/manager").with(as(manager)))
                .andExpect(status().isOk())
                .andExpect(view().name("approval-queue"))
                .andReturn();

        List<QueueItem> queue = (List<QueueItem>) result.getModelAndView().getModel().get("queue");
        assertEquals(urgent.getId(), queue.get(0).getRequestId());
        assertTrue(result.getResponse().getContentAsString().contains("Urgent"));
    }

    // ---- administration ----

    @Test
    void thePolicyPreviewUsesThePriorityParameter() throws Exception {
        PolicyEvaluation urgent = (PolicyEvaluation) mockMvc.perform(get("/admin/policies").with(as(admin))
                        .param("previewAmount", "5000").param("previewCategory", "OTHER")
                        .param("previewPriority", "URGENT").param("previewDepartmentId", ""))
                .andExpect(status().isOk())
                .andReturn().getModelAndView().getModel().get("preview");
        PolicyEvaluation normal = (PolicyEvaluation) mockMvc.perform(get("/admin/policies").with(as(admin))
                        .param("previewAmount", "5000").param("previewCategory", "OTHER")
                        .param("previewDepartmentId", ""))
                .andExpect(status().isOk())
                .andReturn().getModelAndView().getModel().get("preview");

        assertTrue(urgent.getApproverRoles().contains(Role.FINANCE_OFFICER));
        assertFalse(normal.getApproverRoles().contains(Role.FINANCE_OFFICER));
    }
}