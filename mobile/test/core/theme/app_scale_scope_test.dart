import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ieum_mobile/core/theme/app_scale_scope.dart';
import 'package:ieum_mobile/core/theme/text_scale.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// AppScaleScope를 실제 앱처럼 MaterialApp의 builder에 끼워 핀치 → 시트 → 배율 적용 전체를 확인한다.
Future<SharedPreferences> _pump(WidgetTester tester) async {
  SharedPreferences.setMockInitialValues(<String, Object>{});
  final SharedPreferences prefs = await SharedPreferences.getInstance();
  final GlobalKey<NavigatorState> navKey = GlobalKey<NavigatorState>();
  await tester.pumpWidget(ProviderScope(
    overrides: [sharedPreferencesProvider.overrideWithValue(prefs)],
    child: MaterialApp(
      navigatorKey: navKey,
      builder: (context, child) => AppScaleScope(navigatorKey: navKey, child: child!),
      home: Scaffold(
        body: ListView(children: [for (int i = 0; i < 40; i++) SizedBox(height: 60, child: Text('줄 $i'))]),
      ),
    ),
  ));
  return prefs;
}

Future<void> _pinch(WidgetTester tester, {required double ratio, required Duration hold}) async {
  const Offset c = Offset(200, 300);
  final TestGesture a = await tester.startGesture(c - const Offset(50, 0), pointer: 1);
  final TestGesture b = await tester.startGesture(c + const Offset(50, 0), pointer: 2);
  await a.moveTo(c - Offset(50 * ratio, 0));
  await b.moveTo(c + Offset(50 * ratio, 0));
  await tester.pump(hold);
  await a.up();
  await b.up();
  await tester.pumpAndSettle();
}

double _scale(WidgetTester tester) =>
    MediaQuery.of(tester.element(find.text('줄 0'))).textScaler.scale(10) / 10;

void main() {
  testWidgets('임계값 미만이면 시트가 뜨지 않는다', (tester) async {
    await _pump(tester);
    await _pinch(tester, ratio: 1.2, hold: const Duration(seconds: 1));
    expect(find.text('화면을 확대하시겠습니까?'), findsNothing);
  });

  testWidgets('0.3초 미만의 짧은 접촉은 시트를 띄우지 않는다', (tester) async {
    await _pump(tester);
    await _pinch(tester, ratio: 1.6, hold: const Duration(milliseconds: 100));
    expect(find.text('화면을 확대하시겠습니까?'), findsNothing);
  });

  testWidgets('임계값 이상이면 시트가 뜨고 슬라이더가 실제 글자 배율을 바꾸며 시트는 겹치지 않는다', (tester) async {
    final SharedPreferences prefs = await _pump(tester);
    expect(_scale(tester), 1.0);

    await _pinch(tester, ratio: 1.6, hold: const Duration(milliseconds: 400));
    expect(find.text('화면을 확대하시겠습니까?'), findsOneWidget);

    // 시트가 열린 채 다시 확대해도 하나만 유지된다.
    await _pinch(tester, ratio: 1.6, hold: const Duration(milliseconds: 400));
    expect(find.text('화면을 확대하시겠습니까?'), findsOneWidget);

    await tester.drag(find.byKey(const ValueKey('text_scale_slider')), const Offset(2000, 0));
    await tester.pumpAndSettle();
    expect(_scale(tester), closeTo(1.7, 1e-6));
    expect(prefs.getDouble(TextScaleConfig.prefsKey), 1.7);

    // 닫은 뒤에는 다시 열 수 있다.
    await tester.tap(find.text('확인'));
    await tester.pumpAndSettle();
    expect(find.text('화면을 확대하시겠습니까?'), findsNothing);
    await _pinch(tester, ratio: 1.6, hold: const Duration(milliseconds: 400));
    expect(find.text('화면을 확대하시겠습니까?'), findsOneWidget);
  });
}
