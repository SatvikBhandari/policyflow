package com.policyflow.policyflow.entity;

import java.time.LocalDateTime;

import com.policyflow.policyflow.enums.NotificationType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** An in-app message for one user. Unread until read_at is set. */
@Entity
@Table(name = "notifications",
        indexes = @Index(name = "idx_notifications_recipient", columnList = "recipient_id, id"))
public class Notification {

    public static final int MAX_MESSAGE_LENGTH = 300;
    public static final int MAX_LINK_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 30)
    private NotificationType type;

    /** The request this is about (a plain number, not a foreign key). Null when there is none. */
    @Column(name = "request_id")
    private Long requestId;

    /** A path inside this application, such as /manager/requests/5. Null when there is nowhere to go. */
    @Column(length = MAX_LINK_LENGTH)
    private String link;

    @Column(nullable = false, length = MAX_MESSAGE_LENGTH)
    private String message;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Notification() {
        // required by JPA
    }

    public Notification(User recipient, NotificationType type, Long requestId, String link, String message) {
        if (recipient == null) {
            throw new IllegalArgumentException("A notification needs a recipient");
        }
        if (type == null) {
            throw new IllegalArgumentException("A notification needs a type");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("A notification needs a message");
        }
        requireLocalLink(link);

        String text = message.trim();
        if (text.length() > MAX_MESSAGE_LENGTH) {
            text = text.substring(0, MAX_MESSAGE_LENGTH - 1) + "\u2026";
        }
        this.recipient = recipient;
        this.type = type;
        this.requestId = requestId;
        this.link = link;
        this.message = text;
    }

    /** Only paths inside this application are allowed, so a notification can never redirect off-site. */
    private static void requireLocalLink(String link) {
        if (link == null) {
            return;
        }
        boolean local = link.startsWith("/") && !link.startsWith("//") && !link.contains("\\");
        if (!local || link.length() > MAX_LINK_LENGTH) {
            throw new IllegalArgumentException("A notification link must be a short path inside the application");
        }
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /** Marks the notification as read. Calling it again keeps the first read time. */
    public void markRead() {
        if (readAt == null) {
            readAt = LocalDateTime.now();
        }
    }

    public boolean isRead() { return readAt != null; }

    public Long getId() { return id; }
    public User getRecipient() { return recipient; }
    public NotificationType getType() { return type; }
    public Long getRequestId() { return requestId; }
    public String getLink() { return link; }
    public String getMessage() { return message; }
    public LocalDateTime getReadAt() { return readAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}