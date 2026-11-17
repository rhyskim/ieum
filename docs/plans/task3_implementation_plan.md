# [Task 3 구현 계획] Mobile (`mobile/`) 초기 구축 및 백엔드 정책/헬스체크 연동

## 1. Goal Description & Task Scope
Phase 0의 마지막 단계(Task 3: `PLAN.md`의 `0-3. Mobile 초기 구축` 및 `0-4. 무료/유료 전환 대비 Feature Entitlement 기반 모듈 작성` 클라이언트 파트)로, **Flutter (로컬 설치 버전 3.41.4 / Dart 3.11.1 호환 `sdk: ^3.5.0`) + Riverpod + GoRouter + Dio** 기반의 모바일 클라이언트(`mobile/`, Dart 패키지명 `ieum_mobile`, 앱 ID `com.ieum.app`) 골격을 구축합니다.

### 1.1 Phase 0 (Task 3) 범위와 후속 Phase(1~3)의 경계 명확화
- **본 Task 3 (Phase 0) 구현 범위**:
  1. **프로젝트 생성 및 앱 ID 고정**:
     - `flutter create --org com.ieum --project-name ieum_mobile --platforms=android,ios mobile` 실행 후 Android `namespace`/`applicationId` 및 iOS `PRODUCT_BUNDLE_IDENTIFIER`를 `com.ieum.app`으로 고정.
  2. **필수 패키지 구성 (`pubspec.yaml`)**:
     - `flutter_riverpod`, `riverpod_annotation`, `go_router`, `dio`, `freezed_annotation`, `json_annotation` 및 코드 제너레이션(`build_runner`, `riverpod_generator`, `freezed`, `json_serializable`).
     - *(참고: 로컬 Flutter 3.41.4 / Dart 3.11.1 환경에서 `flutter pub get` 실행 시 `analyzer` 및 `build_runner` 버전 충돌이 없도록 호환되는 최신 안정 버전을 해석하며, `flutter_secure_storage`는 `PLAN.md` 0-3에 따라 Phase 1-1 JWT 토큰 저장소 구현 시점에 추가합니다.)*
     - 코드 제너레이션 산출물(`*.g.dart`, `*.freezed.dart`)은 루트 `.gitignore`(`mobile/**/*.g.dart`, `mobile/**/*.freezed.dart`) 정책에 따라 Git에 커밋하지 않고 `dart run build_runner build --delete-conflicting-outputs`로 생성합니다.
  3. **네이티브 플랫폼 설정**:
     - Android: `main/AndroidManifest.xml`에는 `android:usesCleartextTraffic="false"`를 두고, `debug/AndroidManifest.xml`에 `xmlns:tools="http://schemas.android.com/tools"`와 `tools:replace="android:usesCleartextTraffic" android:usesCleartextTraffic="true"`를 선언하여 Debug 빌드에서만 로컬 HTTP(`10.0.2.2:8080`, `localhost:8080`)를 허용합니다.
     - iOS: `ios/Runner/Info.plist`의 `NSAppTransportSecurity`에 `NSAllowsLocalNetworking = true`를 설정(iOS 14+ 표준 로컬 개발 허용 플래그, 외부 도메인은 HTTPS 강제 유지)하고, Release 빌드에서는 `AppEnv.apiBaseUrl`이 `https://`가 아니면 즉시 예외를 던져 차단합니다.
     - 딥링크(`ieum://invite?code=842910`): Android `intent-filter` 및 iOS `CFBundleURLTypes`에 등록하고, `GoRouter`에 `/invite` 플레이스홀더 라우트(`InviteDeepLinkPlaceholderScreen` — Phase 1-2에서 실제 그룹 참여 플로우로 연결)를 등록하여 딥링크 진입 시 에러 라우트로 떨어지지 않도록 처리합니다.
  4. **부모 세대 접근성 테마 (`AppTheme`, `accessibilityConfigProvider`)**:
     - `PRD.md` §5.1(부모 모드 본문 `20sp+`, 제목 `24sp+`, 줄간격 `height: 1.5`, 터치 타깃 최소 높이 `56dp`) 및 `CLAUDE.md` §1.1(일반 모드 명도 대비 `4.5:1` 이상, 고대비 모드 WCAG AAA `7.0:1` 이상)을 준수하고 이중 스케일링(Double Scaling)을 방지한 반응형 테마 구축.
     - *(참고: Phase 0에서는 접근성 설정을 메모리 상태(`accessibilityConfigProvider`)로 유지하며, Phase 3에서 로컬 저장소 및 서버 `users.font_scale_mode`와 영속 동기화합니다.)*
  5. **음성 추상화 인터페이스, `Noop` 기본 구현체 및 DI 프로바이더 (`SttService`, `TtsService`)**:
     - `docs/ARCHITECTURE.md` §3.1과 일치하는 인터페이스, `NoopSttService` / `NoopTtsService` 기본 구현체, Riverpod DI 프로바이더(`sttServiceProvider`, `ttsServiceProvider`) 정의 (`CLAUDE.md` §1.2 준수).
  6. **클라이언트 `FeatureGate` 및 Phase 0 부트스트랩 화면 (`Phase0BootstrapScreen`)**:
     - 하드코딩 폴백 없이 `AsyncNotifier<FeatureEntitlement>`로 백엔드 `GET /api/v1/policies` 및 `GET /api/v1/health`를 호출하여 로딩/에러(재시도)/성공 상태를 표시하는 임시 검증 화면 구현(Phase 1 홈 화면 구현 시 교체).

