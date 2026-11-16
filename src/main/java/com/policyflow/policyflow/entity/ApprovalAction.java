package com.policyflow.policyflow.entity;

import java.time.LocalDateTime;

import com.policyflow.policyflow.enums.ActionType;
import com.policyflow.policyflow.enums.Role;

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

/** One recorded decision (or cancellation) on a request. Never modified after it is written. */
@Entity
@Table(name = "approval_actions")
public class ApprovalAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private Request request;

    /** The stage that was decided. Null for a cancellation. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stage_id")
    private RequestStage stage;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_id", nullable = false)
    private User actor;

    /** The actor's role at the time of the action. */
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", nullable = false, length = 30)
    private Role actorRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 20)
    private ActionType actionType;

    @Column(name = "step_number")
    private Integer stepNumber;

    @Column(name = "comment_text", length = 1000)
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected ApprovalAction() {
        // required by JPA
    }

    public ApprovalAction(Request request, RequestStage stage, User actor,
                          ActionType actionType, String comment) {
        this.request = request;
        this.stage = stage;
        this.actor = actor;
        this.actorRole = actor.getRole();
        this.actionType = actionType;
        this.stepNumber = stage == null ? null : stage.getStepNumber();
        this.comment = comment;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Request getRequest() { return request; }
    public RequestStage getStage() { return stage; }
    public User getActor() { return actor; }
    public Role getActorRole() { return actorRole; }
    public ActionType getActionType() { return actionType; }
    public Integer getStepNumber() { return stepNumber; }
    public String getComment() { return comment; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}