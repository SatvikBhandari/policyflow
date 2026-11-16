package com.policyflow.policyflow.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.Role;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    long countByRole(Role role);

    long countByRoleAndActiveTrue(Role role);

    long countByActiveTrue();

    /** Any active user with this role, other than the excluded one (the requester). */
    boolean existsByRoleAndActiveTrueAndIdNot(Role role, Long excludedId);

    /** Same, limited to one department. */
    boolean existsByRoleAndDepartmentIdAndActiveTrueAndIdNot(Role role, Long departmentId, Long excludedId);

    /** All active users with this role, other than the excluded one (the requester). */
    List<User> findByRoleAndActiveTrueAndIdNot(Role role, Long excludedId);

    /** Same, limited to one department. */
    List<User> findByRoleAndDepartmentIdAndActiveTrueAndIdNot(Role role, Long departmentId, Long excludedId);

    @Query("select u from User u join fetch u.department order by u.fullName")
    List<User> findAllWithDepartment();

    @Query("select u from User u join fetch u.department where u.id = :id")
    Optional<User> findWithDepartmentById(@Param("id") Long id);
}