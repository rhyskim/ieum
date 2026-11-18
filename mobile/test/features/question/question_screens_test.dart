import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/features/question/data/question_repository_impl.dart';
import 'package:ieum_mobile/features/question/domain/question_models.dart';
import 'package:ieum_mobile/features/question/domain/question_repository.dart';
import 'package:ieum_mobile/features/question/presentation/answer_compose_screen.dart';
import 'package:ieum_mobile/features/question/presentation/today_question_screen.dart';

class _FakeQuestionRepository implements QuestionRepository {
  RevealStatus status = RevealStatus.locked;
  bool iAnswered = false;
  bool dadAnswered = true;
  List<AnswerView> visible = const <AnswerView>[];
  ApiException? todayError;
  ApiException? saveError;
  final List<String> saved = <String>[];

  @override
  Future<TodayQuestion> today(String groupId) async {
    if (todayError != null) throw todayError!;
    return TodayQuestion(
      dailyQuestionId: 'dq1',
      groupId: groupId,
      questionText: '오늘 가장 기뻤던 순간은 언제였나요?',
      revealStatus: status,
      hasAnswered: iAnswered,
    );
  }

  @override
  Future<QuestionDetail> detail(String groupId, String dailyQuestionId) async => QuestionDetail(
        dailyQuestionId: dailyQuestionId,
        questionText: '오늘 가장 기뻤던 순간은 언제였나요?',
        revealStatus: status,
        hasAnswered: iAnswered,
        members: <MemberAnswerStatus>[
          MemberAnswerStatus(userId: 'me', displayName: '아들', hasAnswered: iAnswered, mine: true),
          MemberAnswerStatus(userId: 'dad', displayName: '아빠', hasAnswered: dadAnswered, mine: false),
        ],
      );

  @override
  Future<AnswerList> answers(String groupId, String dailyQuestionId) async =>
      AnswerList(revealStatus: status, hasAnswered: iAnswered, answers: visible);

  @override
  Future<void> saveAnswer(String groupId, String dailyQuestionId, String text) async {
    if (saveError != null) throw saveError!;
    saved.add(text);
  }
}

Widget _app(_FakeQuestionRepository repo, Widget home) {
  final GoRouter router = GoRouter(
    routes: <RouteBase>[
      GoRoute(path: '/', builder: (context, state) => home),
      GoRoute(
        path: '/groups/:groupId/question/:dailyQuestionId/answer',
        builder: (context, state) => AnswerComposeScreen(
          groupId: state.pathParameters['groupId']!,
          dailyQuestionId: state.pathParameters['dailyQuestionId']!,
          initialText: state.extra as String?,
        ),
      ),
    ],
  );
  return ProviderScope(
    overrides: [questionRepositoryProvider.overrideWithValue(repo)],
    child: MaterialApp.router(routerConfig: router),
  );
}

/// 가려진 답변의 자리표시(화면 낭독기에는 "가려진 답변"으로만 읽힌다).
final Finder _maskedPlaceholder =
    find.byWidgetPredicate((Widget w) => w is Semantics && w.properties.label == '가려진 답변');

