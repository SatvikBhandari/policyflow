package com.policyflow.policyflow.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.dto.AdminViews.AuditEntry;
import com.policyflow.policyflow.entity.AuditLog;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.repository.AuditLogRepository;

/** Read access to the audit log for administrators. */
@Service
public class AuditLogService {

    public static final int PAGE_SIZE = 20;

    private static final int MAX_ACTOR_TEXT = 150;
    private static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);
    private static final LocalDate LATEST = LocalDate.of(2999, 1, 1);

    private final AuditLogRepository auditLogRepository;
    private final AdminGuard adminGuard;

    public AuditLogService(AuditLogRepository auditLogRepository, AdminGuard adminGuard) {
        this.auditLogRepository = auditLogRepository;
        this.adminGuard = adminGuard;
    }

    /**
     * Filtered, paged search, newest first. Empty or null filters mean "any".
     * Both dates are inclusive.
     *
     * @throws IllegalArgumentException if the start date is after the end date
     */
    @Transactional(readOnly = true)
    public Page<AuditEntry> search(User admin, String action, String entityType, String actorText,
                                   LocalDate from, LocalDate to, int page) {
        adminGuard.requireAdmin(admin);
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("The start date must not be after the end date.");
        }
        LocalDateTime fromTime = (from == null ? EARLIEST : from).atStartOfDay();
        LocalDateTime toTime = (to == null ? LATEST : to).plusDays(1).atStartOfDay();

        return auditLogRepository
                .search(clean(action), clean(entityType), actorPattern(actorText), fromTime, toTime,
                        PageRequest.of(Math.max(page, 0), PAGE_SIZE))
                .map(AuditLogService::toView);
    }

    /** The ten newest entries. */
    @Transactional(readOnly = true)
    public List<AuditEntry> recent(User admin) {
        adminGuard.requireAdmin(admin);
        return auditLogRepository.findTop10ByOrderByIdDesc().stream().map(AuditLogService::toView).toList();
    }

    /** Actions that appear in the log, as value to label, for the filter drop-down. */
    @Transactional(readOnly = true)
    public Map<String, String> actionOptions(User admin) {
        adminGuard.requireAdmin(admin);
        return options(auditLogRepository.findDistinctActions());
    }

    /** Entity types that appear in the log, as value to label, for the filter drop-down. */
    @Transactional(readOnly = true)
    public Map<String, String> entityTypeOptions(User admin) {
        adminGuard.requireAdmin(admin);
        return options(auditLogRepository.findDistinctEntityTypes());
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, String> options(List<String> values) {
        Map<String, String> options = new LinkedHashMap<>();
        for (String value : values) {
            options.put(value, humanize(value));
        }
        return options;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    /** A LIKE pattern for the actor text: "%" for anyone, otherwise a lowercase, escaped "%text%". */
    static String actorPattern(String text) {
        if (text == null || text.isBlank()) {
            return "%";
        }
        String trimmed = text.trim().toLowerCase(Locale.ROOT);
        if (trimmed.length() > MAX_ACTOR_TEXT) {
            trimmed = trimmed.substring(0, MAX_ACTOR_TEXT);
        }
        String escaped = trimmed.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }

    /** REQUEST_FORCE_CLOSED becomes "Request force closed". */
    static String humanize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    static AuditEntry toView(AuditLog log) {
        String link = null;
        if (log.getEntityId() != null) {
            if (AuditService.ENTITY_USER.equals(log.getEntityType())) {
                link = "/admin/users/" + log.getEntityId() + "/edit";
            } else if (AuditService.ENTITY_POLICY.equals(log.getEntityType())) {
                link = "/admin/policies/" + log.getEntityId() + "/edit";
            }
        }
        return new AuditEntry(log.getId(), log.getCreatedAt(), humanize(log.getAction()),
                log.getActorEmail(), humanize(log.getEntityType()), log.getEntityId(),
                log.getDetails(), link);
    }
}