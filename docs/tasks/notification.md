# Task: notification (group-core 머지 후 진행)

- 브랜치: `feat/notification` / worktree: `.worktrees/notification`
- 대상: backend + mobile
- 선행: `group-core`(알림 포트 `GroupNotifier`가 있어야 함). 외부 준비: Firebase 프로젝트와 `google-services.json`/서비스 계정 키는 오너가 만든다(커밋 금지).

## 목표
FCM 푸시를 보낼 수 있는 기반을 만들고, 방장에게 입장 요청이 왔을 때와 요청자에게 승인·거절 결과를 알린다.

## 범위
- 기기 토큰 등록 API(`user_devices` 활용, `(user, token)` 멱등), 로그아웃 시 해당 기기 토큰 해제.
- FCM 발송 구현체(`FirebaseConfig` 조건부 초기화 유지, 키 없으면 비활성). 만료된 토큰(UNREGISTERED)은 삭제.
- `GroupNotifier` 실제 구현: 입장 요청 → 방장, 승인/거절 → 요청자. 알림 실패가 업무 트랜잭션을 롤백시키지 않는다(발송은 커밋 후, 실패는 로그).
- 모바일: 알림 권한 요청(사전 안내 화면 포함), 토큰 등록, 알림 탭 시 해당 화면으로 이동. 푸시를 못 받아도 앱 안의 승인 대기 목록이 기준이다.

## 범위 밖
질문 도착·넛지·리마인드 알림(PLAN 1-4, 3-2), 알림 설정 화면, 알림함.

## 수용 기준 (테스트로 표현)
- [ ] 같은 토큰을 두 번 등록해도 1행. 다른 사용자의 토큰 행은 변경할 수 없다.
- [ ] 입장 요청 시 방장 기기들에 발송 호출, 발송 실패해도 입장 요청은 성공한다.
- [ ] UNREGISTERED 응답 시 토큰 삭제. 로그아웃하면 해당 기기 토큰 해제.
- [ ] Firebase 키가 없는 환경에서도 앱이 기동하고 발송은 건너뛴다.

## 관련 ADR·문서
`docs/adr/0003-group-invite.md`, `docs/ARCHITECTURE.md` §4
