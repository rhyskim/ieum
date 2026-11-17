# [Task 2 구현 계획] Backend (`backend/`) 초기 구축 및 Feature Entitlement 정책 API

## 1. Goal Description & Task Scope
Phase 0의 두 번째 단계(Task 2: `PLAN.md`의 `0-2. Backend 초기 구축` 및 `0-4. 무료/유료 전환 대비 Feature Entitlement 기반 모듈 작성` 백엔드 파트)로, **Java 21 (LTS) + Spring Boot 3.3.5 + Gradle 8.10.2 (Kotlin DSL)** 기반의 백엔드 API 서버 골격을 구축합니다.

### 1.1 Phase 0 (Task 2) 범위와 후속 Phase(1~3)의 경계 명확화
- **본 Task 2 (Phase 0) 구현 범위**:
  1. Gradle 8.10.2(Kotlin DSL) 프로젝트 셋업(`foojay-resolver-convention` 포함, 로컬 `Temurin 21.0.12.1` 설치 확인 완료) 및 필수 의존성(`Spring Web`, `Spring Data JPA`, `Validation`, `Spring Security`, `Spring AOP`, `PostgreSQL`, `Redis`, `Flyway`, `QueryDSL`, `AWS SDK v2 S3`, `Firebase Admin SDK`, `ShedLock`, `SpringDoc OpenAPI`, `Testcontainers`) 구성. *(참고: 모바일이 소셜 토큰을 전달해 서버가 검증하는 구조이므로 웹 리다이렉트용 `OAuth2 Client`는 `PLAN.md` 0-2와 동일하게 제외하고 Phase 1에서 `RestClient`로 구현)*
  2. `docs/DATA_MODEL.md`의 DDL과 100% 일치하는 **Flyway 초기 마이그레이션(`V1__init_schema.sql`)** 적용 및 **9개 도메인 엔티티 전체(`User`, `UserDevice`, `FamilyGroup`, `GroupMember`, `GroupInvite`, `Question`, `DailyQuestion`, `Answer`, `AnswerReaction`)**에 대한 Hibernate `ddl-auto: validate` 통과 검증 (`shedlock` 테이블은 `JdbcTemplateLockProvider` 전용 인프라 테이블로 사용).
  3. 글로벌 공통 인프라:
     - `IeumBackendApplication`: `@ConfigurationPropertiesScan("com.ieum.backend")` 및 `UTC` 기본 타임존 고정
     - `ApiResponse<T>`, `ErrorCode`, `BusinessException`, `GlobalExceptionHandler` (400/403/404/405/406/409/415/500 전 예외 명시적 매핑)
     - `TimeZoneConstants` (KST 19:00 배정 시각 / 익일 17:00 자동 공개 시각 계산기 포함)
     - `BaseCreatedEntity` (`created_at`) 및 `BaseTimeEntity` (`created_at` + `updated_at`) 분리
     - `SecurityConfig` (`csrf.disable()`, `STATELESS`, `permitAll` 경로 명시, `CustomAuthenticationEntryPoint`, `CustomAccessDeniedHandler`를 통해 필터 체인 401/403 에러도 `ApiResponse<Void>` 포맷으로 반환)
     - `S3Config` (`S3Client` 내부 엔드포인트 + `S3Presigner` 퍼블릭 엔드포인트 분리, `pathStyleAccessEnabled(true)`, `useStaticCredentials` 플래그 기반 자격증명 분기)
     - `SchedulerLockConfig` (`@EnableScheduling` + `@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")` + `JdbcTemplateLockProvider` `usingDbTime()`)
     - `FirebaseConfig` (`@ConditionalOnProperty` 기반 선택적 초기화로 인증키 파일 없이도 로컬/테스트 기동 보장)
     - `CurrentUser` Principal 레코드 + `@GroupMemberOnly` 어노테이션 + `GroupMemberAccessAspect`(AOP) + `GroupAccessValidator` + `GroupMemberRepository`
  4. `CLAUDE.md` §1-3을 준수하는 **`FeatureGate` 인터페이스 및 `EntitlementService` 구현체** (`EntitlementPolicyProperties` 기반).
  5. Phase 0 DoD 엔드포인트: **`GET /api/v1/health` (`HealthCheckResponse`)** 및 **`GET /api/v1/policies` (`FeatureEntitlementResponse`)**.
  6. Testcontainers(`postgres:16.6-alpine`) 기반 재현 가능한 통합 테스트(`Phase0ApiIntegrationTest`) 및 `CLAUDE.md` §5의 핵심 도메인 불변식 단위 테스트(`CoreDomainInvariantsTest`).

