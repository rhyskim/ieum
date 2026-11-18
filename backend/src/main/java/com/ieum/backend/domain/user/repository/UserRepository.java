package com.ieum.backend.domain.user.repository;

import com.ieum.backend.domain.user.entity.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** 로그인 수단 연결·해제처럼 사용자 단위 check-then-act를 줄 세울 때 쓰는 행 잠금(SELECT ... FOR UPDATE). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    /**
     * 복구의 조건부 UPDATE(ADR-0002 결정 6·10). 같은 행에 대한 파기 시작(purge_started_at 기록)과 한쪽만 성공한다.
     * 복구 마감(탈퇴 후 graceDays일)은 DB 시각 기준이고, 파기가 이미 시작된 계정(purge_started_at 있음)은
     * 앱 서버 간 시계 차이가 있어도 복구되지 않는다. 0행이면 복구 불가(만료·파기 시작)이거나 이미 복구된 상태다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update users set deleted_at = null, withdraw_content_choice = null, updated_at = now() "
        + "where id = :id and deleted_at > now() - make_interval(days => :graceDays) "
        + "and purge_started_at is null", nativeQuery = true)
    int restoreIfEligible(@Param("id") UUID id, @Param("graceDays") int graceDays);
}
