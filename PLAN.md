# PLAN.md — 이음 (Ieum) 마일스톤 기반 개발 로드맵

본 문서는 1인 풀스택 개발(Flutter + Spring Boot 3 / Java 21 + PostgreSQL + AWS S3) 기준의 단계별 구현 로드맵입니다. 각 Phase는 독립적으로 검증 가능한 완료 정의(Definition of Done, DoD)를 가집니다.

---

## 📊 전체 마일스톤 요약

| 단계 | 목표 | 핵심 산출물 | 예상 난이도 |
| :--- | :--- | :--- | :---: |
| **Phase 0** | 모노레포 & 로컬 인프라 셋업 | Docker Compose, Spring Boot 골격, Flutter 골격, CI/린트 | ★★☆☆☆ |
| **Phase 1** | 코어 루프 (인증/다중그룹/문답/공개) | 소셜 로그인, 초대코드/딥링크, 19시 질문/17시 자동공개, 텍스트 문답 | ★★★☆☆ |
| **Phase 2** | 음성 파이프라인 & 감성 UX | 온디바이스 STT/TTS, 원본 녹음(S3 Presigned), 3분 전화 유도 모달, 30일 수명주기 | ★★★★☆ |
| **Phase 3** | 접근성/알림 고도화 & 출시 준비 | 부모 세대 접근성 모드, 개인별 알림 스케줄러, 개인정보 동의, 스토어 배포 | ★★★☆☆ |

---

## Phase 0: 프로젝트 셋업 및 기반 인프라 구축
> **목표**: 로컬에서 Docker 하나로 DB/스토리지가 뜨고, Spring Boot와 Flutter 앱이 서로 헬스체크 API로 통신할 수 있는 상태를 만든다.

### 태스크 목록
- [x] **0-1. 모노레포 디렉토리 및 Git 초기화** (` 난이도: 하 `)
  - `.gitignore`, `.gitattributes`, `.editorconfig` (Java/Gradle, Flutter/Dart, IDE, `.env` 통합 설정)
  - 루트 `docker-compose.yml` 작성: PostgreSQL 16 (`port: 15432`), Redis 7 (`port: 16379`), LocalStack S3 (`port: 4566` - 로컬 S3 및 30일 Lifecycle 에뮬레이션)
- [x] **0-2. Backend (`backend/`) 초기 구축** (` 난이도: 중 `)
  - Java 21 + Spring Boot 3.3.x + Gradle(Kotlin DSL) 프로젝트 생성
  - 의존성 구성: `Spring Web`, `Spring Data JPA`, `Validation`, `Spring Security`, `Spring AOP`, `PostgreSQL Driver`, `Redis`, `Flyway`, `QueryDSL`, `AWS SDK v2 (S3)`, `ShedLock`, `Firebase Admin SDK` (모바일 소셜 토큰 서버 검증 방식이므로 웹 리다이렉트용 `OAuth2 Client`는 제외하고 Phase 1에서 `RestClient`로 검증)
  - 공통 응답 래퍼(`ApiResponse<T>`), 전역 예외 처리(`GlobalExceptionHandler`), Swagger/SpringDoc OpenAPI 설정
  - Flyway 초기 마이그레이션(`V1__init_schema.sql`) 적용 및 구동 확인
- [x] **0-3. Mobile (`mobile/`) 초기 구축** (` 난이도: 중 `)
  - Flutter 프로젝트 생성 (패키지명: `com.ieum.app`, Dart 패키지: `ieum_mobile`, Android/iOS 전용 타깃 설정)
  - 핵심 패키지 설치: `flutter_riverpod`, `riverpod_annotation`, `go_router`, `dio`, `freezed_annotation`, `json_annotation` (`flutter_secure_storage`는 Phase 1-1 JWT 저장소 구현 시 추가)
  - Feature-first 폴더 구조 생성 및 `ThemeData` (기본 모드 / 부모님 큰글씨 모드) 토큰 정의
- [x] **0-4. 무료/유료 전환 대비 Feature Entitlement 기반 모듈 작성** (` 난이도: 하 `)
  - 서버에서 내려주는 `FeatureEntitlementResponse` (`docs/ARCHITECTURE.md` §5 DTO 규격: `currentPlan`, `canAttachOriginalVoice`, `maxVoiceDurationSeconds=180`, `voiceServerRetentionDays=30`, `canDownloadVoiceToLocal`, `sttEngineType`) 응답 API, `EntitlementService`(`FeatureGate`) 및 클라이언트 프로바이더 구성

