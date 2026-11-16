package com.policyflow.policyflow.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.policyflow.policyflow.entity.ApprovalAction;
import com.policyflow.policyflow.enums.ActionType;

public interface ApprovalActionRepository extends JpaRepository<ApprovalAction, Long> {

    List<ApprovalAction> findByRequestIdOrderByIdAsc(Long requestId);

    boolean existsByRequestIdAndActorId(Long requestId, Long actorId);

    List<ApprovalAction> findTop10ByActorIdAndActionTypeInOrderByIdDesc(Long actorId, Collection<ActionType> types);
}