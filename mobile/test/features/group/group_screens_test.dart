import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/features/group/data/group_repository_impl.dart';
import 'package:ieum_mobile/features/group/domain/group_models.dart';
import 'package:ieum_mobile/features/group/domain/group_repository.dart';
import 'package:ieum_mobile/features/group/presentation/create_group_screen.dart';
import 'package:ieum_mobile/features/group/presentation/group_detail_screen.dart';
import 'package:ieum_mobile/features/group/presentation/group_list_screen.dart';
import 'package:ieum_mobile/features/group/presentation/invite_code_screen.dart';
import 'package:ieum_mobile/features/group/presentation/join_screen.dart';
import 'package:ieum_mobile/features/group/presentation/pending_requests_screen.dart';
import 'package:ieum_mobile/features/group/presentation/support_screen.dart';

class _FakeGroupRepository implements GroupRepository {
  ApiException? joinError;
  JoinStatus joinResult = JoinStatus.pending;
  InviteCode? code;
  final List<String> joined = <String>[];
  final List<String> aliasCalls = <String>[];
  final List<String> supportMessages = <String>[];
  int issued = 0;
  ApiException? approveError;
  List<PendingRequest> pending = <PendingRequest>[
    PendingRequest(userId: 'kid', familyNickname: '막내', requestedAt: DateTime.utc(2026, 11, 17)),
  ];
  final List<String> decisions = <String>[];

  List<GroupSummary> groups = const <GroupSummary>[
    GroupSummary(id: 'g1', name: '우리 가족', owner: true),
  ];

  GroupDetail detailValue = const GroupDetail(
    id: 'g1',
    name: '우리 가족',
    members: <GroupMemberInfo>[
      GroupMemberInfo(
        userId: 'me',
        displayName: '아들',
        familyNickname: '아들',
        owner: true,
        me: true,
      ),
      GroupMemberInfo(
        userId: 'mom',
        displayName: '우리 엄마',
        alias: '우리 엄마',
        familyNickname: '엄마',
        owner: false,
        me: false,
      ),
    ],
  );

  @override
  Future<List<GroupSummary>> myGroups() async => groups;

  @override
  Future<GroupSummary> createGroup(String name, {String? nickname}) async =>
      GroupSummary(id: 'new', name: name, owner: true);

  @override
  Future<GroupDetail> detail(String groupId) async => detailValue;

  @override
  Future<InviteCode?> currentCode(String groupId) async => code;

  @override
  Future<InviteCode> issueCode(String groupId) async {
    issued++;
    code = InviteCode(code: '012345', expiresAt: DateTime.now().add(const Duration(minutes: 5)));
    return code!;
  }

  @override
  Future<JoinStatus> join(String code, {String? nickname}) async {
    joined.add(code);
    if (joinError != null) throw joinError!;
    return joinResult;
  }

  @override
  Future<List<PendingRequest>> pendingRequests(String groupId) async => List<PendingRequest>.of(pending);

  @override
  Future<void> approve(String groupId, String targetUserId) async {
    if (approveError != null) throw approveError!;
    decisions.add('approve:$targetUserId');
    pending = pending.where((PendingRequest r) => r.userId != targetUserId).toList();
  }

  @override
  Future<void> reject(String groupId, String targetUserId) async {
    decisions.add('reject:$targetUserId');
    pending = pending.where((PendingRequest r) => r.userId != targetUserId).toList();
  }

  @override
  Future<void> setAlias(String groupId, String targetUserId, String alias) async =>
      aliasCalls.add('set:$targetUserId:$alias');

  @override
  Future<void> clearAlias(String groupId, String targetUserId) async =>
      aliasCalls.add('clear:$targetUserId');

  @override
  Future<void> submitSupportRequest(String groupId, String message) async =>
      supportMessages.add(message);
}

class _FakeSharer implements CodeSharer {
  final List<String> shared = <String>[];

  @override
  Future<void> share(String text) async => shared.add(text);
}

