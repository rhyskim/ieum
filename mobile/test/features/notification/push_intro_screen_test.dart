import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/core/push/push_service.dart';
import 'package:ieum_mobile/features/notification/data/device_repository_impl.dart';
import 'package:ieum_mobile/features/notification/domain/device_repository.dart';
import 'package:ieum_mobile/features/notification/presentation/push_intro_screen.dart';

class _FakePushService implements PushService {
  _FakePushService({this.granted = true});

  final bool granted;
  final String? token = 'fcm-token-1';
  int permissionRequests = 0;
  final StreamController<String> refresh = StreamController<String>.broadcast();

  @override
  Future<bool> requestPermission() async {
    permissionRequests++;
    return granted;
  }

  @override
  Future<String?> getToken() async => token;

  @override
  Stream<String> get onTokenRefresh => refresh.stream;

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

Widget _app(_FakePushService push, _FakeDeviceRepository devices) {
  final GoRouter router = GoRouter(
    initialLocation: '/notifications/intro',
    routes: <RouteBase>[
      GoRoute(path: '/', builder: (context, state) => const Text('홈 화면')),
      GoRoute(
        path: '/notifications/intro',
        builder: (context, state) => const PushIntroScreen(),
      ),
    ],
  );
  return ProviderScope(
    overrides: [
      pushServiceProvider.overrideWithValue(push),
      deviceRepositoryProvider.overrideWithValue(devices),
    ],
    child: MaterialApp.router(routerConfig: router),
  );
}

void main() {
  testWidgets('사전 안내 화면은 이유를 설명하고, 푸시가 없어도 승인 대기 목록으로 확인할 수 있다고 알리며, 버튼은 56dp 이상이다',
      (tester) async {
    await tester.pumpWidget(_app(_FakePushService(), _FakeDeviceRepository()));

    expect(find.textContaining('승인 대기'), findsOneWidget);
    expect(tester.getSize(find.widgetWithText(FilledButton, '알림 켜기')).height, greaterThanOrEqualTo(56));
    expect(tester.getSize(find.widgetWithText(TextButton, '나중에 할게요')).height, greaterThanOrEqualTo(56));
  });

  testWidgets('알림 켜기: 권한이 허용되면 토큰을 서버에 등록하고 홈으로 이동한다', (tester) async {
    final _FakePushService push = _FakePushService();
    final _FakeDeviceRepository devices = _FakeDeviceRepository();
    await tester.pumpWidget(_app(push, devices));

    await tester.tap(find.text('알림 켜기'));
    await tester.pumpAndSettle();

    expect(push.permissionRequests, 1);
    expect(devices.registered, <String>['fcm-token-1']);
    expect(find.text('홈 화면'), findsOneWidget);
  });

  testWidgets('권한을 거부하면 토큰을 등록하지 않고 그대로 홈으로 이동한다', (tester) async {
    final _FakeDeviceRepository devices = _FakeDeviceRepository();
    await tester.pumpWidget(_app(_FakePushService(granted: false), devices));

    await tester.tap(find.text('알림 켜기'));
    await tester.pumpAndSettle();

    expect(devices.registered, isEmpty);
    expect(find.text('홈 화면'), findsOneWidget);
  });

  testWidgets('나중에 할게요: 권한을 요청하지 않고 홈으로 이동한다', (tester) async {
    final _FakePushService push = _FakePushService();
    await tester.pumpWidget(_app(push, _FakeDeviceRepository()));

    await tester.tap(find.text('나중에 할게요'));
    await tester.pumpAndSettle();

    expect(push.permissionRequests, 0);
    expect(find.text('홈 화면'), findsOneWidget);
  });

  testWidgets('토큰 등록이 서버 오류로 실패해도 앱 사용은 막히지 않고 홈으로 이동한다', (tester) async {
    final _FakeDeviceRepository devices = _FakeDeviceRepository()
      ..error = const ApiException(code: 'INTERNAL_ERROR', message: '서버 오류');
    await tester.pumpWidget(_app(_FakePushService(), devices));

    await tester.tap(find.text('알림 켜기'));
    await tester.pumpAndSettle();

    expect(find.text('홈 화면'), findsOneWidget);
  });
}