---

## 2. Key Architectural Decisions

> [!IMPORTANT]
> **1. 에뮬레이터/시뮬레이터/실기기 네트워크 연결 및 Cleartext 보안 분리 (C1 해결)**
> - **환경별 기본 Base URL (`AppEnv.apiBaseUrl`)**:
>   - `--dart-define=API_BASE_URL=...`이 전달되면 최우선 사용합니다(실기기 테스트 시 `--dart-define=API_BASE_URL=http://<PC_LAN_IP>:8080` 사용).
>   - 미지정 시 `defaultTargetPlatform == TargetPlatform.android`이면 Android 에뮬레이터 호스트 루프백인 `http://10.0.2.2:8080`을, iOS 시뮬레이터(호스트 `localhost`를 직접 공유하므로 `adb reverse` 불필요) 및 호스트 테스트 환경에서는 `http://localhost:8080`을 반환합니다.
> - **Release 빌드 HTTPS 강제**: `kReleaseMode`에서 `apiBaseUrl`이 `https://`로 시작하지 않으면 `StateError`를 던져 평문 통신이 프로덕션에 배포되는 것을 원천 차단합니다.

> [!IMPORTANT]
> **2. `ApiResponse<T>` (`SUCCESS_CODE = "SUCCESS"`), `ServerHealth` 1:1 계약 및 Repository `unwrapOrThrow()` 검증 규칙 (C2 & M-A 해결)**
> - **`ApiResponse<T>` 성공 코드 계약**: Task 2 백엔드의 `ApiResponse.SUCCESS_CODE = "SUCCESS"`와 1:1로 일치하는 `ApiResponse.successCode = 'SUCCESS'`를 정의합니다.
> - **Repository `unwrapOrThrow()` 규칙 (`M-A`)**: `HealthRepositoryImpl`과 `EntitlementRepositoryImpl`은 HTTP 200 응답을 받았더라도 `ApiResponse<T>` 인스턴스 메서드인 `apiResponse.unwrapOrThrow(statusCode: response.statusCode)`를 호출하여, `code != ApiResponse.successCode`이면 서버가 내려준 `code`/`message`로, `data == null`이면 `'EMPTY_RESPONSE_DATA'` 코드로 즉시 `ApiException`을 던지도록 일관되게 처리합니다.
> - **`ServerHealth` 계약 고정**: Task 2의 `HealthCheckResponse`와 정확히 일치하는 5개 필드(`status`, `application`, `profile`, `timezone`, `serverTime`)로 정의합니다.

