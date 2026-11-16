package com.policyflow.policyflow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.dto.ApprovalViews.QueueItem;
import com.policyflow.policyflow.dto.PolicyEvaluation;
import com.policyflow.policyflow.dto.PolicyForm;
import com.policyflow.policyflow.dto.RequestForm;
import com.policyflow.policyflow.entity.ApprovalPolicy;
import com.policyflow.policyflow.entity.AuditLog;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.Notification;
import com.policyflow.policyflow.entity.Request;
import com.policyflow.policyflow.entity.RequestStage;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.RequestPriority;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.repository.ApprovalPolicyRepository;
import com.policyflow.policyflow.repository.AuditLogRepository;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.RequestRepository;
import com.policyflow.policyflow.repository.UserRepository;
import com.policyflow.policyflow.support.WorkflowFixtures;

import jakarta.persistence.EntityManager;

@SpringBootTest
@Transactional // everything is rolled back, so your real data and policies are untouched
class RequestPriorityFlowTest {

    @Autowired private RequestService requestService;
    @Autowired private ApprovalQueueService queueService;
    @Autowired private NotificationService notificationService;
    @Autowired private PolicyAdminService policyAdminService;
    @Autowired private RequestRepository requestRepository;
    @Autowired private ApprovalPolicyRepository policyRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private EntityManager entityManager;

    private Department department;
    private User admin;
    private User employee;
    private User manager;

    @BeforeEach
    void setUp() {
        WorkflowFixtures.isolatePolicies(policyRepository); // only policies created here apply

        department = departmentRepository.save(new Department("Priority Test", "PRIOTST"));
        Department financeDepartment = departmentRepository.save(new Department("Priority Finance", "PRIOFIN"));
        admin = WorkflowFixtures.user(userRepository, "prio-admin", Role.ADMIN, department);
        employee = WorkflowFixtures.user(userRepository, "prio-employee", Role.EMPLOYEE, department);
        manager = WorkflowFixtures.user(userRepository, "prio-manager", Role.MANAGER, department);
        WorkflowFixtures.user(userRepository, "prio-finance", Role.FINANCE_OFFICER, financeDepartment);

        WorkflowFixtures.policy(policyRepository, "PR base manager", Role.MANAGER);
        ApprovalPolicy urgentFinance = new ApprovalPolicy("PR urgent finance", BigDecimal.ZERO, null, null, null);
        urgentFinance.setPriority(RequestPriority.URGENT);
        urgentFinance.addApprover(Role.FINANCE_OFFICER);
        policyRepository.saveAndFlush(urgentFinance);
    }

    // ---- helpers ----

    private Request submit(RequestPriority priority) {
        RequestForm form = WorkflowFixtures.form("5000");
        form.setPriority(priority);
        return requestService.createAndSubmit(form, employee);
    }

    private List<Role> rolesOf(Request request) {
        return request.getStages().stream().map(RequestStage::getApproverRole).toList();
    }

    private PolicyForm policyForm(String name, RequestPriority priority, Long version) {
        PolicyForm form = new PolicyForm();
        form.setName(name);
        form.setMinAmount(BigDecimal.ZERO);
        form.setCategory(RequestCategory.OTHER);
        form.setPriority(priority);
        form.setApproverRoles(new LinkedHashSet<>(List.of(Role.MANAGER)));
        form.setVersion(version);
        return form;
    }

