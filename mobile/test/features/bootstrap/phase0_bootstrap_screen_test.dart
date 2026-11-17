import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ieum_mobile/app.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/core/theme/accessibility_config.dart';
import 'package:ieum_mobile/core/theme/accessibility_notifier.dart';
import 'package:ieum_mobile/features/bootstrap/data/health_repository_impl.dart';
import 'package:ieum_mobile/features/bootstrap/domain/health_repository.dart';
import 'package:ieum_mobile/features/bootstrap/domain/server_health.dart';
import 'package:ieum_mobile/features/entitlement/data/entitlement_repository_impl.dart';
import 'package:ieum_mobile/features/entitlement/domain/entitlement_repository.dart';
import 'package:ieum_mobile/features/entitlement/domain/feature_entitlement.dart';

class _FakeHealthRepository implements HealthRepository {
  _FakeHealthRepository({required this.onFetch});
  final Future<ServerHealth> Function() onFetch;

  @override
  Future<ServerHealth> fetchHealth() => onFetch();
}

class _FakeEntitlementRepository implements EntitlementRepository {
  _FakeEntitlementRepository({required this.onFetch});
  final Future<FeatureEntitlement> Function() onFetch;

  @override
  Future<FeatureEntitlement> fetchEntitlementPolicy() => onFetch();
}

void main() {
  final sampleHealth = ServerHealth(
    status: 'UP',
    application: 'ieum-backend',
    profile: 'local',
    timezone: 'Asia/Seoul',
    serverTime: DateTime.utc(2026, 11, 2, 12),
  );

  const sampleEntitlement = FeatureEntitlement(
    currentPlan: 'FREE',
    canAttachOriginalVoice: true,
    maxVoiceDurationSeconds: 180,
    voiceServerRetentionDays: 30,
    canDownloadVoiceToLocal: true,
    sttEngineType: 'ON_DEVICE',
  );

  group('Phase0BootstrapScreen 위젯 및 접근성 스트레스 테스트', () {
    testWidgets('정상 데이터 수신 시 UP, Asia/Seoul, 180초, 30일 정책을 화면에 표시한다', (tester) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            healthRepositoryProvider.overrideWithValue(
              _FakeHealthRepository(onFetch: () async => sampleHealth),
            ),
            entitlementRepositoryProvider.overrideWithValue(
              _FakeEntitlementRepository(onFetch: () async => sampleEntitlement),
            ),
          ],
          child: const IeumApp(),
        ),
      );

      await tester.pumpAndSettle();

      expect(find.text('상태: UP'), findsOneWidget);
      expect(find.textContaining('Asia/Seoul'), findsOneWidget);
      expect(find.text('현재 플랜: FREE'), findsOneWidget);
      expect(find.text('최대 녹음 시간: 180초'), findsOneWidget);
      expect(find.textContaining('30일'), findsOneWidget);
    });

    testWidgets('네트워크 에러 시 안내 메시지와 재시도 버튼을 표시하고 탭하면 복구된다', (tester) async {
      bool shouldFail = true;

      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            healthRepositoryProvider.overrideWithValue(
              _FakeHealthRepository(
                onFetch: () async {
                  if (shouldFail) {
                    throw const ApiException(
                      code: 'NETWORK_CONNECTION_ERROR',
                      message: '서버에 연결할 수 없습니다.',
                    );
                  }
                  return sampleHealth;
                },
              ),
            ),
            entitlementRepositoryProvider.overrideWithValue(
              _FakeEntitlementRepository(onFetch: () async => sampleEntitlement),
            ),
          ],
          child: const IeumApp(),
        ),
      );

      await tester.pumpAndSettle();
      expect(find.text('서버에 연결할 수 없습니다.'), findsOneWidget);
      expect(find.byKey(const ValueKey('retry_health_button')), findsOneWidget);

      shouldFail = false;
      final retryFinder = find.byKey(const ValueKey('retry_health_button'));
      await tester.ensureVisible(retryFinder);
      await tester.pumpAndSettle();
      await tester.tap(retryFinder);
      await tester.pumpAndSettle();

      expect(find.text('상태: UP'), findsOneWidget);
    });

    testWidgets('360x800 소형 화면 + OS TextScaler 2.0x + XLARGE + 고대비 모드에서도 오버플로우가 0건이다', (tester) async {
      tester.view.physicalSize = const Size(360, 800);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(() {
        tester.view.resetPhysicalSize();
        tester.view.resetDevicePixelRatio();
      });

      final container = ProviderContainer(
        overrides: [
          healthRepositoryProvider.overrideWithValue(
            _FakeHealthRepository(onFetch: () async => sampleHealth),
          ),
          entitlementRepositoryProvider.overrideWithValue(
            _FakeEntitlementRepository(onFetch: () async => sampleEntitlement),
          ),
        ],
      );
      addTearDown(container.dispose);

      container.read(accessibilityConfigProvider.notifier).setFontScaleMode(FontScaleMode.xlarge);
      container.read(accessibilityConfigProvider.notifier).toggleHighContrast(true);

      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: const MediaQuery(
            data: MediaQueryData(
              size: Size(360, 800),
              textScaler: TextScaler.linear(2.0),
            ),
            child: IeumApp(),
          ),
        ),
      );

      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(find.text('상태: UP'), findsOneWidget);
      expect(find.text('최대 녹음 시간: 180초'), findsOneWidget);
    });
  });
}
