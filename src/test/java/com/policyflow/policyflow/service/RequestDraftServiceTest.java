package com.policyflow.policyflow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.dto.RequestForm;
import com.policyflow.policyflow.entity.ApprovalPolicy;
import com.policyflow.policyflow.entity.AuditLog;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.Request;
import com.policyflow.policyflow.entity.RequestStage;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.RequestPriority;
import com.policyflow.policyflow.enums.RequestStatus;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.exception.NoEligibleApproverException;
import com.policyflow.policyflow.exception.ResourceNotFoundException;
import com.policyflow.policyflow.repository.ApprovalPolicyRepository;
import com.policyflow.policyflow.repository.AuditLogRepository;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.RequestRepository;
import com.policyflow.policyflow.repository.UserRepository;
import com.policyflow.policyflow.support.WorkflowFixtures;

import jakarta.persistence.EntityManager;

@SpringBootTest
@Transactional // everything is rolled back, so your real data and policies are untouched
class RequestDraftServiceTest {

    @Autowired private RequestService requestService;
    @Autowired private ApprovalQueueService queueService;
    @Autowired private RequestSearchService searchService;
    @Autowired private NotificationService notificationService;
    @Autowired private RequestRepository requestRepository;
    @Autowired private ApprovalPolicyRepository policyRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private EntityManager entityManager;

    private Department department;
    private User employee;
    private User other;
    private User manager;
    private User finance;

    @BeforeEach
    void setUp() {
        WorkflowFixtures.isolatePolicies(policyRepository); // only policies created here apply
        WorkflowFixtures.policy(policyRepository, "DR base manager", Role.MANAGER);

        department = departmentRepository.save(new Department("Draft Test", "DRAFTTST"));
        Department financeDepartment = departmentRepository.save(new Department("Draft Finance", "DRAFTFIN"));
        employee = WorkflowFixtures.user(userRepository, "dr-employee", Role.EMPLOYEE, department);
        other = WorkflowFixtures.user(userRepository, "dr-other", Role.EMPLOYEE, department);
        manager = WorkflowFixtures.user(userRepository, "dr-manager", Role.MANAGER, department);
        finance = WorkflowFixtures.user(userRepository, "dr-finance", Role.FINANCE_OFFICER, financeDepartment);
    }

    // ---- helpers ----

    private RequestForm form(String title, String amount, RequestCategory category, RequestPriority priority) {
        RequestForm form = new RequestForm();
        form.setTitle(title);
        form.setCategory(category);
        form.setPriority(priority);
        form.setAmount(new BigDecimal(amount));
        form.setDescription("Created by an automated test.");
        return form;
    }

    private RequestForm form(String title) {
        return form(title, "5000", RequestCategory.OTHER, RequestPriority.NORMAL);
    }

    private List<RequestStage> stagesOf(Request request) {
        return request.getStages();
    }

    private List<Role> rolesOf(Request request) {
        return request.getStages().stream().map(RequestStage::getApproverRole).toList();
    }

