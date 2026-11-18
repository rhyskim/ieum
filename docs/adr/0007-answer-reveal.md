# ADR-0007: 답변 저장과 공개 상태 머신

- 상태: 확정
- 날짜: 2026-11-18

## 맥락
`daily_questions.reveal_status`(LOCKED → REVEALED_ALL | REVEALED_TIMEOUT)와 `answers`(`UNIQUE(daily_question_id, user_id)`)가 V1에 있다. 마지막 답변자가 동시에 저장하는 경쟁과 서버 측 마스킹이 핵심이다.

## 최종 결정
1. **동시성**: 답변 저장 트랜잭션은 `daily_questions` 행을 `FOR UPDATE`로 잠근 뒤 답변 저장과 미답변 수 확인을 한다. 공개 전환은 `UPDATE ... WHERE reveal_status='LOCKED'` 조건부 UPDATE로 한 번만 일어나고, 전환이 1행을 바꿨을 때만 알림을 보낸다.
2. **혼합형(확정)**: 상태는 DB에 저장한다. 조회 시점에 `auto_reveal_at`이 지났으면 그 자리에서 같은 조건부 UPDATE로 전환한다. 15시 리마인드·17시 공개 알림은 스케줄러가 맡는다.
3. **서버 측 마스킹**: LOCKED 동안 타인 답변의 본문·음성 URL은 응답에 포함하지 않는다(`hasAnswered`와 호칭만). 블러는 앱의 연출이다. REVEALED_TIMEOUT은 답변을 마친 멤버에게만 타인 답변을 내려준다. 늦게 답변한 사람은 답변을 마친 뒤 열람한다.
4. **전환 우선순위**: 같은 시점에 두 조건이 겹치면 먼저 조건부 UPDATE에 성공한 쪽을 따르되, 전원 답변이 시간 초과보다 우선한다(`REVEALED_ALL`).
5. **0명 답변·마감 이후**: 17시에 아무도 답하지 않으면 상태는 LOCKED로 두고 질문은 건너뛴다(확정). 질문에는 언제든 답할 수 있다. 17시 이후 첫 답변이 들어오면 시간 초과 공개로 즉시 전환한다. 답변 마감은 없다.
6. **최소 글자수**: 텍스트 답변은 공백 제외 30자 이상(수정 시에도 동일). 음성 답변은 변환된 텍스트가 30자 이상이어야 한다(확정). 변환 방식(앱 내 STT 신뢰 여부)은 Phase 2에서 따로 결정한다. 부모용 별도 모드는 없다.
7. **공개 후 수정**: 허용하되 공개 상태는 유지하고 30자 규칙을 적용한다.
8. **멤버 변동 재평가**: 분모는 ADR-0002의 유효 멤버(탈퇴 유예·탈퇴·강퇴·나감 제외)다. 멤버가 빠지는 사건이 공개 조건을 충족시킬 수 있으므로, 탈퇴·강퇴·나가기 처리 후 해당 그룹의 LOCKED 질문을 재평가한다.

## 결과·후속 작업
작업지시서 `docs/tasks/answer.md`(질문 배정 머지 후 착수). 스키마 변경 필요한 컬럼 보정은 Worker가 보고하고 오너 승인 후 반영한다.
9. **락 순서(Worker 제안, Root 승인, 2026-11-19)**: 전역 순서는 users → family_groups(id 오름차순) → daily_questions(id 오름차순)이다. 답변 INSERT의 FK 검사는 users·group·dq 행에 `FOR KEY SHARE`를 걸어 `FOR UPDATE`와 충돌하므로, 저장은 users(본인) `FOR SHARE` → group `FOR SHARE` → dq `FOR UPDATE` 순으로 잡는다. leave/remove는 기존 group `FOR UPDATE` 뒤에 LOCKED dq를 id 오름차순으로 잠가 재평가한다. withdraw는 기존 owned-그룹 루프 뒤에 내 ACTIVE 그룹의 dq만 잠가 재평가하고 group 락은 새로 잡지 않는다. 재평가를 커밋 후 별도 트랜잭션으로 빼는 방식은 크래시 시 공개가 누락될 수 있어 채택하지 않는다. TIMEOUT에서 ALL로 승격하지 않는다(단방향).