    private List<AuditLog> auditOf(ApprovalPolicy policy) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByIdAsc(AuditService.ENTITY_POLICY, policy.getId());
    }

    // ---- routing and storage ----

    @Test
    void aNormalRequestUsesOnlyTheBasePolicy() {
        Request request = submit(RequestPriority.NORMAL);

        assertEquals(List.of(Role.MANAGER), rolesOf(request));
        assertEquals(RequestPriority.NORMAL, request.getPriority());
    }

    @Test
    void anUrgentRequestPicksUpThePriorityPolicyAndKeepsItsPriority() {
        Long id = submit(RequestPriority.URGENT).getId();
        entityManager.flush();
        entityManager.clear(); // read it back from MySQL

        Request reloaded = requestRepository.findDetailedById(id).orElseThrow();

        assertEquals(RequestPriority.URGENT, reloaded.getPriority());
        assertEquals(List.of(Role.MANAGER, Role.FINANCE_OFFICER), rolesOf(reloaded));
        assertTrue(reloaded.getRoutingSummary().contains("PR urgent finance"));
    }

    @Test
    void priorityCanOnlyChangeBeforeSubmission() {
        Request submitted = submit(RequestPriority.NORMAL);
        assertThrows(IllegalStateException.class, () -> submitted.changePriority(RequestPriority.URGENT));

        Request draft = new Request("Title", "Description", RequestCategory.OTHER, new BigDecimal("1"), employee);
        draft.changePriority(RequestPriority.URGENT);
        assertEquals(RequestPriority.URGENT, draft.getPriority());
        assertThrows(IllegalArgumentException.class, () -> draft.changePriority(null));
    }

    // ---- what approvers see ----

    @Test
    void urgentRequestsNotifyApproversWithAnUrgentMarker() {
        submit(RequestPriority.NORMAL);
        submit(RequestPriority.URGENT);

        List<Notification> inbox = notificationService.list(manager, 0).getContent(); // newest first

        assertEquals(2, inbox.size());
        assertTrue(inbox.get(0).getMessage().startsWith("Urgent:"));
        assertFalse(inbox.get(1).getMessage().startsWith("Urgent:"));
    }

    @Test
    void theQueueListsUrgentRequestsFirst() {
        submit(RequestPriority.NORMAL);
        Long urgentId = submit(RequestPriority.URGENT).getId();
        submit(RequestPriority.NORMAL);

        List<QueueItem> queue = queueService.search(manager, null, null, 0).getContent();

        assertEquals(3, queue.size());
        assertEquals(urgentId, queue.get(0).getRequestId());
        assertEquals(RequestPriority.URGENT, queue.get(0).getPriority());
        assertTrue(queue.stream().skip(1).allMatch(item -> item.getPriority() == RequestPriority.NORMAL));
    }

    // ---- administration ----

    @Test
    void anAdministratorCanCreateAndEditAPriorityLimitedPolicy() {
        ApprovalPolicy created = policyAdminService.create(admin,
                policyForm("PR admin policy", RequestPriority.URGENT, null));

        entityManager.flush();
        entityManager.clear();
        ApprovalPolicy reloaded = policyRepository.findWithDepartmentById(created.getId()).orElseThrow();
        assertEquals(RequestPriority.URGENT, reloaded.getPriority());
        assertTrue(auditOf(reloaded).get(0).getDetails().contains("Urgent"));

        policyAdminService.update(admin, reloaded.getId(), policyForm("PR admin policy", null, reloaded.getVersion()));

        entityManager.flush();
        entityManager.clear();
        ApprovalPolicy updated = policyRepository.findWithDepartmentById(created.getId()).orElseThrow();
        assertEquals(null, updated.getPriority());
        List<AuditLog> audit = auditOf(updated);
        assertEquals(List.of(AuditService.POLICY_CREATED, AuditService.POLICY_UPDATED),
                audit.stream().map(AuditLog::getAction).toList());
        assertTrue(audit.get(1).getDetails().contains("priority:"));
    }

    @Test
    void thePolicyPreviewHonoursPriority() {
        PolicyEvaluation urgent = policyAdminService.preview(
                admin, new BigDecimal("5000"), RequestCategory.OTHER, RequestPriority.URGENT, null);
        PolicyEvaluation normal = policyAdminService.preview(
                admin, new BigDecimal("5000"), RequestCategory.OTHER, null);

        assertEquals(List.of(Role.MANAGER, Role.FINANCE_OFFICER), urgent.getApproverRoles());
        assertEquals(List.of(Role.MANAGER), normal.getApproverRoles());
    }
}