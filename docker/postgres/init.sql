-- 현재 초기화된 데이터베이스 이름을 동적으로 참조하여 기본 타임존 설정 (.env의 POSTGRES_DB 변경에 안전)
DO $$
BEGIN
    EXECUTE format('ALTER DATABASE %I SET timezone TO ''Asia/Seoul''', current_database());
END
$$;

-- UUID 생성을 위한 pgcrypto 확장 활성화
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