> [!IMPORTANT]
> **3. 부모 세대 접근성 타이포그래피 (`PRD.md` §5.1 준수), `Row` 안전 버튼 최소 크기, 명도 대비 검증 (M2 & Minor 1~2 해결)**
> - **타이포그래피 및 줄간격**:
>   - `FontScaleMode.normal` (자녀 기본): 본문(`bodyLarge`) `18.0sp`, 제목(`titleLarge`) `22.0sp`, 줄간격 `height: 1.5`
>   - `FontScaleMode.large` (부모님 기본): 본문 `20.0sp`, 제목 `24.0sp`, 줄간격 `height: 1.5`
>   - `FontScaleMode.xlarge` (부모님 아주 크게): 본문 `22.0sp`, 제목 `26.0sp`, 줄간격 `height: 1.5`
>   - `MaterialApp.router(builder: ...)`에서는 시스템 `MediaQuery.textScalerOf(context).clamp(minScaleFactor: 1.0, maxScaleFactor: 1.5)`를 적용하여 OS 접근성 큰 글씨 배율을 최대 `1.5x`(본문 기준 최대 `33sp`)까지 폭넓게 존중하되, `2.0x` 이상의 극단적 중복 확대로 인해 360dp 소형 기기에서 화면 밖으로 버튼이 밀려나는 현상만 안전하게 방지합니다.
> - **`Row` 내부에서도 안전한 버튼 `minimumSize` (`Minor 2`)**:
>   - 테마의 버튼 `minimumSize`에 `Size(double.infinity, 56)`을 전역 설정하면 `Row` 내부에 버튼을 배치할 때 무한 너비 제약(`BoxConstraints forces an infinite width`) 오류가 발생할 수 있습니다.
>   - 따라서 전역 테마의 `minimumSize`는 **`const Size(88, 56)`** (최소 너비 88dp, 최소 높이 56dp + 가변 패딩 `EdgeInsets.symmetric(horizontal: 20, vertical: 14)`)로 설정하여 `Row`와 `Column` 모두에서 안전하게 동작하게 하고, 화면 하단 주요 행동 버튼은 위젯에서 `SizedBox(width: double.infinity, child: ...)`로 감싸 전체 너비(Full-width)를 적용합니다.
> - **명도 대비 보장 (`Minor 1`)**:
>   - **일반 모드**: 배경 `#FAF8F5`(따뜻한 아이보리), 본문 텍스트 `#1F1D1A`(대비율 `15.6:1 >= 4.5:1`), 주요 버튼 배경 `#8C3B2B` + 텍스트 `#FFFFFF`(대비율 `7.2:1 >= 4.5:1`)로 `CLAUDE.md` §1.1의 `4.5:1` 이상을 충족합니다.
>   - **고대비 모드**: 배경 `#FFFFFF`, 본문 `#111111`(대비율 `18.8:1 >= 7.0:1`), 주요 버튼 배경 `#7A1C00` + 텍스트 `#FFFFFF`(대비율 `10.4:1 >= 7.0:1`)로 **WCAG AAA (`7.0:1` 이상)**를 충족합니다.

---

## 3. Proposed Changes

### 3.1 디렉토리 구조 (`mobile/`)