---

## 2. Key Architectural Decisions

> [!IMPORTANT]
> **1. Hibernate 6 `CHAR(6)` (`bpchar`) 스키마 검증 호환성 (C1)**
> - PostgreSQL의 `CHAR(6)` 컬럼은 내부적으로 `bpchar`(`Types#CHAR`) 타입으로 생성되며, Hibernate 6에서 기본 `String` 필드는 `varchar`를 기대하므로 별도 어노테이션이 없으면 `ddl-auto: validate`가 실패합니다.
> - 전체 10개 테이블 중 고정 길이 `CHAR`를 사용하는 유일한 컬럼인 `group_invites.invite_code`(`CHAR(6)`)는 엔티티(`GroupInvite.inviteCode`)에 **`@JdbcTypeCode(SqlTypes.CHAR) @Column(name = "invite_code", length = 6, nullable = false, unique = true, columnDefinition = "char(6)")`**를 명시하여 Hibernate 6 `validate`를 완벽히 통과시킵니다.

> [!IMPORTANT]
> **2. AWS SDK v2 S3 (`2.28.29`) LocalStack 연동, Path-Style 강제 및 Presigned PUT 서명 규칙**
> - `S3Client`(서버 내부 `HeadObject`/`DeleteObject` 통신용, `aws.s3.endpoint` 사용)와 `S3Presigner`(클라이언트 업로드/다운로드 서명용, `aws.s3.public-endpoint` 사용) 모두 **`S3Configuration.builder().pathStyleAccessEnabled(s3Properties.pathStyleAccessEnabled()).build()`**를 적용합니다.
> - 자격 증명은 `s3Properties.useStaticCredentials()`가 `true`일 때(`local`, `test`) `StaticCredentialsProvider`(`accessKey`, `secretKey`)를 사용하고, `false`일 때(`prod`) `DefaultCredentialsProvider`를 사용합니다.
> - **S3 Presigned PUT 크기 제한**: S3 Presigned PUT은 POST Policy의 `content-length-range`를 지원하지 않으므로, `FeatureGate.maxAudioFileSizeBytes(userTier, groupTier)`(v1 FREE 기준 `3,145,728 Bytes = 3MB`) 이하인지 사전 검증한 뒤 `PutObjectRequest.builder().contentLength(fileSizeBytes).contentType("audio/mp4")`로 서명에 고정하며, 업로드 완료 후 `HeadObject`로 2차 검증합니다.

> [!IMPORTANT]
> **3. 타임존 이원화 (`UTC` 저장 + `Asia/Seoul` 비즈니스 사이클) 및 `ShedLock` DB 시간 기준 락**
> - `IeumBackendApplication.main()` 및 `@PostConstruct`에서 `TimeZone.setDefault(TimeZone.getTimeZone("UTC"))`를 호출하고, `spring.jpa.properties.hibernate.jdbc.time_zone=UTC`로 고정합니다.
> - 모든 `TIMESTAMPTZ` 컬럼은 `Instant`로, `TIME` 컬럼(`question_assign_time`, `custom_notification_time`)은 `LocalTime`(KST 기준 시각)으로 매핑합니다.
> - `SchedulerLockConfig`에 `@EnableScheduling`과 `@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")`을 선언하고, 서버 간 클럭 스큐(Clock Skew) 방지를 위해 `JdbcTemplateLockProvider.Configuration.builder().withJdbcTemplate(jdbcTemplate).usingDbTime().build()`를 구성합니다.

> [!IMPORTANT]
> **4. 엔티티 시간 Auditing 계층 분리 (`BaseCreatedEntity` vs `BaseTimeEntity`, M2)**
> - 테이블마다 보유한 시간 컬럼이 다르므로 단일 `BaseTimeEntity` 상속으로 인한 `ddl-auto: validate` 실패를 방지하기 위해 아래와 같이 정확히 분리 매핑합니다 (`JpaConfig`에 `@EnableJpaAuditing` 선언):
>
> | 구분 | 상위 클래스 / 매핑 방식 | 보유 컬럼 | 대상 엔티티 (9개) |
> | :--- | :--- | :--- | :--- |
> | 생성+수정 시각 | `BaseTimeEntity extends BaseCreatedEntity` | `created_at`, `updated_at` | `User`, `FamilyGroup`, `Answer` |
> | 생성 시각만 보유 | `BaseCreatedEntity` | `created_at` | `GroupInvite`, `Question`, `DailyQuestion`, `AnswerReaction` |
> | 도메인 전용 시각 | 엔티티 내부 직접 매핑 (상속 없음) | `joined_at`, `left_at` | `GroupMember` |
> | 도메인 전용 시각 | 엔티티 내부 직접 매핑 (상속 없음) | `last_active_at` | `UserDevice` |

