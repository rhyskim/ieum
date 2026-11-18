/// 서버 `POST /api/v1/auth/{provider}` 의 {provider} 경로 값과 버튼 한글 라벨.
enum SocialProvider {
  kakao('kakao', '카카오로 시작하기', '카카오'),
  naver('naver', '네이버로 시작하기', '네이버'),
  apple('apple', 'Apple로 시작하기', 'Apple');

  const SocialProvider(this.pathSegment, this.label, this.displayName);

  final String pathSegment;
  final String label;

  /// 로그인 수단 화면에서 쓰는 이름.
  final String displayName;

  /// 서버 응답의 `KAKAO` 같은 enum 이름을 변환한다.
  static SocialProvider fromServerName(String name) =>
      SocialProvider.values.firstWhere((p) => p.name == name.toLowerCase());
}

/// 탈퇴 시 내가 쓴 글·목소리 처리 선택. 서버 `DELETE /api/v1/users/me`의 `contentChoice` 값.
enum ContentChoice {
  keep('KEEP'),
  delete('DELETE');

  const ContentChoice(this.apiValue);

  final String apiValue;
}

/// 내 계정에 연결된 로그인 수단 한 개.
class LinkedIdentity {
  const LinkedIdentity({required this.provider, required this.linkedAt});

  factory LinkedIdentity.fromJson(Map<String, dynamic> json) => LinkedIdentity(
        provider: SocialProvider.fromServerName(json['provider'] as String),
        linkedAt: DateTime.parse(json['linkedAt'] as String),
      );

  final SocialProvider provider;
  final DateTime linkedAt;
}

/// 로그인된 기기 한 대(서버의 refresh family). 토큰이 갱신돼도 [id]는 바뀌지 않는다.
class DeviceSession {
  const DeviceSession({
    required this.id,
    required this.deviceLabel,
    required this.createdAt,
    this.lastUsedAt,
  });

  factory DeviceSession.fromJson(Map<String, dynamic> json) => DeviceSession(
        id: json['id'] as String,
        deviceLabel: (json['deviceLabel'] as String?) ?? '알 수 없는 기기',
        createdAt: DateTime.parse(json['createdAt'] as String),
        lastUsedAt:
            json['lastUsedAt'] == null ? null : DateTime.parse(json['lastUsedAt'] as String),
      );

  final String id;
  final String deviceLabel;
  final DateTime createdAt;
  final DateTime? lastUsedAt;
}