Widget _app(_FakeGroupRepository repo, Widget home, {_FakeSharer? sharer}) {
  final GoRouter router = GoRouter(
    routes: <RouteBase>[
      GoRoute(path: '/', builder: (context, state) => home),
      GoRoute(path: '/groups', builder: (context, state) => const Text('가족 목록 이동')),
      GoRoute(
        path: '/groups/:id',
        builder: (context, state) => const Text('가족 상세 이동'),
        routes: <RouteBase>[
          GoRoute(path: 'support', builder: (context, state) => const Text('관리자 문의 이동')),
        ],
      ),
    ],
  );
  return ProviderScope(
    overrides: [
      groupRepositoryProvider.overrideWithValue(repo),
      if (sharer != null) codeSharerProvider.overrideWithValue(sharer),
    ],
    child: MaterialApp.router(routerConfig: router),
  );
}

void _expectTouchTarget(WidgetTester tester, Finder finder) {
  expect(tester.getSize(finder).height, greaterThanOrEqualTo(56));
}

void main() {
  testWidgets('가족 목록: 내 그룹과 만들기·입장 버튼이 한글 라벨, 56dp 이상으로 표시된다', (tester) async {
    await tester.pumpWidget(_app(_FakeGroupRepository(), const GroupListScreen()));
    await tester.pumpAndSettle();

    expect(find.text('우리 가족'), findsOneWidget);
    expect(find.text('방장'), findsOneWidget);
    _expectTouchTarget(tester, find.widgetWithText(ElevatedButton, '가족 만들기'));
    _expectTouchTarget(tester, find.widgetWithText(OutlinedButton, '코드로 입장하기'));
  });

  testWidgets('가족 만들기: 이름이 비면 안내하고, 입력하면 생성 후 상세로 이동한다', (tester) async {
    await tester.pumpWidget(_app(_FakeGroupRepository(), const CreateGroupScreen()));

    await tester.tap(find.widgetWithText(ElevatedButton, '가족 만들기'));
    await tester.pump();
    expect(find.text('가족 이름을 입력해 주세요.'), findsOneWidget);

    await tester.enterText(find.widgetWithText(TextField, '가족 이름'), '새 가족');
    await tester.tap(find.widgetWithText(ElevatedButton, '가족 만들기'));
    await tester.pumpAndSettle();
    expect(find.text('가족 상세 이동'), findsOneWidget);
  });

  testWidgets('코드 입력: 숫자 키패드·56dp 버튼, 6자리 미만이면 서버를 호출하지 않는다', (tester) async {
    final _FakeGroupRepository repo = _FakeGroupRepository();
    await tester.pumpWidget(_app(repo, const JoinScreen()));

    final TextField codeField = tester.widget(find.widgetWithText(TextField, '입장 코드'));
    expect(codeField.keyboardType, TextInputType.number);
    _expectTouchTarget(tester, find.widgetWithText(ElevatedButton, '입장 요청하기'));

    await tester.enterText(find.widgetWithText(TextField, '입장 코드'), '12a34');
    await tester.tap(find.widgetWithText(ElevatedButton, '입장 요청하기'));
    await tester.pump();
    expect(find.text('숫자 6자리를 모두 입력해 주세요.'), findsOneWidget);
    expect(repo.joined, isEmpty);
  });

  testWidgets('승인 대기: 입장 요청이 접수되면 친절한 대기 문구가 보이고 그룹 정보는 없다', (tester) async {
    final _FakeGroupRepository repo = _FakeGroupRepository();
    await tester.pumpWidget(_app(repo, const JoinScreen()));

    await tester.enterText(find.widgetWithText(TextField, '입장 코드'), '012345');
    await tester.tap(find.widgetWithText(ElevatedButton, '입장 요청하기'));
    await tester.pumpAndSettle();

    expect(repo.joined, <String>['012345']);
    expect(find.text('방장이 확인하고 있어요'), findsOneWidget);
    expect(find.textContaining('우리 가족'), findsNothing);
    _expectTouchTarget(tester, find.widgetWithText(ElevatedButton, '확인'));
  });

  testWidgets('만료·없는 코드 오류는 서버가 준 안내 문구를 그대로 보여 준다', (tester) async {
    final _FakeGroupRepository repo = _FakeGroupRepository()
      ..joinError = const ApiException(
        code: 'INVITE_CODE_EXPIRED',
        message: '입장 코드의 유효 시간이 지났어요. 방장에게 새 코드를 받아 주세요.',
        statusCode: 410,
      );
    await tester.pumpWidget(_app(repo, const JoinScreen()));

    await tester.enterText(find.widgetWithText(TextField, '입장 코드'), '012345');
    await tester.tap(find.widgetWithText(ElevatedButton, '입장 요청하기'));
    await tester.pumpAndSettle();

    expect(find.textContaining('유효 시간이 지났어요'), findsOneWidget);
    expect(find.text('방장이 확인하고 있어요'), findsNothing);
  });

  testWidgets('코드 보기: 큰 숫자와 남은 시간을 보여 주고 복사해 전달하며 새 코드를 만들 수 있다', (tester) async {
    final _FakeGroupRepository repo = _FakeGroupRepository()
      ..code = InviteCode(code: '000123', expiresAt: DateTime.now().add(const Duration(minutes: 5)));
    final _FakeSharer sharer = _FakeSharer();
    await tester.pumpWidget(_app(repo, const InviteCodeScreen(groupId: 'g1'), sharer: sharer));
    await tester.pump();
    await tester.pump();

    expect(find.text('000123'), findsOneWidget);
    expect(find.textContaining('남은 시간'), findsOneWidget);
    expect(tester.widget<Text>(find.text('000123')).style!.fontSize, greaterThanOrEqualTo(48));

    await tester.tap(find.widgetWithText(ElevatedButton, '코드 복사하기'));
    await tester.pump();
    expect(sharer.shared.single, contains('000123'));

    await tester.tap(find.widgetWithText(OutlinedButton, '새 코드 만들기'));
    await tester.pump();
    await tester.pump();
    expect(repo.issued, 1);
    expect(find.text('012345'), findsOneWidget);

    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('코드 보기: 유효한 코드가 없으면 새로 만들라고 안내한다', (tester) async {
    await tester.pumpWidget(_app(_FakeGroupRepository(), const InviteCodeScreen(groupId: 'g1')));
    await tester.pump();
    await tester.pump();

    expect(find.textContaining('유효한 코드가 없어요'), findsOneWidget);
    _expectTouchTarget(tester, find.widgetWithText(ElevatedButton, '새 코드 만들기'));
  });

  testWidgets('그룹 상세: 내가 정한 호칭으로 멤버가 보이고, 호칭을 저장할 수 있다', (tester) async {
    final _FakeGroupRepository repo = _FakeGroupRepository();
    await tester.pumpWidget(_app(repo, const GroupDetailScreen(groupId: 'g1')));
    await tester.pumpAndSettle();

    expect(find.text('우리 엄마'), findsOneWidget);
    expect(find.textContaining('아들 (나, 방장)'), findsOneWidget);
    expect(find.widgetWithText(ElevatedButton, '입장 코드 보기'), findsOneWidget);
    _expectTouchTarget(tester, find.widgetWithText(TextButton, '호칭 바꾸기'));

    await tester.tap(find.text('호칭 바꾸기'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, '내가 부르는 호칭'), '어머니');
    await tester.tap(find.text('저장'));
    await tester.pumpAndSettle();

    expect(repo.aliasCalls, <String>['set:mom:어머니']);
  });

  testWidgets('그룹 상세: 방장이 아니면 입장 코드 버튼이 보이지 않는다', (tester) async {
    final _FakeGroupRepository repo = _FakeGroupRepository()
      ..detailValue = const GroupDetail(
        id: 'g1',
        name: '우리 가족',
        members: <GroupMemberInfo>[
          GroupMemberInfo(
            userId: 'me',
            displayName: '딸',
            familyNickname: '딸',
            owner: false,
            me: true,
          ),
        ],
      );
    await tester.pumpWidget(_app(repo, const GroupDetailScreen(groupId: 'g1')));
    await tester.pumpAndSettle();

    expect(find.text('입장 코드 보기'), findsNothing);
    expect(find.text('관리자에게 문의하기'), findsNothing);
  });

  testWidgets('상한 도달 문의: 내용이 없으면 안내하고, 보내면 접수 문구가 보인다', (tester) async {
    final _FakeGroupRepository repo = _FakeGroupRepository();
    await tester.pumpWidget(_app(repo, const SupportScreen(groupId: 'g1')));

    expect(find.textContaining('20명을 넘으셨나요'), findsOneWidget);
    _expectTouchTarget(tester, find.widgetWithText(ElevatedButton, '문의 보내기'));

    await tester.tap(find.widgetWithText(ElevatedButton, '문의 보내기'));
    await tester.pump();
    expect(find.text('문의 내용을 입력해 주세요.'), findsOneWidget);
    expect(repo.supportMessages, isEmpty);

    await tester.enterText(find.widgetWithText(TextField, '문의 내용'), '대가족이에요');
    await tester.tap(find.widgetWithText(ElevatedButton, '문의 보내기'));
    await tester.pumpAndSettle();

    expect(repo.supportMessages, <String>['대가족이에요']);
    expect(find.textContaining('문의가 접수되었어요'), findsOneWidget);
  });

  testWidgets('입장 잠금(429): 잠금 안내가 보이고 코드 입력창은 사라진다', (tester) async {
    final _FakeGroupRepository repo = _FakeGroupRepository()
      ..joinError = const ApiException(
        code: 'JOIN_LOCKED',
        message: '입장 시도가 너무 많아요. 20분 뒤에 다시 시도해 주세요.',
        statusCode: 429,
      );
    await tester.pumpWidget(_app(repo, const JoinScreen()));

    await tester.enterText(find.widgetWithText(TextField, '입장 코드'), '012345');
    await tester.tap(find.widgetWithText(ElevatedButton, '입장 요청하기'));
    await tester.pumpAndSettle();

    expect(find.text('잠시 쉬어 가요'), findsOneWidget);
    expect(find.textContaining('20분 뒤에'), findsOneWidget);
    expect(find.byType(TextField), findsNothing);
    _expectTouchTarget(tester, find.widgetWithText(ElevatedButton, '확인'));
  });

  testWidgets('승인 대기 목록: 승인·거절 버튼이 56dp 이상이고 누르면 처리되어 목록에서 사라진다', (tester) async {
    final _FakeGroupRepository repo = _FakeGroupRepository();
    await tester.pumpWidget(_app(repo, const PendingRequestsScreen(groupId: 'g1')));
    await tester.pumpAndSettle();

    expect(find.textContaining('막내님이 가족이 되고 싶어 해요'), findsOneWidget);
    _expectTouchTarget(tester, find.widgetWithText(ElevatedButton, '승인'));
    _expectTouchTarget(tester, find.widgetWithText(OutlinedButton, '거절'));

    await tester.tap(find.widgetWithText(ElevatedButton, '승인'));
    await tester.pumpAndSettle();

    expect(repo.decisions, <String>['approve:kid']);
    expect(find.textContaining('막내님'), findsNothing);
    expect(find.text('기다리는 입장 요청이 없어요.'), findsOneWidget);
  });

  testWidgets('인원 상한(GROUP_FULL)에 걸리면 관리자 문의 화면으로 안내한다', (tester) async {
    final _FakeGroupRepository repo = _FakeGroupRepository()
      ..approveError = const ApiException(
        code: 'GROUP_FULL',
        message: '가족 인원이 가득 찼어요. 관리자에게 문의해 주세요.',
        statusCode: 409,
      );
    await tester.pumpWidget(_app(repo, const PendingRequestsScreen(groupId: 'g1')));
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(ElevatedButton, '승인'));
    await tester.pumpAndSettle();

    expect(find.text('관리자 문의 이동'), findsOneWidget);
    expect(repo.decisions, isEmpty);
  });
}