```text
mobile/
├── pubspec.yaml
├── analysis_options.yaml
├── android/
│   └── app/src/
│       ├── debug/AndroidManifest.xml        # Debug 전용 tools:replace="android:usesCleartextTraffic" android:usesCleartextTraffic="true"
│       └── main/AndroidManifest.xml         # usesCleartextTraffic="false" 및 ieum://invite 딥링크
├── ios/
│   └── Runner/Info.plist                    # NSAllowsLocalNetworking=true 및 ieum:// URL Scheme
├── lib/
│   ├── main.dart
│   ├── app.dart
│   ├── core/
│   │   ├── config/
│   │   │   └── app_env.dart
│   │   ├── network/
│   │   │   ├── api_response.dart            # ApiResponse<T> 및 unwrapOrThrow() 포함
│   │   │   ├── api_exception.dart
│   │   │   ├── auth_token_reader.dart       # Phase 1 JWT 주입용 Seam 인터페이스
│   │   │   └── dio_provider.dart            # timeout, debug 로깅, onDispose(dio.close) 포함
│   │   ├── router/
│   │   │   └── app_router.dart              # '/' (Phase0BootstrapScreen) 및 '/invite' 플레이스홀더 라우트
│   │   ├── theme/
│   │   │   ├── accessibility_config.dart    # FontScaleMode 및 AccessibilityConfig (@freezed)
│   │   │   ├── accessibility_notifier.dart  # accessibilityConfigProvider 정의 (ARCHITECTURE §2.1 일치)
│   │   │   ├── app_colors.dart              # 일반 모드(>=4.5:1) 및 고대비 모드(>=7.0:1) 팔레트
│   │   │   └── app_theme.dart
│   │   └── voice/
│   │       ├── stt_recognition_result.dart
│   │       ├── stt_service.dart             # SttService 인터페이스 및 NoopSttService 구현체 포함
│   │       ├── tts_service.dart             # TtsService 인터페이스 및 NoopTtsService 구현체 포함
│   │       └── voice_providers.dart         # sttServiceProvider, ttsServiceProvider (DI)
│   └── features/
│       ├── entitlement/
│       │   ├── domain/
│       │   │   ├── feature_entitlement.dart
│       │   │   ├── feature_gate.dart
│       │   │   └── entitlement_repository.dart
│       │   ├── data/
│       │   │   └── entitlement_repository_impl.dart
│       │   └── presentation/
│       │       └── entitlement_notifier.dart
│       └── bootstrap/                       # Phase 0 검증 전용 (Phase 1 홈 구현 시 대체)
│           ├── domain/
│           │   ├── server_health.dart
│           │   └── health_repository.dart
│           ├── data/
│           │   └── health_repository_impl.dart
│           └── presentation/
│               ├── health_notifier.dart
│               ├── phase0_bootstrap_screen.dart
│               └── widgets/
│                   ├── accessibility_control_card.dart
│                   ├── server_health_card.dart
│                   └── entitlement_policy_card.dart
└── test/
    ├── core/
    │   ├── network/dio_error_mapping_test.dart
    │   └── theme/accessibility_theme_test.dart
    └── features/
        ├── entitlement/entitlement_repository_test.dart
        └── bootstrap/
            ├── health_repository_test.dart
            └── phase0_bootstrap_screen_test.dart
```

---

### 3.2 `pubspec.yaml` 및 `analysis_options.yaml` 전문

#### [NEW] [mobile/pubspec.yaml](file:///c:/Users/user/Desktop/Project_Idea/for-family/mobile/pubspec.yaml)
```yaml
name: ieum_mobile
description: "이음 (Ieum) — 부모님과 함께하는 하루 한 질문 기록 앱"
publish_to: 'none'
version: 0.1.0+1

environment:
  sdk: ^3.5.0

dependencies:
  flutter:
    sdk: flutter
  flutter_riverpod: ^2.6.1
  riverpod_annotation: ^2.6.1
  go_router: ^14.6.2
  dio: ^5.7.0
  freezed_annotation: ^2.4.4
  json_annotation: ^4.9.0

dev_dependencies:
  flutter_test:
    sdk: flutter
  flutter_lints: ^5.0.0
  build_runner: ^2.4.13
  riverpod_generator: ^2.6.3
  freezed: ^2.5.7
  json_serializable: ^6.9.0

flutter:
  uses-material-design: true
```

#### [NEW] [mobile/analysis_options.yaml](file:///c:/Users/user/Desktop/Project_Idea/for-family/mobile/analysis_options.yaml)
```yaml
include: package:flutter_lints/flutter.yaml

analyzer:
  errors:
    invalid_annotation_target: ignore
  exclude:
    - "**/*.g.dart"
    - "**/*.freezed.dart"

linter:
  rules:
    prefer_const_constructors: true
    prefer_final_locals: true
    always_declare_return_types: true
```

