package com.policyflow.policyflow.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.policyflow.policyflow.entity.AuditLog;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByEntityTypeAndEntityIdOrderByIdAsc(String entityType, Long entityId);

    List<AuditLog> findTop10ByOrderByIdDesc();

    @Query("select distinct a.action from AuditLog a order by a.action")
    List<String> findDistinctActions();

    @Query("select distinct a.entityType from AuditLog a order by a.entityType")
    List<String> findDistinctEntityTypes();

    /**
     * Filtered, paged search, newest first. An empty action or entityType means "any";
     * actorPattern is a LIKE pattern (use "%" for "anyone") that uses '!' as its escape character.
     */
    @Query("""
            select a from AuditLog a
            where (:action = '' or a.action = :action)
              and (:entityType = '' or a.entityType = :entityType)
              and lower(coalesce(a.actorEmail, '')) like :actorPattern escape '!'
              and a.createdAt >= :fromTime
              and a.createdAt < :toTime
            order by a.id desc
            """)
    Page<AuditLog> search(@Param("action") String action,
                          @Param("entityType") String entityType,
                          @Param("actorPattern") String actorPattern,
                          @Param("fromTime") LocalDateTime fromTime,
                          @Param("toTime") LocalDateTime toTime,
                          Pageable pageable);
}