---

## 3. Proposed Changes

### 3.1 디렉토리 및 패키지 구조 (`backend/`)

```text
backend/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat / gradle/wrapper/
└── src/
    ├── main/
    │   ├── java/com/ieum/backend/
    │   │   ├── IeumBackendApplication.java
    │   │   ├── global/
    │   │   │   ├── common/
    │   │   │   │   ├── ApiResponse.java
    │   │   │   │   ├── BaseCreatedEntity.java
    │   │   │   │   ├── BaseTimeEntity.java
    │   │   │   │   └── TimeZoneConstants.java
    │   │   │   ├── config/
    │   │   │   │   ├── FirebaseConfig.java
    │   │   │   │   ├── JpaConfig.java
    │   │   │   │   ├── QueryDslConfig.java
    │   │   │   │   ├── S3Config.java
    │   │   │   │   ├── S3Properties.java
    │   │   │   │   ├── SchedulerLockConfig.java
    │   │   │   │   ├── SecurityConfig.java
    │   │   │   │   └── SwaggerConfig.java
    │   │   │   ├── security/
    │   │   │   │   ├── CurrentUser.java
    │   │   │   │   ├── CustomAccessDeniedHandler.java
    │   │   │   │   ├── CustomAuthenticationEntryPoint.java
    │   │   │   │   ├── GroupAccessValidator.java
    │   │   │   │   ├── GroupMemberAccessAspect.java
    │   │   │   │   └── GroupMemberOnly.java
    │   │   │   ├── exception/
    │   │   │   │   ├── BusinessException.java
    │   │   │   │   ├── ErrorCode.java
    │   │   │   │   └── GlobalExceptionHandler.java
    │   │   │   └── health/
    │   │   │       ├── HealthCheckController.java
    │   │   │       └── HealthCheckResponse.java
    │   │   └── domain/
    │   │       ├── common/enums/
    │   │       │   ├── AudioStatus.java
    │   │       │   ├── DeviceOs.java
    │   │       │   ├── EmojiCode.java
    │   │       │   ├── FontScaleMode.java
    │   │       │   ├── GroupPlanTier.java
    │   │       │   ├── GroupStatus.java
    │   │       │   ├── InputMethod.java
    │   │       │   ├── MemberRole.java
    │   │       │   ├── MemberStatus.java
    │   │       │   ├── MembershipTier.java
    │   │       │   ├── OAuthProvider.java
    │   │       │   ├── QuestionCategory.java
    │   │       │   ├── ReactionType.java
    │   │       │   ├── ReactionVoiceStatus.java
    │   │       │   └── RevealStatus.java
    │   │       ├── user/entity/
    │   │       │   ├── User.java
    │   │       │   └── UserDevice.java
    │   │       ├── group/
    │   │       │   ├── entity/
    │   │       │   │   ├── FamilyGroup.java
    │   │       │   │   ├── GroupMember.java
    │   │       │   │   └── GroupInvite.java
    │   │       │   └── repository/
    │   │       │       └── GroupMemberRepository.java
    │   │       ├── question/entity/
    │   │       │   ├── Question.java
    │   │       │   └── DailyQuestion.java
    │   │       ├── answer/entity/
    │   │       │   ├── Answer.java
    │   │       │   └── AnswerReaction.java
    │   │       └── entitlement/
    │   │           ├── config/EntitlementPolicyProperties.java
    │   │           ├── controller/EntitlementPolicyController.java
    │   │           ├── dto/FeatureEntitlementResponse.java
    │   │           └── service/
    │   │               ├── FeatureGate.java
    │   │               └── EntitlementService.java
    │   └── resources/
    │       ├── application.yml
    │       ├── application-local.yml
    │       └── db/migration/
    │           └── V1__init_schema.sql
    └── test/
        ├── java/com/ieum/backend/
        │   ├── api/Phase0ApiIntegrationTest.java
        │   └── domain/CoreDomainInvariantsTest.java
        └── resources/
            └── application-test.yml
```
*(참고: `answer_reactions.voice_status`는 `EXPIRING_NOTIFIED`가 없는 `{NONE, ACTIVE, EXPIRED_DELETED, PERMANENT}` 집합이므로 `Answer.audioStatus`의 `AudioStatus`와 구분하여 `ReactionVoiceStatus` 전용 Enum으로 매핑합니다.)*