---

### 3.3 Core 네트워크: `ApiResponse<T>` (`unwrapOrThrow`) 및 Repository 구현 (`M-A` 해결)

#### [NEW] `lib/core/network/api_response.dart`
```dart
@Freezed(genericArgumentFactories: true)
abstract class ApiResponse<T> with _$ApiResponse<T> {
  const ApiResponse._();

  const factory ApiResponse({
    required String code,
    required String message,
    T? data,
    required DateTime timestamp,
  }) = _ApiResponse<T>;

  /// Task 2 백엔드 ApiResponse.SUCCESS_CODE = "SUCCESS" 와 1:1 계약 고정
  static const String successCode = 'SUCCESS';

  factory ApiResponse.fromJson(
    Map<String, dynamic> json,
    T Function(Object? json) fromJsonT,
  ) => _$ApiResponseFromJson(json, fromJsonT);

  /// code != 'SUCCESS' 이거나 data == null 이면 즉시 ApiException 발생
  T unwrapOrThrow({int? statusCode}) {
    if (code != successCode) {
      throw ApiException(
        code: code,
        message: message,
        statusCode: statusCode,
      );
    }
    final T? payload = data;
    if (payload == null) {
      throw ApiException(
        code: 'EMPTY_RESPONSE_DATA',
        message: '서버 응답 데이터가 비어 있습니다.',
        statusCode: statusCode,
      );
    }
    return payload;
  }
}
```

#### [NEW] `lib/features/bootstrap/data/health_repository_impl.dart` & `entitlement_repository_impl.dart`
```dart
class HealthRepositoryImpl implements HealthRepository {
  const HealthRepositoryImpl(this._dio);
  final Dio _dio;

  @override
  Future<ServerHealth> fetchHealth() async {
    try {
      final response = await _dio.get<Map<String, dynamic>>('/api/v1/health');
      final body = response.data;
      if (body == null) {
        throw ApiException(
          code: 'EMPTY_BODY',
          message: '서버 응답 본문이 비어 있습니다.',
          statusCode: response.statusCode,
        );
      }
      final apiResponse = ApiResponse<ServerHealth>.fromJson(
        body,
        (json) => ServerHealth.fromJson(json! as Map<String, dynamic>),
      );
      return apiResponse.unwrapOrThrow(statusCode: response.statusCode);
    } on DioException catch (e) {
      throw ApiException.fromDioException(e);
    }
  }
}
```
*(동일한 패턴으로 `EntitlementRepositoryImpl.fetchEntitlementPolicy()`도 `GET /api/v1/policies` 호출 후 `apiResponse.unwrapOrThrow()`를 수행합니다.)*

---

### 3.4 음성 추상화 인터페이스, `Noop` 기본 구현체 및 DI 프로바이더 (`M-B` 해결)

#### [NEW] `lib/core/voice/stt_recognition_result.dart`, `stt_service.dart`, `tts_service.dart`, `voice_providers.dart`
```dart
// lib/core/voice/stt_recognition_result.dart
@freezed
abstract class SttRecognitionResult with _$SttRecognitionResult {
  const factory SttRecognitionResult({
    required String text,
    required bool isFinal,
    double? confidence,
  }) = _SttRecognitionResult;
}

// lib/core/voice/stt_service.dart
abstract interface class SttService {
  Future<bool> initialize();
  Stream<SttRecognitionResult> startListening({
    Duration maxDuration = const Duration(minutes: 3),
  });
  Future<void> stopListening();
}

class NoopSttService implements SttService {
  const NoopSttService();

  @override
  Future<bool> initialize() async => false;

  @override
  Stream<SttRecognitionResult> startListening({
    Duration maxDuration = const Duration(minutes: 3),
  }) => const Stream<SttRecognitionResult>.empty();

  @override
  Future<void> stopListening() async {}
}

// lib/core/voice/tts_service.dart
abstract final class TtsSpeechRate {
  static const double normal = 1.0;
  static const double slow = 0.85;
}

abstract interface class TtsService {
  Future<void> initialize();
  Future<void> speak(String text, {double speechRate = TtsSpeechRate.normal});
  Future<void> stop();
}

class NoopTtsService implements TtsService {
  const NoopTtsService();

  @override
  Future<void> initialize() async {}

  @override
  Future<void> speak(String text, {double speechRate = TtsSpeechRate.normal}) async {}

  @override
  Future<void> stop() async {}
}

// lib/core/voice/voice_providers.dart
@riverpod
SttService sttService(SttServiceRef ref) => const NoopSttService();

@riverpod
TtsService ttsService(TtsServiceRef ref) => const NoopTtsService();
```

