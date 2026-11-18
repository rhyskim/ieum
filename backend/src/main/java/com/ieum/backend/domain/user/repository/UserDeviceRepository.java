package com.ieum.backend.domain.user.repository;

import com.ieum.backend.domain.user.entity.UserDevice;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UserDeviceRepository extends JpaRepository<UserDevice, UUID> {

    /** (user, token) 기준 원자적 upsert. 동시 등록에도 1행만 남고, 키가 user를 포함하므로 타인 행은 건드리지 않는다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "insert into user_devices (id, user_id, fcm_token, device_os, last_active_at) "
        + "values (gen_random_uuid(), :userId, :fcmToken, :deviceOs, :now) "
        + "on conflict (user_id, fcm_token) do update "
        + "set device_os = excluded.device_os, last_active_at = excluded.last_active_at",
        nativeQuery = true)
    int upsert(UUID userId, String fcmToken, String deviceOs, Instant now);

    /** 본인 행만 삭제한다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserDevice d where d.user.id = :userId and d.fcmToken = :fcmToken")
    int deleteByUserIdAndFcmToken(UUID userId, String fcmToken);
}
