CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    oauth_provider VARCHAR(20) NOT NULL,
    oauth_subject VARCHAR(128) NOT NULL,
    email VARCHAR(255),
    name VARCHAR(50) NOT NULL,
    phone_number VARCHAR(30),
    default_role VARCHAR(20) NOT NULL DEFAULT 'CHILD',
    font_scale_mode VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    high_contrast_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    voice_consent_agreed BOOLEAN NOT NULL DEFAULT FALSE,
    voice_consent_agreed_at TIMESTAMPTZ,
    membership_tier VARCHAR(20) NOT NULL DEFAULT 'FREE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_users_oauth UNIQUE (oauth_provider, oauth_subject)
);

CREATE TABLE family_groups (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    creator_id UUID NOT NULL REFERENCES users(id),
    plan_tier VARCHAR(30) NOT NULL DEFAULT 'FREE',
    question_assign_time TIME NOT NULL DEFAULT '19:00:00',
    current_question_seq INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE group_members (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id UUID NOT NULL REFERENCES family_groups(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    member_role VARCHAR(20) NOT NULL,
    family_nickname VARCHAR(40) NOT NULL,
    target_call_phone VARCHAR(30),
    custom_notification_time TIME NOT NULL DEFAULT '19:00:00',
    notification_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    left_at TIMESTAMPTZ,
    CONSTRAINT uq_group_member UNIQUE (group_id, user_id)
);

CREATE INDEX idx_group_members_notify_time ON group_members(custom_notification_time) WHERE status = 'ACTIVE' AND notification_enabled = TRUE;

CREATE TABLE group_invites (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id UUID NOT NULL REFERENCES family_groups(id) ON DELETE CASCADE,
    inviter_id UUID NOT NULL REFERENCES users(id),
    invite_code CHAR(6) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_invite_code_digits CHECK (invite_code ~ '^[0-9]{6}$')
);

CREATE TABLE questions (
    id BIGSERIAL PRIMARY KEY,
    sequence_order INT NOT NULL UNIQUE,
    category VARCHAR(30) NOT NULL,
    question_text TEXT NOT NULL,
    tts_guide_text TEXT,
    follow_up_hint TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE daily_questions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id UUID NOT NULL REFERENCES family_groups(id) ON DELETE CASCADE,
    question_id BIGINT NOT NULL REFERENCES questions(id),
    cycle_date DATE NOT NULL,
    reveal_status VARCHAR(30) NOT NULL DEFAULT 'LOCKED',
    assigned_at TIMESTAMPTZ NOT NULL,
    reminder_sent BOOLEAN NOT NULL DEFAULT FALSE,
    auto_reveal_at TIMESTAMPTZ NOT NULL,
    revealed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_daily_question_group_date UNIQUE (group_id, cycle_date),
    CONSTRAINT uq_daily_question_id_group UNIQUE (id, group_id)
);

CREATE INDEX idx_daily_questions_auto_reveal ON daily_questions(reveal_status, auto_reveal_at) WHERE reveal_status = 'LOCKED';

-- NOTE (TODO Premium): v1은 FREE 플랜(최대 180초, 3MB)만 활성화하므로 chk_audio_duration <= 180으로 강제합니다.
-- 향후 PREMIUM(600초) 플랜 결제 오픈 시 Flyway 후속 마이그레이션으로 CHECK 상한을 600초로 확장합니다.
CREATE TABLE answers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    daily_question_id UUID NOT NULL,
    group_id UUID NOT NULL REFERENCES family_groups(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id),
    text_content TEXT,
    stt_raw_text TEXT,
    input_method VARCHAR(30) NOT NULL,
    audio_object_key VARCHAR(512),
    audio_duration_sec INT,
    audio_size_bytes BIGINT,
    audio_codec VARCHAR(30) DEFAULT 'aac_m4a_64k',
    audio_status VARCHAR(30) NOT NULL DEFAULT 'NONE',
    audio_expires_at TIMESTAMPTZ,
    reached_max_duration BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_answers_daily_question_group FOREIGN KEY (daily_question_id, group_id)
        REFERENCES daily_questions(id, group_id) ON DELETE CASCADE,
    CONSTRAINT uq_answer_per_user UNIQUE (daily_question_id, user_id),
    CONSTRAINT chk_audio_duration CHECK (audio_duration_sec IS NULL OR (audio_duration_sec >= 1 AND audio_duration_sec <= 180))
);

CREATE INDEX idx_answers_audio_lifecycle ON answers(audio_status, audio_expires_at) WHERE audio_status IN ('ACTIVE', 'EXPIRING_NOTIFIED');

CREATE TABLE answer_reactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    answer_id UUID NOT NULL REFERENCES answers(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reaction_type VARCHAR(20) NOT NULL,
    emoji_code VARCHAR(30),
    voice_object_key VARCHAR(512),
    voice_duration_sec INT,
    voice_status VARCHAR(30) NOT NULL DEFAULT 'NONE',
    voice_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_reaction_voice_duration CHECK (voice_duration_sec IS NULL OR (voice_duration_sec >= 1 AND voice_duration_sec <= 30))
);

CREATE TABLE user_devices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    fcm_token VARCHAR(512) NOT NULL,
    device_os VARCHAR(20) NOT NULL,
    last_active_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_fcm_token UNIQUE (user_id, fcm_token)
);

CREATE TABLE shedlock (
    name VARCHAR(64) NOT NULL PRIMARY KEY,
    lock_until TIMESTAMPTZ NOT NULL,
    locked_at TIMESTAMPTZ NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);
