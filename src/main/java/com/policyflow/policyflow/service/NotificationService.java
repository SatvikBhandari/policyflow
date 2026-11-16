package com.policyflow.policyflow.service;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.entity.Notification;
import com.policyflow.policyflow.entity.Request;
import com.policyflow.policyflow.entity.RequestStage;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.NotificationType;
import com.policyflow.policyflow.enums.RequestPriority;
import com.policyflow.policyflow.enums.RequestStatus;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.exception.ResourceNotFoundException;
import com.policyflow.policyflow.repository.NotificationRepository;
import com.policyflow.policyflow.security.RoleLanding;
import com.policyflow.policyflow.util.MoneyFormatter;

/**
 * Creates and reads in-app notifications.
 * The on... methods are called from inside the transaction of the action they describe,
 * so a notification exists if and only if that action was saved.
 */
@Service
public class NotificationService {

    public static final int PAGE_SIZE = 15;
    private static final int MAX_QUOTED_TEXT = 200;

    private final NotificationRepository notificationRepository;
    private final ApproverResolver approverResolver;
    private final MoneyFormatter money;

    public NotificationService(NotificationRepository notificationRepository,
                               ApproverResolver approverResolver,
                               MoneyFormatter money) {
        this.notificationRepository = notificationRepository;
        this.approverResolver = approverResolver;
        this.money = money;
    }

    // ------------------------------------------------------------------ events

    /** A request was submitted: tell everyone who may decide its first step. */
    @Transactional
    public void onSubmitted(Request request) {
        notifyCurrentStageApprovers(request);
    }

    /** A step was approved: tell the requester, then the approvers of the next step (if any). */
    @Transactional
    public void onApproved(Request request, RequestStage approvedStage, User approver) {
        resolveApprovalRequests(request.getId()); // the to-do for the step that was just decided is done

        String reference = request.getReference();
        if (request.getStatus() == RequestStatus.APPROVED) {
            notifyRequester(request, NotificationType.REQUEST_APPROVED, reference + " was fully approved.");
            return;
        }

        RequestStage next = request.getCurrentStage();
        String message = reference + " was approved by " + approver.getFullName()
                + " (" + approvedStage.getApproverRole().getLabel() + ")."
                + (next == null ? "" : " Next: " + next.getApproverRole().getLabel() + ".");
        notifyRequester(request, NotificationType.STAGE_APPROVED, message);
        notifyCurrentStageApprovers(request);
    }

    /** A step was rejected: tell the requester why. */
    @Transactional
    public void onRejected(Request request, RequestStage rejectedStage, User approver, String comment) {
        resolveApprovalRequests(request.getId());
        notifyRequester(request, NotificationType.REQUEST_REJECTED,
                request.getReference() + " was rejected by " + approver.getFullName()
                        + " (" + rejectedStage.getApproverRole().getLabel() + "). Reason: " + quote(comment));
    }

    /**
     * The requester cancelled: tell the approvers who had it in their queue.
     *
     * @param waitingRole the role whose step was pending before the cancellation, or null if none
     */
    @Transactional
    public void onCancelled(Request request, Role waitingRole) {
        resolveApprovalRequests(request.getId());
        if (waitingRole == null) {
            return;
        }
        User requester = request.getEmployee();
        for (User approver : approverResolver.eligibleApprovers(waitingRole, requester)) {
            create(approver, NotificationType.REQUEST_WITHDRAWN, request.getId(), null,
                    request.getReference() + " was withdrawn by " + requester.getFullName() + ".");
        }
    }

    /** An administrator closed a stuck request: tell the requester why. */
    @Transactional
    public void onForceClosed(Request request, User admin, String reason) {
        resolveApprovalRequests(request.getId());
        notifyRequester(request, NotificationType.REQUEST_CLOSED,
                request.getReference() + " was closed by an administrator. Reason: " + quote(reason));
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public Page<Notification> list(User user, int page) {
        return notificationRepository.findByRecipientIdOrderByIdDesc(
                user.getId(), PageRequest.of(Math.max(page, 0), PAGE_SIZE));
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        if (userId == null) {
            return 0;
        }
        return notificationRepository.countByRecipientIdAndReadAtIsNull(userId);
    }

    /**
     * Marks one of the user's own notifications as read.
     *
     * @throws ResourceNotFoundException if it does not exist or belongs to someone else
     */
    @Transactional
    public Notification markRead(User user, Long notificationId) {
        Notification notification = notificationRepository.findByIdAndRecipientId(notificationId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        notification.markRead();
        return notification;
    }

    /** Marks all of the user's unread notifications as read and returns how many there were. */
    @Transactional
    public int markAllRead(User user) {
        List<Notification> unread = notificationRepository.findByRecipientIdAndReadAtIsNull(user.getId());
        unread.forEach(Notification::markRead);
        return unread.size();
    }

    // ------------------------------------------------------------------ helpers

    private void notifyCurrentStageApprovers(Request request) {
        RequestStage current = request.getCurrentStage();
        if (current == null) {
            return;
        }
        User requester = request.getEmployee();
        String message = (request.getPriority() == RequestPriority.URGENT ? "Urgent: " : "")
                + request.getReference() + " (" + money.format(request.getAmount(), 2) + ") from "
                + requester.getFullName() + " needs your decision (step " + current.getStepNumber()
                + " of " + request.getStages().size() + ").";
        String link = RoleLanding.urlFor(current.getApproverRole()) + "/requests/" + request.getId();

        for (User approver : approverResolver.eligibleApprovers(current.getApproverRole(), requester)) {
            create(approver, NotificationType.APPROVAL_REQUESTED, request.getId(), link, message);
        }
    }

    private void notifyRequester(Request request, NotificationType type, String message) {
        create(request.getEmployee(), type, request.getId(), "/requests/" + request.getId(), message);
    }

    /** A step has been decided, withdrawn or closed, so its "needs your decision" notifications are no longer to-dos. */
    private void resolveApprovalRequests(Long requestId) {
        notificationRepository
                .findByRequestIdAndTypeAndReadAtIsNull(requestId, NotificationType.APPROVAL_REQUESTED)
                .forEach(Notification::markRead);
    }

    private void create(User recipient, NotificationType type, Long requestId, String link, String message) {
        notificationRepository.save(new Notification(recipient, type, requestId, link, message));
    }

    private static String quote(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= MAX_QUOTED_TEXT
                ? trimmed
                : trimmed.substring(0, MAX_QUOTED_TEXT - 1) + "\u2026";
    }
}