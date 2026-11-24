import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ieum_mobile/features/voice/application/voice_providers.dart';
import 'package:ieum_mobile/features/voice/domain/recording_models.dart';
import 'package:ieum_mobile/features/voice/domain/voice_ports.dart';
import 'package:ieum_mobile/features/voice/presentation/call_prompt_dialog.dart';
import 'package:ieum_mobile/features/voice/presentation/voice_recorder_screen.dart';

import 'fakes.dart';

class _Harness {
  final FakeRecorder recorder = FakeRecorder();
  final FakeClock clock = FakeClock();
  final FakePreviewPlayer player = FakePreviewPlayer();
  final FakePhoneLauncher launcher = FakePhoneLauncher();

  Widget app({String callName = '엄마', String? callPhone}) => ProviderScope(
        overrides: [
          audioRecorderProvider.overrideWithValue(recorder),
          recordingClockProvider.overrideWithValue(clock),
          previewPlayerProvider.overrideWithValue(player),
          phoneLauncherProvider.overrideWithValue(launcher),
        ],
        child: MaterialApp(home: VoiceRecorderScreen(callName: callName, callPhone: callPhone)),
      );
}

Future<void> _startAndReach180(WidgetTester tester, _Harness h) async {
  await tester.tap(find.text('녹음 시작'));
  await tester.pump();
  h.clock.advance(const Duration(seconds: 180));
  await tester.pump();
  await tester.pump();
}

void _expectButtonsAtLeast56(WidgetTester tester) {
  final Finder buttons = find.byWidgetPredicate(
    (Widget w) => w is ElevatedButton || w is OutlinedButton || w is TextButton,
  );
  expect(buttons, findsWidgets);
  for (final Element e in buttons.evaluate()) {
    final Size size = tester.getSize(find.byWidget(e.widget));
    expect(size.height, greaterThanOrEqualTo(56), reason: '${e.widget.runtimeType} 높이');
    expect(size.width, greaterThanOrEqualTo(48));
  }
}

