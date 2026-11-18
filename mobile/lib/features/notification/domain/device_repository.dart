enum DevicePlatform {
  android('ANDROID'),
  ios('IOS');

  const DevicePlatform(this.apiValue);

  final String apiValue;
}

abstract interface class DeviceRepository {
  /// 이 기기의 FCM 토큰을 서버에 등록한다(멱등).
  Future<void> register(String fcmToken, DevicePlatform platform);
}
