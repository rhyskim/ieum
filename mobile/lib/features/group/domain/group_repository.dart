import 'package:ieum_mobile/features/group/domain/group_models.dart';

abstract interface class GroupRepository {
  Future<List<GroupSummary>> myGroups();

  Future<GroupSummary> createGroup(String name, {String? nickname});

  Future<GroupDetail> detail(String groupId);

  /// 현재 유효한 입장 코드. 없거나 만료됐으면 null.
  Future<InviteCode?> currentCode(String groupId);

  Future<InviteCode> issueCode(String groupId);

  Future<JoinStatus> join(String code, {String? nickname});

  /// 방장 전용: 만료되지 않은 승인 대기 목록.
  Future<List<PendingRequest>> pendingRequests(String groupId);

  /// 방장 전용. 인원 상한이면 [ApiException]의 code가 GROUP_FULL이다.
  Future<void> approve(String groupId, String targetUserId);

  Future<void> reject(String groupId, String targetUserId);

  Future<void> setAlias(String groupId, String targetUserId, String alias);

  Future<void> clearAlias(String groupId, String targetUserId);

  Future<void> submitSupportRequest(String groupId, String message);
}

/// 코드를 공유 시트로 내보낸다. 테스트에서는 대체한다.
abstract interface class CodeSharer {
  Future<void> share(String text);
}
