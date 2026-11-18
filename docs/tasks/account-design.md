# account 설계 정리 (Worker)

근거: `docs/adr/0002-account-purge.md`(결정 1~13, 확정), `docs/adr/0003-group-invite.md`

## PR 분할 (Root 승인)
- **PR 1**: 계정 연결, V5 전체, 탈퇴 요청 개편(콘텐츠 선택·방장 위임·기기 토큰 삭제), 유효 멤버 단일 정의, 복구(조건부 UPDATE·상한 충돌), 모바일(복구·로그인 수단·탈퇴 화면).
- **PR 2**(PR 1 머지 후): 파기 배치, Apple 해지 큐/작업. 아래 §5·§6은 PR 2의 설계다.

## 1. V5 (`V5__account_withdrawal_purge.sql`, 승인됨. 원래 V4였으나 notification이 먼저 머지되어 V4를 쓰므로 번호를 올림)
- `users`: `withdraw_content_choice`(KEEP/DELETE), `purge_started_at`(파기 임대 시각), `purged_at`(익명화 완료). CHECK 두 개: 선택값 범위, 탈퇴 상태가 아니면 세 컬럼 모두 NULL. 파기 후보 부분 인덱스.
- `user_identities`: `UNIQUE(user_id, provider)` (제공자당 1개의 최종 방어선).
- `apple_revocations`: `user_id` UNIQUE, status `PENDING/DONE/SKIPPED/FAILED`, `attempts`, `next_attempt_at`, `last_error`. (테이블만 PR 1에서 만든다. 사용은 PR 2.)
- 그룹 상태는 기존 `ACTIVE/ARCHIVED`를 쓴다(`DISSOLVED`는 만들지 않는다: 작업지시서 문구와 다름, 해산은 기존 `archive()`).

## 2. 유효 멤버 (결정 11)
정의는 `GroupMemberRepository.VALID_USER`(`u.deletedAt is null`) 한 곳. 인원 COUNT, 멤버 목록, 방장 후보, 승인 대기 목록, 방장 확인, 접근 검사, 승인·거절·강퇴 대상 조회가 모두 이 조각을 붙인 `@Query`를 쓴다. 상태·소속으로 목록·개수를 세는 기존 파생 쿼리는 제거했다. 남긴 것은 호출자 본인이 대상인 조회(`findByGroup_IdAndUser_Id`: 입장·이탈, `findMyActive`: 내 그룹 목록)와 탈퇴·복구가 쓰는 "내 그룹 id" 조회뿐이다.
- 효과: 탈퇴 직후 30분간 유효한 Access 토큰으로도 그룹에 접근할 수 없다(`existsValidActive`).

## 3. 탈퇴 요청 (`AccountService.withdraw`)
한 트랜잭션. 락 순서: `users` 행(FOR UPDATE) → 내가 방장인 그룹(id 오름차순, FOR UPDATE).
1. 이미 탈퇴한 계정이면 아무것도 바꾸지 않는다(멱등, 처음 선택·시각 유지).
2. `deleted_at`·콘텐츠 선택 저장 후 flush(방장 후보·인원 쿼리가 나를 유예 중으로 보게 한다).
3. 방장 위임: 그룹을 잠근 뒤 멤버십을 새로 읽어, 본인 외 가장 오래된 유효 ACTIVE 멤버(가입 일시 → id)에게 `is_owner`를 넘긴다. 내 멤버십은 `ACTIVE` 유지. 후보가 없으면 그룹을 그대로 둔다(파기 때 해산, PR 2). `leave()`와 다른 점이다(즉시 해산·LEFT 아님).
4. `user_devices` 삭제, Refresh 전체 폐기(마지막, `clearAutomatically` 때문).
- 요청 본문에 `contentChoice`(KEEP/DELETE) 필수, 없으면 400. 위임 대상을 사용자가 직접 지정하는 기능은 넣지 않았다(ADR-0003의 "직접 지정"은 기본값만 구현, 작업지시서에 없음).

