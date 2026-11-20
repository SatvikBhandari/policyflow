package com.policyflow.policyflow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.Notification;
import com.policyflow.policyflow.entity.Request;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.NotificationType;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.exception.ResourceNotFoundException;
import com.policyflow.policyflow.repository.ApprovalPolicyRepository;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.NotificationRepository;
import com.policyflow.policyflow.repository.UserRepository;
import com.policyflow.policyflow.support.WorkflowFixtures;

@SpringBootTest
@Transactional // everything is rolled back, so your real data is untouched
class NotificationServiceTest {

    @Autowired private NotificationService service;
    @Autowired private RequestService requestService;
    @Autowired private ApprovalService approvalService;
    @Autowired private AdminOversightService oversightService;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private ApprovalPolicyRepository policyRepository;

    private Department department;
    private Department otherDepartment;
    private User employee;
    private User manager;
    private User secondManager;
    private User outsiderManager;
    private User finance;
    private User admin;

    @BeforeEach
    void setUp() {
        WorkflowFixtures.isolatePolicies(policyRepository); // only policies created by each test apply

        department = departmentRepository.save(new Department("Notif Test", "NTFTST"));
        otherDepartment = departmentRepository.save(new Department("Notif Other", "NTFOTH"));
        employee = user("ntf-employee", Role.EMPLOYEE, department);
        manager = user("ntf-manager", Role.MANAGER, department);
        secondManager = user("ntf-manager2", Role.MANAGER, department);
        outsiderManager = user("ntf-outsider", Role.MANAGER, otherDepartment);
        finance = user("ntf-finance", Role.FINANCE_OFFICER, otherDepartment);
        admin = user("ntf-admin", Role.ADMIN, department);
    }

    // ---- helpers ----

    private User user(String prefix, Role role, Department dept) {
        return WorkflowFixtures.user(userRepository, prefix, role, dept);
    }

    private void policy(Role... roles) {
        WorkflowFixtures.policy(policyRepository, "NT policy", roles);
    }

    private Request submit() {
        return requestService.createAndSubmit(WorkflowFixtures.form("5000"), employee);
    }

    private List<Notification> inbox(User user) {
        return service.list(user, 0).getContent();
    }

    private void deactivate(User user) {
        user.setActive(false);
        userRepository.saveAndFlush(user);
    }

    // ---- who is notified when a request is submitted ----

    @Test
    void submissionNotifiesTheEligibleApproversOfTheFirstStageOnly() {
        policy(Role.MANAGER, Role.FINANCE_OFFICER);
        User inactiveManager = user("ntf-inactive", Role.MANAGER, department);
        deactivate(inactiveManager);

        Request request = submit();

        for (User approver : List.of(manager, secondManager)) {
            List<Notification> inbox = inbox(approver);
            assertEquals(1, inbox.size());
            Notification notification = inbox.get(0);
            assertEquals(NotificationType.APPROVAL_REQUESTED, notification.getType());
            assertEquals("/manager/requests/" + request.getId(), notification.getLink());
            assertEquals(request.getId(), notification.getRequestId());
            assertTrue(notification.getMessage().contains(request.getReference()));
            assertTrue(notification.getMessage().contains("needs your decision"));
            assertFalse(notification.isRead());
        }
        // wrong department, a later step, an inactive account, and the requester hear nothing
        for (User bystander : List.of(outsiderManager, finance, inactiveManager, employee)) {
            assertTrue(inbox(bystander).isEmpty(), bystander.getEmail());
        }
    }

    @Test
    void aManagerWhoRaisesARequestIsNotNotifiedAboutTheirOwnRequest() {
        policy(Role.MANAGER);
        User managerRequester = user("ntf-manager-requester", Role.MANAGER, department);

        requestService.createAndSubmit(WorkflowFixtures.form("5000"), managerRequester);

        // A manager's request moves up to the Finance Officer, so no manager hears about it
        assertTrue(inbox(manager).isEmpty());
        assertTrue(inbox(secondManager).isEmpty());
        assertTrue(inbox(managerRequester).isEmpty());
        assertEquals(1, inbox(finance).size());
    }

    // ---- approving ----

    @Test
    void approvingMovesTheToDoOnAndTellsTheRequester() {
        policy(Role.MANAGER, Role.FINANCE_OFFICER);
        Request request = submit();

        approvalService.approve(request.getId(), manager, "ok");

        // the to-do is finished for both managers, not just the one who decided
        for (User approver : List.of(manager, secondManager)) {
            Notification todo = inbox(approver).get(0);
            assertEquals(NotificationType.APPROVAL_REQUESTED, todo.getType());
            assertTrue(todo.isRead());
        }

        List<Notification> requesterInbox = inbox(employee);
        assertEquals(1, requesterInbox.size());
        Notification progress = requesterInbox.get(0);
        assertEquals(NotificationType.STAGE_APPROVED, progress.getType());
        assertEquals("/requests/" + request.getId(), progress.getLink());
        assertTrue(progress.getMessage().contains(manager.getFullName()));
        assertTrue(progress.getMessage().contains("Next: Finance Officer"));

        List<Notification> financeInbox = inbox(finance);
        assertEquals(1, financeInbox.size());
        assertEquals(NotificationType.APPROVAL_REQUESTED, financeInbox.get(0).getType());
        assertEquals("/finance/requests/" + request.getId(), financeInbox.get(0).getLink());
        assertFalse(financeInbox.get(0).isRead());
    }

