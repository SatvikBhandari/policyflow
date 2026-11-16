package com.policyflow.policyflow.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.entity.AuditLog;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.repository.AuditLogRepository;

/** Writes audit-log entries. Called from inside the transaction of the action being recorded. */
@Service
public class AuditService {

    public static final String ENTITY_REQUEST = "REQUEST";
    public static final String ENTITY_USER = "USER";
    public static final String ENTITY_POLICY = "POLICY";

    public static final String REQUEST_SUBMITTED = "REQUEST_SUBMITTED";
    public static final String STAGE_APPROVED = "STAGE_APPROVED";
    public static final String REQUEST_APPROVED = "REQUEST_APPROVED";
    public static final String REQUEST_REJECTED = "REQUEST_REJECTED";
    public static final String REQUEST_CANCELLED = "REQUEST_CANCELLED";
    public static final String REQUEST_FORCE_CLOSED = "REQUEST_FORCE_CLOSED";
    public static final String REQUEST_DRAFT_DELETED = "REQUEST_DRAFT_DELETED";

    public static final String USER_CREATED = "USER_CREATED";
    public static final String USER_UPDATED = "USER_UPDATED";
    public static final String USER_ACTIVATED = "USER_ACTIVATED";
    public static final String USER_DEACTIVATED = "USER_DEACTIVATED";
    public static final String USER_PASSWORD_RESET = "USER_PASSWORD_RESET";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";

    public static final String POLICY_CREATED = "POLICY_CREATED";
    public static final String POLICY_UPDATED = "POLICY_UPDATED";
    public static final String POLICY_ACTIVATED = "POLICY_ACTIVATED";
    public static final String POLICY_DEACTIVATED = "POLICY_DEACTIVATED";

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public void record(User actor, String action, String entityType, Long entityId, String details) {
        auditLogRepository.save(new AuditLog(
                actor.getId(), actor.getEmail(), action, entityType, entityId, details));
    }
}