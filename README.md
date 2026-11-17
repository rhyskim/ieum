# 이음 (Ieum) — 부모님과 함께하는 하루 한 질문 기록 앱

> **"매일 나누는 안부 너머, 부모님의 목소리와 마음을 기록합니다."**
>
> 부모와 자식이 하루에 하나의 질문에 각자 답하고, 서로의 답변과 실제 목소리를 확인하며 관계를 기록·보존하는 양방향 가족 소통 모바일 애플리케이션입니다.

---

## 1. 문제 정의 및 해결책

### 문제 (Problem)
- 성인이 된 자식과 부모님은 자주 연락하지만, 대화는 늘 *"밥 먹었니? / 응"* 수준에서 머무릅니다. 서로의 젊은 시절, 가치관, 속마음을 깊이 물어볼 계기가 부족합니다.
- 기존의 가족 기록 서비스는 자식이 부모를 일방적으로 인터뷰하거나 텍스트 위주의 저널 형태라 **타이핑이 느리고 작은 글씨에 피로감을 느끼는 50~70대 부모 세대가 지속적으로 사용하기 어렵습니다.**

### 해결책 (Solution)
1. **양방향 하루 한 질문 루프 (Someone 스타일 가족판)**
   - 매일 저녁 7시(19:00 KST), 가족 그룹에 오늘의 질문이 도착합니다.
   - 가족 모두가 답변을 완료하거나 마감 시간(다음 날 오후 5시)이 되면 서로의 답변이 동시에 열립니다.
2. **음성 1급 시민 설계 (Voice-First UX)**
   - **TTS (질문 읽어주기)**: 작은 글씨를 읽지 않아도 앱이 오늘의 질문을 따뜻한 음성으로 읽어줍니다.
   - **STT (말하면 텍스트로 변환)**: 키보드 타이핑 없이 말하기만 하면 자동으로 텍스트 답변으로 변환됩니다.
   - **원본 목소리 그대로 전달 (Voice Archive)**: STT 텍스트 변환과 별개로, **부모님과 자식이 녹음한 실제 목소리 원본(`.m4a`)을 그대로 전달하고 재생**할 수 있습니다. (v1 무료 제공, 향후 프리미엄 구독 기능과 연동 가능한 유연한 권한 구조 적용)
   - **3분 제한과 감성적 전화 유도 UX**: 서버 부하 방지와 대화의 여운을 위해 음성 녹음은 최대 3분으로 제한됩니다. 3분을 초과할 만큼 하고 싶은 이야기가 많을 때는 *"오늘은 나누고 싶은 이야기가 많은가 봐요. 오늘 밤은 OO님께 직접 전화를 걸어 목소리를 들으며 이야기를 이어가 보는 건 어떨까요?"*라는 감성 안내와 함께 바로 전화 걸기 버튼을 제공합니다.
3. **부모 세대 맞춤형 접근성 (Senior-Friendly)**
   - 큰 글씨, 넓은 터치 영역, 고대비 색상, 3탭 이내에 완료되는 직관적인 화면 동선을 기본값으로 제공합니다.
4. **유연한 가족 구성 및 다중 그룹 지원**
   - 2인(엄마+딸)부터 N인(엄마+아빠+남매)까지 자유롭게 구성할 수 있으며, 한 사용자가 여러 가족 방(예: 친정 방, 시댁 방)에 동시에 참여할 수 있습니다.

---

## 2. 핵심 기능 요약 (v1 Scope)

| 구분 | 주요 기능 | 비고 |
| :--- | :--- | :--- |
| **인증 및 온보딩** | 카카오 / 네이버 / Apple 소셜 로그인, 역할(부모/자식) 및 호칭 설정 | 5070 세대 최소 마찰 가입 |
| **다중 그룹 & 초대** | N:M 다중 그룹 참여, 6자리 숫자 초대코드 및 카카오톡 공유(딥링크) 초대 | 썸원(Someone) 방식 차용 |
| **오늘의 질문 & 알림** | 매일 19:00 KST 질문 배정, 개인별 커스텀 푸시 알림 시각 설정 | 기본 알림 시각은 그룹 설정 따름 |
| **멀티모달 답변 작성** | ① 텍스트 입력 ② STT 음성→텍스트 변환 ③ **원본 음성 녹음 및 전달(최대 3분)** | 3분 도달 시 감성 전화 연결 모달 |
| **답변 공개 & 넛지** | 전원 답변 시 즉시 공개 / 미답변 시 익일 17:00(새 질문 2시간 전) 자동 공개 | 가족 답변 시 넛지 + 마감 2시간 전 리마인드 |
| **리액션 & 아카이브** | 이모지 리액션, 짧은 음성 리액션(최대 30초), 날짜별/카테고리별 타임라인 | 텍스트 영구 보관 |
| **음성 수명주기 관리** | 원본 음성 서버 30일 보관 후 자동 삭제, 삭제 7일 전 알림, **내 기기 영구 다운로드** | Feature Flag로 향후 유료화 대비 |

