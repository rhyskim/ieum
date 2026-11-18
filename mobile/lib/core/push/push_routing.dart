/// 알림 탭 시 이동할 경로. 알 수 없는 알림이거나 값이 올바르지 않으면 null(이동하지 않는다).
/// 알림 data는 외부 입력이므로 groupId는 경로를 조작할 수 없는 문자만 허용한다.
String? routeForPush(Map<String, String> data) {
  final String? groupId = data['groupId'];
  if (groupId == null || !RegExp(r'^[A-Za-z0-9-]+$').hasMatch(groupId)) {
    return null;
  }
  return switch (data['type']) {
    'JOIN_REQUESTED' => '/groups/$groupId/requests',
    'JOIN_APPROVED' => '/groups/$groupId',
    'JOIN_REJECTED' => '/groups',
    // 새 질문 도착과 "모두 답했어요" 알림 모두 해당 그룹의 오늘의 질문 화면으로 간다.
    'QUESTION_ASSIGNED' || 'ALL_ANSWERED' => '/groups/$groupId/question',
    _ => null,
  };
}