## 4. 복구 (`AccountService.restore`)
소셜 토큰 재검증은 트랜잭션 밖(`AuthService`), DB 부분은 한 트랜잭션.
1. 조건부 UPDATE: `deleted_at > now() - 30일 AND purge_started_at IS NULL`(DB 시각). 복구 마감 30일, 파기 시작 31일 이후라 두 조건은 겹치지 않고, 이 UPDATE는 시계 차이에 대한 이중 방어다. 0행이면 이미 복구됐는지 다시 읽어 멱등 성공, 아니면 `ACCOUNT_RESTORE_EXPIRED`.
2. 내 ACTIVE 그룹을 id 오름차순으로 잠그고(승인과 같은 그룹 행 잠금), 본인을 포함한 유효 ACTIVE 수가 상한(20)을 넘으면 그 그룹의 내 멤버십을 `LEFT`로 바꾼다. 방장은 돌아오지 않는다.

## 5. 파기 배치 (PR 2 설계, ADR 결정 9·10)
매일 04:00 KST, ShedLock `account-purge`. 사용자 1명 단위: ① 클레임 UPDATE(`purge_started_at`을 30분 임대로 기록, `deleted_at <= now-31일 AND purged_at IS NULL AND (purge_started_at IS NULL OR 30분 경과)`; 0행이면 건너뜀) → ② S3 삭제(콘텐츠 선택이 DELETE일 때, 멱등, 실패 시 DB 보존·중단) → ③ DB 한 트랜잭션(콘텐츠 선택 실행의 DB 부분, `user_identities`·`refresh_tokens`·`user_devices` 삭제, 내가 보는 사람인 `member_aliases` 삭제, `group_members` 익명화, 혼자 남은 그룹 `archive`, Apple 큐 INSERT, `users` 익명화 + 마지막에 `purged_at`).
- 익명화: NOT NULL 컬럼(`users.name`, `group_members.family_nickname`)에는 `'탈퇴한 사용자'`를 넣고, 이메일·전화·`target_call_phone`은 NULL. **ADR 8의 "이메일·전화 익명화용 제약 완화"에 해당하는 제약은 코드에서 찾지 못했다(이미 NULL 허용). Root 확인: 완화할 제약이 없으면 위 방식.**
- 테스트(PR 2): 31일 미만 비대상, 이중 실행 동일 결과, 단계별 실패 주입 후 재실행, S3 실패 시 DB 행 보존, 서버 2대 동시 실행 이중 처리 없음, 파기 후 같은 소셜 계정으로 새 가입.
- **미정**: 콘텐츠 `DELETE` 범위(리액션 포함 여부). ADR 결정 3 "리액션은 유지"와 작업지시서 "리액션도 삭제"가 충돌하여 Root가 오너에게 확인 중. PR 2 착수 전 확정.

## 6. Apple 해지 큐 (PR 2 설계, 결정 13)
`AppleTokenRevoker.revoke(userId)` → `DONE | SKIPPED | 예외(재시도)`. 이번 구현체는 키가 없어 `SKIPPED`를 기록한다. 5분 주기 별도 작업(ShedLock `apple-revocation`)이 `PENDING AND next_attempt_at <= now()`를 `FOR UPDATE SKIP LOCKED`로 집어 처리. 실패 시 대기 = `min(10분 × 2^(n-1), 24시간)` ±20% 지터, 8회 후 `FAILED`. 파기는 큐에 넣기만 하므로 해지 실패가 파기를 막지 않는다. Apple 토큰 자체는 현재 저장하지 않아 실제 연결 시 암호화 컬럼이 필요하다(범위 밖).

## 7. 작업지시서·ADR과 다르게 해석한 곳 (PR 본문에도 기재)
1. 그룹 해산 상태: `DISSOLVED` 대신 기존 `ARCHIVED`.
2. 익명화 제약 완화: 해당 제약 없음, `'탈퇴한 사용자'` 문자열로 대체.
3. `leave()`를 탈퇴에 그대로 쓰지 않고 후보 선정만 공유(`ownerCandidates`).
4. `DELETE /api/v1/users/me`가 본문 필수로 바뀌어 기존 클라이언트·테스트는 본문을 보내야 한다(API 계약 변경).
5. "그룹 화면의 멤버 표시: 탈퇴한 사용자(호칭 수정 가능)": 유예 중 사용자는 다른 가족 화면에서 보이지 않는 것이 확정 규칙이라 멤버 목록에는 나오지 않는다. 과거 답변 작성자 표시와 호칭 수정은 답변 표시 로직(범위 밖)과 함께 별도 확정이 필요하다. Root 확인 요청 중.
