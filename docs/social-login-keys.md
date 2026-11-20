# 소셜 로그인 키 주입 방법

키·시크릿은 **커밋하지 않는다**. 앱은 빌드 시 `--dart-define`으로, 서버는 환경변수로 받는다. 키가 비어 있으면 앱은 정상 기동하고, 해당 제공자 로그인 버튼이 "소셜 로그인 설정이 아직 연결되지 않았습니다." 안내를 보인다(`SOCIAL_SDK_NOT_CONFIGURED`).

## 모바일 (`--dart-define`)
| 키 | 용도 | 비고 |
|---|---|---|
| `KAKAO_NATIVE_APP_KEY` | 카카오 SDK 초기화 + Android 리다이렉트 스킴 `kakao<키>` | Gradle이 `dart-defines`에서 읽어 매니페스트에 넣는다 |
| `NAVER_CLIENT_ID` / `NAVER_CLIENT_SECRET` | 네이버 SDK 초기화 | 둘 다 있어야 활성 |
| `NAVER_CLIENT_NAME` | 네이버 동의 화면의 앱 이름 | 기본 `이음` |
| `NAVER_URL_SCHEME` | iOS 전용 | Android에서는 불필요(iOS 출시 때) |

로컬 실행은 gitignore된 파일을 쓴다.

```json
// mobile/dart_defines.local.json (커밋 금지, .gitignore에 등록됨)
{
  "KAKAO_NATIVE_APP_KEY": "...",
  "NAVER_CLIENT_ID": "...",
  "NAVER_CLIENT_SECRET": "..."
}
```

```bash
cd mobile && flutter run --dart-define-from-file=dart_defines.local.json
```

## 서버 (환경변수)
| 변수 | 용도 |
|---|---|
| `KAKAO_APP_ID` | 카카오 앱 ID(숫자). 토큰의 `app_id`와 비교해 다른 앱 토큰을 거절한다. 비어 있으면 모든 카카오 로그인이 거절된다 |

네이버는 서버에 키가 필요 없다(프로필 API 응답으로 유효성만 확인).

## 개발자가 콘솔에서 할 일
- 카카오 디벨로퍼스: 앱 생성 → 네이티브 앱 키 발급, Android 플랫폼에 패키지명 `com.ieum.app`과 키 해시 등록, 카카오 로그인 활성화. (서버용 `KAKAO_APP_ID`는 앱 ID)
- 네이버 개발자센터: 애플리케이션 등록 → Client ID/Secret, Android 환경에 패키지명 `com.ieum.app` 추가. 검수 승인 전에는 등록한 테스트 계정만 로그인된다(ADR-0005).
- Apple은 이번 범위 밖(iOS 출시 시).