---

### 3.2 Gradle 빌드 및 설정 (`settings.gradle.kts`, `gradle.properties`, `build.gradle.kts`)

#### [NEW] [backend/settings.gradle.kts](file:///c:/Users/user/Desktop/Project_Idea/for-family/backend/settings.gradle.kts)
```kotlin
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "ieum-backend"
```

#### [NEW] [backend/gradle.properties](file:///c:/Users/user/Desktop/Project_Idea/for-family/backend/gradle.properties)
```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
```

#### [NEW] [backend/build.gradle.kts](file:///c:/Users/user/Desktop/Project_Idea/for-family/backend/build.gradle.kts)
```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.ieum"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

val querydslVersion = "5.1.0"
val awsSdkVersion = "2.28.29"
val shedlockVersion = "5.16.0"
val firebaseAdminVersion = "9.4.1"
val springdocVersion = "2.6.0"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    implementation("com.querydsl:querydsl-jpa:${querydslVersion}:jakarta")
    annotationProcessor("com.querydsl:querydsl-apt:${querydslVersion}:jakarta")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")

    implementation(platform("software.amazon.awssdk:bom:${awsSdkVersion}"))
    implementation("software.amazon.awssdk:s3")

    implementation("net.javacrumbs.shedlock:shedlock-spring:${shedlockVersion}")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:${shedlockVersion}")

    implementation("com.google.firebase:firebase-admin:${firebaseAdminVersion}")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${springdocVersion}")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

---

### 3.3 Flyway V1 초기 스키마 (`V1__init_schema.sql`)

`docs/DATA_MODEL.md` §2의 DDL과 100% 일치합니다.

#### [NEW] [backend/src/main/resources/db/migration/V1__init_schema.sql](file:///c:/Users/user/Desktop/Project_Idea/for-family/backend/src/main/resources/db/migration/V1__init_schema.sql)
```sql
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
```

---

### 3.4 애플리케이션 설정 파일 (`application.yml`, `application-local.yml`, `application-test.yml`)

#### [NEW] [backend/src/main/resources/application.yml](file:///c:/Users/user/Desktop/Project_Idea/for-family/backend/src/main/resources/application.yml)
```yaml
spring:
  application:
    name: ieum-backend
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:local}
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        jdbc:
          time_zone: UTC
        format_sql: true
  flyway:
    enabled: true
    locations: classpath:db/migration
  jackson:
    time-zone: UTC
    serialization:
      write-dates-as-timestamps: false

management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      show-details: never

springdoc:
  api-docs:
    enabled: ${SWAGGER_ENABLED:true}
  swagger-ui:
    enabled: ${SWAGGER_ENABLED:true}
    path: /swagger-ui.html

firebase:
  enabled: ${FIREBASE_ENABLED:false}
  credentials-path: ${FIREBASE_CREDENTIALS_PATH:}

