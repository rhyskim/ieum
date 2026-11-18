import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/core/push/push_service.dart';
import 'package:ieum_mobile/features/auth/data/auth_repository_impl.dart';
import 'package:ieum_mobile/features/auth/domain/auth_models.dart';
import 'package:ieum_mobile/features/auth/domain/auth_repository.dart';
import 'package:ieum_mobile/features/auth/presentation/login_screen.dart';
import 'package:ieum_mobile/features/notification/application/push_registrar.dart';
import 'package:ieum_mobile/features/notification/data/device_repository_impl.dart';
import 'package:ieum_mobile/features/notification/domain/device_repository.dart';

class _FakePushService implements PushService {
  _FakePushService({required this.granted, this.statusError});

  final bool granted;
  final Object? statusError;
  int permissionRequests = 0;

  @override
  Future<bool> isPermissionGranted() async {
    if (statusError != null) throw statusError!;
    return granted;
  }

  @override
  Future<bool> requestPermission() async {
    permissionRequests++;
    return granted;
  }

  @override
  Future<String?> getToken() async => 'fcm-token-1';

  @override
  Stream<String> get onTokenRefresh => const Stream<String>.empty();

  @override
  Future<Map<String, String>?> initialMessage() async => null;

  @override
  Stream<Map<String, String>> get onMessageTap => const Stream<Map<String, String>>.empty();
}

class _FakeDeviceRepository implements DeviceRepository {
  final List<String> registered = <String>[];
  ApiException? error;

  @override
  Future<void> register(String fcmToken, DevicePlatform platform) async {
    if (error != null) throw error!;
    registered.add(fcmToken);
  }
}

class _FakeAuthRepository implements AuthRepository {
  @override
  Future<bool> login(SocialProvider provider, String socialToken, {String? deviceLabel}) async =>
      false;

  @override
  Future<void> logout() async {}

  @override
  Future<List<DeviceSession>> fetchSessions() async => <DeviceSession>[];

  @override
  Future<void> revokeSession(String sessionId) async {}
}

class _FakeTokenSource implements SocialTokenSource {
  @override
  Future<String> obtainToken(SocialProvider provider) async => 'social-token';
}

ProviderContainer _container(_FakePushService push, _FakeDeviceRepository devices) {
  final ProviderContainer container = ProviderContainer(
    overrides: [
      pushServiceProvider.overrideWithValue(push),
      deviceRepositoryProvider.overrideWithValue(devices),
    ],
  );
  addTearDown(container.dispose);
  return container;
}

Widget _loginApp(_FakePushService push, _FakeDeviceRepository devices) {
  final GoRouter router = GoRouter(
    initialLocation: '/login',
    routes: <RouteBase>[
      GoRoute(path: '/', builder: (context, state) => const Text('홈 화면')),
      GoRoute(path: '/login', builder: (context, state) => const LoginScreen()),
      GoRoute(
        path: '/notifications/intro',
        builder: (context, state) => const Text('알림 안내 화면'),
      ),
    ],
  );
  return ProviderScope(
    overrides: [
      pushServiceProvider.overrideWithValue(push),
      deviceRepositoryProvider.overrideWithValue(devices),
      authRepositoryProvider.overrideWithValue(_FakeAuthRepository()),
      socialTokenSourceProvider.overrideWithValue(_FakeTokenSource()),
    ],
    child: MaterialApp.router(routerConfig: router),
  );
}

void main() {
  group('PushRegistrar.registerIfGranted', () {
    test('OS 알림 권한이 이미 허용이면 권한 팝업 없이 현재 토큰을 등록한다', () async {
      final _FakePushService push = _FakePushService(granted: true);
      final _FakeDeviceRepository devices = _FakeDeviceRepository();

      final bool granted = await _container(push, devices).read(pushRegistrarProvider).registerIfGranted();

      expect(granted, isTrue);
      expect(devices.registered, <String>['fcm-token-1']);
      expect(push.permissionRequests, 0);
    });

    test('허용 전이면 등록도 권한 요청도 하지 않고 false를 돌려준다', () async {
      final _FakePushService push = _FakePushService(granted: false);
      final _FakeDeviceRepository devices = _FakeDeviceRepository();

      final bool granted = await _container(push, devices).read(pushRegistrarProvider).registerIfGranted();

      expect(granted, isFalse);
      expect(devices.registered, isEmpty);
      expect(push.permissionRequests, 0);
    });

    test('권한 조회나 서버 등록이 실패해도 예외를 던지지 않는다', () async {
      final _FakeDeviceRepository failing = _FakeDeviceRepository()
        ..error = const ApiException(code: 'INTERNAL_ERROR', message: '서버 오류');
      expect(
        await _container(_FakePushService(granted: true), failing)
            .read(pushRegistrarProvider)
            .registerIfGranted(),
        isTrue,
      );
      expect(
        await _container(_FakePushService(granted: true, statusError: StateError('x')), _FakeDeviceRepository())
            .read(pushRegistrarProvider)
            .registerIfGranted(),
        isFalse,
      );
    });
  });

  group('로그인 직후 화면 분기', () {
    testWidgets('권한이 이미 허용이면 안내 화면 없이 토큰을 조용히 등록하고 홈으로 간다', (tester) async {
      final _FakeDeviceRepository devices = _FakeDeviceRepository();
      await tester.pumpWidget(_loginApp(_FakePushService(granted: true), devices));

      await tester.tap(find.text('카카오로 시작하기'));
      await tester.pumpAndSettle();

      expect(devices.registered, <String>['fcm-token-1']);
      expect(find.text('홈 화면'), findsOneWidget);
      expect(find.text('알림 안내 화면'), findsNothing);
    });

    testWidgets('권한이 허용 전이면 등록하지 않고 알림 안내 화면으로 간다', (tester) async {
      final _FakeDeviceRepository devices = _FakeDeviceRepository();
      await tester.pumpWidget(_loginApp(_FakePushService(granted: false), devices));

      await tester.tap(find.text('카카오로 시작하기'));
      await tester.pumpAndSettle();

      expect(devices.registered, isEmpty);
      expect(find.text('알림 안내 화면'), findsOneWidget);
    });
  });
}
