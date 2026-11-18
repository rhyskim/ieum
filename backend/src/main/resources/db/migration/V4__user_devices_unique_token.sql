-- ADR-0004: FCM 토큰은 한 번에 한 사용자에게만 속한다. UNIQUE(user_id, fcm_token)을 UNIQUE(fcm_token)으로 대체한다.
-- 등록 API 도입 전이라 중복이 없어야 하지만, 제약 추가가 실패하지 않도록 같은 토큰은 가장 최근 활성 행만 남긴다.
DELETE FROM user_devices d
USING user_devices newer
WHERE d.fcm_token = newer.fcm_token
  AND (d.last_active_at, d.id) < (newer.last_active_at, newer.id);

ALTER TABLE user_devices DROP CONSTRAINT uq_user_fcm_token;
ALTER TABLE user_devices ADD CONSTRAINT uq_fcm_token UNIQUE (fcm_token);