---

### 3.5 접근성 모델 (`AccessibilityConfig`) 및 Notifier (`accessibilityConfigProvider`, `M-B` 해결)

#### [NEW] `lib/core/theme/accessibility_config.dart`
```dart
@JsonEnum(alwaysCreate: true)
enum FontScaleMode {
  @JsonValue('NORMAL')
  normal(bodySize: 18.0, titleSize: 22.0, label: '보통 (자녀 기본)'),
  @JsonValue('LARGE')
  large(bodySize: 20.0, titleSize: 24.0, label: '크게 (부모님 권장)'),
  @JsonValue('XLARGE')
  xlarge(bodySize: 22.0, titleSize: 26.0, label: '아주 크게');

  const FontScaleMode({
    required this.bodySize,
    required this.titleSize,
    required this.label,
  });

  final double bodySize;
  final double titleSize;
  final String label;

  bool get isSeniorMode => this != FontScaleMode.normal;
}

@freezed
abstract class AccessibilityConfig with _$AccessibilityConfig {
  const factory AccessibilityConfig({
    @Default(FontScaleMode.normal) FontScaleMode fontScaleMode,
    @Default(false) bool highContrastEnabled,
  }) = _AccessibilityConfig;

  factory AccessibilityConfig.fromJson(Map<String, dynamic> json) =>
      _$AccessibilityConfigFromJson(json);
}
```

#### [NEW] `lib/core/theme/accessibility_notifier.dart`
- `riverpod_generator ^2.6.x`에서 `class AccessibilityConfigNotifier`는 `accessibilityConfigNotifierProvider`를 생성하므로, `ARCHITECTURE.md` §2.1의 표준 명칭인 `accessibilityConfigProvider`와 100% 호환되도록 명시적 top-level provider 별칭(`final accessibilityConfigProvider = accessibilityConfigNotifierProvider;`)을 함께 선언합니다.
```dart
@Riverpod(keepAlive: true)
class AccessibilityConfigNotifier extends _$AccessibilityConfigNotifier {
  @override
  AccessibilityConfig build() => const AccessibilityConfig();

  void setFontScaleMode(FontScaleMode mode) {
    state = state.copyWith(fontScaleMode: mode);
  }

  void toggleHighContrast(bool enabled) {
    state = state.copyWith(highContrastEnabled: enabled);
  }

  /// 부모님 역할 선택 시 큰 글씨 + 고대비 기본 활성화
  void applyParentDefaultPreset() {
    state = const AccessibilityConfig(
      fontScaleMode: FontScaleMode.large,
      highContrastEnabled: true,
    );
  }
}

/// ARCHITECTURE.md §2.1 표준 프로바이더 명칭 별칭 고정
final accessibilityConfigProvider = accessibilityConfigNotifierProvider;
```

#### [NEW] `mobile/android/app/src/debug/AndroidManifest.xml` (`<application>` 태그 Cleartext 오버라이드)
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <application
        android:usesCleartextTraffic="true"
        tools:replace="android:usesCleartextTraffic" />
