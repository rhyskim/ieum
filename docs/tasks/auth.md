# Task: auth

- 브랜치: `feat/auth` / worktree: `.worktrees/auth`
- 대상: backend + mobile (PLAN 1-1 중 로그인·JWT·세션)
- 근거: `docs/adr/0001-auth-jwt.md` (확정). 이 문서와 ADR이 어긋나면 구현하지 말고 Root에 묻는다.

## 목표
카카오·네이버·Apple 토큰을 서버가 검증해 자체 Access/Refresh Token을 발급하고, 기기별 세션을 관리한다. 앱은 토큰을 안전하게 저장하고 만료 시 자동 갱신한다.

## 범위
Backend
- `POST /api/v1/auth/{provider}`: 소셜 토큰 검증(제공자 서버에 직접 확인, 우리 앱용 토큰인지 검증) → `user_identities`로 조회, 없으면 신규 가입 → 토큰 발급. 신규 여부를 응답에 포함한다.
- `POST /api/v1/auth/refresh`: Rotation. 이미 사용된 Refresh가 오면 같은 family 전체 폐기 후 401.
- `POST /api/v1/auth/logout`(현재 기기), `GET /api/v1/auth/sessions`(기기 목록), `DELETE /api/v1/auth/sessions/{id}`(원격 로그아웃).
- JWT 인증 필터가 `CurrentUser(userId, membershipTier)`를 SecurityContext에 넣는다. 기존 `@GroupMemberOnly`가 동작해야 한다.
- Flyway `V2`: `user_identities(user_id, provider, subject, email, UNIQUE(provider, subject))`, `refresh_tokens(id, user_id, family_id, token_hash, device_label, expires_at, used_at, revoked_at, last_used_at, created_at)`. 기존 `users`의 `oauth_*` 컬럼 이전 방식은 Root 승인 후 확정.
- Apple: 첫 로그인의 이름·이메일을 저장한다.
- 오류 응답: 카카오·네이버·Apple 장애/타임아웃은 503, 위조·만료 토큰은 401 `INVALID_SOCIAL_TOKEN`, Access 만료는 401 `TOKEN_EXPIRED`. 토큰 원문을 로그에 남기지 않는다.
- 동시 가입 요청은 DB 유니크 제약 위반을 잡아 재조회로 처리한다.

Mobile
- `flutter_secure_storage`로 토큰 저장. `NoopAuthTokenReader`를 실제 구현으로 교체.
- Dio 인터셉터: 401 `TOKEN_EXPIRED` → 갱신 **1회만**(동시 요청은 대기) → 원 요청 재시도.
- 로그인 화면(카카오·네이버·Apple 버튼, 한글 라벨)과 기기 목록 화면. 접근성 규칙(루트 CLAUDE.md §5) 준수.

## 범위 밖
프로필 온보딩 UI, 탈퇴·30일 복구, 계정 연결(`docs/tasks/account.md`), 위치(IP) 수집, FCM 토큰 연동.

## 수용 기준 (테스트로 표현)
- [ ] 유효한 소셜 토큰(제공자 호출은 stub)으로 신규 가입과 기존 로그인이 각각 성공하고 신규 여부가 구분된다.
- [ ] 다른 앱용 토큰, 위조 토큰은 401, 제공자 타임아웃은 503.
- [ ] 동일 `(provider, subject)`로 동시 가입 2건 → 사용자 1명, 둘 다 성공 응답.
- [ ] Refresh 갱신 시 이전 토큰이 무효가 되고, 사용된 토큰 재사용 시 family 전체가 폐기된다.
- [ ] DB에는 Refresh 원문이 없고 해시만 있다.
- [ ] 기기 A 로그아웃이 기기 B 세션에 영향을 주지 않는다. 다른 사용자의 세션은 조회·삭제할 수 없다.
- [ ] JWT 없이 보호 API 호출 시 401, 유효 JWT면 `@GroupMemberOnly` 검사가 동작한다.
- [ ] 모바일: 동시 401 3건이 갱신 요청 1회만 만든다(테스트로 증명).

## 관련 ADR·문서
`docs/adr/0001-auth-jwt.md`, `docs/DATA_MODEL.md`, `docs/PRD.md` FR-1