    @Test
    void finalApprovalTellsTheRequesterAndNobodyElse() {
        policy(Role.MANAGER);
        Request request = submit();

        approvalService.approve(request.getId(), manager, null);

        List<Notification> requesterInbox = inbox(employee);
        assertEquals(1, requesterInbox.size());
        assertEquals(NotificationType.REQUEST_APPROVED, requesterInbox.get(0).getType());
        assertTrue(requesterInbox.get(0).getMessage().contains("fully approved"));
        assertEquals("/requests/" + request.getId(), requesterInbox.get(0).getLink());
        assertTrue(inbox(finance).isEmpty());
    }

    // ---- rejecting, cancelling, closing ----

    @Test
    void rejectionTellsTheRequesterWhyAndClearsTheToDo() {
        policy(Role.MANAGER, Role.FINANCE_OFFICER);
        Request request = submit();

        approvalService.reject(request.getId(), manager, "Over budget");

        List<Notification> requesterInbox = inbox(employee);
        assertEquals(1, requesterInbox.size());
        assertEquals(NotificationType.REQUEST_REJECTED, requesterInbox.get(0).getType());
        assertTrue(requesterInbox.get(0).getMessage().contains("Over budget"));
        assertTrue(requesterInbox.get(0).getMessage().contains(manager.getFullName()));

        assertTrue(inbox(manager).get(0).isRead());
        assertTrue(inbox(secondManager).get(0).isRead());
        assertTrue(inbox(finance).isEmpty()); // the request never reached finance
    }

    @Test
    void cancellingWithdrawsTheRequestFromTheApprovers() {
        policy(Role.MANAGER);
        Request request = submit();

        requestService.cancel(request.getId(), employee, "No longer needed");

        for (User approver : List.of(manager, secondManager)) {
            List<Notification> inbox = inbox(approver);
            assertEquals(2, inbox.size());
            assertEquals(NotificationType.REQUEST_WITHDRAWN, inbox.get(0).getType()); // newest first
            assertNull(inbox.get(0).getLink()); // there is nothing left to open
            assertTrue(inbox.get(0).getMessage().contains("withdrawn"));
            assertEquals(NotificationType.APPROVAL_REQUESTED, inbox.get(1).getType());
            assertTrue(inbox.get(1).isRead()); // the old to-do is cleared
        }
        assertTrue(inbox(employee).isEmpty()); // the requester did this themselves
    }

    @Test
    void forceClosingTellsTheRequesterWhy() {
        policy(Role.MANAGER);
        Request request = submit();
        deactivate(manager);
        deactivate(secondManager); // nobody is left to approve, so the request is stuck

        oversightService.forceClose(admin, request.getId(), "No manager left");

        List<Notification> requesterInbox = inbox(employee);
        assertEquals(1, requesterInbox.size());
        assertEquals(NotificationType.REQUEST_CLOSED, requesterInbox.get(0).getType());
        assertTrue(requesterInbox.get(0).getMessage().contains("No manager left"));
        assertTrue(requesterInbox.get(0).getMessage().contains("administrator"));
        assertTrue(inbox(manager).get(0).isRead());
    }

    // ---- reading and marking ----

    @Test
    void unreadCountsFollowMarkingAsRead() {
        policy(Role.MANAGER);
        submit();
        Notification notification = inbox(manager).get(0);

        assertEquals(1L, service.unreadCount(manager.getId()));
        assertEquals(1L, service.unreadCount(secondManager.getId()));

        assertTrue(service.markRead(manager, notification.getId()).isRead());

        assertEquals(0L, service.unreadCount(manager.getId()));
        assertEquals(1L, service.unreadCount(secondManager.getId())); // a colleague's copy is untouched
        assertEquals(0L, service.unreadCount(null));
    }

    @Test
    void onlyTheRecipientCanMarkANotificationRead() {
        policy(Role.MANAGER);
        submit();
        Notification notification = inbox(manager).get(0);

        assertThrows(ResourceNotFoundException.class, () -> service.markRead(employee, notification.getId()));
        assertThrows(ResourceNotFoundException.class, () -> service.markRead(manager, 999_999_999L));

        assertFalse(inbox(manager).get(0).isRead());
    }

    @Test
    void markAllReadOnlyAffectsTheCallersNotifications() {
        policy(Role.MANAGER);
        submit();
        submit();
        assertEquals(2L, service.unreadCount(manager.getId()));

        assertEquals(2, service.markAllRead(manager));

        assertEquals(0L, service.unreadCount(manager.getId()));
        assertEquals(2L, service.unreadCount(secondManager.getId()));
        assertEquals(0, service.markAllRead(manager)); // nothing left to mark
    }

    @Test
    void theListIsNewestFirstAndPaged() {
        for (int i = 1; i <= 17; i++) {
            notificationRepository.save(
                    new Notification(employee, NotificationType.STAGE_APPROVED, null, null, "Message " + i));
        }

        Page<Notification> first = service.list(employee, 0);
        Page<Notification> second = service.list(employee, 1);

        assertEquals(17L, first.getTotalElements());
        assertEquals(NotificationService.PAGE_SIZE, first.getContent().size());
        assertEquals(2, second.getContent().size());
        assertEquals("Message 17", first.getContent().get(0).getMessage());
        assertEquals("Message 2", second.getContent().get(0).getMessage());
        assertEquals("Message 1", second.getContent().get(1).getMessage());
        assertEquals("Message 17", service.list(employee, -4).getContent().get(0).getMessage());
    }
}