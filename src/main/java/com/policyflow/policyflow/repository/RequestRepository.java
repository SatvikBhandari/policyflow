package com.policyflow.policyflow.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.policyflow.policyflow.entity.Request;
import com.policyflow.policyflow.enums.RequestCategory;
import com.policyflow.policyflow.enums.RequestStatus;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.enums.StageStatus;

public interface RequestRepository extends JpaRepository<Request, Long> {

    List<Request> findByEmployeeIdOrderByCreatedAtDesc(Long employeeId);

    @EntityGraph(attributePaths = "stages")
    Optional<Request> findByIdAndEmployeeId(Long id, Long employeeId);

    long countByEmployeeIdAndStatus(Long employeeId, RequestStatus status);

    long countByStatus(RequestStatus status);

    /**
     * One employee's requests, filtered and paged, newest first. The filter flags are 0 (ignore the filter)
     * or 1 (apply it); the pattern is a LIKE pattern that uses '!' as its escape character.
     */
    @Query("""
            select r from Request r
            where r.employee.id = :employeeId
              and (:filterByStatus = 0 or r.status = :status)
              and (:filterByCategory = 0 or r.category = :category)
              and (lower(r.title) like :pattern escape '!'
                   or lower(coalesce(r.reference, '')) like :pattern escape '!')
              and r.createdAt >= :fromTime
              and r.createdAt < :toTime
            order by r.id desc
            """)
    Page<Request> searchOwn(@Param("employeeId") Long employeeId,
                            @Param("filterByStatus") int filterByStatus,
                            @Param("status") RequestStatus status,
                            @Param("filterByCategory") int filterByCategory,
                            @Param("category") RequestCategory category,
                            @Param("pattern") String pattern,
                            @Param("fromTime") LocalDateTime fromTime,
                            @Param("toTime") LocalDateTime toTime,
                            Pageable pageable);

    /** Total amount of the requests in one status, or null when there are none. */
    @Query("select sum(r.amount) from Request r where r.status = :status")
    BigDecimal sumAmountByStatus(@Param("status") RequestStatus status);

    /** Every request in one status, with requester, department and stages loaded, oldest first. */
    @Query("""
            select r from Request r
              join fetch r.employee e
              join fetch e.department
              left join fetch r.stages
            where r.status = :status
            order by r.createdAt asc
            """)
    List<Request> findAllByStatusDetailed(@Param("status") RequestStatus status);

    /** A request with everything an approver's review page needs. */
    @EntityGraph(attributePaths = {"stages", "employee", "employee.department"})
    Optional<Request> findDetailedById(Long id);

    @Query("""
            select r from Request r
              join fetch r.employee e
              join fetch e.department
            where r.status = :requestStatus
              and e.id <> :approverId
              and exists (select s.id from RequestStage s
                          where s.request = r
                            and s.status = :stageStatus
                            and s.approverRole = :role)
            order by r.createdAt asc
            """)
    List<Request> findAwaitingRole(@Param("requestStatus") RequestStatus requestStatus,
                                   @Param("stageStatus") StageStatus stageStatus,
                                   @Param("role") Role role,
                                   @Param("approverId") Long approverId);

    @Query("""
            select r from Request r
              join fetch r.employee e
              join fetch e.department
            where r.status = :requestStatus
              and e.id <> :approverId
              and e.department.id = :departmentId
              and exists (select s.id from RequestStage s
                          where s.request = r
                            and s.status = :stageStatus
                            and s.approverRole = :role)
            order by r.createdAt asc
            """)
    List<Request> findAwaitingRoleInDepartment(@Param("requestStatus") RequestStatus requestStatus,
                                               @Param("stageStatus") StageStatus stageStatus,
                                               @Param("role") Role role,
                                               @Param("approverId") Long approverId,
                                               @Param("departmentId") Long departmentId);

    /** Requests whose current stage is waiting for this role (any department). */
    default List<Request> findAwaitingApproval(Role role, Long approverId) {
        return findAwaitingRole(RequestStatus.PENDING_APPROVAL, StageStatus.PENDING, role, approverId);
    }

    /** Same, limited to requesters from one department. */
    default List<Request> findAwaitingApprovalInDepartment(Role role, Long approverId, Long departmentId) {
        return findAwaitingRoleInDepartment(
                RequestStatus.PENDING_APPROVAL, StageStatus.PENDING, role, approverId, departmentId);
    }
}