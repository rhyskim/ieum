-- group-core (ADR-0003): 방장 표시, 입장 대기(PENDING)·강퇴(REMOVED), 활성 코드 유니크, 호칭, 입장 시도 상태, 보안 이벤트, 문의.

-- 방장은 member_role(PARENT/CHILD, 세대 의미)과 별개 개념이므로 is_owner 컬럼으로 둔다.
ALTER TABLE group_members ADD COLUMN is_owner BOOLEAN NOT NULL DEFAULT FALSE;
-- PENDING 요청 시각(승인 대기 2일 만료 기준). 기존 행은 NULL.
ALTER TABLE group_members ADD COLUMN requested_at TIMESTAMPTZ;
ALTER TABLE group_members ADD CONSTRAINT chk_group_member_status
    CHECK (status IN ('PENDING', 'ACTIVE', 'LEFT', 'REMOVED'));
-- 그룹당 ACTIVE 방장은 1명.
CREATE UNIQUE INDEX uq_group_active_owner ON group_members(group_id) WHERE is_owner AND status = 'ACTIVE';
CREATE INDEX idx_group_members_user_status ON group_members(user_id, status);

-- 기존 invite_code 전체 기간 UNIQUE를 걷어내고, 활성 코드끼리만 유니크 + 그룹당 활성 1개.
ALTER TABLE group_invites DROP CONSTRAINT group_invites_invite_code_key;
CREATE UNIQUE INDEX uq_group_invites_active_code ON group_invites(invite_code) WHERE is_active;
CREATE UNIQUE INDEX uq_group_invites_active_per_group ON group_invites(group_id) WHERE is_active;

-- 호칭: 보는 사람(viewer)마다 대상(target)을 다르게 부른다.
CREATE TABLE member_aliases (
    group_id UUID NOT NULL REFERENCES family_groups(id) ON DELETE CASCADE,
    viewer_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    alias VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (group_id, viewer_id, target_id)
);

-- 입장 시도 제한 상태(사용자당 1행). 동작 로직은 별도 승인 후 구현한다.
CREATE TABLE join_attempt_state (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    window_started_at TIMESTAMPTZ,
    fail_count INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    lock_count INT NOT NULL DEFAULT 0,
    lock_count_started_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_join_fail_count CHECK (fail_count >= 0),
    CONSTRAINT chk_join_lock_count CHECK (lock_count >= 0)
);

-- 이상 사용자 기록(자동 차단 없음).
CREATE TABLE security_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event_type VARCHAR(50) NOT NULL,
    detail TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_security_events_user ON security_events(user_id, created_at);

-- 관리자 문의(전달 채널 없음, DB 저장까지).
CREATE TABLE support_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id UUID NOT NULL REFERENCES family_groups(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    message VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
