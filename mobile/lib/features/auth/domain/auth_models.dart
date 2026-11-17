/// 서버 `POST /api/v1/auth/{provider}` 의 {provider} 경로 값과 버튼 한글 라벨.
enum SocialProvider {
  kakao('kakao', '카카오로 시작하기'),
  naver('naver', '네이버로 시작하기'),
  apple('apple', 'Apple로 시작하기');

  const SocialProvider(this.pathSegment, this.label);

  final String pathSegment;
  final String label;
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