</manifest>
```

---

### 3.6 `GoRouter` 설정 (`lib/core/router/app_router.dart` — `/invite` 딥링크 라우트 포함, `Minor 5` 해결)
```dart
@riverpod
GoRouter appRouter(AppRouterRef ref) {
  return GoRouter(
    initialLocation: '/',
    routes: [
      GoRoute(
        path: '/',
        builder: (context, state) => const Phase0BootstrapScreen(),
      ),
      GoRoute(
        path: '/invite',
        builder: (context, state) {
          final code = state.uri.queryParameters['code'] ?? '';
          // Phase 1-2에서 실제 그룹 초대 참여 화면으로 교체될 플레이스홀더
          return Scaffold(
            appBar: AppBar(title: const Text('가족 초대 연결')),
            body: Center(child: Text('초대 코드: $code (Phase 1에서 연결됩니다)')),
          );
        },
      ),
    ],
  );
}
```

---

## 4. Verification Plan

### 4.1 Automated Verification (`flutter analyze` & `flutter test`)
1. **패키지 설치 및 코드 생성**:
   - `cd mobile && flutter pub get`
   - `dart run build_runner build --delete-conflicting-outputs`
2. **정적 분석**:
   - `flutter analyze` (No issues found 검증)
3. **자동화 단위 및 위젯 테스트 (`flutter test`)**:
   - **`dio_error_mapping_test.dart`, `health_repository_test.dart`, `entitlement_repository_test.dart`**:
     - 커스텀 Mock `HttpClientAdapter`를 주입하여 ① `200 OK` 정상 `ApiResponse` 파싱, ② `200 OK`이지만 `code != "SUCCESS"` 또는 `data == null`일 때 `unwrapOrThrow()`의 `ApiException` 변환, ③ `401/500 ApiResponse` 에러 매핑, ④ `connectionTimeout` 타임아웃 매핑, ⑤ `connectionError`(서버 다운) 매핑, ⑥ `502` 비-JSON 응답 매핑을 모두 검증.
   - **`accessibility_theme_test.dart`**:
     - `FontScaleMode.normal`(`18sp`/`22sp`), `large`(`20sp`/`24sp`), `xlarge`(`22sp`/`26sp`) 전환 시 폰트 크기와 줄간격(`height == 1.5`), 버튼 `minimumSize`(`Size(88, 56)`) 검증.
     - **일반 모드 명도 대비 검증**: `ColorScheme`의 전경/배경 쌍 대비율이 `CLAUDE.md` §1.1 기준인 **`4.5:1` 이상**인지 `computeLuminance()`로 계산 검증.
     - **고대비 모드 명도 대비 검증**: `highContrastEnabled == true`일 때 전경/배경 쌍 대비율이 **WCAG AAA 기준인 `7.0:1` 이상**인지 `computeLuminance()`로 계산 검증.
   - **`phase0_bootstrap_screen_test.dart`**:
     - Provider Override를 통해 `AsyncLoading`, `AsyncError`(에러 메시지 및 `[🔄 다시 연결 확인]` 재시도 버튼 탭 동작), `AsyncData`(`UP`, `Asia/Seoul`, `180초`, `30일` 표시) 3가지 상태를 모두 검증.
     - 접근성 모드 버튼(`Row` 내부 배치 포함) 탭 시 무한 너비 제약 오류 없이 텍스트 크기가 즉시 변경되고 모든 아이콘 버튼에 한글 텍스트 라벨이 함께 존재하는지 검증.
     - **오버플로우 스트레스 테스트**: 화면 크기를 `360x800`dp로 설정하고 OS `TextScaler.linear(2.0)` + `FontScaleMode.xlarge`를 동시에 켠 상태에서 `RenderFlex overflow` 예외가 0건인지 검증.

### 4.2 Manual DoD Verification Checklist
- 루트에서 `docker compose up -d` → `cd backend && ./gradlew bootRun` → `cd mobile && flutter run` (Android 에뮬레이터) 실행 시 화면에 백엔드 헬스체크(`UP`)와 권한 정책(`FREE`, `180초`, `30일`)이 실시간으로 표시되는지 확인.
- *(환경 참고: 현재 호스트 OS가 Windows이므로 Android 에뮬레이터 및 Windows 호스트 테스트를 기준으로 검증하며, iOS 시뮬레이터 빌드는 추후 macOS 환경/CI에서 검증합니다.)*
