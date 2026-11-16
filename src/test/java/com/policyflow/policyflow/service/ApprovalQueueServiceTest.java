package com.policyflow.policyflow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.dto.ApprovalViews.QueueItem;
import com.policyflow.policyflow.dto.RequestForm;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.Request;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.repository.ApprovalPolicyRepository;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.UserRepository;
import com.policyflow.policyflow.support.WorkflowFixtures;

@SpringBootTest
@Transactional // everything is rolled back, so your real data is untouched
class ApprovalQueueServiceTest {

    @Autowired private ApprovalQueueService queueService;
    @Autowired private RequestService requestService;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ApprovalPolicyRepository policyRepository;

    private User employeeA;
    private User employeeB;
    private User manager;
    private User outsiderManager;
    private String zedReference;

    @BeforeEach
    void setUp() {
        WorkflowFixtures.isolatePolicies(policyRepository);
        WorkflowFixtures.policy(policyRepository, "AQ manager only", Role.MANAGER);

        Department department = departmentRepository.save(new Department("Queue Test", "QUEUETST"));
        Department other = departmentRepository.save(new Department("Queue Other", "QUEUEOTH"));
        employeeA = WorkflowFixtures.user(userRepository, "queue-a", Role.EMPLOYEE, department);
        employeeB = WorkflowFixtures.user(userRepository, "zed-requester", Role.EMPLOYEE, department);
        manager = WorkflowFixtures.user(userRepository, "queue-manager", Role.MANAGER, department);
        outsiderManager = WorkflowFixtures.user(userRepository, "queue-outsider", Role.MANAGER, other);

        // 23 requests from A (12 hardware, 11 software) and 1 travel request from B: 24 pending in total
        for (int i = 1; i <= 23; i++) {
            create(employeeA, "Queue item " + i, i % 2 == 0 ? RequestCategory.SOFTWARE : RequestCategory.HARDWARE);
        }
        zedReference = create(employeeB, "Zed trip", RequestCategory.TRAVEL).getReference();
    }

    // ---- helpers ----

    private Request create(User who, String title, RequestCategory category) {
        RequestForm form = new RequestForm();
        form.setTitle(title);
        form.setCategory(category);
        form.setAmount(new BigDecimal("5000"));
        form.setDescription("Created by an automated test.");
        return requestService.createAndSubmit(form, who);
    }

    private Page<QueueItem> search(User approver, String text, RequestCategory category, int page) {
        return queueService.search(approver, text, category, page);
    }

    private static QueueItem item(String reference, String title, String categoryLabel,
                                  String requester, String department) {
        return new QueueItem(1L, reference, title, categoryLabel, new BigDecimal("100"),
                requester, department, LocalDateTime.now(), 1, 2);
    }

    // ---- matching rules (no database) ----

    @Test
    void noFiltersMatchEverything() {
        QueueItem item = item("PF-00001", "Laptop", "Hardware", "Aarav Sharma", "Engineering");

        assertTrue(ApprovalQueueService.matches(item, null, null));
        assertTrue(ApprovalQueueService.matches(item, "   ", null));
    }

    @Test
    void theCategoryMustMatchWhenGiven() {
        QueueItem item = item("PF-00001", "Laptop", "Hardware", "Aarav Sharma", "Engineering");

        assertTrue(ApprovalQueueService.matches(item, null, RequestCategory.HARDWARE));
        assertFalse(ApprovalQueueService.matches(item, null, RequestCategory.SOFTWARE));
        assertFalse(ApprovalQueueService.matches(item, "laptop", RequestCategory.SOFTWARE));
    }

    @Test
    void textMatchesReferenceTitleRequesterOrDepartmentIgnoringCase() {
        QueueItem item = item("PF-00001", "Laptop", "Hardware", "Aarav Sharma", "Engineering");

        assertTrue(ApprovalQueueService.matches(item, "pf-0000", null));
        assertTrue(ApprovalQueueService.matches(item, "LAPTOP", null));
        assertTrue(ApprovalQueueService.matches(item, " aarav ", null));
        assertTrue(ApprovalQueueService.matches(item, "engineer", null));
        assertFalse(ApprovalQueueService.matches(item, "finance", null));
        assertFalse(ApprovalQueueService.matches(item, "%", null)); // a wildcard is just a character here
    }

    // ---- paging and filtering against the database ----

    @Test
    void theQueueIsPagedWithoutLosingOrRepeatingRequests() {
        Page<QueueItem> first = search(manager, null, null, 0);
        Page<QueueItem> second = search(manager, null, null, 1);

        assertEquals(24L, first.getTotalElements());
        assertEquals(2, first.getTotalPages());
        assertEquals(ApprovalQueueService.PAGE_SIZE, first.getContent().size());
        assertEquals(4, second.getContent().size());

        Set<Long> ids = new HashSet<>();
        first.getContent().forEach(item -> ids.add(item.getRequestId()));
        second.getContent().forEach(item -> ids.add(item.getRequestId()));
        assertEquals(24, ids.size());
    }

    @Test
    void textFilterMatchesTitleRequesterAndReference() {
        List<QueueItem> byTitle = search(manager, "item 7", null, 0).getContent();
        assertEquals(1, byTitle.size());
        assertEquals("Queue item 7", byTitle.get(0).getTitle());

        assertEquals(1L, search(manager, "zed", null, 0).getTotalElements());              // requester name
        assertEquals(1L, search(manager, zedReference, null, 0).getTotalElements());       // reference
        assertEquals(24L, search(manager, "queue test", null, 0).getTotalElements());      // department name
        assertEquals(0L, search(manager, "nothing like this", null, 0).getTotalElements());
    }

    @Test
    void categoryFilterSelectsOnlyThatCategory() {
        assertEquals(12L, search(manager, null, RequestCategory.HARDWARE, 0).getTotalElements());
        assertEquals(11L, search(manager, null, RequestCategory.SOFTWARE, 0).getTotalElements());
        assertEquals(1L, search(manager, null, RequestCategory.TRAVEL, 0).getTotalElements());
        assertEquals(0L, search(manager, null, RequestCategory.OFFICE_SUPPLIES, 0).getTotalElements());
    }

    @Test
    void textAndCategoryCombine() {
        assertEquals(11L, search(manager, "item", RequestCategory.SOFTWARE, 0).getTotalElements());
        assertEquals(0L, search(manager, "zed", RequestCategory.HARDWARE, 0).getTotalElements());
        assertEquals(1L, search(manager, "zed", RequestCategory.TRAVEL, 0).getTotalElements());
    }

    @Test
    void onlyApproversWithAccessSeeAnything() {
        assertEquals(0L, search(employeeA, null, null, 0).getTotalElements());        // not an approver
        assertEquals(0L, search(outsiderManager, null, null, 0).getTotalElements());  // another department
    }

    @Test
    void pageNumbersOutOfRangeAreHandled() {
        List<Long> firstPageIds = search(manager, null, null, 0).getContent()
                .stream().map(QueueItem::getRequestId).toList();

        assertEquals(firstPageIds,
                search(manager, null, null, -3).getContent().stream().map(QueueItem::getRequestId).toList());

        Page<QueueItem> beyond = search(manager, null, null, 50);
        assertTrue(beyond.getContent().isEmpty());
        assertEquals(24L, beyond.getTotalElements());
    }
}