import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ieum_mobile/core/theme/accessibility_config.dart';
import 'package:ieum_mobile/core/theme/app_theme.dart';
import 'package:ieum_mobile/core/theme/paper_background.dart';
import 'package:ieum_mobile/core/theme/paper_theme.dart';
import 'package:ieum_mobile/core/theme/pinch_zoom_detector.dart';
import 'package:ieum_mobile/core/theme/text_scale.dart';
import 'package:ieum_mobile/core/theme/text_scale_sheet.dart';
import 'package:ieum_mobile/features/settings/presentation/settings_screen.dart';
import 'package:ieum_mobile/features/shell/presentation/placeholder_screens.dart';
import 'package:shared_preferences/shared_preferences.dart';

double _lin(double c) => c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4).toDouble();
double _luminance(Color c) => 0.2126 * _lin(c.r) + 0.7152 * _lin(c.g) + 0.0722 * _lin(c.b);
double contrast(Color a, Color b) {
  final double la = _luminance(a), lb = _luminance(b);
  return (max(la, lb) + 0.05) / (min(la, lb) + 0.05);
}

Widget _host(Widget child, {double systemScale = 1.0, SharedPreferences? prefs}) {
  return ProviderScope(
    overrides: [sharedPreferencesProvider.overrideWithValue(prefs)],
    child: Consumer(
      builder: (context, ref, _) {
        final double user = ref.watch(userTextScaleProvider);
        return MaterialApp(
          theme: AppTheme.buildTheme(const AccessibilityConfig()),
          builder: (context, c) => MediaQuery(
            data: MediaQuery.of(context)
                .copyWith(textScaler: TextScaler.linear(TextScaleConfig.effective(systemScale, user))),
            child: PaperBackground(child: c!),
          ),
          home: child,
        );
      },
    ),
  );
}

Widget _scrollList() =>
    ListView(children: [for (int i = 0; i < 50; i++) SizedBox(height: 60, child: Text('줄 $i'))]);

