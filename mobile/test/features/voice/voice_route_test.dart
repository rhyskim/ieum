import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:ieum_mobile/core/router/app_router.dart';
import 'package:ieum_mobile/features/question/presentation/answer_compose_screen.dart';

void main() {
  const String voicePath = '/groups/g1/question/dq1/voice';

  test('실제 라우터에 목소리 녹음 경로가 등록돼 있다', () {
    final ProviderContainer container = ProviderContainer();
    addTearDown(container.dispose);
    final GoRouter router = container.read(appRouterProvider);

    final matches = router.configuration.findMatch(Uri.parse(voicePath));
    expect(matches.isError, isFalse);
    expect(matches.uri.path, voicePath);
  });

  testWidgets('답변 작성 화면의 "목소리로 답하기" 버튼이 같은 경로로 이동한다', (WidgetTester tester) async {
    final GoRouter router = GoRouter(
      initialLocation: '/groups/g1/question/dq1/answer',
      routes: <RouteBase>[
        GoRoute(
          path: '/groups/:groupId/question/:dailyQuestionId/answer',
          builder: (BuildContext _, GoRouterState s) => AnswerComposeScreen(
            groupId: s.pathParameters['groupId']!,
            dailyQuestionId: s.pathParameters['dailyQuestionId']!,
          ),
        ),
        GoRoute(path: voicePath, builder: (BuildContext _, GoRouterState __) => const Text('voice-screen')),
      ],
    );
    await tester.pumpWidget(ProviderScope(child: MaterialApp.router(routerConfig: router)));

    await tester.dragUntilVisible(find.text('목소리로 답하기'), find.byType(ListView), const Offset(0, -200));
    await tester.tap(find.text('목소리로 답하기'));
    await tester.pumpAndSettle();

    expect(find.text('voice-screen'), findsOneWidget);
  });
}
