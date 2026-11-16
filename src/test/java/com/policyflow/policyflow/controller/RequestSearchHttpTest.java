package com.policyflow.policyflow.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.math.BigDecimal;
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

import com.policyflow.policyflow.dto.RequestForm;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.repository.ApprovalPolicyRepository;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.UserRepository;
import com.policyflow.policyflow.security.AuthenticatedUser;
import com.policyflow.policyflow.service.RequestService;
import com.policyflow.policyflow.support.WorkflowFixtures;

@SpringBootTest
@Transactional // everything is rolled back, so your real data is untouched
class RequestSearchHttpTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ApprovalPolicyRepository policyRepository;
    @Autowired private RequestService requestService;

    private MockMvc mockMvc;
    private User employee;
    private User other;
    private User manager;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        WorkflowFixtures.isolatePolicies(policyRepository);
        WorkflowFixtures.policy(policyRepository, "HS manager only", Role.MANAGER);

        Department department = departmentRepository.save(new Department("Http Search", "HTTPSRCH"));
        employee = WorkflowFixtures.user(userRepository, "hs-employee", Role.EMPLOYEE, department);
        other = WorkflowFixtures.user(userRepository, "hs-other", Role.EMPLOYEE, department);
        manager = WorkflowFixtures.user(userRepository, "hs-manager", Role.MANAGER, department);
    }

    // ---- helpers ----

    private static RequestPostProcessor as(User account) {
        return user(AuthenticatedUser.from(account));
    }

    private void create(User who, String title, RequestCategory category) {
        RequestForm form = new RequestForm();
        form.setTitle(title);
        form.setCategory(category);
        form.setAmount(new BigDecimal("5000"));
        form.setDescription("Created by an automated test.");
        requestService.createAndSubmit(form, who);
    }

    @SuppressWarnings("unchecked")
    private static <T> Page<T> pageOf(MvcResult result, String attribute) {
        return (Page<T>) result.getModelAndView().getModel().get(attribute);
    }

    // ---- My Requests ----

    @Test
    void textFilterNarrowsTheListAndTheRenderedPage() throws Exception {
        create(employee, "Laptop A", RequestCategory.HARDWARE);
        create(employee, "Chair B", RequestCategory.OFFICE_SUPPLIES);

        MvcResult result = mockMvc.perform(get("/requests").with(as(employee)).param("q", "laptop"))
                .andExpect(status().isOk())
                .andExpect(view().name("my-requests"))
                .andReturn();

        assertEquals(1L, pageOf(result, "requestsPage").getTotalElements());
        String html = result.getResponse().getContentAsString();
        assertTrue(html.contains("Laptop A"));
        assertFalse(html.contains("Chair B"));
    }

    @Test
    void statusAndCategoryFiltersApply() throws Exception {
        create(employee, "Laptop A", RequestCategory.HARDWARE);
        create(employee, "Licence B", RequestCategory.SOFTWARE);

        MvcResult software = mockMvc.perform(get("/requests").with(as(employee)).param("category", "SOFTWARE"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(1L, pageOf(software, "requestsPage").getTotalElements());

        MvcResult pending = mockMvc.perform(get("/requests").with(as(employee)).param("status", "PENDING_APPROVAL"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(2L, pageOf(pending, "requestsPage").getTotalElements());

        MvcResult cancelled = mockMvc.perform(get("/requests").with(as(employee)).param("status", "CANCELLED"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0L, pageOf(cancelled, "requestsPage").getTotalElements());
    }

    @Test
    void theModelStillCarriesTheCurrentPageAsAPlainList() throws Exception {
        create(employee, "Laptop A", RequestCategory.HARDWARE);

        MvcResult result = mockMvc.perform(get("/requests").with(as(employee))).andExpect(status().isOk()).andReturn();

        Object requests = result.getModelAndView().getModel().get("requests");
        assertTrue(requests instanceof List<?>);
        assertEquals(1, ((List<?>) requests).size());
    }

    @Test
    void anUnknownStatusOrCategoryIsABadRequest() throws Exception {
        mockMvc.perform(get("/requests").with(as(employee)).param("status", "BOGUS"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/requests").with(as(employee)).param("category", "BOGUS"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void badDatesShowAFilterErrorInsteadOfFailing() throws Exception {
        mockMvc.perform(get("/requests").with(as(employee)).param("from", "not-a-date"))
                .andExpect(status().isOk())
                .andExpect(view().name("my-requests"))
                .andExpect(model().attributeExists("filterError"));

        mockMvc.perform(get("/requests").with(as(employee)).param("from", "2026-12-31").param("to", "2026-01-01"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("filterError"));
    }

    @Test
    void thePagerKeepsTheFilters() throws Exception {
        for (int i = 1; i <= 12; i++) {
            create(employee, "Page test " + i, RequestCategory.OTHER);
        }

        MvcResult first = mockMvc.perform(get("/requests").with(as(employee)).param("q", "page test"))
                .andExpect(status().isOk()).andReturn();
        String html = first.getResponse().getContentAsString();
        assertTrue(html.contains("page=1"), "the first page should link to the second");
        assertTrue(html.contains("q=page"), "the link should keep the search text");

        MvcResult second = mockMvc.perform(get("/requests").with(as(employee))
                        .param("q", "page test").param("page", "1"))
                .andExpect(status().isOk()).andReturn();
        Page<?> secondPage = pageOf(second, "requestsPage");
        assertEquals(2, secondPage.getNumberOfElements());
        assertTrue(secondPage.hasPrevious());
        assertFalse(secondPage.hasNext());
    }

    @Test
    void eachEmployeeOnlySearchesTheirOwnRequests() throws Exception {
        create(other, "Laptop secret", RequestCategory.HARDWARE);

        MvcResult result = mockMvc.perform(get("/requests").with(as(employee)).param("q", "laptop"))
                .andExpect(status().isOk()).andReturn();

        assertEquals(0L, pageOf(result, "requestsPage").getTotalElements());
        assertFalse(result.getResponse().getContentAsString().contains("Laptop secret"));
    }

    @Test
    void emptyStatesDifferForNoRequestsAndNoMatches() throws Exception {
        String none = mockMvc.perform(get("/requests").with(as(employee)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(none.contains("No requests yet"));
        assertFalse(none.contains("No matching requests"));

        String noMatch = mockMvc.perform(get("/requests").with(as(employee)).param("q", "nothing"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(noMatch.contains("No matching requests"));
        assertFalse(noMatch.contains("No requests yet"));
    }

    // ---- approval queue ----

    @Test
    void theManagerQueueFiltersAndKeepsTheListAttribute() throws Exception {
        create(employee, "Alpha laptop", RequestCategory.HARDWARE);
        create(employee, "Beta licence", RequestCategory.SOFTWARE);

        MvcResult all = mockMvc.perform(get("/manager").with(as(manager)))
                .andExpect(status().isOk())
                .andExpect(view().name("approval-queue"))
                .andReturn();
        assertEquals(2L, pageOf(all, "queuePage").getTotalElements());
        assertTrue(all.getModelAndView().getModel().get("queue") instanceof List<?>);

        MvcResult byText = mockMvc.perform(get("/manager").with(as(manager)).param("q", "alpha"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(1L, pageOf(byText, "queuePage").getTotalElements());

        MvcResult byCategory = mockMvc.perform(get("/manager").with(as(manager)).param("category", "SOFTWARE"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(1L, pageOf(byCategory, "queuePage").getTotalElements());

        String noMatch = mockMvc.perform(get("/manager").with(as(manager)).param("q", "zzz"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(noMatch.contains("No matching requests"));

        mockMvc.perform(get("/manager").with(as(manager)).param("category", "BOGUS"))
                .andExpect(status().isBadRequest());
    }
}