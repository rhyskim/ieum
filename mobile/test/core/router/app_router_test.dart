import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:ieum_mobile/core/router/app_router.dart';
import 'package:ieum_mobile/core/theme/text_scale.dart';
import 'package:ieum_mobile/features/group/data/group_repository_impl.dart';
import 'package:ieum_mobile/features/group/domain/group_models.dart';
import 'package:shared_preferences/shared_preferences.dart';

Future<GoRouter> _pump(WidgetTester tester) async {
  SharedPreferences.setMockInitialValues(<String, Object>{});
  final ProviderContainer container = ProviderContainer(overrides: [
    sharedPreferencesProvider.overrideWithValue(null),
    myGroupsProvider.overrideWith(
      (ref) async => const <GroupSummary>[GroupSummary(id: 'g1', name: '우리 가족', owner: true)],
    ),
  ]);
  addTearDown(container.dispose);
  final GoRouter router = container.read(appRouterProvider);
  tester.view.physicalSize = const Size(360 * 3, 640 * 3);
  tester.view.devicePixelRatio = 3;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(routerConfig: router),
  ));
  await tester.pumpAndSettle();
  return router;
}

void _expectHomeTabs(WidgetTester tester) {
  for (final String label in <String>['홈', '목록', '캘린더', '설정']) {
    expect(find.text(label), findsOneWidget);
  }
  expect(find.text('우리 가족'), findsOneWidget);
}

void main() {
  testWidgets('앱 시작과 로그인·복구·푸시 안내 이후의 "/" 이동은 홈 탭으로 간다', (tester) async {
    final GoRouter router = await _pump(tester);
    _expectHomeTabs(tester);
    expect(router.routeInformationProvider.value.uri.path, '/home');

    router.go('/settings');
    await tester.pumpAndSettle();
    expect(find.text('글자 크기 100%'), findsOneWidget);

    router.go('/'); // login_screen, restore_screen, push_intro_screen이 쓰는 목적지
    await tester.pumpAndSettle();
    _expectHomeTabs(tester);
    expect(router.routeInformationProvider.value.uri.path, '/home');
  });

  testWidgets('"/groups"(입장 완료·거절 알림)도 홈 탭으로 간다', (tester) async {
    final GoRouter router = await _pump(tester);
    router.go('/list');
    await tester.pumpAndSettle();
    router.go('/groups');
    await tester.pumpAndSettle();
    _expectHomeTabs(tester);
    expect(router.routeInformationProvider.value.uri.path, '/home');
  });

  testWidgets('탭을 오가도 4개 탭 모두 열린다', (tester) async {
    await _pump(tester);
    await tester.tap(find.text('목록'));
    await tester.pumpAndSettle();
    expect(find.text('지난 질문을 모아 볼 수 있어요.'), findsOneWidget);
    await tester.tap(find.text('캘린더'));
    await tester.pumpAndSettle();
    expect(find.text('준비 중입니다.'), findsOneWidget);
    await tester.tap(find.text('설정'));
    await tester.pumpAndSettle();
    expect(find.text('오픈소스 라이선스'), findsOneWidget);
    await tester.tap(find.text('홈'));
    await tester.pumpAndSettle();
    expect(find.text('우리 가족'), findsOneWidget);
  });

  testWidgets('푸시 탭이 쓰는 router.go(그룹 하위 경로)는 뒤로 갈 곳이 있어 앱이 종료되지 않는다', (tester) async {
    final GoRouter router = await _pump(tester);
    router.go('/groups/create'); // router.go로 직접 진입하는 푸시 이동과 같은 방식
    await tester.pumpAndSettle();
    expect(router.canPop(), isTrue);
    router.pop();
    await tester.pumpAndSettle();
    expect(router.canPop(), isFalse);
    expect(find.text('내 가족'), findsOneWidget); // 마지막 화면이 빈 화면이 아니라 가족 목록
  });
}