aws:
  s3:
    endpoint: ${S3_ENDPOINT:http://localhost:4566}
    public-endpoint: ${S3_PUBLIC_ENDPOINT:http://localhost:4566}
    region: ${S3_REGION:ap-northeast-2}
    access-key: ${S3_ACCESS_KEY:test}
    secret-key: ${S3_SECRET_KEY:test}
    bucket-name: ${S3_BUCKET_NAME:ieum-voice-storage-local}
    ephemeral-prefix: ${S3_EPHEMERAL_PREFIX:audio/ephemeral-30d/}
    permanent-prefix: ${S3_PERMANENT_PREFIX:audio/permanent/}
    path-style-access-enabled: ${S3_PATH_STYLE_ENABLED:true}
    use-static-credentials: ${S3_USE_STATIC_CREDENTIALS:true}

# 서버 주도형 Feature Entitlement 정책 설정 (v1은 FREE 플랜 활성, 향후 PREMIUM 확장 대비)
# voiceServerRetentionDays = 36500 은 약 100년(사실상 서버 영구 보관)을 의미하는 상수값입니다.
ieum:
  entitlement:
    default-plan: FREE
    plans:
      FREE:
        can-attach-original-voice: true
        max-voice-duration-seconds: 180
        max-audio-size-bytes: 3145728
        voice-server-retention-days: 30
        can-download-voice-to-local: true
        stt-engine-type: ON_DEVICE
      PREMIUM:
        can-attach-original-voice: true
        max-voice-duration-seconds: 600
        max-audio-size-bytes: 10485760
        voice-server-retention-days: 36500
        can-download-voice-to-local: true
        stt-engine-type: CLOUD_WHISPER
```

#### [NEW] [backend/src/main/resources/application-local.yml](file:///c:/Users/user/Desktop/Project_Idea/for-family/backend/src/main/resources/application-local.yml)
```yaml
spring:
  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST:localhost}:${POSTGRES_PORT:15432}/${POSTGRES_DB:ieum_local}
    username: ${POSTGRES_USER:ieum_user}
    password: ${POSTGRES_PASSWORD:ieum_pass_local}
    driver-class-name: org.postgresql.Driver
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:16379}
```

#### [NEW] [backend/src/test/resources/application-test.yml](file:///c:/Users/user/Desktop/Project_Idea/for-family/backend/src/test/resources/application-test.yml)
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true

management:
  health:
    redis:
      enabled: false

aws:
  s3:
    endpoint: http://localhost:4566
    public-endpoint: http://localhost:4566
    region: ap-northeast-2
    access-key: test
    secret-key: test
    bucket-name: ieum-voice-storage-test
    ephemeral-prefix: audio/ephemeral-30d/
    permanent-prefix: audio/permanent/
    path-style-access-enabled: true
    use-static-credentials: true
```

---

### 3.5 도메인 상태 전이 가드, KST 계산기, `SecurityConfig`, `CurrentUser` & AOP, `FeatureGate`, `GlobalExceptionHandler` 상세 설계

#### 1) `TimeZoneConstants` (`com.ieum.backend.global.common.TimeZoneConstants`)
```java
public final class TimeZoneConstants {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    public static final LocalTime DAILY_QUESTION_ASSIGN_TIME_KST = LocalTime.of(19, 0);
    public static final LocalTime PRE_REVEAL_REMINDER_TIME_KST = LocalTime.of(15, 0);
    public static final LocalTime AUTO_REVEAL_TIME_KST = LocalTime.of(17, 0);

    private TimeZoneConstants() {}

    public static Instant assignedAt(LocalDate cycleDate) {
        return ZonedDateTime.of(cycleDate, DAILY_QUESTION_ASSIGN_TIME_KST, KST).toInstant();
    }

    public static Instant autoRevealAt(LocalDate cycleDate) {
        return ZonedDateTime.of(cycleDate.plusDays(1), AUTO_REVEAL_TIME_KST, KST).toInstant();
    }
}
```

#### 2) `DailyQuestion` 및 `Answer` 도메인 상태 전이 가드 (`@Setter` 금지)
- **`DailyQuestion.create(FamilyGroup group, Question question, LocalDate cycleDate)`**:
  - `revealStatus = RevealStatus.LOCKED`, `assignedAt = TimeZoneConstants.assignedAt(cycleDate)`, `autoRevealAt = TimeZoneConstants.autoRevealAt(cycleDate)`로 초기화합니다.
- **`DailyQuestion.checkAndRevealIfAllAnswered(int activeMemberCount, int answeredCount, Instant now)`**:
  - `activeMemberCount <= 0` 또는 `answeredCount < activeMemberCount`이면 변경 없음(`false` 반환).
  - `revealStatus == RevealStatus.LOCKED`이면 `revealStatus = RevealStatus.REVEALED_ALL`, `revealedAt = now`로 전환(`true` 반환).
  - `revealStatus == RevealStatus.REVEALED_TIMEOUT`이면(익일 17:00 타임아웃 공개 이후 지각 멤버가 마저 답변하여 전원 답변 달성 시) `revealStatus = RevealStatus.REVEALED_ALL`로 승격하되 **최초 공개 시각인 `revealedAt`은 덮어쓰지 않고 그대로 유지**(`true` 반환).
  - 이미 `REVEALED_ALL`이면 no-op(`false` 반환).
- **`DailyQuestion.revealOnTimeoutIfEligible(int answeredCount, Instant now)`**:
  - `revealStatus == RevealStatus.LOCKED && !now.isBefore(autoRevealAt) && answeredCount >= 1`일 때만 `revealStatus = RevealStatus.REVEALED_TIMEOUT`, `revealedAt = now`로 전환(`true` 반환). 그 외에는 변경 없음(`false` 반환).