void main() {
  testWidgets('150초에 타이머가 경고 색으로 바뀌고 안내 문구가 나온다', (WidgetTester tester) async {
    final _Harness h = _Harness();
    await tester.pumpWidget(h.app());
    await tester.tap(find.text('녹음 시작'));
    await tester.pump();

    expect(find.text('30초 뒤 녹음이 마무리됩니다'), findsNothing);
    final ColorScheme colors = Theme.of(tester.element(find.byType(VoiceRecorderScreen))).colorScheme;
    expect(tester.widget<Text>(find.text('00:00')).style?.color, colors.onSurface);

    h.clock.advance(const Duration(seconds: 150));
    await tester.pump();

    expect(find.text('30초 뒤 녹음이 마무리됩니다'), findsOneWidget);
    expect(find.text('02:30'), findsOneWidget);
    expect(tester.widget<Text>(find.text('02:30')).style?.color, colors.error);
  });

  testWidgets('180초에 자동 완료되고 전화 유도 모달이 한 번만 뜬다', (WidgetTester tester) async {
    final _Harness h = _Harness();
    await tester.pumpWidget(h.app(callName: '엄마', callPhone: '01012345678'));
    await _startAndReach180(tester, h);

    expect(find.byType(CallPromptDialog), findsOneWidget);
    expect(find.textContaining('엄마에게 직접 전화해서'), findsOneWidget);
    expect(find.text('녹음한 3분 먼저 등록하기'), findsOneWidget);
    expect(h.recorder.discarded, isEmpty);

    // "먼저 등록하기"(이번 범위에서는 완료 상태로 돌아가기) → 모달이 닫히고 다시 뜨지 않는다.
    await tester.tap(find.text('녹음한 3분 먼저 등록하기'));
    await tester.pumpAndSettle();
    expect(find.byType(CallPromptDialog), findsNothing);
    expect(find.text('들어 보기'), findsOneWidget);

    h.clock.advance(const Duration(seconds: 60));
    await tester.pump();
    expect(find.byType(CallPromptDialog), findsNothing);
    expect(h.recorder.stopCalls, 1);
  });

  testWidgets('전화번호가 있으면 tel: 실행을 호출한다', (WidgetTester tester) async {
    final _Harness h = _Harness();
    await tester.pumpWidget(h.app(callPhone: ' 01012345678 '));
    await _startAndReach180(tester, h);

    await tester.tap(find.text('엄마에게 지금 전화 걸기'));
    await tester.pump();

    expect(h.launcher.called, <String>['01012345678']);
    expect(find.textContaining('전화 앱을 열지 못했어요'), findsNothing);
  });

  testWidgets('전화 실행에 실패하면 직접 걸도록 안내한다', (WidgetTester tester) async {
    final _Harness h = _Harness()..launcher.result = false;
    await tester.pumpWidget(h.app(callPhone: '01012345678'));
    await _startAndReach180(tester, h);

    await tester.tap(find.text('엄마에게 지금 전화 걸기'));
    await tester.pump();

    expect(find.textContaining('전화 앱을 열지 못했어요'), findsOneWidget);
  });

  for (final String? phone in <String?>[null, '', '   ']) {
    testWidgets('전화번호가 없으면(${phone == null ? 'null' : "'$phone'"}) 전화 버튼이 비활성이고 이유가 보인다',
        (WidgetTester tester) async {
      final _Harness h = _Harness();
      await tester.pumpWidget(h.app(callPhone: phone));
      await _startAndReach180(tester, h);

      final ElevatedButton call = tester.widget<ElevatedButton>(
        find.widgetWithText(ElevatedButton, '엄마에게 지금 전화 걸기'),
      );
      expect(call.onPressed, isNull);
      expect(find.textContaining('등록된 전화번호가 없어서'), findsOneWidget);
      expect(h.launcher.called, isEmpty);
      // 비활성이어도 먼저 등록하기는 누를 수 있다.
      expect(find.text('녹음한 3분 먼저 등록하기'), findsOneWidget);
    });
  }

  testWidgets('권한 거부·다른 앱 사용 안내가 화면에 나온다', (WidgetTester tester) async {
    final _Harness h = _Harness()..recorder.startError = permissionDenied;
    await tester.pumpWidget(h.app());
    await tester.tap(find.text('녹음 시작'));
    await tester.pump();
    expect(find.textContaining('마이크 사용을 허용해 주셔야'), findsOneWidget);
    expect(find.text('녹음 시작'), findsOneWidget);

    h.recorder.startError = const RecorderException(RecordingNotice.permissionPermanentlyDenied);
    await tester.tap(find.text('녹음 시작'));
    await tester.pump();
    expect(find.textContaining('휴대폰 설정에서'), findsOneWidget);
    expect(find.text('설정 열기'), findsOneWidget);

    h.recorder.startError = const RecorderException(RecordingNotice.micBusy);
    await tester.tap(find.text('녹음 시작'));
    await tester.pump();
    expect(find.textContaining('다른 앱이 마이크를'), findsOneWidget);
  });

  testWidgets('저장 공간 부족 안내는 이미 녹음한 내용이 남아 있다고 알려 준다', (WidgetTester tester) async {
    final _Harness h = _Harness();
    await tester.pumpWidget(h.app());
    await tester.tap(find.text('녹음 시작'));
    await tester.pump();
    h.recorder.stopError = const RecorderException(RecordingNotice.storageFull);
    h.clock.advance(const Duration(seconds: 30));
    await tester.tap(find.text('녹음 끝내기'));
    await tester.pump();
    await tester.pump();

    expect(find.textContaining('저장 공간이 부족해요'), findsOneWidget);
    expect(find.textContaining('남아 있어요'), findsOneWidget);
    expect(h.recorder.discarded, isEmpty);
    expect(find.text('들어 보기'), findsOneWidget);
  });

  testWidgets('미리 듣기: 재생/정지와 탐색 바가 동작한다', (WidgetTester tester) async {
    final _Harness h = _Harness();
    await tester.pumpWidget(h.app());
    await tester.tap(find.text('녹음 시작'));
    await tester.pump();
    h.clock.advance(const Duration(seconds: 20));
    await tester.tap(find.text('녹음 끝내기'));
    await tester.pump();
    await tester.pump();

    expect(h.player.loaded, <String>[h.recorder.started.single]);
    await tester.tap(find.text('들어 보기'));
    await tester.pump();
    expect(h.player.plays, 1);
    expect(find.text('멈추기'), findsOneWidget);

    await tester.tap(find.text('멈추기'));
    await tester.pump();
    expect(h.player.pauses, 1);

    await tester.tap(find.byType(Slider));
    await tester.pump();
    expect(h.player.seeks, isNotEmpty);

    // 등록은 이번 범위에서 비활성 자리표시.
    final ElevatedButton register = tester.widget<ElevatedButton>(
      find.widgetWithText(ElevatedButton, '답변으로 등록하기 (준비 중)'),
    );
    expect(register.onPressed, isNull);
  });

  group('접근성 불변식', () {
    testWidgets('경고 색 타이머와 본문의 배경 대비가 4.5:1 이상이다', (WidgetTester tester) async {
      await tester.pumpWidget(_Harness().app());
      final ThemeData theme = Theme.of(tester.element(find.byType(VoiceRecorderScreen)));
      final Color bg = theme.scaffoldBackgroundColor;
      double lum(Color c) => c.computeLuminance();
      double contrast(Color a, Color b) {
        final double hi = lum(a) > lum(b) ? lum(a) : lum(b);
        final double lo = lum(a) > lum(b) ? lum(b) : lum(a);
        return (hi + 0.05) / (lo + 0.05);
      }

      expect(contrast(theme.colorScheme.error, bg), greaterThanOrEqualTo(4.5));
      expect(contrast(theme.colorScheme.onSurface, bg), greaterThanOrEqualTo(4.5));
    });

    testWidgets('녹음 전·녹음 중·일시정지·완료 화면 모두 본문 18sp 이상, 버튼 56dp 이상, 한글 라벨', (WidgetTester tester) async {
      final _Harness h = _Harness();
      await tester.pumpWidget(h.app());

      Future<void> check() async {
        // 앱바 제목은 AppBar가 따로 크기를 정하므로 제외하고 본문 Text만 본다.
        final List<Element> bodyTexts = find
            .descendant(of: find.byType(ListView), matching: find.byType(Text))
            .evaluate()
            .toList();
        expect(bodyTexts, isNotEmpty);
        for (final Element e in bodyTexts) {
          final Text t = e.widget as Text;
          final TextStyle style = DefaultTextStyle.of(e).style.merge(t.style);
          expect(style.fontSize ?? 14, greaterThanOrEqualTo(18), reason: '"${t.data}"');
        }
        _expectButtonsAtLeast56(tester);
      }

      await check(); // idle
      await tester.tap(find.text('녹음 시작'));
      await tester.pump();
      expect(find.text('잠깐 멈추기'), findsOneWidget);
      await check(); // recording

      await tester.tap(find.text('잠깐 멈추기'));
      await tester.pump();
      expect(find.text('이어서 녹음'), findsOneWidget);
      await check(); // paused

      await tester.tap(find.text('녹음 끝내기'));
      await tester.pump();
      await tester.pump();
      expect(find.text('다시 녹음'), findsOneWidget);
      await check(); // completed
    });

    testWidgets('전화 유도 모달: 본문 18sp 이상, 버튼 56dp 이상', (WidgetTester tester) async {
      final _Harness h = _Harness();
      await tester.pumpWidget(h.app(callPhone: '01012345678'));
      await _startAndReach180(tester, h);

      final Finder dialog = find.byType(CallPromptDialog);
      expect(dialog, findsOneWidget);
      for (final Element e in find.descendant(of: dialog, matching: find.byType(Text)).evaluate()) {
        final Text t = e.widget as Text;
        final TextStyle style = DefaultTextStyle.of(e).style.merge(t.style);
        expect(style.fontSize ?? 14, greaterThanOrEqualTo(18), reason: '"${t.data}"');
      }
      for (final Element e in find.descendant(of: dialog, matching: find.byType(ElevatedButton)).evaluate()) {
        expect(tester.getSize(find.byWidget(e.widget)).height, greaterThanOrEqualTo(56));
      }
      for (final Element e in find.descendant(of: dialog, matching: find.byType(OutlinedButton)).evaluate()) {
        expect(tester.getSize(find.byWidget(e.widget)).height, greaterThanOrEqualTo(56));
      }
    });
  });
}
