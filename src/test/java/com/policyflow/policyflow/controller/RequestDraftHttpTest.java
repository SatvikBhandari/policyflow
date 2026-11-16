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

import com.policyflow.policyflow.dto.RequestForm;
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
import com.policyflow.policyflow.service.RequestService;
import com.policyflow.policyflow.support.WorkflowFixtures;

@SpringBootTest
@Transactional // everything is rolled back, so your real data is untouched
class RequestDraftHttpTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ApprovalPolicyRepository policyRepository;
    @Autowired private RequestRepository requestRepository;
    @Autowired private RequestService requestService;

    private MockMvc mockMvc;
    private User employee;
    private User other;
    private User lonely;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        WorkflowFixtures.isolatePolicies(policyRepository);
        WorkflowFixtures.policy(policyRepository, "HD base manager", Role.MANAGER);

        Department department = departmentRepository.save(new Department("Http Draft", "HTTPDRAFT"));
        Department emptyDepartment = departmentRepository.save(new Department("Http Draft Empty", "HTTPDEMP"));
        employee = WorkflowFixtures.user(userRepository, "hd-employee", Role.EMPLOYEE, department);
        other = WorkflowFixtures.user(userRepository, "hd-other", Role.EMPLOYEE, department);
        WorkflowFixtures.user(userRepository, "hd-manager", Role.MANAGER, department);
        lonely = WorkflowFixtures.user(userRepository, "hd-lonely", Role.EMPLOYEE, emptyDepartment);
        session = new MockHttpSession();
    }

    // ---- helpers ----

    private static RequestPostProcessor as(User account) {
        return user(AuthenticatedUser.from(account));
    }

    private Request createDraft(User who) {
        RequestForm form = WorkflowFixtures.form("5000");
        form.setTitle("My draft");
        return requestService.saveDraft(form, who);
    }

    private Request createSubmitted(User who) {
        return requestService.createAndSubmit(WorkflowFixtures.form("5000"), who);
    }

    private String openFormToken(User who) throws Exception {
        MvcResult result = mockMvc.perform(get("/requests/new").with(as(who)).session(session))
                .andExpect(status().isOk())
                .andReturn();
        return (String) result.getModelAndView().getModel().get("formToken");
    }

    private ResultActions postForm(String url, User who, String action, String title, String amount, String token)
            throws Exception {
        var request = post(url).with(as(who)).with(csrf()).session(session)
                .param("title", title)
                .param("category", "OTHER")
                .param("amount", amount)
                .param("description", "Created by an automated test.")
                .param("priority", "NORMAL")
                .param("action", action);
        if (token != null) {
            request.param("formToken", token);
        }
        return mockMvc.perform(request);
    }

    private Request onlyRequestOf(User who) {
        List<Request> requests = requestRepository.findByEmployeeIdOrderByCreatedAtDesc(who.getId());
        assertEquals(1, requests.size());
        return requests.get(0);
    }

    private RequestStatus statusOf(Long id) {
        return requestRepository.findById(id).orElseThrow().getStatus();
    }

    // ---- saving a new draft ----

    @Test
    void savingADraftOverHttpCreatesAnUnroutedDraft() throws Exception {
        postForm("/requests", employee, "draft", "Saved for later", "5000", openFormToken(employee))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/requests/*"))
                .andExpect(flash().attributeExists("successMessage"));

        Request saved = onlyRequestOf(employee);
        assertEquals(RequestStatus.DRAFT, saved.getStatus());
        assertEquals("Saved for later", saved.getTitle());
        assertTrue(saved.getStages().isEmpty());
    }

    @Test
    void theSameFormTokenCreatesOnlyOneDraft() throws Exception {
        String token = openFormToken(employee);

        postForm("/requests", employee, "draft", "Double click", "5000", token)
                .andExpect(flash().attributeExists("successMessage"));
        postForm("/requests", employee, "draft", "Double click", "5000", token)
                .andExpect(redirectedUrl("/requests"))
                .andExpect(flash().attributeExists("infoMessage"));

        assertEquals(1, requestRepository.findByEmployeeIdOrderByCreatedAtDesc(employee.getId()).size());
    }

    @Test
    void anInvalidDraftIsRedisplayedWithFieldErrorsAndNothingIsSaved() throws Exception {
        mockMvc.perform(post("/requests").with(as(employee)).with(csrf()).session(session)
                        .param("action", "draft").param("formToken", openFormToken(employee)))
                .andExpect(status().isOk())
                .andExpect(view().name("new-request"))
                .andExpect(model().attributeHasFieldErrors("requestForm", "title", "category", "amount", "description"));

        assertTrue(requestRepository.findByEmployeeIdOrderByCreatedAtDesc(employee.getId()).isEmpty());
    }

    // ---- what the pages show ----

    @Test
    void theDraftPageOffersEditSubmitAndDeleteButNotCancel() throws Exception {
        Request draft = createDraft(employee);

        String html = mockMvc.perform(get("/requests/" + draft.getId()).with(as(employee)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("This request is a draft"));
        assertTrue(html.contains("Submit for approval"));
        assertTrue(html.contains("Delete draft"));
        assertFalse(html.contains("Cancel this request"));
    }

    @Test
    void aSubmittedRequestOffersCancelAndNoDraftActions() throws Exception {
        Request submitted = createSubmitted(employee);

        String html = mockMvc.perform(get("/requests/" + submitted.getId()).with(as(employee)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("Cancel this request"));
        assertFalse(html.contains("This request is a draft"));
        assertFalse(html.contains("Delete draft"));
    }

    @Test
    void myRequestsLinksDraftsToTheirEditPage() throws Exception {
        Request draft = createDraft(employee);

        String html = mockMvc.perform(get("/requests").with(as(employee)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("/requests/" + draft.getId() + "/edit"));
    }

    // ---- editing ----

    @Test
    void theEditPageIsPrefilledWithTheDraft() throws Exception {
        Request draft = createDraft(employee);

        MvcResult result = mockMvc.perform(get("/requests/" + draft.getId() + "/edit").with(as(employee)))
                .andExpect(status().isOk())
                .andExpect(view().name("new-request"))
                .andExpect(model().attribute("editing", true))
                .andExpect(model().attribute("formAction", "/requests/" + draft.getId() + "/edit"))
                .andReturn();

        RequestForm prefilled = (RequestForm) result.getModelAndView().getModel().get("requestForm");
        assertEquals("My draft", prefilled.getTitle());
    }

    @Test
    void savingEditsUpdatesTheDraftAndKeepsItADraft() throws Exception {
        Request draft = createDraft(employee);

        postForm("/requests/" + draft.getId() + "/edit", employee, "draft", "Edited title", "8000", null)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/requests/" + draft.getId()))
                .andExpect(flash().attributeExists("successMessage"));

        Request updated = requestRepository.findById(draft.getId()).orElseThrow();
        assertEquals("Edited title", updated.getTitle());
        assertEquals(RequestStatus.DRAFT, updated.getStatus());
    }

    @Test
    void submittingFromTheEditPageSendsTheDraftForApproval() throws Exception {
        Request draft = createDraft(employee);

        postForm("/requests/" + draft.getId() + "/edit", employee, "submit", "Edited and sent", "8000", null)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/requests"))
                .andExpect(flash().attributeExists("successMessage"));

        Request submitted = requestRepository.findDetailedById(draft.getId()).orElseThrow();
        assertEquals(RequestStatus.PENDING_APPROVAL, submitted.getStatus());
        assertEquals("Edited and sent", submitted.getTitle());
        assertEquals(1, submitted.getStages().size());
    }

    @Test
    void anInvalidEditChangesNothing() throws Exception {
        Request draft = createDraft(employee);

        postForm("/requests/" + draft.getId() + "/edit", employee, "draft", "", "8000", null)
                .andExpect(status().isOk())
                .andExpect(view().name("new-request"))
                .andExpect(model().attributeHasFieldErrors("requestForm", "title"));

        assertEquals("My draft", requestRepository.findById(draft.getId()).orElseThrow().getTitle());
    }

    @Test
    void aSubmittedRequestCannotBeEdited() throws Exception {
        Request submitted = createSubmitted(employee);

        mockMvc.perform(get("/requests/" + submitted.getId() + "/edit").with(as(employee)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/requests/" + submitted.getId()))
                .andExpect(flash().attributeExists("errorMessage"));

        postForm("/requests/" + submitted.getId() + "/edit", employee, "draft", "Sneaky change", "1", null)
                .andExpect(redirectedUrl("/requests/" + submitted.getId()))
                .andExpect(flash().attributeExists("errorMessage"));

        assertEquals("Workflow test request", requestRepository.findById(submitted.getId()).orElseThrow().getTitle());
    }

    // ---- submitting and deleting a draft ----

    @Test
    void theSubmitButtonSendsADraftForApprovalOnlyOnce() throws Exception {
        Request draft = createDraft(employee);

        mockMvc.perform(post("/requests/" + draft.getId() + "/submit").with(as(employee)).with(csrf()))
                .andExpect(redirectedUrl("/requests/" + draft.getId()))
                .andExpect(flash().attributeExists("successMessage"));
        assertEquals(RequestStatus.PENDING_APPROVAL, statusOf(draft.getId()));

        mockMvc.perform(post("/requests/" + draft.getId() + "/submit").with(as(employee)).with(csrf()))
                .andExpect(redirectedUrl("/requests/" + draft.getId()))
                .andExpect(flash().attributeExists("errorMessage"));
        assertEquals(1, requestRepository.findDetailedById(draft.getId()).orElseThrow().getStages().size());
    }

    @Test
    void submittingReportsAMissingApproverAndKeepsTheDraft() throws Exception {
        Request draft = createDraft(lonely); // the department has no manager

        mockMvc.perform(post("/requests/" + draft.getId() + "/submit").with(as(lonely)).with(csrf()))
                .andExpect(redirectedUrl("/requests/" + draft.getId()))
                .andExpect(flash().attributeExists("errorMessage"));

        assertEquals(RequestStatus.DRAFT, statusOf(draft.getId()));
    }

    @Test
    void deletingADraftRemovesIt() throws Exception {
        Request draft = createDraft(employee);

        mockMvc.perform(post("/requests/" + draft.getId() + "/delete").with(as(employee)).with(csrf()))
                .andExpect(redirectedUrl("/requests"))
                .andExpect(flash().attributeExists("successMessage"));

        assertTrue(requestRepository.findById(draft.getId()).isEmpty());
    }

    @Test
    void aSubmittedRequestCannotBeDeleted() throws Exception {
        Request submitted = createSubmitted(employee);

        mockMvc.perform(post("/requests/" + submitted.getId() + "/delete").with(as(employee)).with(csrf()))
                .andExpect(redirectedUrl("/requests/" + submitted.getId()))
                .andExpect(flash().attributeExists("errorMessage"));

        assertTrue(requestRepository.findById(submitted.getId()).isPresent());
    }

    // ---- security ----

    @Test
    void otherEmployeesGetNotFoundForSomeoneElsesDraft() throws Exception {
        Request draft = createDraft(employee);
        String base = "/requests/" + draft.getId();

        mockMvc.perform(get(base + "/edit").with(as(other))).andExpect(status().isNotFound());
        postForm(base + "/edit", other, "draft", "Hacked", "1", null).andExpect(status().isNotFound());
        mockMvc.perform(post(base + "/submit").with(as(other)).with(csrf())).andExpect(status().isNotFound());
        mockMvc.perform(post(base + "/delete").with(as(other)).with(csrf())).andExpect(status().isNotFound());

        Request unchanged = requestRepository.findById(draft.getId()).orElseThrow();
        assertEquals(RequestStatus.DRAFT, unchanged.getStatus());
        assertEquals("My draft", unchanged.getTitle());
    }

    @Test
    void changingDraftsNeedsACsrfToken() throws Exception {
        Request draft = createDraft(employee);
        String base = "/requests/" + draft.getId();

        mockMvc.perform(post(base + "/edit").with(as(employee))
                        .param("title", "No token").param("action", "draft"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(base + "/submit").with(as(employee))).andExpect(status().isForbidden());
        mockMvc.perform(post(base + "/delete").with(as(employee))).andExpect(status().isForbidden());

        assertEquals(RequestStatus.DRAFT, statusOf(draft.getId()));
    }
}