### 완료 정의 (DoD)
- `docker compose up -d` 실행 후 `./gradlew bootRun` 시 Flyway 스키마가 자동 생성되고 Swagger UI(`/swagger-ui.html`)가 정상 노출된다.
- 에뮬레이터에서 Flutter 앱 실행 시 백엔드 `/api/v1/health` 및 `/api/v1/policies`를 호출해 화면에 표시한다.

---

## Phase 1: 코어 루프 (인증, 다중 그룹, 하루 한 질문, 답변 공개)
> **목표**: 음성 기능 없이 텍스트만으로도 "가입 → 가족 방 생성/초대 → 오후 7시 질문 도착 → 가족 답변 → 전원 답변(또는 익일 오후 5시) 시 열람 → 타임라인 보관"의 전체 루프가 완벽히 동작한다.

### 태스크 목록
- [ ] **1-1. 소셜 인증 및 JWT 발급 (`auth`)** (` 난이도: 중 `)
  - 카카오(1순위), Apple(iOS 필수), 네이버(선택) OAuth2 토큰 검증 및 회원 가입/로그인 API 구현
  - Access Token / Refresh Token 발급 및 Flutter `Dio` 인터셉터 자동 갱신 연동
  - 온보딩 프로필 입력(이름, 생년월일/연령대, 기본 역할: 부모/자식, 전화번호(선택 - 3분 초과 전화 연결용))
- [ ] **1-2. 다중 가족 그룹 생성 및 초대 시스템 (`group`)** (` 난이도: 중 `)
  - 가족 방 생성 API (방 이름, 내 역할 `PARENT`/`CHILD`, 방 내 호칭 예: `엄마`, `막내아들`)
  - **6자리 숫자 초대코드** 생성(유효기간 5분, 방장 승인 후 입장) 및 코드 입력 참여 API → `docs/tasks/group-core.md`
  - **초대 링크·딥링크** 연동은 **보류**(출시 후 이용률을 보고 결정) → `docs/tasks/invite-link.md`
  - 홈 화면 상단 **그룹 스위처(Group Switcher)**: 여러 가족 방(친정, 시댁 등) 간 전환 UI
- [ ] **1-3. 질문 은행 시드 데이터 및 오후 7시 일일 질문 배정 (`question`)** (` 난이도: 중 `)
  - 카테고리별(추억 `MEMORY`, 일상 `DAILY`, 가치관 `VALUE`, 감사 `GRATITUDE`, 젊은 시절 `YOUTH`) 초기 질문 100개 시드 데이터(`V2__seed_questions.sql`) 적재
  - 매일 **19:00 KST**에 활성 그룹별로 다음 순서의 질문을 `daily_questions` 테이블에 생성하는 Spring Scheduler 구현
- [ ] **1-4. 텍스트 답변 작성 및 공개 상태 머신 (`answer`)** (` 난이도: 상 `)
  - 답변 등록/수정 API (공개 전까지만 수정 가능)
  - **즉시 공개 트랜잭션**: 답변 저장 시 해당 그룹의 활성 멤버 전원이 답변했는지 검사 → 전원 완료 시 `reveal_status = REVEALED_ALL`로 변경 및 전체 멤버에게 "모두가 답변을 마쳤어요!" FCM 발송
  - **미답변자 넛지(Nudge) 알림**: 누군가 먼저 답변했을 때, 아직 답하지 않은 가족에게 *"OO님이 오늘의 질문에 답했어요. 답변을 남기면 서로의 이야기를 볼 수 있어요!"* FCM 발송
  - **익일 17:00 KST(새 질문 2시간 전) 타임아웃 자동 공개**: 미답변자가 있어도 최소 1명 이상 답변한 경우 `REVEALED_TIMEOUT` 상태로 전환하여 답변한 사람들끼리 먼저 열람 허용 (미답변자는 늦게라도 답하면 열람 가능)
- [ ] **1-5. 아카이브 타임라인 및 이모지 리액션 (`archive`)** (` 난이도: 중 `)
  - 날짜별/카테고리별 지난 문답 리스트 조회 API (QueryDSL 페이징)
  - 가족의 답변에 5종 감정 이모지 리액션(`LOVE` ❤️ 사랑해요, `TOUCHED` 🥹 감동이에요, `THANKS` 🙏 고마워요, `FUNNY` 😊 웃음나요, `HUG` 🫂 안아줄래요) 남기기 기능

