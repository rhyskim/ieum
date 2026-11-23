import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:ieum_mobile/core/theme/accessibility_config.dart';
import 'package:ieum_mobile/core/theme/app_theme.dart';
import 'package:ieum_mobile/core/theme/paper_background.dart';
import 'package:ieum_mobile/core/theme/text_scale.dart';
import 'package:ieum_mobile/features/group/data/group_repository_impl.dart';
import 'package:ieum_mobile/features/group/domain/group_models.dart';
import 'package:ieum_mobile/features/group/presentation/group_list_screen.dart';
import 'package:ieum_mobile/features/question/data/question_repository_impl.dart';
import 'package:ieum_mobile/features/question/domain/question_models.dart';
import 'package:ieum_mobile/features/question/domain/question_repository.dart';
import 'package:ieum_mobile/features/question/presentation/answer_compose_screen.dart';
import 'package:ieum_mobile/features/question/presentation/today_question_screen.dart';
import 'package:ieum_mobile/features/shell/presentation/main_shell.dart';
import 'package:ieum_mobile/features/shell/presentation/placeholder_screens.dart';

const String _longText =
    '어릴 적 여름방학마다 할머니 댁에 내려가 마루에서 수박을 먹던 기억이 가장 선명해요. 매미 소리와 선풍기 바람, 할머니가 부채질해 주시던 손길까지 하나하나 떠오릅니다.';

class _Repo implements QuestionRepository {
  _Repo(this.status);
  final RevealStatus status;

  @override
  Future<TodayQuestion> today(String groupId) async => TodayQuestion(
        dailyQuestionId: 'dq1',
        groupId: 'g1',
        questionText: '어린 시절 가장 행복했던 여름날의 기억을 자세히 들려주세요.',
        revealStatus: status,
        hasAnswered: true,
      );

  @override
  Future<QuestionDetail> detail(String groupId, String dailyQuestionId) async => QuestionDetail(
        dailyQuestionId: 'dq1',
        questionText: '어린 시절 가장 행복했던 여름날의 기억을 자세히 들려주세요.',
        revealStatus: status,
        hasAnswered: true,
        members: <MemberAnswerStatus>[
          const MemberAnswerStatus(userId: 'me', displayName: '아들', hasAnswered: true, mine: true),
          const MemberAnswerStatus(userId: 'dad', displayName: '아빠', hasAnswered: true, mine: false),
        ],
      );

  @override
  Future<AnswerList> answers(String groupId, String dailyQuestionId) async => AnswerList(
        revealStatus: status,
        hasAnswered: true,
        answers: <AnswerView>[
          const AnswerView(answerId: 'a1', authorUserId: 'me', authorName: '아들', mine: true, textContent: _longText),
          const AnswerView(answerId: 'a2', authorUserId: 'dad', authorName: '아빠', mine: false, textContent: _longText),
        ],
      );

  @override
  Future<void> saveAnswer(String groupId, String dailyQuestionId, String text) async {}
}

Widget _app(Widget home, double scale, {RevealStatus status = RevealStatus.revealedAll}) {
  return ProviderScope(
    overrides: [
      questionRepositoryProvider.overrideWithValue(_Repo(status)),
      myGroupsProvider.overrideWith(
        (ref) async => const <GroupSummary>[
          GroupSummary(id: 'g1', name: '우리 가족 이야기 모임 아주 긴 이름', owner: true),
          GroupSummary(id: 'g2', name: '외가', owner: false),
        ],
      ),
    ],
    child: MaterialApp.router(
      theme: AppTheme.buildTheme(const AccessibilityConfig()),
      builder: (context, c) => MediaQuery(
        data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(scale)),
        child: PaperBackground(child: c!),
      ),
      routerConfig: GoRouter(routes: <RouteBase>[GoRoute(path: '/', builder: (_, __) => home)]),
    ),
  );
}

Widget _shellApp(double scale) {
  final GoRouter router = GoRouter(
    initialLocation: '/home',
    routes: <RouteBase>[
      StatefulShellRoute.indexedStack(
        builder: (context, state, shell) => MainShell(navigationShell: shell),
        branches: <StatefulShellBranch>[
          StatefulShellBranch(routes: <RouteBase>[
            GoRoute(path: '/home', builder: (_, __) => const Text('홈 화면')),
          ]),
          StatefulShellBranch(routes: <RouteBase>[
            GoRoute(path: '/list', builder: (_, __) => const ListTabPlaceholder()),
          ]),
          StatefulShellBranch(routes: <RouteBase>[
            GoRoute(path: '/calendar', builder: (_, __) => const CalendarPlaceholderScreen()),
          ]),
          StatefulShellBranch(routes: <RouteBase>[
            GoRoute(path: '/settings', builder: (_, __) => const Text('설정 화면')),
          ]),
        ],
      ),
    ],
  );
  return ProviderScope(
    child: MaterialApp.router(
      theme: AppTheme.buildTheme(const AccessibilityConfig()),
      builder: (context, c) => MediaQuery(
        data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(scale)),
        child: PaperBackground(child: c!),
      ),
      routerConfig: router,
    ),
  );
}

void main() {
  // 시스템 1.5배 × 사용자 1.7배는 상한 1.7로 잘리므로 화면이 받는 최대 배율은 1.7이다.
  for (final double scale in <double>[1.0, TextScaleConfig.maxEffectiveScale]) {
    final String pct = '${(scale * 100).round()}%';
    final Map<String, Widget> screens = <String, Widget>{
      '홈(가족 목록)': const GroupListScreen(),
      '오늘의 질문': const TodayQuestionScreen(groupId: 'g1'),
      '답변 작성': const AnswerComposeScreen(groupId: 'g1', dailyQuestionId: 'dq1', initialText: _longText),
    };
    testWidgets('오늘의 질문(답변 가려진 상태)이 배율 $pct에서 오버플로 없이 그려진다', (tester) async {
      tester.view.physicalSize = const Size(360 * 3, 640 * 3);
      tester.view.devicePixelRatio = 3;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(
          _app(const TodayQuestionScreen(groupId: 'g1'), scale, status: RevealStatus.locked));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });

    testWidgets('하단 4탭이 배율 $pct에서 오버플로 없이 그려지고 탭 높이가 48dp 이상이다', (tester) async {
      tester.view.physicalSize = const Size(360 * 3, 640 * 3);
      tester.view.devicePixelRatio = 3;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(_shellApp(scale));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      for (final String label in <String>['홈', '목록', '캘린더', '설정']) {
        expect(find.text(label), findsOneWidget);
      }
      expect(tester.getSize(find.byType(NavigationBar)).height, greaterThanOrEqualTo(48));
      await tester.tap(find.text('캘린더'));
      await tester.pumpAndSettle();
      expect(find.text('준비 중입니다.'), findsOneWidget);
    });

    screens.forEach((String name, Widget screen) {
      testWidgets('$name 화면이 배율 $pct에서 오버플로 없이 그려진다', (tester) async {
        tester.view.physicalSize = const Size(360 * 3, 640 * 3);
        tester.view.devicePixelRatio = 3;
        addTearDown(tester.view.reset);
        await tester.pumpWidget(_app(screen, scale));
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        expect(find.byType(Scaffold), findsWidgets);
      });
    });
  }
}
