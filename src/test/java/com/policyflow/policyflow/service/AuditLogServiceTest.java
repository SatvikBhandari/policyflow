package com.policyflow.policyflow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.dto.AdminViews.AuditEntry;
import com.policyflow.policyflow.entity.AuditLog;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.repository.AuditLogRepository;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.UserRepository;

@SpringBootTest
@Transactional // everything is rolled back, so your real audit log is untouched
class AuditLogServiceTest {

    @Autowired private AuditLogService service;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;

    private User admin;
    private User employee;

    @BeforeEach
    void setUp() {
        Department department = departmentRepository.save(new Department("Audit Test", "AUDTST"));
        admin = userRepository.save(new User("Audit Admin", "aud-admin@policyflow.local", Role.ADMIN, department));
        employee = userRepository.save(new User("Audit Employee", "aud-employee@policyflow.local", Role.EMPLOYEE, department));
    }

    // ---- helpers ----

    private void log(String email, String action, String entityType, long entityId, String details) {
        auditLogRepository.save(new AuditLog(1L, email, action, entityType, entityId, details));
    }

    private Page<AuditEntry> search(String action, String entityType, String actor,
                                    LocalDate from, LocalDate to, int page) {
        return service.search(admin, action, entityType, actor, from, to, page);
    }

    // ---- pure helpers ----

    @Test
    void actorPatternEscapesWildcards() {
        assertEquals("%", AuditLogService.actorPattern(null));
        assertEquals("%", AuditLogService.actorPattern("   "));
        assertEquals("%alice%", AuditLogService.actorPattern("  Alice "));
        assertEquals("%100!%!_x!!%", AuditLogService.actorPattern("100%_x!"));
    }

    @Test
    void humanizeTurnsConstantsIntoLabels() {
        assertEquals("Request force closed", AuditLogService.humanize("REQUEST_FORCE_CLOSED"));
        assertEquals("User", AuditLogService.humanize("USER"));
        assertEquals("", AuditLogService.humanize(null));
        assertEquals("", AuditLogService.humanize("  "));
    }

    @Test
    void toViewLinksUsersAndPoliciesOnly() {
        assertEquals("/admin/users/7/edit",
                AuditLogService.toView(new AuditLog(1L, "a@b.c", "USER_UPDATED", "USER", 7L, "x")).getLink());
        assertEquals("/admin/policies/3/edit",
                AuditLogService.toView(new AuditLog(1L, "a@b.c", "POLICY_UPDATED", "POLICY", 3L, "x")).getLink());
        assertNull(AuditLogService.toView(new AuditLog(1L, "a@b.c", "REQUEST_APPROVED", "REQUEST", 5L, "x")).getLink());
        assertNull(AuditLogService.toView(new AuditLog(1L, "a@b.c", "USER_UPDATED", "USER", null, "x")).getLink());
    }

    // ---- searching ----

    @Test
    void filtersByEntityTypeAndAction() {
        log("zz6c.alice@policyflow.local", "ZZ6C_ONE", "ZZ6C_ENTITY", 1, "first");
        log("zz6c.bob@policyflow.local", "ZZ6C_TWO", "ZZ6C_ENTITY", 2, "second");
        log("zz6c.alice@policyflow.local", "ZZ6C_TWO", "ZZ6C_OTHER", 3, "third");

        assertEquals(2L, search("", "ZZ6C_ENTITY", "", null, null, 0).getTotalElements());
        assertEquals(2L, search("ZZ6C_TWO", "", "", null, null, 0).getTotalElements());
        assertEquals(1L, search("ZZ6C_TWO", "ZZ6C_ENTITY", "", null, null, 0).getTotalElements());
        assertEquals(0L, search("ZZ6C_NONE", "", "", null, null, 0).getTotalElements());
    }

    @Test
    void actorFilterIsCaseInsensitivePartialAndLiteral() {
        log("zz6c.alice@policyflow.local", "ZZ6C_ONE", "ZZ6C_ENTITY", 1, "first");
        log("zz6c.alice@policyflow.local", "ZZ6C_TWO", "ZZ6C_ENTITY", 2, "second");
        log("zz6c.bob@policyflow.local", "ZZ6C_TWO", "ZZ6C_ENTITY", 3, "third");

        assertEquals(2L, search("", "", "ZZ6C.ALICE", null, null, 0).getTotalElements()); // ignores case
        assertEquals(2L, search("", "", "6c.ali", null, null, 0).getTotalElements());     // partial match
        assertEquals(0L, search("", "", "zz6c_alice", null, null, 0).getTotalElements()); // '_' is not a wildcard

        Page<AuditEntry> percent = search("", "", "zz6c%", null, null, 0); // '%' is not a wildcard either
        assertEquals(0L, percent.getTotalElements());
    }

    @Test
    void dateRangeFiltersByDay() {
        log("zz6c.alice@policyflow.local", "ZZ6C_ONE", "ZZ6C_ENTITY", 1, "first");
        log("zz6c.alice@policyflow.local", "ZZ6C_TWO", "ZZ6C_ENTITY", 2, "second");
        LocalDate today = LocalDate.now();

        assertEquals(2L, search("", "", "zz6c", today, today, 0).getTotalElements());
        assertEquals(2L, search("", "", "zz6c", today.minusDays(1), today.plusDays(1), 0).getTotalElements());
        assertEquals(0L, search("", "", "zz6c", today.plusDays(1), null, 0).getTotalElements());
        assertEquals(0L, search("", "", "zz6c", null, today.minusDays(1), 0).getTotalElements());

        assertThrows(IllegalArgumentException.class,
                () -> search("", "", "", today, today.minusDays(1), 0));
    }

    @Test
    void paginationAndOrdering() {
        for (int i = 1; i <= 25; i++) {
            log("zz6c.page@policyflow.local", "ZZ6C_PAGE", "ZZ6C_PAGE", i, "row " + i);
        }

        Page<AuditEntry> first = search("", "ZZ6C_PAGE", "", null, null, 0);
        Page<AuditEntry> second = search("", "ZZ6C_PAGE", "", null, null, 1);

        assertEquals(25L, first.getTotalElements());
        assertEquals(2, first.getTotalPages());
        assertEquals(AuditLogService.PAGE_SIZE, first.getContent().size());
        assertEquals(5, second.getContent().size());

        // newest first, also across the page boundary
        assertTrue(first.getContent().get(0).getId() > first.getContent().get(1).getId());
        assertTrue(first.getContent().get(19).getId() > second.getContent().get(0).getId());

        assertTrue(search("", "ZZ6C_PAGE", "", null, null, 9).getContent().isEmpty());
        // a negative page number is treated as the first page
        assertEquals(first.getContent().get(0).getId(),
                search("", "ZZ6C_PAGE", "", null, null, -3).getContent().get(0).getId());
    }

    @Test
    void optionsListTheValuesInUse() {
        log("zz6c.alice@policyflow.local", "ZZ6C_OPT_ACTION", "ZZ6C_OPT_ENTITY", 1, "x");

        Map<String, String> actions = service.actionOptions(admin);
        Map<String, String> entityTypes = service.entityTypeOptions(admin);

        assertEquals("Zz6c opt action", actions.get("ZZ6C_OPT_ACTION"));
        assertTrue(entityTypes.containsKey("ZZ6C_OPT_ENTITY"));
    }

    @Test
    void onlyAnAdministratorCanSearch() {
        assertThrows(AccessDeniedException.class, () -> service.search(employee, "", "", "", null, null, 0));
        assertThrows(AccessDeniedException.class, () -> service.recent(employee));
        assertThrows(AccessDeniedException.class, () -> service.actionOptions(employee));
    }
}