- **`Answer.markAudioExpiringNotified()`**:
  - `audioStatus == AudioStatus.ACTIVE`일 때만 `AudioStatus.EXPIRING_NOTIFIED`로 전환하며, 그 외 상태(`NONE`, `PERMANENT`, `EXPIRED_DELETED`, `EXPIRING_NOTIFIED`)에서 호출 시 `BusinessException(ErrorCode.INVALID_AUDIO_STATE_TRANSITION)` 발생.
- **`Answer.markAudioExpired()`**:
  - `audioStatus == AudioStatus.ACTIVE || audioStatus == AudioStatus.EXPIRING_NOTIFIED`일 때만 `AudioStatus.EXPIRED_DELETED`로 전환하며 **`textContent`와 `sttRawText`는 절대 수정/삭제하지 않고 보존**합니다 (`CLAUDE.md` §6-5 준수).
  - `PERMANENT` 또는 `NONE` 상태에서 호출 시 `BusinessException(ErrorCode.INVALID_AUDIO_STATE_TRANSITION)`을 던져 영구 보관 음성이 실수로 만료 처리되는 것을 원천 차단합니다.

#### 3) `SecurityConfig`, `CurrentUser` Principal 및 `GroupMemberAccessAspect` 계약 확정
```java
// com.ieum.backend.global.security.CurrentUser
public record CurrentUser(
    UUID userId,
    MembershipTier membershipTier
) {}
```
- **`SecurityConfig` (`@Configuration @EnableWebSecurity`)**:
  - `csrf(AbstractHttpConfigurer::disable)`, `formLogin(AbstractHttpConfigurer::disable)`, `httpBasic(AbstractHttpConfigurer::disable)`
  - `sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))`
  - `exceptionHandling(e -> e.authenticationEntryPoint(customAuthenticationEntryPoint).accessDeniedHandler(customAccessDeniedHandler))`
  - `authorizeHttpRequests`:
    - `requestMatchers("/api/v1/health", "/api/v1/policies", "/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()`
    - `anyRequest().authenticated()`
- **`GroupMemberRepository` (`com.ieum.backend.domain.group.repository.GroupMemberRepository`)**:
  - `GroupMember` 엔티티의 `@ManyToOne FamilyGroup group` 및 `@ManyToOne User user` 연관 경로를 명확히 해석하도록 `boolean existsByGroup_IdAndUser_IdAndStatus(UUID groupId, UUID userId, MemberStatus status);`로 정의합니다.
- **`GroupMemberAccessAspect` (`@Aspect @Component`)**:
  - `@Before("@annotation(com.ieum.backend.global.security.GroupMemberOnly)")`를 가로챕니다.
  - `SecurityContextHolder.getContext().getAuthentication()`이 `null`이거나 `principal`이 `CurrentUser`가 아니면 `throw new BusinessException(ErrorCode.UNAUTHORIZED)`를 발생시킵니다.
  - `MethodSignature`의 파라미터들 중 `@PathVariable("groupId")` 어노테이션이 붙어 있거나 파라미터명이 `"groupId"`인 `UUID` 인자를 추출합니다(추출 실패 시 `IllegalStateException` 발생).
  - `groupAccessValidator.validateActiveMember(groupId, currentUser.userId())`를 호출하며, `groupMemberRepository.existsByGroup_IdAndUser_IdAndStatus(groupId, userId, MemberStatus.ACTIVE)`가 `false`이면 `throw new BusinessException(ErrorCode.GROUP_ACCESS_DENIED)`를 발생시킵니다.

