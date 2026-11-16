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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.dto.PolicyCoverage;
import com.policyflow.policyflow.dto.PolicyEvaluation;
import com.policyflow.policyflow.dto.PolicyForm;
import com.policyflow.policyflow.entity.ApprovalPolicy;
import com.policyflow.policyflow.entity.AuditLog;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.Request;
import com.policyflow.policyflow.entity.RequestStage;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.exception.PolicyAdminException;
import com.policyflow.policyflow.repository.ApprovalPolicyRepository;
import com.policyflow.policyflow.repository.AuditLogRepository;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.RequestRepository;
import com.policyflow.policyflow.repository.UserRepository;
import com.policyflow.policyflow.support.WorkflowFixtures;

import jakarta.persistence.EntityManager;

@SpringBootTest
@Transactional // everything is rolled back, so your real policies and users are untouched
class PolicyAdminServiceTest {

    @Autowired private PolicyAdminService service;
    @Autowired private RequestService requestService;
    @Autowired private RequestRepository requestRepository;
    @Autowired private ApprovalPolicyRepository policyRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private EntityManager entityManager;

    private Department department;
    private Department otherDepartment;
    private User admin;
    private User employee;

    @BeforeEach
    void setUp() {
        WorkflowFixtures.isolatePolicies(policyRepository); // only policies created by each test apply

        department = departmentRepository.save(new Department("Policy Admin Test", "PATST"));
        otherDepartment = departmentRepository.save(new Department("Policy Admin Other", "PAOTH"));
        admin = userRepository.save(new User("Policy Admin", "pa-admin@policyflow.local", Role.ADMIN, department));
        employee = userRepository.save(new User("Policy Employee", "pa-employee@policyflow.local", Role.EMPLOYEE, department));
    }

    // ---- helpers ----

    private PolicyForm form(String name, String min, String max, RequestCategory category,
                            Department dept, Role... roles) {
        PolicyForm form = new PolicyForm();
        form.setName(name);
        form.setMinAmount(new BigDecimal(min));
        form.setMaxAmount(max == null ? null : new BigDecimal(max));
        form.setCategory(category);
        form.setDepartmentId(dept == null ? null : dept.getId());
        form.setApproverRoles(new LinkedHashSet<>(List.of(roles)));
        form.setActive(true);
        return form;
    }

