import 'package:flutter_test/flutter_test.dart';
import 'package:ieum_mobile/core/push/push_routing.dart';

void main() {
  test('입장 요청 알림은 방장의 승인 대기 목록으로 이동한다', () {
    expect(
      routeForPush(<String, String>{'type': 'JOIN_REQUESTED', 'groupId': 'g-1'}),
      '/groups/g-1/requests',
    );
  });

  test('승인 알림은 해당 그룹 화면, 거절 알림은 그룹 목록으로 이동한다', () {
    expect(
      routeForPush(<String, String>{'type': 'JOIN_APPROVED', 'groupId': 'g-1'}),
      '/groups/g-1',
    );
    expect(
      routeForPush(<String, String>{'type': 'JOIN_REJECTED', 'groupId': 'g-1'}),
      '/groups',
    );
  });

  test('새 질문·모두 답변 알림은 해당 그룹의 오늘의 질문 화면으로 이동한다', () {
    expect(
      routeForPush(<String, String>{'type': 'QUESTION_ASSIGNED', 'groupId': 'g-1'}),
      '/groups/g-1/question',
    );
    expect(
      routeForPush(<String, String>{'type': 'ALL_ANSWERED', 'groupId': 'g-1'}),
      '/groups/g-1/question',
    );
    expect(routeForPush(<String, String>{'type': 'QUESTION_ASSIGNED', 'groupId': '../x'}), isNull);
    expect(routeForPush(<String, String>{'type': 'QUESTION_ASSIGNED'}), isNull);
  });

  test('알 수 없는 type이거나 groupId가 없거나 경로 조작 문자가 있으면 이동하지 않는다', () {
    expect(routeForPush(<String, String>{'type': 'UNKNOWN', 'groupId': 'g-1'}), isNull);
    expect(routeForPush(<String, String>{'type': 'JOIN_REQUESTED'}), isNull);
    expect(routeForPush(<String, String>{'type': 'JOIN_REQUESTED', 'groupId': ''}), isNull);
    expect(routeForPush(<String, String>{'type': 'JOIN_REQUESTED', 'groupId': '../x'}), isNull);
    expect(routeForPush(<String, String>{}), isNull);
  });
}
