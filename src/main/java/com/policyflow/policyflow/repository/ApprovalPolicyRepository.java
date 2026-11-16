package com.policyflow.policyflow.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.policyflow.policyflow.entity.ApprovalPolicy;

public interface ApprovalPolicyRepository extends JpaRepository<ApprovalPolicy, Long> {

    List<ApprovalPolicy> findByActiveTrue();

    boolean existsByName(String name);

    Optional<ApprovalPolicy> findByNameIgnoreCase(String name);

    @Query("select p from ApprovalPolicy p left join fetch p.department order by p.minAmount, p.name")
    List<ApprovalPolicy> findAllWithDepartment();

    @Query("select p from ApprovalPolicy p left join fetch p.department where p.id = :id")
    Optional<ApprovalPolicy> findWithDepartmentById(@Param("id") Long id);
}