import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// FCM 접근을 감싼 포트. Firebase 설정이 없는 환경에서는 [NoopPushService]가 쓰인다.
abstract interface class PushService {
  /// 권한 팝업 없이 현재 OS 알림 권한이 허용 상태인지 조회한다.
  Future<bool> isPermissionGranted();

  /// 알림 권한을 요청한다. 허용되면 true.
  Future<bool> requestPermission();

  Future<String?> getToken();

  Stream<String> get onTokenRefresh;

  /// 앱이 종료된 상태에서 알림을 눌러 열렸다면 그 알림의 data.
  Future<Map<String, String>?> initialMessage();

  /// 백그라운드 상태에서 알림을 눌렀을 때의 data.
  Stream<Map<String, String>> get onMessageTap;
}

class NoopPushService implements PushService {
  const NoopPushService();

  @override
  Future<bool> isPermissionGranted() async => false;

  @override
  Future<bool> requestPermission() async => false;

  @override
  Future<String?> getToken() async => null;

  @override
  Stream<String> get onTokenRefresh => const Stream<String>.empty();

  @override
  Future<Map<String, String>?> initialMessage() async => null;

  @override
  Stream<Map<String, String>> get onMessageTap => const Stream<Map<String, String>>.empty();
}

class FirebasePushService implements PushService {
  FirebasePushService([FirebaseMessaging? messaging])
      : _messaging = messaging ?? FirebaseMessaging.instance;

  final FirebaseMessaging _messaging;

  @override
  Future<bool> isPermissionGranted() async =>
      _isGranted(await _messaging.getNotificationSettings());

  @override
  Future<bool> requestPermission() async => _isGranted(await _messaging.requestPermission());

  bool _isGranted(NotificationSettings settings) =>
      settings.authorizationStatus == AuthorizationStatus.authorized ||
      settings.authorizationStatus == AuthorizationStatus.provisional;

  @override
  Future<String?> getToken() => _messaging.getToken();

  @override
  Stream<String> get onTokenRefresh => _messaging.onTokenRefresh;

  @override
  Future<Map<String, String>?> initialMessage() async =>
      _dataOf(await _messaging.getInitialMessage());

  @override
  Stream<Map<String, String>> get onMessageTap => FirebaseMessaging.onMessageOpenedApp
      .map(_dataOf)
      .where((Map<String, String>? d) => d != null)
      .cast<Map<String, String>>();

  Map<String, String>? _dataOf(RemoteMessage? message) =>
      message?.data.map((String k, dynamic v) => MapEntry<String, String>(k, '$v'));
}

/// 기본은 no-op. main()에서 Firebase 초기화에 성공했을 때만 [FirebasePushService]로 교체한다.
final Provider<PushService> pushServiceProvider =
    Provider<PushService>((Ref ref) => const NoopPushService());
