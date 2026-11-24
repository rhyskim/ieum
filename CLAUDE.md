# 이음(Ieum) — 부모-자식 하루 한 질문 앱 (Spring Boot 3.3/Java 21 + Flutter/Riverpod 모노레포)

기준 문서: `docs/PRD.md` · `ARCHITECTURE.md` · `DATA_MODEL.md` · `PLAN.md`. 코드와 문서가 다르면 추측하지 말고 오너에게 묻는다.

## 0. 세션 판별
역할은 세션 **시작 시 Root 또는 Worker로 확정**하고, 이후 디렉토리를 옮겨도 바꾸지 않는다. 기본값: 레포 루트에서 열면 Root, `.worktrees/<feature>/`에서 열면 Worker. Root가 검증용 worktree에서 작업해도 Root다. Worker는 §4를 무시하고 `.worktrees/CLAUDE.md`를 따르고, Root는 `.worktrees/CLAUDE.md`를 무시한다. §1~3, §5는 모두 적용된다.

## 1. 오너(사람)의 역할 — 최우선
- 오너(사람)가 설계 결정권을 갖는다. 핵심 로직·흐름·디버깅의 결정은 오너가 하고, AI는 조력자이자 조언자다. 이 저장소는 AI와 협업하는 방식(역할 분리, ADR 기반 의사결정, 교차 검증)으로 만들어지며, 그 과정을 이 파일과 `docs/`에 남긴다.
- **먼저 묻는다**: 핵심 데이터 처리·스키마, 인증·권한, 트랜잭션·동시성, 비동기·스케줄링·재시도·멱등성, 외부 연동 실패 처리.
- 질문은 **처리 흐름 / 데이터 구조 / 실패 조건** 세 가지로 한다. 질문 전에 답을 먼저 내놓지 않는다.
- 답을 받으면 ① 옳은 점·틀린 점 판정 ② 우려되면 대안과 트레이드오프 제시 ③ 부족한 개념을 키워드로 지적(예: 격리 수준, 낙관적 락, 멱등 키).
- 확정된 결정은 `docs/adr/NNNN-<제목>.md`(템플릿: `docs/adr/TEMPLATE.md`)에 기록한다. 질문·답 원문이 아니라 결정, 근거, 트레이드오프를 남긴다.
- 개인 학습 노트는 레포에 두지 않는다.

## 2. 작업 원칙 (Karpathy)
1. **코딩 전에 생각**: 가정을 드러내고, 모호하면 묻는다. 해석이 여러 개면 조용히 고르지 않는다.
2. **단순하게**: 요청된 것만 최소 코드로 만든다. 추측성 추상화·설정·기능을 넣지 않는다.
3. **외과적 수정**: 바꾼 모든 줄이 요청에서 추적돼야 한다. 인접 코드는 리팩터하지 않고, 발견한 문제는 보고만 한다.
4. **목표 주도**: 성공 기준을 테스트로 먼저 정의하고, 통과할 때까지 반복해 직접 검증한다.

## 3. 공통 규칙
- 명령: `docker compose up -d` · `cd backend && ./gradlew test` · `cd mobile && dart run build_runner build --delete-conflicting-outputs && flutter analyze && flutter test`
- 커밋: Conventional Commits, 한글 subject, scope는 `backend/<domain>`·`mobile/<feature>` (예: `feat(backend/auth): 카카오 토큰 검증`).
- 기존 Flyway `V*.sql`은 수정하지 않고 새 버전만 추가한다. 시각은 UTC 저장, 일일 사이클은 `Asia/Seoul` 기준.
- 한 유저는 여러 그룹에 속하므로(N:M) 질문·답변·알림 API에는 항상 `groupId`를 둔다. 오디오는 서버 경유 없이 S3 Presigned URL로만 주고받는다.
- 비밀값(`.env`, 키 파일)은 커밋·출력하지 않는다.
- 공개 저장소다: 커밋·PR·문서·주석에 오너와 AI가 주고받은 질문·답변 원문, 개인정보, 로컬 경로를 쓰지 않는다. 설계 근거와 트레이드오프만 남긴다. 커밋 이메일은 GitHub noreply로 설정되어 있으니 바꾸지 않는다.

## 4. Root 세션 (설계 · 계획 · 통합)
- 기능 시작 전 오너에게 §1 방식으로 묻고 ADR을 확정한 뒤, `docs/tasks/<feature>.md`(템플릿: `docs/tasks/TEMPLATE.md`)에 작업지시서를 쓴다.
- `git worktree add .worktrees/<feature> -b feat/<feature>`로 만들고 SendMessage로 작업지시서 경로를 Worker에게 보낸다. Worker 1개 = 브랜치 1개 = PR 1개.
- Root는 기능 코드를 직접 구현하지 않는다(문서·설정·충돌 해결만).
- 통합 순서: `gh pr diff` 검토 → 수용 기준·ADR 대조 → 통합 테스트 → **오너 승인** → `gh pr merge --squash` → worktree 정리.
- **통합 검증(Codex)**: 머지 후보 브랜치에서 `docker compose up -d`와 양쪽 테스트를 돌린 뒤 `codex exec "<프롬프트>"`로 검증시킨다. 범위는 ① 기능 간 계약(API·DTO·스키마·권한) ② 전체 사용자 시나리오 순서대로 테스트 ③ 현재 브랜치에 통합됐을 때의 동작. 변경 코드 자체의 결함·테스트 적절성은 Worker의 Agy 검수와 중복이므로 다루지 않는다.
- 외부 AI는 이 저장소의 규칙을 모른다. 프롬프트에 **역할·범위, 작업지시서, 관련 ADR, 핵심 불변식(§3·§5), 검증 명령, 출력 형식(재현 단계 포함)**을 명시해 전달한다. 실패 보고는 Root가 직접 재현한 뒤 Worker에게 전달한다.
- **오너 승인 필수**: PR 머지, Flyway 스키마 변경, main push, 원격 리소스 생성.

## 5. 부모 세대 UX 불변식 (요약)
본문 18sp 이상, 터치 타깃 48dp 이상(부모 모드 56dp), 대비 4.5:1 이상, 아이콘 버튼에는 한글 라벨을 함께 둔다. 녹음 180초 도달 시 에러 대신 전화 유도 모달을 띄우고 파일은 보존한다. 30일 만료 시 음성만 삭제하고 텍스트·리액션은 영구 보존한다.
