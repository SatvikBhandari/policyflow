package com.policyflow.policyflow.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.policyflow.policyflow.entity.Notification;
import com.policyflow.policyflow.enums.NotificationType;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByRecipientIdOrderByIdDesc(Long recipientId, Pageable pageable);

    long countByRecipientIdAndReadAtIsNull(Long recipientId);

    /** Was this person ever told that a decision from them was needed on this request? */
    boolean existsByRecipientIdAndRequestIdAndType(Long recipientId, Long requestId, NotificationType type);

    /** Scoped to the recipient, so nobody can reach another person's notification by guessing an id. */
    Optional<Notification> findByIdAndRecipientId(Long id, Long recipientId);

    List<Notification> findByRecipientIdAndReadAtIsNull(Long recipientId);

    List<Notification> findByRequestIdAndTypeAndReadAtIsNull(Long requestId, NotificationType type);
}