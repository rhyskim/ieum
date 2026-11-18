package com.ieum.backend.domain.user.repository;

import com.ieum.backend.domain.user.entity.UserDevice;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserDeviceRepository extends JpaRepository<UserDevice, UUID> {

    /**
     * 토큰 기준 원자적 upsert(ADR-0004). 같은 토큰이 다른 사용자 행으로 있으면 소유를 이전하므로
     * 한 토큰은 항상 한 사용자에게만 속하고, 동시 등록에도 1행만 남는다(UNIQUE(fcm_token)).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "insert into user_devices (id, user_id, fcm_token, device_os, last_active_at) "
        + "values (gen_random_uuid(), :userId, :fcmToken, :deviceOs, :now) "
        + "on conflict (fcm_token) do update "
        + "set user_id = excluded.user_id, device_os = excluded.device_os, "
        + "last_active_at = excluded.last_active_at",
        nativeQuery = true)
    int upsert(UUID userId, String fcmToken, String deviceOs, Instant now);

    /** 로그아웃: 본인 소유일 때만 삭제한다(소유가 이전된 토큰은 건드리지 않는다). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserDevice d where d.user.id = :userId and d.fcmToken = :fcmToken")
    int deleteByUserIdAndFcmToken(UUID userId, String fcmToken);

    /** FCM이 UNREGISTERED로 알려 준 죽은 토큰 삭제. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserDevice d where d.fcmToken = :fcmToken")
    int deleteByFcmToken(String fcmToken);

    @Query("select d.fcmToken from UserDevice d where d.user.id = :userId")
    List<String> findTokensByUserId(UUID userId);

    /** 탈퇴 요청: 푸시 대상에서 즉시 빠지도록 기기 토큰(FCM)을 모두 삭제한다. 없어도 성공(멱등). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserDevice d where d.user.id = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}
