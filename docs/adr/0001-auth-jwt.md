# ADR-0001: 소셜 로그인과 JWT 인증 설계

- 상태: 확정
- 날짜: 2026-11-17

## 맥락
PLAN 1-1. 소셜 토큰을 서버가 검증해 자체 JWT를 발급한다. 현재 `SecurityConfig`는 stateless이고 JWT 필터가 없어 보호 API는 전부 401이다. `users`에는 `UNIQUE(oauth_provider, oauth_subject)`와 `deleted_at`이 있다. 모바일은 `NoopAuthTokenReader`만 있다.

## 최종 결정 (2026-11-17)
1. **로그인 수단**: 카카오 · 네이버 · Apple 모두 구현한다. Apple은 첫 로그인 때만 주는 이름·이메일을 그때 저장하고, 탈퇴 시 Apple 토큰을 해지한다.
2. **Refresh 저장소**: PostgreSQL. 원문이 아니라 해시로 저장한다. Redis는 쓰지 않는다.
3. **기기 정책**: 기기별 세션(다기기 허용). Refresh Token Rotation + 재사용 탐지(family 폐기). 설정에서 "로그인된 기기 목록"과 "이 기기 로그아웃"을 제공한다. 위치(IP)는 v1에서 수집하지 않는다.
4. **토큰 수명**: Access 30분, Refresh 60일 슬라이딩.
5. **탈퇴**: 소프트 삭제 후 **30일 유예 기간 내 재로그인 시 복구**. 30일이 지나면 개인정보를 파기한다. 유예 중 그룹 내 표시 방식은 account 작업지시서에서 정한다.
6. **계정 연결**: 한 사용자에 여러 로그인 수단을 연결한다. **이메일 일치로 자동 연결하지 않고**, 로그인된 상태에서 설정 화면으로만 연결한다. `(provider, subject)`는 `users`에서 분리해 `user_identities` 테이블로 옮긴다.

7. **Refresh 동시 사용**: 유예 시간(leeway) 10초. 이미 사용된 Refresh가 사용 후 10초 안에 오면 같은 family에 새 토큰을 추가 발급하고, 10초가 지나면 family 전체를 폐기한다. 사용 처리는 `UPDATE ... WHERE used_at IS NULL` 조건부 갱신으로 원자적으로 한다.
8. **탈퇴·복구 (2026-11-17 추가, 5번 보완)**: 탈퇴 유예 중 로그인하면 자동 복구하지 않고 확인 단계를 거친다(`ACCOUNT_PENDING_DELETION` 응답 → 소셜 토큰 재검증 후 `restore`). 탈퇴 요청 API는 auth 범위에 포함한다. 30일 후 파기 배치는 account 작업으로 미룬다.

## 결과·후속 작업
- 작업지시서: `docs/tasks/auth.md`(로그인·JWT·세션), `docs/tasks/account.md`는 auth 머지 후 작성(탈퇴·복구·계정 연결 UI).
- 스키마 변경(`V2`: `user_identities`, `refresh_tokens`)은 오너 승인 후 진행한다.
- `ARCHITECTURE.md`의 Redis(Refresh Token) 기술을 이 결정에 맞게 수정했다.
