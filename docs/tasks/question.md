# Task: question (질문 은행 + 일일 질문 배정)

- 브랜치: `feat/question` / worktree: `.worktrees/question`
- 대상: backend (+ 홈 화면에서 "오늘의 질문"을 읽는 최소 모바일은 다음 작업 answer에서)
- 선행: auth, group-core(머지됨), notification(머지됨, 푸시 포트 재사용)

## 목표
매일 19시 이후 모든 ACTIVE 그룹에 그룹당 질문 1개가 정확히 한 번 배정되고, 서버가 꺼져 있었어도 누락이 복구된다.

## 범위
- 보정형 배정 작업: 19시 이후 5분마다, 오늘(cycle_date, Asia/Seoul 19:00 사이클) 질문이 없는 ACTIVE 그룹에 `INSERT ... ON CONFLICT DO NOTHING`. ShedLock 적용. 지난 사이클은 보정하지 않는다.
- 질문 선택: 그룹이 받은 질문 수 + 1번째(`sequence_order`), 비활성 질문 제외. 그룹 생성 날에도 즉시 배정(생성 후 같은 사이클에 질문이 없으면 다음 작업 실행에서 배정되면 충족, 별도 즉시 호출이 있으면 같은 멱등 경로 사용).
- `auto_reveal_at` = 배정 날짜 다음날 17:00 KST. 늦게 배정돼도 그대로.
- 배정이 새 행을 만든 경우에만 커밋 후 비동기 푸시("새 질문이 도착했어요", 개인정보 없음, `groupId`·type만). 알림 실패는 배정에 영향 없음.
- 질문 적재: 개발용 샘플 30개를 Flyway 버전 마이그레이션으로 추가(실제 질문은 이후 CSV→새 마이그레이션). 남은 질문이 임계치 이하이면 경고 로그, 은행 소진 시 배정 건너뛰기+오류 로그.
- 조회 API `GET /api/v1/groups/{groupId}/questions/today`(오늘의 질문과 상태 `hasAnswered=false` 등 현재 가능한 최소 필드). 답변·공개는 answer 작업.

## 범위 밖
답변 저장·공개 상태 전환·15시 리마인드·17시 공개 알림(answer), 스페셜 데이, 질문 관리 화면.

## 수용 기준 (테스트로 표현)
- [ ] 같은 사이클에 작업이 여러 번·동시에 실행돼도 그룹당 `daily_questions`는 1행(서버 2대 동시 실행 테스트 포함).
- [ ] 서버가 19시에 꺼져 있던 상황을 시각 주입으로 재현: 19:30 첫 실행에서 누락 그룹이 모두 채워진다. 푸시는 새로 만든 그룹에만 호출된다.
- [ ] 그룹이 받은 질문 수에 따라 `sequence_order` 순서로 배정되고, 비활성 질문은 건너뛴다.
- [ ] 은행이 비면 배정을 건너뛰고 다른 그룹에는 영향이 없다.
- [ ] 오늘의 질문 조회는 그룹의 유효 멤버만 가능하다(타 그룹·탈퇴 유예 사용자 거절).
- [ ] `auto_reveal_at`이 배정 날짜 다음날 17:00 KST로 저장된다.

## 관련 ADR·문서
`docs/adr/0006-question-assignment.md`, `docs/adr/0004-notification.md`, `docs/adr/0002-account-purge.md`(유효 멤버)
