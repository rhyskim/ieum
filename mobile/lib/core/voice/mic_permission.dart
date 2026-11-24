import 'package:permission_handler/permission_handler.dart';

enum MicPermissionStatus { granted, denied, permanentlyDenied }

/// 마이크 권한 확인·요청과 앱 설정 열기. 영구 거부는 OS가 더 이상 팝업을 띄우지 않으므로 설정 화면으로 안내한다.
abstract interface class MicPermissionService {
  Future<MicPermissionStatus> request();
  Future<void> openSettings();
}

class OnDeviceMicPermissionService implements MicPermissionService {
  const OnDeviceMicPermissionService();

  @override
  Future<MicPermissionStatus> request() async {
    final PermissionStatus status = await Permission.microphone.request();
    if (status.isGranted) return MicPermissionStatus.granted;
    if (status.isPermanentlyDenied || status.isRestricted) return MicPermissionStatus.permanentlyDenied;
    return MicPermissionStatus.denied;
  }

  @override
  Future<void> openSettings() async {
    await openAppSettings();
  }
}

class NoopMicPermissionService implements MicPermissionService {
  const NoopMicPermissionService();

  @override
  Future<MicPermissionStatus> request() async => MicPermissionStatus.denied;

  @override
  Future<void> openSettings() async {}
}
