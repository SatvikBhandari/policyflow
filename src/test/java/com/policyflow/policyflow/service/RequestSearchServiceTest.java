package com.policyflow.policyflow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.dto.RequestForm;
import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.Request;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.RequestStatus;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.repository.ApprovalPolicyRepository;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.UserRepository;
import com.policyflow.policyflow.support.WorkflowFixtures;

import jakarta.persistence.EntityManager;

@SpringBootTest
@Transactional // everything is rolled back, so your real data is untouched
class RequestSearchServiceTest {

    @Autowired private RequestSearchService searchService;
    @Autowired private RequestService requestService;
    @Autowired private ApprovalService approvalService;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ApprovalPolicyRepository policyRepository;
    @Autowired private EntityManager entityManager;

    private User employee;
    private User other;
    private User manager;

    @BeforeEach
    void setUp() {
        WorkflowFixtures.isolatePolicies(policyRepository);
        WorkflowFixtures.policy(policyRepository, "RSS manager only", Role.MANAGER);

        Department department = departmentRepository.save(new Department("Search Test", "SRCHTST"));
        employee = WorkflowFixtures.user(userRepository, "srch-employee", Role.EMPLOYEE, department);
        other = WorkflowFixtures.user(userRepository, "srch-other", Role.EMPLOYEE, department);
        manager = WorkflowFixtures.user(userRepository, "srch-manager", Role.MANAGER, department);
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

    private Page<Request> search(String text, RequestStatus status, RequestCategory category,
                                 LocalDate from, LocalDate to, int page) {
        return searchService.searchOwn(employee, text, status, category, from, to, page);
    }

    private static List<String> titles(Page<Request> page) {
        return page.getContent().stream().map(Request::getTitle).toList();
    }

    // ---- text ----

    @Test
    void textMatchesTheTitleAndTheReferenceIgnoringCase() {
        Request laptop = create(employee, "Laptop for design team", RequestCategory.HARDWARE);
        create(employee, "Office chairs", RequestCategory.OFFICE_SUPPLIES);

        assertEquals(List.of("Laptop for design team"), titles(search("LAPTOP", null, null, null, null, 0)));
        assertEquals(List.of("Office chairs"), titles(search("  chair ", null, null, null, null, 0)));
        assertEquals(List.of("Laptop for design team"),
                titles(search(laptop.getReference().toLowerCase(), null, null, null, null, 0)));
        assertEquals(2L, search("", null, null, null, null, 0).getTotalElements());
        assertEquals(0L, search("zzzz", null, null, null, null, 0).getTotalElements());
    }

    @Test
    void percentAndUnderscoreAreMatchedLiterally() {
        create(employee, "Save 100% now", RequestCategory.OTHER);
        create(employee, "Save 1000 now", RequestCategory.OTHER);
        create(employee, "Plan A_B", RequestCategory.OTHER);
        create(employee, "Plan AxB", RequestCategory.OTHER);

        assertEquals(List.of("Save 100% now"), titles(search("100%", null, null, null, null, 0)));
        assertEquals(List.of("Plan A_B"), titles(search("a_b", null, null, null, null, 0)));
    }

    // ---- status and category ----

    @Test
    void statusFilterSelectsOnlyThatStatus() {
        Request approved = create(employee, "Approved one", RequestCategory.OTHER);
        Request rejected = create(employee, "Rejected one", RequestCategory.OTHER);
        Request cancelled = create(employee, "Cancelled one", RequestCategory.OTHER);
        create(employee, "Pending one", RequestCategory.OTHER);

        approvalService.approve(approved.getId(), manager, null);
        approvalService.reject(rejected.getId(), manager, "No");
        requestService.cancel(cancelled.getId(), employee, null);

        assertEquals(List.of("Approved one"), titles(search(null, RequestStatus.APPROVED, null, null, null, 0)));
        assertEquals(List.of("Rejected one"), titles(search(null, RequestStatus.REJECTED, null, null, null, 0)));
        assertEquals(List.of("Cancelled one"), titles(search(null, RequestStatus.CANCELLED, null, null, null, 0)));
        assertEquals(List.of("Pending one"), titles(search(null, RequestStatus.PENDING_APPROVAL, null, null, null, 0)));
        assertEquals(0L, search(null, RequestStatus.DRAFT, null, null, null, 0).getTotalElements());
        assertEquals(4L, search(null, null, null, null, null, 0).getTotalElements());
    }

    @Test
    void categoryFilterSelectsOnlyThatCategory() {
        create(employee, "A laptop", RequestCategory.HARDWARE);
        create(employee, "A licence", RequestCategory.SOFTWARE);
        create(employee, "A flight", RequestCategory.TRAVEL);

        assertEquals(List.of("A licence"), titles(search(null, null, RequestCategory.SOFTWARE, null, null, 0)));
        assertEquals(List.of("A flight"), titles(search(null, null, RequestCategory.TRAVEL, null, null, 0)));
        assertEquals(0L, search(null, null, RequestCategory.OFFICE_SUPPLIES, null, null, 0).getTotalElements());
    }

    @Test
    void filtersCombine() {
        create(employee, "Blue laptop", RequestCategory.HARDWARE);
        create(employee, "Blue licence", RequestCategory.SOFTWARE);
        Request red = create(employee, "Red laptop", RequestCategory.HARDWARE);
        approvalService.approve(red.getId(), manager, null);

        assertEquals(List.of("Blue laptop"),
                titles(search("blue", RequestStatus.PENDING_APPROVAL, RequestCategory.HARDWARE, null, null, 0)));
        assertEquals(List.of("Red laptop"),
                titles(search("laptop", RequestStatus.APPROVED, RequestCategory.HARDWARE, null, null, 0)));
        assertEquals(0L, search("red", RequestStatus.PENDING_APPROVAL, null, null, null, 0).getTotalElements());
    }

    // ---- dates ----

    @Test
    void dateRangeFiltersByTheDayTheRequestWasCreated() {
        Request old = create(employee, "Old request", RequestCategory.OTHER);
        create(employee, "New request", RequestCategory.OTHER);

        // Move one request ten days into the past (created_at is not updatable through the entity)
        entityManager.flush();
        entityManager.createNativeQuery("update requests set created_at = :t where id = :id")
                .setParameter("t", LocalDateTime.now().minusDays(10))
                .setParameter("id", old.getId())
                .executeUpdate();
        entityManager.clear();

        LocalDate today = LocalDate.now();
        assertEquals(List.of("New request"), titles(search(null, null, null, today.minusDays(1), null, 0)));
        assertEquals(List.of("Old request"), titles(search(null, null, null, null, today.minusDays(5), 0)));
        assertEquals(2L, search(null, null, null, today.minusDays(11), today, 0).getTotalElements());
        assertEquals(1L, search(null, null, null, today, today, 0).getTotalElements()); // both ends inclusive
        assertEquals(0L, search(null, null, null, today.plusDays(1), null, 0).getTotalElements());

        assertThrows(IllegalArgumentException.class,
                () -> search(null, null, null, today, today.minusDays(1), 0));
    }

    // ---- pagination and ownership ----

    @Test
    void resultsArePagedNewestFirst() {
        for (int i = 1; i <= 23; i++) {
            create(employee, "Paged " + i, RequestCategory.OTHER);
        }

        Page<Request> first = search(null, null, null, null, null, 0);
        Page<Request> second = search(null, null, null, null, null, 1);
        Page<Request> third = search(null, null, null, null, null, 2);

        assertEquals(23L, first.getTotalElements());
        assertEquals(3, first.getTotalPages());
        assertEquals(RequestSearchService.PAGE_SIZE, first.getContent().size());
        assertEquals(RequestSearchService.PAGE_SIZE, second.getContent().size());
        assertEquals(3, third.getContent().size());

        assertEquals("Paged 23", first.getContent().get(0).getTitle());
        assertTrue(first.getContent().get(0).getId() > first.getContent().get(1).getId());
        assertTrue(first.getContent().get(9).getId() > second.getContent().get(0).getId());
        assertEquals("Paged 1", third.getContent().get(2).getTitle());

        assertTrue(search(null, null, null, null, null, 99).getContent().isEmpty());
        assertEquals(first.getContent().get(0).getId(),
                search(null, null, null, null, null, -2).getContent().get(0).getId()); // negative means the first page
    }

    @Test
    void anEmployeeOnlyEverSeesTheirOwnRequests() {
        create(other, "Secret plan", RequestCategory.HARDWARE);

        assertEquals(0L, search("secret", null, null, null, null, 0).getTotalElements());
        assertEquals(0L, search(null, null, null, null, null, 0).getTotalElements());
        assertEquals(1L, searchService.searchOwn(other, "secret", null, null, null, null, 0).getTotalElements());
    }
}