---

## 3. 기술 스택 (Tech Stack)

본 프로젝트는 **단일 레포지토리(Monorepo)** 내에서 모바일 클라이언트와 백엔드 서버를 함께 관리합니다.

### Mobile Client (`mobile/`)
- **Framework & Language**: Flutter (3.24+) / Dart (3.5+) — iOS & Android 네이티브 앱 전용 출시
- **Architecture & State Management**: Feature-first Layered Architecture + **Riverpod (`flutter_riverpod`, `riverpod_annotation`)**
- **Routing**: `go_router` (딥링크 연동 포함)
- **Voice & Audio**:
  - 온디바이스 STT: `speech_to_text` (추후 클라우드 STT 교체 가능하도록 인터페이스 추상화)
  - 온디바이스 TTS: `flutter_tts`
  - 원본 녹음 및 재생: `record` (AAC/m4a, 64kbps, mono), `just_audio`
- **Network & Auth**: `dio`, `kakao_flutter_sdk_user`, `flutter_naver_login`, `sign_in_with_apple`, `firebase_messaging` (FCM)

### Backend Server (`backend/`)
- **Framework & Language**: **Java 21 (LTS)** + **Spring Boot 3.3.x**
- **Build Tool**: Gradle (Kotlin DSL)
- **Persistence**: **PostgreSQL 16** + Spring Data JPA (Hibernate) + QueryDSL + Flyway (DB 마이그레이션)
- **Authentication**: OAuth2 소셜 검증 (Kakao, Naver, Apple) + 자체 JWT (Access / Refresh Token)
- **Storage**: AWS S3 (서울 리전 `ap-northeast-2`) — Presigned URL 기반 직접 업로드/다운로드 및 30일 Object Lifecycle 정책
- **Push & Scheduling**: Firebase Admin SDK (FCM 발송), Spring Scheduler / Quartz (19:00 질문 배포, 17:00 자동 공개, 개인별 알림 발송)
- **Local Dev Infra**: Docker & `docker-compose.yml` (PostgreSQL 16, LocalStack S3, Redis 7)

---

## 4. 모노레포 디렉토리 구조

```text
for-family/
├── README.md                 # 프로젝트 개요 및 실행 가이드
├── CLAUDE.md                 # AI 어시스턴트(Claude Code 등) 개발 규칙 및 컨벤션
├── PLAN.md                   # Phase 0 ~ Phase 3 마일스톤 및 상세 태스크 로드맵
├── docker-compose.yml        # 로컬 개발용 인프라 (PostgreSQL, Redis, LocalStack S3)
├── docs/
│   ├── PRD.md                # 상세 제품 요구사항 정의서 (유저 플로우, 예외 처리, 개인정보 체크리스트)
│   ├── DATA_MODEL.md         # PostgreSQL ERD, DDL 스키마, S3 경로 및 수명주기 설계
│   └── ARCHITECTURE.md       # 클라이언트/백엔드 레이어링, 음성 파이프라인, 유료화 확장 설계
├── backend/                  # Spring Boot (Java 21) API 서버
│   ├── build.gradle.kts
│   ├── src/main/java/com/ieum/backend/
│   └── src/main/resources/
│       ├── application.yml
│       └── db/migration/     # Flyway SQL 마이그레이션 파일
└── mobile/                   # Flutter (Dart) iOS/Android 앱
    ├── pubspec.yaml
    └── lib/
        ├── core/             # 공통 유틸, 네트워크(Dio), 테마(접근성), 추상 서비스(STT/TTS)
        ├── features/         # 도메인 기능별 모듈 (auth, group, question, answer, archive, settings)
        └── main.dart
```

---

## 5. 로컬 실행 방법 (Quick Start — Placeholder)

### 5.1 로컬 인프라 실행 (Docker)
```bash
# 루트 디렉토리에서 PostgreSQL(15432), Redis(16379), LocalStack S3(4566) 컨테이너 실행
docker compose up -d
```

### 5.2 백엔드 서버 실행 (`backend/`)
```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=local'
```

### 5.3 모바일 앱 실행 (`mobile/`)
```bash
cd mobile
flutter pub get
dart run build_runner build --delete-conflicting-outputs
flutter run
```

---

## 6. 관련 문서 링크
- [CLAUDE.md](./CLAUDE.md) — 코딩 컨벤션, 아키텍처 원칙 및 AI 협업 가이드
- [PLAN.md](./PLAN.md) — 단계별 마일스톤 및 개발 체크리스트
- [docs/PRD.md](./docs/PRD.md) — 제품 요구사항 정의서
- [docs/DATA_MODEL.md](./docs/DATA_MODEL.md) — 데이터베이스 스키마 및 스토리지 명세
- [docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md) — 시스템 아키텍처 및 상세 기술 설계