    private List<String> auditActions(Long requestId) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByIdAsc(AuditService.ENTITY_REQUEST, requestId)
                .stream().map(AuditLog::getAction).toList();
    }

    private Request reload(Long id) {
        entityManager.flush();
        entityManager.clear();
        return requestRepository.findDetailedById(id).orElseThrow();
    }

    // ---- saving ----

    @Test
    void savingADraftCreatesAReferencedDraftWithoutRouting() {
        Request draft = requestService.saveDraft(form("Draft one"), employee);

        assertEquals(RequestStatus.DRAFT, draft.getStatus());
        assertNotNull(draft.getId());
        assertTrue(draft.getReference().startsWith("PF-"));
        assertTrue(stagesOf(draft).isEmpty());
        assertNull(draft.getSubmittedAt());
        assertNull(draft.getRoutingSummary());
        assertEquals(0L, notificationService.unreadCount(manager.getId())); // nobody is told about a draft
        assertTrue(auditActions(draft.getId()).isEmpty());
    }

    @Test
    void aDraftNeedsNoApproverUntilItIsSubmitted() {
        Department emptyDepartment = departmentRepository.save(new Department("Draft Empty", "DRAFTEMP"));
        User lonely = WorkflowFixtures.user(userRepository, "dr-lonely", Role.EMPLOYEE, emptyDepartment);

        Request draft = requestService.saveDraft(form("Waiting for a manager"), lonely); // saving works
        Long draftId = draft.getId();

        assertThrows(NoEligibleApproverException.class, () -> requestService.submitDraft(draftId, lonely));

        assertEquals(RequestStatus.DRAFT, reload(draftId).getStatus()); // it stays a draft
    }

    // ---- editing ----

    @Test
    void editingADraftPersistsTheChanges() {
        Long id = requestService.saveDraft(form("Original"), employee).getId();

        requestService.updateDraft(id, form("Renamed", "7500.50", RequestCategory.SOFTWARE, RequestPriority.URGENT), employee);

        Request reloaded = reload(id);
        assertEquals("Renamed", reloaded.getTitle());
        assertEquals(0, new BigDecimal("7500.50").compareTo(reloaded.getAmount()));
        assertEquals(RequestCategory.SOFTWARE, reloaded.getCategory());
        assertEquals(RequestPriority.URGENT, reloaded.getPriority());
        assertEquals(RequestStatus.DRAFT, reloaded.getStatus());
    }

    // ---- submitting ----

    @Test
    void submittingADraftRoutesItLikeANormalSubmission() {
        Request draft = requestService.saveDraft(form("To be sent"), employee);
        Long id = draft.getId();
        String reference = draft.getReference();

        Request submitted = requestService.submitDraft(id, employee);

        assertEquals(RequestStatus.PENDING_APPROVAL, submitted.getStatus());
        assertEquals(reference, submitted.getReference()); // the reference never changes
        assertNotNull(submitted.getSubmittedAt());
        assertEquals(List.of(Role.MANAGER), rolesOf(submitted));
        assertEquals(List.of(AuditService.REQUEST_SUBMITTED), auditActions(id));
        assertEquals(1L, notificationService.unreadCount(manager.getId()));
    }

    @Test
    void aDraftIsRoutedByThePoliciesInForceWhenItIsSubmitted() {
        Long id = requestService.saveDraft(form("Saved before the rules changed"), employee).getId();

        // The company adds a rule after the draft was saved
        WorkflowFixtures.policy(policyRepository, "DR extra finance", Role.FINANCE_OFFICER);

        Request submitted = requestService.submitDraft(id, employee);

        assertEquals(List.of(Role.MANAGER, Role.FINANCE_OFFICER), rolesOf(submitted));
    }

    @Test
    void editingAndSubmittingTogetherAppliesTheEditsFirst() {
        ApprovalPolicy urgentFinance = new ApprovalPolicy("DR urgent finance", BigDecimal.ZERO, null, null, null);
        urgentFinance.setPriority(RequestPriority.URGENT);
        urgentFinance.addApprover(Role.FINANCE_OFFICER);
        policyRepository.saveAndFlush(urgentFinance);
        Long id = requestService.saveDraft(form("Starts normal"), employee).getId();

        Request submitted = requestService.updateAndSubmitDraft(
                id, form("Edited and sent", "5000", RequestCategory.OTHER, RequestPriority.URGENT), employee);

        assertEquals("Edited and sent", submitted.getTitle());
        assertEquals(RequestPriority.URGENT, submitted.getPriority());
        assertEquals(List.of(Role.MANAGER, Role.FINANCE_OFFICER), rolesOf(submitted));
        assertEquals(RequestStatus.PENDING_APPROVAL, submitted.getStatus());
    }

    @Test
    void aDraftCanOnlyBeSubmittedOnce() {
        Long id = requestService.saveDraft(form("Once only"), employee).getId();
        requestService.submitDraft(id, employee);

        assertThrows(IllegalStateException.class, () -> requestService.submitDraft(id, employee));

        assertEquals(1L, notificationService.unreadCount(manager.getId())); // no second wave of notifications
        assertEquals(List.of(AuditService.REQUEST_SUBMITTED), auditActions(id));
        assertEquals(1, reload(id).getStages().size());
    }

    // ---- ownership ----

    @Test
    void onlyTheOwnerCanEditSubmitOrDeleteADraft() {
        Long id = requestService.saveDraft(form("Mine"), employee).getId();

        assertThrows(ResourceNotFoundException.class, () -> requestService.updateDraft(id, form("Hacked"), other));
        assertThrows(ResourceNotFoundException.class, () -> requestService.updateAndSubmitDraft(id, form("Hacked"), other));
        assertThrows(ResourceNotFoundException.class, () -> requestService.submitDraft(id, other));
        assertThrows(ResourceNotFoundException.class, () -> requestService.deleteDraft(id, other));

        Request unchanged = reload(id);
        assertEquals(RequestStatus.DRAFT, unchanged.getStatus());
        assertEquals("Mine", unchanged.getTitle());
    }

    // ---- deleting and the rules around it ----

    @Test
    void deletingADraftRemovesItAndIsAudited() {
        Long id = requestService.saveDraft(form("Throw away"), employee).getId();

        requestService.deleteDraft(id, employee);

        entityManager.flush();
        entityManager.clear();
        assertTrue(requestRepository.findById(id).isEmpty());
        assertEquals(List.of(AuditService.REQUEST_DRAFT_DELETED), auditActions(id));
    }

    @Test
    void submittedRequestsCannotBeEditedSubmittedAgainOrDeleted() {
        Long id = requestService.createAndSubmit(form("Already sent"), employee).getId();

        assertThrows(IllegalStateException.class, () -> requestService.updateDraft(id, form("Changed"), employee));
        assertThrows(IllegalStateException.class, () -> requestService.submitDraft(id, employee));
        assertThrows(IllegalStateException.class, () -> requestService.deleteDraft(id, employee));

        Request unchanged = reload(id);
        assertEquals(RequestStatus.PENDING_APPROVAL, unchanged.getStatus());
        assertEquals("Already sent", unchanged.getTitle());
    }

    @Test
    void draftsAreDeletedNotCancelled() {
        Long id = requestService.saveDraft(form("Not cancellable"), employee).getId();

        assertThrows(IllegalStateException.class, () -> requestService.cancel(id, employee, "no"));

        assertEquals(RequestStatus.DRAFT, reload(id).getStatus());
    }

    // ---- who can see a draft ----

    @Test
    void draftsAreInvisibleToApproversButListedForTheOwner() {
        Long id = requestService.saveDraft(form("Private draft"), employee).getId();

        assertTrue(queueService.search(manager, null, null, 0).getContent().stream()
                .noneMatch(item -> item.getRequestId().equals(id)));

        Page<Request> mine = searchService.searchOwn(employee, null, RequestStatus.DRAFT, null, null, null, 0);
        assertEquals(1L, mine.getTotalElements());
        assertEquals(id, mine.getContent().get(0).getId());

        assertEquals(0L, searchService.searchOwn(other, null, RequestStatus.DRAFT, null, null, null, 0).getTotalElements());
    }
}