### 완료 정의 (DoD)
- 2대 이상의 기기(또는 에뮬레이터 2개 + 테스트 계정 2개)에서 자식 계정으로 방을 만들고 초대코드로 부모 계정이 입장할 수 있다.
- 양쪽이 답변을 제출하는 즉시 잠금 화면이 풀리며 서로의 답변이 보이고, 1명만 답한 상태에서 스케줄러 트리거 시 타임아웃 공개가 정상 작동한다.

---

## Phase 2: 음성 파이프라인 & 감성 UX (핵심 차별화)
> **목표**: 부모님 세대를 위한 TTS(질문 낭독), STT(말해서 텍스트 입력), 그리고 **실제 목소리 원본 파일(`.m4a`) 녹음/전달/재생/보관** 및 **3분 초과 감성 전화 유도 UX**를 완성한다.

### 태스크 목록
- [ ] **2-1. 음성 추상화 레이어 및 온디바이스 TTS/STT 연동** (` 난이도: 중 `)
  - `TtsService` 인터페이스 및 `OnDeviceTtsServiceImpl` (`flutter_tts`) 구현: 질문 카드 내 `[🔊 질문 듣기]` 버튼 연동 (속도 조절 `0.85x` 느리게 / `1.0x` 기본 지원)
  - `SttService` 인터페이스 및 `OnDeviceSttServiceImpl` (`speech_to_text`) 구현: 답변 작성 화면에서 `[🎤 말해서 글쓰기]` 누르면 실시간으로 음성 인식 텍스트가 입력창에 채워지는 UX
- [ ] **2-2. 원본 음성 녹음기 및 S3 Presigned URL 업로드 파이프라인** (` 난이도: 상 `)
  - `record` 패키지를 이용한 `AAC/m4a (64kbps, mono)` 포맷 녹음 모듈 구현 (일시정지, 다시 녹음, 파형 애니메이션 표시)
  - Backend: S3 Presigned PUT URL 발급 API (`POST /api/v1/groups/{groupId}/questions/{dailyQuestionId}/audio-upload-url`) 및 다운로드용 Presigned GET URL 발급 로직 구현
  - Mobile: 원본 음성 녹음 후 S3 직접 업로드 → 업로드된 `audioObjectKey`와 재생 시간(`durationSeconds`)을 답변 API에 함께 전달
  - **STT + 원본 음성 동시 활용 UX**: 말하면서 STT로 텍스트도 만들고 동시에 원본 목소리 파일도 첨부할지, 목소리만 보낼지 선택 가능한 직관적 토글 제공
- [ ] **2-3. 3분(180초) 녹음 제한 및 감성 전화 유도 모달 (`CallPromptDialog`)** (` 난이도: 중 `)
  - 녹음 시간 2분 30초(150초) 경과 시 타이머 색상 변경 및 *"30초 뒤 녹음이 마무리됩니다"* 부드러운 안내
  - **3분(180초) 도달 시**: 녹음을 안전하게 자동 완료(3분 분량 저장 보장)한 직후 감성 모달 팝업 노출:
    > *"오늘은 이 질문에 대해 나누고 싶은 이야기가 많은가 봐요. 오늘 밤은 [가족 호칭]에게 직접 전화해서 목소리를 들으며 이 이야기를 나눠보는 건 어떨까요?"*
  - 모달 내 `[📞 지금 전화 걸기]` (`url_launcher` `tel:` 스키마 연동) 및 `[녹음된 3분 답변 먼저 등록하기]` 액션 버튼 제공
- [ ] **2-4. 원본 음성 재생기 & 음성 리액션(최대 30초)** (` 난이도: 중 `)
  - `just_audio` 기반 커스텀 오디오 플레이어 위젯(재생/일시정지, 구간 탐색 바, 남은 보관 일수 배지 표시)
  - 부모님/자식의 답변 카드 하단에 텍스트 댓글 대신 **30초 이내의 짧은 음성 답장(리액션)** 녹음·재생 기능 구현