    private List<String> auditActions(ApprovalPolicy policy) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByIdAsc(AuditService.ENTITY_POLICY, policy.getId())
                .stream().map(AuditLog::getAction).toList();
    }

    private ApprovalPolicy reload(Long id) {
        entityManager.flush();
        entityManager.clear();
        return policyRepository.findWithDepartmentById(id).orElseThrow();
    }

    private List<Role> rolesOf(Request request) {
        return request.getStages().stream().map(RequestStage::getApproverRole).toList();
    }

    // ---- creating ----

    @Test
    void createStoresThePolicyItsApproversAndAnAuditEntry() {
        ApprovalPolicy created = service.create(admin, form("  PA Mid Value  ", "10000", "50000",
                RequestCategory.HARDWARE, department, Role.FINANCE_OFFICER, Role.MANAGER));

        ApprovalPolicy reloaded = reload(created.getId());
        assertEquals("PA Mid Value", reloaded.getName());
        assertEquals(List.of(Role.MANAGER, Role.FINANCE_OFFICER), reloaded.getApproverRolesInOrder());
        assertEquals(RequestCategory.HARDWARE, reloaded.getCategory());
        assertEquals(department.getId(), reloaded.getDepartment().getId());
        assertTrue(reloaded.isActive());
        assertEquals(List.of(AuditService.POLICY_CREATED), auditActions(reloaded));
    }

    @Test
    void duplicateNamesAreRejectedIgnoringCase() {
        service.create(admin, form("PA Duplicate", "0", null, null, null, Role.MANAGER));

        PolicyAdminException ex = assertThrows(PolicyAdminException.class, () ->
                service.create(admin, form("pa DUPLICATE", "0", null, null, null, Role.MANAGER)));

        assertEquals("name", ex.getField());
    }

    @Test
    void approversAreRequiredAndMustBeApproverRoles() {
        PolicyAdminException none = assertThrows(PolicyAdminException.class, () ->
                service.create(admin, form("PA No Approvers", "0", null, null, null)));
        assertEquals("approverRoles", none.getField());

        PolicyAdminException wrongRole = assertThrows(PolicyAdminException.class, () ->
                service.create(admin, form("PA Wrong Role", "0", null, null, null, Role.MANAGER, Role.EMPLOYEE)));
        assertEquals("approverRoles", wrongRole.getField());

        assertTrue(policyRepository.findByNameIgnoreCase("PA No Approvers").isEmpty());
        assertTrue(policyRepository.findByNameIgnoreCase("PA Wrong Role").isEmpty());
    }

    @Test
    void aMaximumNotAboveTheMinimumIsRejected() {
        PolicyAdminException ex = assertThrows(PolicyAdminException.class, () ->
                service.create(admin, form("PA Bad Range", "5000", "5000", null, null, Role.MANAGER)));

        assertEquals("maxAmount", ex.getField());
        assertTrue(policyRepository.findByNameIgnoreCase("PA Bad Range").isEmpty());
    }

    @Test
    void amountsWithMoreThanTwoDecimalPlacesAreRejected() {
        PolicyAdminException ex = assertThrows(PolicyAdminException.class, () ->
                service.create(admin, form("PA Decimals", "10.555", null, null, null, Role.MANAGER)));

        assertEquals("minAmount", ex.getField());
    }

    @Test
    void anInactiveDepartmentIsRejected() {
        otherDepartment.setActive(false);
        departmentRepository.saveAndFlush(otherDepartment);

        PolicyAdminException ex = assertThrows(PolicyAdminException.class, () ->
                service.create(admin, form("PA Inactive Dept", "0", null, null, otherDepartment, Role.MANAGER)));

        assertEquals("departmentId", ex.getField());
    }

    @Test
    void onlyAnActiveAdministratorCanUseTheService() {
        PolicyForm form = form("PA Sneaky", "0", null, null, null, Role.MANAGER);

        assertThrows(AccessDeniedException.class, () -> service.create(employee, form));

        admin.setActive(false);
        userRepository.saveAndFlush(admin);
        assertThrows(AccessDeniedException.class, () -> service.create(admin, form));
        assertTrue(policyRepository.findByNameIgnoreCase("PA Sneaky").isEmpty());
    }

    // ---- editing ----

    @Test
    void updateChangesConditionsAndApproversAndIsAudited() {
        ApprovalPolicy created = service.create(admin, form("PA Edit Me", "0", "10000", null, null, Role.MANAGER));
        PolicyForm edit = form("PA Edited", "0", "25000", RequestCategory.SOFTWARE, otherDepartment,
                Role.MANAGER, Role.FINANCE_OFFICER);
        edit.setVersion(created.getVersion());

        service.update(admin, created.getId(), edit);

        ApprovalPolicy reloaded = reload(created.getId());
        assertEquals("PA Edited", reloaded.getName());
        assertEquals(0, new BigDecimal("25000").compareTo(reloaded.getMaxAmount()));
        assertEquals(RequestCategory.SOFTWARE, reloaded.getCategory());
        assertEquals(otherDepartment.getId(), reloaded.getDepartment().getId());
        assertEquals(List.of(Role.MANAGER, Role.FINANCE_OFFICER), reloaded.getApproverRolesInOrder());

        assertEquals(List.of(AuditService.POLICY_CREATED, AuditService.POLICY_UPDATED), auditActions(reloaded));
        String details = auditLogRepository
                .findByEntityTypeAndEntityIdOrderByIdAsc(AuditService.ENTITY_POLICY, reloaded.getId()).get(1).getDetails();
        assertTrue(details.contains("PA Edited"));
        assertTrue(details.contains("Finance Officer"));
    }

    @Test
    void updateWithoutChangesWritesNoAuditEntry() {
        ApprovalPolicy created = service.create(admin, form("PA Same", "0", "10000", null, null, Role.MANAGER));
        PolicyForm same = form("PA Same", "0.00", "10000.00", null, null, Role.MANAGER);
        same.setVersion(created.getVersion());

        service.update(admin, created.getId(), same);

        assertEquals(List.of(AuditService.POLICY_CREATED), auditActions(created));
    }

    @Test
    void aFormOpenedBeforeAnotherEditIsRejected() {
        ApprovalPolicy created = service.create(admin, form("PA Stale Base", "0", null, null, null, Role.MANAGER));
        Long id = created.getId();
        Long openedVersion = created.getVersion();

        PolicyForm first = form("PA Stale One", "0", null, null, null, Role.MANAGER);
        first.setVersion(openedVersion);
        service.update(admin, id, first); // succeeds and moves the policy to a newer version

        PolicyForm second = form("PA Stale Two", "0", null, null, null, Role.MANAGER);
        second.setVersion(openedVersion); // still the old version
        assertThrows(OptimisticLockingFailureException.class, () -> service.update(admin, id, second));

        assertEquals("PA Stale One", reload(id).getName());
    }

    @Test
    void aConcurrentChangeIsCaughtByTheVersionCheckAtSaveTime() {
        ApprovalPolicy created = service.create(admin, form("PA Race Base", "0", null, null, null, Role.MANAGER));
        Long id = created.getId();
        PolicyForm edit = form("PA Race Edited", "0", null, null, null, Role.MANAGER);
        edit.setVersion(created.getVersion());

        // Another session commits a change to the same row (simulated with direct SQL)
        entityManager.createNativeQuery("update approval_policies set version = version + 1 where id = :id")
                .setParameter("id", id)
                .executeUpdate();

        assertThrows(OptimisticLockingFailureException.class, () -> service.update(admin, id, edit));
    }

    // ---- enabling and disabling ----

    @Test
    void disablingAndEnablingAreAuditedAndNeedTheCurrentVersion() {
        ApprovalPolicy created = service.create(admin, form("PA Toggle", "0", null, null, null, Role.MANAGER));
        Long id = created.getId();
        Long firstVersion = created.getVersion();

        service.setActive(admin, id, false, firstVersion);
        assertFalse(reload(id).isActive());

        // The old version is now out of date
        assertThrows(OptimisticLockingFailureException.class, () -> service.setActive(admin, id, true, firstVersion));

        Long currentVersion = policyRepository.findById(id).orElseThrow().getVersion();
        service.setActive(admin, id, true, currentVersion);
        assertTrue(reload(id).isActive());

        assertEquals(List.of(AuditService.POLICY_CREATED, AuditService.POLICY_DEACTIVATED, AuditService.POLICY_ACTIVATED),
                auditActions(created));
    }

    // ---- preview and coverage ----

    @Test
    void previewUsesTheLivePoliciesAndFallsBackWhenNoneMatch() {
        ApprovalPolicy low = service.create(admin, form("PA Low", "0", "10000", null, null, Role.MANAGER));
        service.create(admin, form("PA High", "10000", null, null, null, Role.MANAGER, Role.FINANCE_OFFICER));

        PolicyEvaluation small = service.preview(admin, new BigDecimal("5000"), RequestCategory.HARDWARE, null);
        assertEquals(List.of(Role.MANAGER), small.getApproverRoles());
        assertEquals(1, small.getMatchedPolicies().size());

        PolicyEvaluation large = service.preview(admin, new BigDecimal("20000"), RequestCategory.HARDWARE, null);
        assertEquals(List.of(Role.MANAGER, Role.FINANCE_OFFICER), large.getApproverRoles());

        service.setActive(admin, low.getId(), false, low.getVersion()); // now nothing matches 5,000
        PolicyEvaluation fallback = service.preview(admin, new BigDecimal("5000"), RequestCategory.HARDWARE, null);
        assertTrue(fallback.isFallbackUsed());
        assertEquals(List.of(Role.DEPARTMENT_HEAD), fallback.getApproverRoles());
    }

    @Test
    void previewRejectsAnInvalidAmountOrCategory() {
        assertEquals("amount", assertThrows(PolicyAdminException.class,
                () -> service.preview(admin, null, RequestCategory.OTHER, null)).getField());
        assertEquals("amount", assertThrows(PolicyAdminException.class,
                () -> service.preview(admin, BigDecimal.ZERO, RequestCategory.OTHER, null)).getField());
        assertEquals("category", assertThrows(PolicyAdminException.class,
                () -> service.preview(admin, new BigDecimal("100"), null, null)).getField());
    }

    @Test
    void coverageReflectsTheActivePolicies() {
        assertFalse(service.coverage(admin).isGeneralPolicyPresent());

        service.create(admin, form("PA Only Low", "0", "10000", null, null, Role.MANAGER));

        PolicyCoverage coverage = service.coverage(admin);
        assertTrue(coverage.isGeneralPolicyPresent());
        assertEquals(1, coverage.getGaps().size());
        assertTrue(coverage.getGaps().get(0).isOpenEnded());
        assertEquals(0, new BigDecimal("10000").compareTo(coverage.getGaps().get(0).getFrom()));
    }

    // ---- the key guarantee ----

    @Test
    void anEditedPolicyAppliesToLaterRequestsOnly() {
        WorkflowFixtures.user(userRepository, "pa-manager", Role.MANAGER, department);
        WorkflowFixtures.user(userRepository, "pa-finance", Role.FINANCE_OFFICER, otherDepartment);

        ApprovalPolicy policy = service.create(admin, form("PA Everything", "0", null, null, null, Role.MANAGER));
        Request before = requestService.createAndSubmit(WorkflowFixtures.form("5000"), employee);

        PolicyForm edit = form("PA Everything", "0", null, null, null, Role.FINANCE_OFFICER);
        edit.setVersion(policy.getVersion());
        service.update(admin, policy.getId(), edit);

        Request after = requestService.createAndSubmit(WorkflowFixtures.form("5000"), employee);
        Long beforeId = before.getId();
        Long afterId = after.getId();
        entityManager.flush();
        entityManager.clear();

        assertEquals(List.of(Role.MANAGER),
                rolesOf(requestRepository.findDetailedById(beforeId).orElseThrow()));
        assertEquals(List.of(Role.FINANCE_OFFICER),
                rolesOf(requestRepository.findDetailedById(afterId).orElseThrow()));
    }
}