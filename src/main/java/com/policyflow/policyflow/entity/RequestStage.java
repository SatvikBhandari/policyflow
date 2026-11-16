package com.policyflow.policyflow.entity;

import java.time.LocalDateTime;

import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.enums.StageStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/** One step of a request's approval chain. Created once at submission and never driven by later policy edits. */
@Entity
@Table(name = "request_stages",
        uniqueConstraints = @UniqueConstraint(name = "uk_request_stage_step",
                columnNames = {"request_id", "step_number"}))
public class RequestStage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private Request request;

    /** 1-based position in the chain. */
    @Column(name = "step_number", nullable = false)
    private int stepNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "approver_role", nullable = false, length = 30)
    private Role approverRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StageStatus status;

    /** Optimistic locking: two people deciding the same stage at once cannot both succeed. */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected RequestStage() {
        // required by JPA
    }

    RequestStage(Request request, int stepNumber, Role approverRole, StageStatus status) {
        this.request = request;
        this.stepNumber = stepNumber;
        this.approverRole = approverRole;
        this.status = status;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // ---- transitions (package-private: only Request drives them) ----

    void markApproved() {
        requireStatus(StageStatus.PENDING, "approved");
        this.status = StageStatus.APPROVED;
    }

    void markRejected() {
        requireStatus(StageStatus.PENDING, "rejected");
        this.status = StageStatus.REJECTED;
    }

    /** WAITING -> PENDING: it is this stage's turn. */
    void activate() {
        requireStatus(StageStatus.WAITING, "activated");
        this.status = StageStatus.PENDING;
    }

    /** The request ended before this stage was decided. Already-decided stages are left alone. */
    void close() {
        if (status == StageStatus.PENDING || status == StageStatus.WAITING) {
            this.status = StageStatus.CLOSED;
        }
    }

    private void requireStatus(StageStatus expected, String action) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Stage " + stepNumber + " cannot be " + action + " (current status: " + status + ")");
        }
    }

    public Long getId() { return id; }
    public Request getRequest() { return request; }
    public int getStepNumber() { return stepNumber; }
    public Role getApproverRole() { return approverRole; }
    public StageStatus getStatus() { return status; }
    public Long getVersion() { return version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}