- [ ] **2-5. 음성 파일 30일 보관 주기(Lifecycle) 및 기기 로컬 다운로드** (` 난이도: 중 `)
  - AWS S3 버킷 Lifecycle Rule 설정 (`audio/ephemeral-30d/` 프리픽스 객체 생성 31일 차 물리적 자동 만료 삭제, 앱/DB는 30일 시점 만료 처리)
  - Backend: 매일 오전 10:00 KST 만료 예정(D-7, 삭제 7일 전) 음성 파일을 가진 그룹 멤버에게 *"일주일 뒤 OO님의 목소리 파일이 서버에서 정리됩니다. 소중한 목소리를 기기에 저장해 두세요"* 알림 스케줄러 구현 (`audio_status = EXPIRING_NOTIFIED`)
  - Backend: 매일 오전 10:00 KST 30일 경과 데이터의 `audio_status`를 `EXPIRED_DELETED`로 업데이트하는 배치 작업
  - Mobile: `[💾 내 폰에 목소리 영구 저장]` 버튼 구현 (스마트폰 로컬 문서/오디오 폴더에 `.m4a` 파일 다운로드 및 오프라인 재생 지원)

### 완료 정의 (DoD)
- 부모 계정에서 키보드를 전혀 쓰지 않고 `[🔊 질문 듣기]` → `[🎤 목소리로 답하기]`만으로 답변과 실제 목소리 원본을 등록할 수 있다.
- 녹음이 3분에 도달하면 파일이 유실되지 않고 저장되며, 가족에게 전화를 걸 수 있는 감성 안내창이 정상 동작한다.
- 업로드된 원본 음성을 상대방이 재생할 수 있고, 내 기기에 다운로드하여 서버 만료 후에도 들을 수 있다.

---

## Phase 3: 부모 세대 접근성, 개인별 알림 스케줄링 & 출시 준비
> **목표**: 부모님이 혼자서도 헤매지 않는 접근성 완성도를 갖추고, 개인별 알림 커스텀 및 법적 필수 요건(개인정보/음성 수집 동의)을 완비하여 스토어 심사 가능한 수준으로 마감한다.

### 태스크 목록
- [ ] **3-1. 부모 세대 접근성 모드 (Senior Accessibility Mode)** (` 난이도: 중 `)
  - 온보딩 시 역할을 `부모(PARENT)`로 선택하면 자동으로 **'큰 글씨·고대비·단순 화면 모드'**가 기본 활성화되도록 처리
  - 설정 화면에서 글씨 크기 3단계(보통 / 크게 / 아주 크게) 실시간 미리보기 및 전환
- [ ] **3-2. 개인별 커스텀 알림 및 마감 2시간 전 리마인더 스케줄러** (` 난이도: 상 `)
  - 질문 생성 시각은 매일 **19:00 KST**로 고정하되, 각 멤버가 해당 그룹의 알림을 받을 시각(`custom_notification_time`, 기본값 `19:00`, 개인별 `07:00 ~ 23:00` 변경 가능) 설정 API 및 UI 구현
  - 마감 2시간 전(**익일 15:00 KST**, 자동 공개 시각인 17:00의 2시간 전)까지 미답변인 멤버에게만 *"오늘의 질문이 2시간 뒤(오후 5시)에 마감되어 공개됩니다. 짧게라도 목소리를 남겨보세요!"* 리마인드 푸시 발송
- [ ] **3-3. 개인정보 및 민감정보(음성) 수집·이용 동의 플로우** (` 난이도: 하 `)
  - 서비스 이용약관, 개인정보 처리방침, **음성 데이터 녹음·전송·보관(30일) 별도 동의** 체크박스 및 상세 안내 모달 구현
  - 그룹 탈퇴 및 회원 탈퇴 시 계정/음성 데이터 즉시 파기(또는 법령에 따른 유예 후 파기) API 구현
- [ ] **3-4. 네트워크 예외 처리 및 임시 저장(Draft)** (` 난이도: 중 `)
  - 답변 작성 중 앱이 꺼지거나 네트워크가 끊겨도 작성 중이던 텍스트와 로컬에 녹음된 `.m4a` 파일을 유지하고 재접속 시 이어서 업로드할 수 있는 로컬 임시 저장소 구현
- [ ] **3-5. 스토어 배포 준비 및 프로덕션 인프라 구성** (` 난이도: 중 `)
  - 서울 리전(`ap-northeast-2`) AWS RDS(PostgreSQL), S3, EC2/ECS(또는 Elastic Beanstalk) 프로덕션 배포 스크립트 구성
  - iOS App Store / Google Play Store 스크린샷, 개인정보 처리방침 웹페이지 URL 연결, 릴리즈 빌드 검증

### 완료 정의 (DoD)
- 모든 기능 체크리스트 및 단위/통합 테스트가 통과하고, 실제 스마트폰(iOS/Android)에서 푸시 알림, 소셜 로그인, 음성 녹음/재생이 끊김 없이 동작한다.
