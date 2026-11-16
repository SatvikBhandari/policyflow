package com.policyflow.policyflow.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.hibernate.annotations.Check;
import org.hibernate.annotations.ColumnDefault;

import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.RequestPriority;
import com.policyflow.policyflow.enums.RequestStatus;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.enums.StageStatus;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "requests")
@Check(constraints = "amount > 0")
public class Request {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Human-readable reference such as PF-00042, assigned right after the first save.
    @Column(unique = true, length = 20)
    private String reference;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(nullable = false, length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RequestCategory category;

    // Amount in INR. BigDecimal avoids floating-point rounding errors.
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RequestStatus status = RequestStatus.DRAFT;

    /** How urgent the request is. Existing rows default to NORMAL. Can only change while the request is a draft. */
    @ColumnDefault("'NORMAL'")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RequestPriority priority = RequestPriority.NORMAL;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private User employee;

    /** The approval chain, generated once at submission. */
    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("stepNumber ASC")
    private List<RequestStage> stages = new ArrayList<>();

    /** Which policies (or fallback) decided the chain, recorded at submission. */
    @Column(name = "routing_summary", length = 1000)
    private String routingSummary;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Request() {
        // required by JPA
    }

    public Request(String title, String description, RequestCategory category,
                   BigDecimal amount, User employee) {
        this.title = title;
        this.description = description;
        this.category = category;
        this.amount = amount;
        this.employee = employee;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** Builds the reference from the generated id. Call once, after the first save. */
    public void assignReference() {
        if (id == null) {
            throw new IllegalStateException("Request must be saved before a reference can be assigned");
        }
        this.reference = String.format("PF-%05d", id);
    }

    /** DRAFT -> SUBMITTED. Any other starting status is rejected. */
    public void submit() {
        if (status != RequestStatus.DRAFT) {
            throw new IllegalStateException("Only draft requests can be submitted (current status: " + status + ")");
        }
        this.status = RequestStatus.SUBMITTED;
        this.submittedAt = LocalDateTime.now();
    }

    /**
     * SUBMITTED -> PENDING_APPROVAL. Creates one stage per approver role, in order:
     * the first stage waits for a decision, the others wait their turn.
     */
    public void routeForApproval(List<Role> approverRoles, String routingSummary) {
        if (status != RequestStatus.SUBMITTED) {
            throw new IllegalStateException(
                    "Only submitted requests can be routed for approval (current status: " + status + ")");
        }
        if (!stages.isEmpty()) {
            throw new IllegalStateException("This request already has approval stages");
        }
        if (approverRoles == null || approverRoles.isEmpty()) {
            throw new IllegalArgumentException("At least one approver is required");
        }
        for (Role role : approverRoles) {
            if (role == null || !role.isApprover()) {
                throw new IllegalArgumentException("Role cannot approve requests: " + role);
            }
        }

        int step = 1;
        for (Role role : approverRoles) {
            StageStatus initial = step == 1 ? StageStatus.PENDING : StageStatus.WAITING;
            stages.add(new RequestStage(this, step, role, initial));
            step++;
        }
        this.routingSummary = routingSummary;
        this.status = RequestStatus.PENDING_APPROVAL;
    }

    /**
     * Approves the stage that is awaiting a decision. The next stage becomes pending,
     * or the whole request becomes APPROVED when this was the last stage.
     *
     * @return the stage that was approved
     */
    public RequestStage approveCurrentStage() {
        RequestStage current = requirePendingStage();
        current.markApproved();

        RequestStage next = null;
        for (RequestStage stage : stages) {
            if (stage.getStepNumber() == current.getStepNumber() + 1) {
                next = stage;
            }
        }
        if (next == null) {
            this.status = RequestStatus.APPROVED;
        } else {
            next.activate();
        }
        return current;
    }

    /**
     * Rejects the stage that is awaiting a decision. A rejection ends the workflow:
     * the request becomes REJECTED and the later stages are closed.
     *
     * @return the stage that was rejected
     */
    public RequestStage rejectCurrentStage() {
        RequestStage current = requirePendingStage();
        current.markRejected();
        for (RequestStage stage : stages) {
            stage.close(); // closes only stages that are still WAITING
        }
        this.status = RequestStatus.REJECTED;
        return current;
    }

    /**
     * A request can be cancelled until the first approver has approved it.
     * (Drafts and old SUBMITTED requests without stages can be cancelled too.)
     */
    public boolean canBeCancelled() {
        if (status == RequestStatus.DRAFT || status == RequestStatus.SUBMITTED) {
            return true;
        }
        if (status != RequestStatus.PENDING_APPROVAL) {
            return false;
        }
        return stages.stream().noneMatch(stage -> stage.getStatus() == StageStatus.APPROVED);
    }

    /** Moves the request to CANCELLED and closes every open stage. */
    public void cancel() {
        if (!canBeCancelled()) {
            throw new IllegalStateException("This request can no longer be cancelled (status: " + status + ")");
        }
        for (RequestStage stage : stages) {
            stage.close();
        }
        this.status = RequestStatus.CANCELLED;
    }

    /*
     * Ends a request that can no longer be decided (for example because nobody is left who may approve it).
     * The request becomes REJECTED; stages that were still open become CLOSED, earlier approvals are kept.
    */
    public void forceClose() {
        if (status != RequestStatus.PENDING_APPROVAL) {
            throw new IllegalStateException(
                    "Only requests awaiting approval can be closed by an administrator (current status: " + status + ")");
        }
        for (RequestStage stage : stages) {
            stage.close(); // closes only stages that are still PENDING or WAITING
        }
        this.status = RequestStatus.REJECTED;
    }

    private RequestStage requirePendingStage() {
        if (status != RequestStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Request is not awaiting approval (current status: " + status + ")");
        }
        RequestStage current = getCurrentStage();
        if (current == null) {
            throw new IllegalStateException("Request has no stage awaiting a decision");
        }
        return current;
    }

    /** The stage currently awaiting a decision, or null when there is none. */
    public RequestStage getCurrentStage() {
        for (RequestStage stage : stages) {
            if (stage.getStatus() == StageStatus.PENDING) {
                return stage;
            }
        }
        return null;
    }

    /** Read-only view of the approval chain. */
    public List<RequestStage> getStages() { return Collections.unmodifiableList(stages); }

    public Long getId() { return id; }
    public String getReference() { return reference; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public RequestCategory getCategory() { return category; }
    public void setCategory(RequestCategory category) { this.category = category; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public RequestStatus getStatus() { return status; }

    public RequestPriority getPriority() { return priority; }

    /**
     * Changes a draft. Only allowed while the request is a draft, and every value is checked
     * before anything is changed, so a rejected edit leaves the draft untouched.
     */
    public void updateDraft(String title, String description, RequestCategory category,
                            BigDecimal amount, RequestPriority priority) {
        if (status != RequestStatus.DRAFT) {
            throw new IllegalStateException("Only draft requests can be edited (current status: " + status + ")");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Title is required");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Description is required");
        }
        if (category == null) {
            throw new IllegalArgumentException("Category is required");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }
        if (priority == null) {
            throw new IllegalArgumentException("Priority is required");
        }
        this.title = title.trim();
        this.description = description.trim();
        this.category = category;
        this.amount = amount;
        this.priority = priority;
    }

    /** Sets the priority. Only allowed while the request is still a draft (before it is submitted). */
    public void changePriority(RequestPriority priority) {
        if (priority == null) {
            throw new IllegalArgumentException("Priority is required");
        }
        if (status != RequestStatus.DRAFT) {
            throw new IllegalStateException(
                    "Priority can only be changed before the request is submitted (current status: " + status + ")");
        }
        this.priority = priority;
    }

    public User getEmployee() { return employee; }

    public String getRoutingSummary() { return routingSummary; }

    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}