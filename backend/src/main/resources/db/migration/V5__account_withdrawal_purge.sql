-- account (ADR-0002): 탈퇴 콘텐츠 선택, 파기 진행 상태, 로그인 수단 제공자당 1개, Apple 해지 재시도 큐.

-- 탈퇴 요청 시 저장하는 콘텐츠 선택(KEEP 계속 공개 / DELETE 영구 삭제). 실행은 파기 시점(PR 2).
ALTER TABLE users ADD COLUMN withdraw_content_choice VARCHAR(10);
-- 파기 시도(임대) 시각: 처리 중 표시이며 30분이 지나면 다른 실행이 이어받는다.
ALTER TABLE users ADD COLUMN purge_started_at TIMESTAMPTZ;
-- 익명화 완료 시각. 값이 있으면 PURGED(복구 불가, 행은 tombstone으로 영구 보존).
ALTER TABLE users ADD COLUMN purged_at TIMESTAMPTZ;

ALTER TABLE users ADD CONSTRAINT chk_users_withdraw_choice
    CHECK (withdraw_content_choice IS NULL OR withdraw_content_choice IN ('KEEP', 'DELETE'));
-- 탈퇴 상태가 아니면(복구됨) 선택·파기 진행 표시가 모두 비어 있어야 한다.
ALTER TABLE users ADD CONSTRAINT chk_users_withdraw_state
    CHECK (deleted_at IS NOT NULL
        OR (withdraw_content_choice IS NULL AND purge_started_at IS NULL AND purged_at IS NULL));
-- 파기 대상 조회(배치)용.
CREATE INDEX idx_users_purge_candidates ON users(deleted_at)
    WHERE deleted_at IS NOT NULL AND purged_at IS NULL;

-- 한 사용자는 제공자당 로그인 수단 1개. 연결·해제의 사용자 행 잠금이 임시 방어선이었고 이 제약이 최종 방어선이다.
ALTER TABLE user_identities ADD CONSTRAINT uq_user_identities_user_provider UNIQUE (user_id, provider);

-- Apple 토큰 해지 재시도 큐. 파기는 이 큐에 넣기만 하고 실제 호출·재시도는 별도 작업이 한다.
CREATE TABLE apple_revocations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_apple_revocations_user UNIQUE (user_id),
    CONSTRAINT chk_apple_revocations_status CHECK (status IN ('PENDING', 'DONE', 'SKIPPED', 'FAILED')),
    CONSTRAINT chk_apple_revocations_attempts CHECK (attempts >= 0)
);
CREATE INDEX idx_apple_revocations_due ON apple_revocations(next_attempt_at) WHERE status = 'PENDING';
