import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/core/push/push_routing.dart';
import 'package:ieum_mobile/core/push/push_service.dart';
import 'package:ieum_mobile/core/router/app_router.dart';
import 'package:ieum_mobile/features/notification/data/device_repository_impl.dart';
import 'package:ieum_mobile/features/notification/domain/device_repository.dart';

/// 푸시 토큰 등록. 푸시는 보조 채널이므로 실패해도 예외를 밖으로 던지지 않는다.
class PushRegistrar {
  const PushRegistrar(this._push, this._devices);

  final PushService _push;
  final DeviceRepository _devices;

  /// 권한을 요청하고 허용되면 토큰을 등록한다. 허용 여부를 반환한다.
  Future<bool> enable() async {
    try {
      if (!await _push.requestPermission()) return false;
      await registerCurrentToken();
      return true;
    } on Object catch (e) {
      debugPrint('푸시 활성화 실패: ${e.runtimeType}');
      return false;
    }
  }

  /// 로그인 직후용: OS 알림 권한이 이미 허용 상태일 때만 권한 팝업 없이 조용히 토큰을 등록한다.
  /// 같은 기기에서 계정이 바뀌어도 새 계정으로 소유가 이전된다. 허용 전이면 아무것도 하지 않고 false.
  Future<bool> registerIfGranted() async {
    try {
      if (!await _push.isPermissionGranted()) return false;
      await registerCurrentToken();
      return true;
    } on Object catch (e) {
      debugPrint('푸시 자동 등록 실패: ${e.runtimeType}');
      return false;
    }
  }

  Future<void> registerCurrentToken() async {
    final String? token = await _push.getToken();
    if (token != null) await register(token);
  }

  Future<void> register(String token) async {
    try {
      await _devices.register(token, _platform());
    } on ApiException catch (e) {
      debugPrint('토큰 등록 실패: ${e.code}');
    }
  }

  DevicePlatform _platform() =>
      defaultTargetPlatform == TargetPlatform.iOS ? DevicePlatform.ios : DevicePlatform.android;
}

final Provider<PushRegistrar> pushRegistrarProvider = Provider<PushRegistrar>(
  (Ref ref) => PushRegistrar(ref.watch(pushServiceProvider), ref.watch(deviceRepositoryProvider)),
);

/// 토큰 갱신과 알림 탭 이동을 앱 수명 동안 연결한다. IeumApp이 watch한다.
final Provider<void> pushBindingProvider = Provider<void>((Ref ref) {
  final PushService push = ref.watch(pushServiceProvider);
  final PushRegistrar registrar = ref.watch(pushRegistrarProvider);
  final GoRouter router = ref.watch(appRouterProvider);

  void open(Map<String, String>? data) {
    final String? route = data == null ? null : routeForPush(data);
    if (route != null) router.go(route);
  }

  final refreshSub = push.onTokenRefresh.listen(registrar.register);
  final tapSub = push.onMessageTap.listen(open);
  push.initialMessage().then(open).catchError((Object _) {});

  ref.onDispose(() {
    refreshSub.cancel();
    tapSub.cancel();
  });
});