#### 4) `FeatureGate` 인터페이스 및 `EntitlementService` 시그니처와 플랜 매핑 규칙 확정
```java
public interface FeatureGate {
    String resolveEffectivePlan(MembershipTier userTier, GroupPlanTier groupTier);
    FeatureEntitlementResponse getEntitlementPolicy(MembershipTier userTier, GroupPlanTier groupTier);
    void assertCanAttachOriginalVoice(MembershipTier userTier, GroupPlanTier groupTier);
    int maxVoiceDurationSeconds(MembershipTier userTier, GroupPlanTier groupTier);
    long maxAudioFileSizeBytes(MembershipTier userTier, GroupPlanTier groupTier);
    void validateVoiceUpload(int durationSec, long fileSizeBytes, MembershipTier userTier, GroupPlanTier groupTier);
}
```
- **플랜 매핑 규칙 (`resolveEffectivePlan`)**: `userTier == MembershipTier.PREMIUM || groupTier == GroupPlanTier.PREMIUM_FAMILY`이면 `"PREMIUM"` 키(`ieum.entitlement.plans.PREMIUM`)로 매핑하고, 그 외에는 `ieum.entitlement.default-plan`(`"FREE"`, `ieum.entitlement.plans.FREE`)으로 매핑합니다.
- `assertCanAttachOriginalVoice(...)`: 해당 플랜의 `canAttachOriginalVoice`가 `false`이면 `BusinessException(ErrorCode.FEATURE_NOT_ENTITLED)`(`403 Forbidden`)을 던집니다.
- `validateVoiceUpload(...)`: `durationSec <= 0 || durationSec > maxVoiceDurationSeconds(...)`이면 `ErrorCode.VOICE_DURATION_EXCEEDED`(`400`), `fileSizeBytes <= 0 || fileSizeBytes > maxAudioFileSizeBytes(...)`이면 `ErrorCode.VOICE_FILE_SIZE_EXCEEDED`(`400`)를 던집니다.
- `EntitlementPolicyController` (`GET /api/v1/policies`): `@AuthenticationPrincipal CurrentUser currentUser`를 주입받아 `currentUser != null ? currentUser.membershipTier() : MembershipTier.FREE`와 기본 `GroupPlanTier.FREE`를 `featureGate.getEntitlementPolicy(...)`에 전달합니다.

#### 5) `ApiResponse<T>` 및 `HealthCheckResponse` (Task 3 클라이언트와 1:1 계약 고정)
```java
// com.ieum.backend.global.common.ApiResponse
public record ApiResponse<T>(
    String code,
    String message,
    T data,
    Instant timestamp
) {
    public static final String SUCCESS_CODE = "SUCCESS";
    public static final String DEFAULT_SUCCESS_MESSAGE = "요청이 성공적으로 처리되었습니다.";

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(SUCCESS_CODE, DEFAULT_SUCCESS_MESSAGE, data, Instant.now());
    }

    public static ApiResponse<Void> error(ErrorCode errorCode, String customMessage) {
        return new ApiResponse<>(errorCode.getCode(), customMessage, null, Instant.now());
    }
}

// com.ieum.backend.global.health.HealthCheckResponse (GET /api/v1/health)
public record HealthCheckResponse(
    String status,       // "UP"
    String application,  // "ieum-backend"
    String profile,      // "local"
    String timezone,     // "Asia/Seoul"
    Instant serverTime   // ISO-8601 UTC
) {}
```

#### 6) `ErrorCode` 및 `GlobalExceptionHandler` (4xx 클라이언트 에러의 500 둔갑 방지)
- **`ErrorCode` 전체 목록**:
  - `INVALID_INPUT (HttpStatus.BAD_REQUEST, "요청 입력값이 올바르지 않습니다.")`
  - `VOICE_DURATION_EXCEEDED (HttpStatus.BAD_REQUEST, "녹음 제한 시간을 초과했습니다.")`
  - `VOICE_FILE_SIZE_EXCEEDED (HttpStatus.BAD_REQUEST, "음성 파일 최대 용량을 초과했습니다.")`
  - `UNAUTHORIZED (HttpStatus.UNAUTHORIZED, "인증이 필요합니다.")`
  - `ACCESS_DENIED (HttpStatus.FORBIDDEN, "접근 권한이 없습니다.")`
  - `GROUP_ACCESS_DENIED (HttpStatus.FORBIDDEN, "해당 가족 그룹의 멤버만 접근할 수 있습니다.")`
  - `FEATURE_NOT_ENTITLED (HttpStatus.FORBIDDEN, "현재 요금제에서 사용할 수 없는 기능입니다.")`
  - `RESOURCE_NOT_FOUND (HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다.")`
  - `METHOD_NOT_ALLOWED (HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다.")`
  - `NOT_ACCEPTABLE (HttpStatus.NOT_ACCEPTABLE, "지원하지 않는 Accept 미디어 타입입니다.")`
  - `INVALID_AUDIO_STATE_TRANSITION (HttpStatus.CONFLICT, "유효하지 않은 음성 상태 변경 요청입니다.")`
  - `UNSUPPORTED_MEDIA_TYPE (HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type입니다.")`
  - `INTERNAL_ERROR (HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.")`
