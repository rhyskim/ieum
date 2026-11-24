import 'package:permission_handler/permission_handler.dart';

enum MicPermissionResult { granted, denied, permanentlyDenied }

/// 마이크 권한 요청과 설정 화면 열기. 녹음기·기기 연동 등 마이크를 쓰는 기능이 함께 쓰도록 `permission_handler`를 가린다.
abstract interface class MicPermissionGateway {
  /// 필요하면 권한 대화상자를 띄워 결과를 돌려준다.
  Future<MicPermissionResult> request();

  /// 영구 거부 시 사용자가 직접 켜도록 앱 설정을 연다.
  Future<void> openSettings();
}

class PermissionHandlerMicGateway implements MicPermissionGateway {
  const PermissionHandlerMicGateway();

  @override
  Future<MicPermissionResult> request() async {
    final PermissionStatus status = await Permission.microphone.request();
    if (status.isGranted) return MicPermissionResult.granted;
    if (status.isPermanentlyDenied) return MicPermissionResult.permanentlyDenied;
    return MicPermissionResult.denied;
  }

  @override
  Future<void> openSettings() async {
    await openAppSettings();
  }
}