void main() {
  group('색 대비', () {
    test('본문·보조 글자는 배경 그라데이션 양 끝에서 4.5:1 이상', () {
      for (final Color bg in PaperTheme.backgroundSamples) {
        expect(contrast(PaperTheme.textPrimary, bg), greaterThanOrEqualTo(4.5));
        expect(contrast(PaperTheme.textSecondary, bg), greaterThanOrEqualTo(4.5));
      }
    });

    test('버튼 글자와 표면 위 글자도 4.5:1 이상', () {
      expect(contrast(PaperTheme.onAccent, PaperTheme.accent), greaterThanOrEqualTo(4.5));
      expect(contrast(PaperTheme.textPrimary, PaperTheme.surface), greaterThanOrEqualTo(4.5));
      expect(contrast(PaperTheme.error, PaperTheme.surface), greaterThanOrEqualTo(4.5));
    });
  });

  group('크기 불변식', () {
    test('본문 글자는 18sp 이상, 주요 버튼은 56dp 이상', () {
      final ThemeData t = AppTheme.buildTheme(const AccessibilityConfig());
      expect(t.textTheme.bodyLarge!.fontSize, greaterThanOrEqualTo(PaperTheme.minBodySize));
      expect(t.textTheme.bodyMedium!.fontSize, greaterThanOrEqualTo(PaperTheme.minBodySize));
      expect(AppTheme.minButtonSize.height, greaterThanOrEqualTo(PaperTheme.primaryButtonHeight));
    });

    testWidgets('설정 화면 버튼과 슬라이더 터치 영역은 48dp 이상', (tester) async {
      SharedPreferences.setMockInitialValues({});
      await tester.pumpWidget(_host(const SettingsScreen()));
      await tester.pumpAndSettle();
      expect(tester.getSize(find.byKey(const ValueKey('open_licenses_button'))).height,
          greaterThanOrEqualTo(56));
      expect(tester.getSize(find.byKey(const ValueKey('text_scale_slider'))).height,
          greaterThanOrEqualTo(PaperTheme.minTouchTarget));
      expect(find.text('오픈소스 라이선스'), findsOneWidget);
    });
  });

  group('글자 배율', () {
    test('시스템 × 사용자 배율은 상한을 넘지 않는다', () {
      expect(TextScaleConfig.effective(1.5, 1.7), TextScaleConfig.maxEffectiveScale);
      expect(TextScaleConfig.effective(1.0, 1.15), closeTo(1.15, 1e-9));
      expect(TextScaleConfig.effective(0.8, 1.0), 1.0);
    });

    test('저장값은 가장 가까운 단계로 보정된다', () {
      expect(TextScaleConfig.snap(1.14), 1.15);
      expect(TextScaleConfig.snap(9), 1.7);
    });

    testWidgets('슬라이더 조작이 배율을 바꾸고 저장되며 다시 시작하면 복원된다', (tester) async {
      SharedPreferences.setMockInitialValues({});
      SharedPreferences prefs = await SharedPreferences.getInstance();
      await tester.pumpWidget(_host(const SettingsScreen(), prefs: prefs));
      await tester.pumpAndSettle();
      expect(find.text('글자 크기 100%'), findsOneWidget);

      await tester.drag(find.byKey(const ValueKey('text_scale_slider')), const Offset(2000, 0));
      await tester.pumpAndSettle();
      expect(find.text('글자 크기 170%'), findsOneWidget);
      expect(prefs.getDouble(TextScaleConfig.prefsKey), 1.7);

      // 앱 재시작: 같은 저장소로 새 트리를 만든다.
      await tester.pumpWidget(const SizedBox());
      prefs = await SharedPreferences.getInstance();
      await tester.pumpWidget(_host(const SettingsScreen(), prefs: prefs));
      await tester.pumpAndSettle();
      expect(find.text('글자 크기 170%'), findsOneWidget);
    });
  });

  group('두 손가락 확대 제스처', () {
    Future<int> pinch(WidgetTester tester, {required double endRatio, required Duration hold}) async {
      int triggers = 0;
      await tester.pumpWidget(MaterialApp(
        home: PinchZoomDetector(onTrigger: () => triggers++, child: _scrollList()),
      ));
      const Offset c = Offset(200, 300);
      final TestGesture a = await tester.startGesture(c - const Offset(50, 0), pointer: 1);
      final TestGesture b = await tester.startGesture(c + const Offset(50, 0), pointer: 2);
      final double half = 50 * endRatio;
      await a.moveTo(c - Offset(half, 0));
      await b.moveTo(c + Offset(half, 0));
      await tester.pump(hold);
      await a.up();
      await b.up();
      await tester.pump(const Duration(seconds: 1));
      return triggers;
    }

    testWidgets('변화가 25% 미만이면 오래 유지해도 동작하지 않는다', (tester) async {
      expect(await pinch(tester, endRatio: 1.2, hold: const Duration(seconds: 1)), 0);
    });

    testWidgets('25% 이상이어도 0.3초 미만이면 동작하지 않는다', (tester) async {
      expect(await pinch(tester, endRatio: 1.5, hold: const Duration(milliseconds: 100)), 0);
    });

    testWidgets('25% 이상이 0.3초 이상 유지되면 한 번 동작한다', (tester) async {
      expect(await pinch(tester, endRatio: 1.5, hold: const Duration(milliseconds: 400)), 1);
    });

    testWidgets('축소(25% 이상)도 같은 임계값으로 동작한다', (tester) async {
      expect(await pinch(tester, endRatio: 0.5, hold: const Duration(milliseconds: 400)), 1);
    });

    testWidgets('한 손가락 스크롤은 그대로 동작하고 제스처를 일으키지 않는다', (tester) async {
      int triggers = 0;
      await tester.pumpWidget(MaterialApp(
        home: PinchZoomDetector(onTrigger: () => triggers++, child: _scrollList()),
      ));
      await tester.drag(find.byType(ListView), const Offset(0, -300));
      await tester.pump(const Duration(seconds: 1));
      expect(find.text('줄 0'), findsNothing);
      expect(triggers, 0);
    });

    testWidgets('시트: 제목과 슬라이더가 뜨고 조작하면 배율이 바뀐다', (tester) async {
      SharedPreferences.setMockInitialValues({});
      final SharedPreferences prefs = await SharedPreferences.getInstance();
      await tester.pumpWidget(_host(
        Builder(builder: (c) => TextButton(onPressed: () => showTextScaleSheet(c), child: const Text('열기'))),
        prefs: prefs,
      ));
      await tester.tap(find.text('열기'));
      await tester.pumpAndSettle();
      expect(find.text('화면을 확대하시겠습니까?'), findsOneWidget);
      await tester.drag(find.byKey(const ValueKey('text_scale_slider')), const Offset(2000, 0));
      await tester.pumpAndSettle();
      expect(prefs.getDouble(TextScaleConfig.prefsKey), 1.7);
    });
  });

  group('최대 배율 오버플로', () {
    for (final double scale in <double>[1.0, 1.7]) {
      testWidgets('설정·목록·캘린더 탭이 배율 ${(scale * 100).round()}%에서 오버플로 없이 그려진다', (tester) async {
        SharedPreferences.setMockInitialValues({'flutter.text_scale_user': scale});
        final SharedPreferences prefs = await SharedPreferences.getInstance();
        tester.view.physicalSize = const Size(360 * 3, 640 * 3);
        tester.view.devicePixelRatio = 3;
        addTearDown(tester.view.reset);
        for (final Widget w in <Widget>[
          const SettingsScreen(),
          const ListTabPlaceholder(),
          const CalendarPlaceholderScreen(),
        ]) {
          await tester.pumpWidget(_host(w, prefs: prefs, systemScale: 1.5));
          await tester.pumpAndSettle();
          expect(tester.takeException(), isNull);
        }
      });
    }
  });
}