- **`GlobalExceptionHandler` 매핑 테이블**:
  - `BusinessException` → `e.getErrorCode().getHttpStatus()`
  - `MethodArgumentNotValidException`, `HandlerMethodValidationException`, `ConstraintViolationException`, `HttpMessageNotReadableException`, `MethodArgumentTypeMismatchException`, `MissingServletRequestParameterException`, `MissingPathVariableException` → `400 Bad Request` (`ErrorCode.INVALID_INPUT`)
  - `AccessDeniedException` → `403 Forbidden` (`ErrorCode.ACCESS_DENIED`)
  - `NoResourceFoundException` → `404 Not Found` (`ErrorCode.RESOURCE_NOT_FOUND`)
  - `HttpRequestMethodNotSupportedException` → `405 Method Not Allowed` (`ErrorCode.METHOD_NOT_ALLOWED`)
  - `HttpMediaTypeNotAcceptableException` → `406 Not Acceptable` (`ErrorCode.NOT_ACCEPTABLE`)
  - `HttpMediaTypeNotSupportedException` → `415 Unsupported Media Type` (`ErrorCode.UNSUPPORTED_MEDIA_TYPE`)
  - `Exception` → `500 Internal Server Error` (`ErrorCode.INTERNAL_ERROR`, 내부 상세 스택트레이스는 서버 로그에만 남기고 응답에는 노출하지 않음)

---

## 4. Verification Plan

### Automated Tests (`./gradlew test`)
1. **`Phase0ApiIntegrationTest` (`@SpringBootTest` + `@Testcontainers` + `@ServiceConnection`)**:
   - `PostgreSQLContainer<>("postgres:16.6-alpine")`를 자동 기동하여 Flyway `V1__init_schema.sql` 실행 및 **9개 도메인 엔티티 전체(`invite_code CHAR(6)` 포함)**가 Hibernate 6 `ddl-auto: validate`를 완벽히 통과하는지 검증.
   - `GET /api/v1/health` 호출 시 `200 OK` 및 `HealthCheckResponse` 5개 필드(`status == "UP"`, `application == "ieum-backend"`, `profile`, `timezone == "Asia/Seoul"`, `serverTime`) 검증.
   - `GET /api/v1/policies` 호출 시:
     - 미인증 상태에서 기본 `FREE` 정책(`currentPlan == "FREE"`, `maxVoiceDurationSeconds == 180`, `voiceServerRetentionDays == 30`) 반환 검증.
     - `CurrentUser(userId, MembershipTier.PREMIUM)` 인증 컨텍스트 주입 상태에서 `PREMIUM` 정책(`currentPlan == "PREMIUM"`, `maxVoiceDurationSeconds == 600`, `voiceServerRetentionDays == 36500`) 반환 검증.
   - `GET /v3/api-docs` 호출 시 `200 OK` 검증.
   - 미인증 상태로 보호된 경로(`GET /api/v1/groups/00000000-0000-0000-0000-000000000001`) 호출 시 `CustomAuthenticationEntryPoint`에 의해 `401 Unauthorized` + `ApiResponse` 포맷(`code == "UNAUTHORIZED"`)이 반환되는지 검증.
   - `csrf.disable()` 상태에서 허용되지 않은 HTTP 메서드(`DELETE /api/v1/health`) 호출 시 403이나 500이 아닌 `405 Method Not Allowed` (`code == "METHOD_NOT_ALLOWED"`) 반환 검증.
2. **`CoreDomainInvariantsTest` (순수 단위 테스트 + Mockito)**:
   - 전원 답변 즉시 공개(`REVEALED_ALL`) 및 `REVEALED_TIMEOUT` 이후 지각 답변 시 `REVEALED_ALL` 승격 + 기존 `revealedAt` 보존 검증.
   - 익일 `16:59:59 KST` (`LOCKED` 유지) vs `17:00:00 KST` (`REVEALED_TIMEOUT` 전환) 경계 검증 및 0명 답변 시 미전환 검증.
   - 다중 그룹 격리(`GroupAccessValidator` & `GroupMemberAccessAspect` 단위 테스트): 비소속 그룹 접근 시 `ErrorCode.GROUP_ACCESS_DENIED (403)`, 미인증 시 `ErrorCode.UNAUTHORIZED (401)` 발생 검증.
   - 음성 수명주기 상태 전이 가드(`ACTIVE` → `EXPIRING_NOTIFIED` → `EXPIRED_DELETED`) 시 텍스트 보존 및 `PERMANENT`/`NONE` 만료 시도 시 `ErrorCode.INVALID_AUDIO_STATE_TRANSITION (409)` 예외 검증.
