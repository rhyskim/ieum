class GroupSummary {
  const GroupSummary({required this.id, required this.name, required this.owner});

  factory GroupSummary.fromJson(Map<String, dynamic> json) => GroupSummary(
        id: json['id'] as String,
        name: json['name'] as String,
        owner: json['owner'] as bool,
      );

  final String id;
  final String name;
  final bool owner;
}

class GroupMemberInfo {
  const GroupMemberInfo({
    required this.userId,
    required this.displayName,
    required this.familyNickname,
    required this.owner,
    required this.me,
    this.alias,
  });

  factory GroupMemberInfo.fromJson(Map<String, dynamic> json) => GroupMemberInfo(
        userId: json['userId'] as String,
        displayName: json['displayName'] as String,
        alias: json['alias'] as String?,
        familyNickname: json['familyNickname'] as String,
        owner: json['owner'] as bool,
        me: json['me'] as bool,
      );

  final String userId;

  /// 내가 정한 호칭이 있으면 그것, 없으면 상대가 입장 때 정한 이름.
  final String displayName;
  final String? alias;
  final String familyNickname;
  final bool owner;
  final bool me;
}

class GroupDetail {
  const GroupDetail({required this.id, required this.name, required this.members});

  factory GroupDetail.fromJson(Map<String, dynamic> json) => GroupDetail(
        id: json['id'] as String,
        name: json['name'] as String,
        members: (json['members'] as List<dynamic>)
            .map((dynamic e) => GroupMemberInfo.fromJson(e as Map<String, dynamic>))
            .toList(),
      );

  final String id;
  final String name;
  final List<GroupMemberInfo> members;

  bool get iAmOwner => members.any((GroupMemberInfo m) => m.me && m.owner);
}

class InviteCode {
  const InviteCode({required this.code, required this.expiresAt});

  factory InviteCode.fromJson(Map<String, dynamic> json) => InviteCode(
        code: json['code'] as String,
        expiresAt: DateTime.parse(json['expiresAt'] as String),
      );

  final String code;
  final DateTime expiresAt;
}

class PendingRequest {
  const PendingRequest({required this.userId, required this.familyNickname, required this.requestedAt});

  factory PendingRequest.fromJson(Map<String, dynamic> json) => PendingRequest(
        userId: json['userId'] as String,
        familyNickname: json['familyNickname'] as String,
        requestedAt: DateTime.parse(json['requestedAt'] as String),
      );

  final String userId;
  final String familyNickname;
  final DateTime requestedAt;
}

/// 입장 요청 결과. 입장 전에는 그룹 정보를 받지 않는다.
enum JoinStatus {
  pending,
  active;

  static JoinStatus fromServer(String value) => switch (value) {
        'ACTIVE' => JoinStatus.active,
        _ => JoinStatus.pending,
      };
}