/// 실제 앱처럼 목록 화면 위에 작성 화면을 push로 연다(저장 후 pop할 곳이 있어야 한다).
Future<void> _openCompose(WidgetTester tester, _FakeQuestionRepository repo) async {
  await tester.pumpWidget(_app(repo, const Scaffold(body: Text('목록'))));
  final BuildContext context = tester.element(find.text('목록'));
  GoRouter.of(context).push('/groups/g1/question/dq1/answer');
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('LOCKED: 아빠가 답했다는 사실만 보이고 본문은 없으며 가려진 자리표시가 블러로 보인다', (tester) async {
    final _FakeQuestionRepository repo = _FakeQuestionRepository();
    await tester.pumpWidget(_app(repo, const TodayQuestionScreen(groupId: 'g1')));
    await tester.pumpAndSettle();

    expect(find.text('오늘 가장 기뻤던 순간은 언제였나요?'), findsOneWidget);
    expect(find.textContaining('오후 5시가 되면'), findsOneWidget);
    expect(find.text('답변 완료'), findsOneWidget); // 아빠
    expect(find.text('아직 안 썼어요'), findsOneWidget); // 나
    expect(_maskedPlaceholder, findsOneWidget);
    expect(find.byType(ImageFilteredFinderHelper.type), findsWidgets);
    // 본문 자리에는 실제 답변이 없다.
    expect(find.textContaining('아빠의 답변'), findsNothing);
  });

  testWidgets('버튼은 한글 라벨에 56dp 이상이고 본문 글자는 18sp 이상이다', (tester) async {
    await tester.pumpWidget(_app(_FakeQuestionRepository(), const TodayQuestionScreen(groupId: 'g1')));
    await tester.pumpAndSettle();

    expect(tester.getSize(find.widgetWithText(ElevatedButton, '답변하기')).height, greaterThanOrEqualTo(56));
    for (final Text t in tester.widgetList<Text>(find.descendant(
      of: find.byType(ListView),
      matching: find.byType(Text),
    ))) {
      final double size = t.style?.fontSize ?? 14;
      expect(size, greaterThanOrEqualTo(18), reason: '"${t.data}"');
    }
  });

  testWidgets('공개 후에는 열람 권한이 있는 답변 본문이 보인다', (tester) async {
    final _FakeQuestionRepository repo = _FakeQuestionRepository()
      ..status = RevealStatus.revealedAll
      ..iAnswered = true
      ..visible = const <AnswerView>[
        AnswerView(answerId: 'a1', authorUserId: 'me', authorName: '아들', mine: true, textContent: '내가 쓴 답변'),
        AnswerView(answerId: 'a2', authorUserId: 'dad', authorName: '아빠', mine: false, textContent: '아빠가 쓴 답변'),
      ];
    await tester.pumpWidget(_app(repo, const TodayQuestionScreen(groupId: 'g1')));
    await tester.pumpAndSettle();

    expect(find.text('아빠가 쓴 답변'), findsOneWidget);
    expect(find.text('내가 쓴 답변'), findsOneWidget);
    expect(_maskedPlaceholder, findsNothing);
    expect(find.widgetWithText(ElevatedButton, '내 답변 고치기'), findsOneWidget);
  });

  testWidgets('시간 초과 공개인데 내가 답하지 않았으면 타인 본문 없이 안내만 보인다', (tester) async {
    final _FakeQuestionRepository repo = _FakeQuestionRepository()..status = RevealStatus.revealedTimeout;
    await tester.pumpWidget(_app(repo, const TodayQuestionScreen(groupId: 'g1')));
    await tester.pumpAndSettle();

    expect(find.textContaining('내 답변을 쓰면 가족의 답변을 볼 수 있어요'), findsOneWidget);
    expect(_maskedPlaceholder, findsOneWidget);
  });

  testWidgets('질문이 아직 배정되지 않았으면 친절한 안내를 보여 준다', (tester) async {
    final _FakeQuestionRepository repo = _FakeQuestionRepository()
      ..todayError = const ApiException(code: 'RESOURCE_NOT_FOUND', message: 'x', statusCode: 404);
    await tester.pumpWidget(_app(repo, const TodayQuestionScreen(groupId: 'g1')));
    await tester.pumpAndSettle();

    expect(find.textContaining('아직 오늘의 질문이 도착하지 않았어요'), findsOneWidget);
  });

  testWidgets('작성: 공백 빼고 30자 미만이면 저장이 막히고 남은 글자 수를 안내한다', (tester) async {
    final _FakeQuestionRepository repo = _FakeQuestionRepository();
    await _openCompose(tester, repo);

    expect(tester.widget<ElevatedButton>(find.widgetWithText(ElevatedButton, '저장하기')).onPressed, isNull);

    await tester.enterText(find.byType(TextField), '가 ' * 29); // 공백 빼면 29자
    await tester.pump();
    expect(tester.widget<ElevatedButton>(find.widgetWithText(ElevatedButton, '저장하기')).onPressed, isNull);
    expect(find.textContaining('지금 29자예요'), findsOneWidget);

    await tester.enterText(find.byType(TextField), '가 ' * 30);
    await tester.pump();
    expect(tester.widget<ElevatedButton>(find.widgetWithText(ElevatedButton, '저장하기')).onPressed, isNotNull);
    expect(tester.getSize(find.widgetWithText(ElevatedButton, '저장하기')).height, greaterThanOrEqualTo(56));
  });

  testWidgets('작성: 30자 이상이면 서버에 저장하고, 서버가 거절하면 그 안내를 보여 준다', (tester) async {
    final _FakeQuestionRepository repo = _FakeQuestionRepository();
    await _openCompose(tester, repo);

    final String text = '오늘은 가족과 함께 저녁을 먹어서 정말 기뻤어요 ${'정말'.padRight(10, '좋')}';
    await tester.enterText(find.byType(TextField), text);
    await tester.pump();
    repo.saveError = const ApiException(code: 'ANSWER_TOO_SHORT', message: '답변은 공백을 뺀 30자 이상 써 주세요.');
    await tester.tap(find.widgetWithText(ElevatedButton, '저장하기'));
    await tester.pumpAndSettle();
    expect(find.text('답변은 공백을 뺀 30자 이상 써 주세요.'), findsOneWidget);
    expect(repo.saved, isEmpty);

    repo.saveError = null;
    await tester.tap(find.widgetWithText(ElevatedButton, '저장하기'));
    await tester.pumpAndSettle();
    expect(repo.saved, <String>[text]);
  });
}

/// ImageFiltered 위젯을 타입으로 찾기 위한 도우미.
abstract final class ImageFilteredFinderHelper {
  static final Type type = ImageFiltered;
}
