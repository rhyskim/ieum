import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:ieum_mobile/core/voice/mic_permission.dart';
import 'package:ieum_mobile/core/voice/stt_recognition_result.dart';
import 'package:ieum_mobile/core/voice/stt_service.dart';
import 'package:ieum_mobile/core/voice/tts_service.dart';
import 'package:ieum_mobile/core/voice/voice_providers.dart';
import 'package:ieum_mobile/features/question/data/question_repository_impl.dart';
import 'package:ieum_mobile/features/question/domain/question_models.dart';
import 'package:ieum_mobile/features/question/domain/question_repository.dart';
import 'package:ieum_mobile/features/question/presentation/answer_compose_screen.dart';
import 'package:ieum_mobile/features/question/presentation/listen_question_button.dart';
import 'package:ieum_mobile/features/question/presentation/speak_to_write_button.dart';

class _FakeTts implements TtsService {
  bool koreanAvailable = true;
  final List<double> rates = <double>[];
  int stopCalls = 0;
  Completer<void>? _speaking;

  @override
  Future<bool> initialize() async => koreanAvailable;

  @override
  Future<void> speak(String text, {double speechRate = TtsSpeechRate.normal}) {
    rates.add(speechRate);
    _speaking = Completer<void>();
    return _speaking!.future;
  }

  @override
  Future<void> stop() async {
    stopCalls++;
    final Completer<void>? c = _speaking;
    if (c != null && !c.isCompleted) c.complete();
  }

  void finishSpeaking() => _speaking?.complete();
}

class _FakeStt implements SttService {
  SttAvailability availability = SttAvailability.ready;
  StreamController<SttRecognitionResult> results = StreamController<SttRecognitionResult>();
  int stopCalls = 0;

  @override
  Future<SttAvailability> initialize() async => availability;

  @override
  Stream<SttRecognitionResult> startListening({Duration maxDuration = const Duration(minutes: 3)}) {
    results = StreamController<SttRecognitionResult>();
    return results.stream;
  }

  @override
  Future<void> stopListening() async {
    stopCalls++;
    if (!results.isClosed) await results.close();
  }
}

class _FakePermission implements MicPermissionService {
  int openSettingsCalls = 0;

  @override
  Future<MicPermissionStatus> request() async => MicPermissionStatus.granted;

  @override
  Future<void> openSettings() async => openSettingsCalls++;
}

class _FakeRepo implements QuestionRepository {
  final List<String> saved = <String>[];

  @override
  Future<void> saveAnswer(String groupId, String dailyQuestionId, String text) async => saved.add(text);

  @override
  Future<TodayQuestion> today(String groupId) => throw UnimplementedError();

  @override
  Future<QuestionDetail> detail(String groupId, String dailyQuestionId) => throw UnimplementedError();

  @override
  Future<AnswerList> answers(String groupId, String dailyQuestionId) => throw UnimplementedError();
}

Widget _wrap(Widget child, {_FakeTts? tts, _FakeStt? stt, _FakePermission? permission, _FakeRepo? repo}) {
  return ProviderScope(
    overrides: [
      ttsServiceProvider.overrideWithValue(tts ?? _FakeTts()),
      sttServiceProvider.overrideWithValue(stt ?? _FakeStt()),
      micPermissionServiceProvider.overrideWithValue(permission ?? _FakePermission()),
      if (repo != null) questionRepositoryProvider.overrideWithValue(repo),
    ],
    child: MaterialApp(home: Scaffold(body: SingleChildScrollView(child: child))),
  );
}

void main() {
  group('질문 듣기', () {
    testWidgets('듣기 버튼이 재생/정지로 바뀌고, 정지하면 서비스에 정지가 전달된다', (tester) async {
      final _FakeTts tts = _FakeTts();
      await tester.pumpWidget(_wrap(const ListenQuestionButton(text: '오늘 기뻤던 일은?'), tts: tts));
      await tester.pumpAndSettle();

      await tester.tap(find.text('질문 듣기'));
      await tester.pump();
      expect(find.text('듣기 멈추기'), findsOneWidget);
      expect(tts.rates, <double>[TtsSpeechRate.normal]);

      await tester.tap(find.text('듣기 멈추기'));
      await tester.pumpAndSettle();
      expect(tts.stopCalls, 1);
      expect(find.text('질문 듣기'), findsOneWidget);
    });

    testWidgets('읽기가 끝나면 버튼이 다시 "질문 듣기"로 돌아온다', (tester) async {
      final _FakeTts tts = _FakeTts();
      await tester.pumpWidget(_wrap(const ListenQuestionButton(text: '질문'), tts: tts));
      await tester.pumpAndSettle();
      await tester.tap(find.text('질문 듣기'));
      await tester.pump();

      tts.finishSpeaking();
      await tester.pumpAndSettle();
      expect(find.text('질문 듣기'), findsOneWidget);
    });

    testWidgets('천천히 읽기를 켜면 0.85배로 전달되고, 읽는 중이면 새 속도로 다시 읽는다', (tester) async {
      final _FakeTts tts = _FakeTts();
      await tester.pumpWidget(_wrap(const ListenQuestionButton(text: '질문'), tts: tts));
      await tester.pumpAndSettle();

      await tester.tap(find.text('질문 듣기'));
      await tester.pump();
      await tester.tap(find.byType(Switch));
      await tester.pumpAndSettle();
      expect(tts.rates, <double>[TtsSpeechRate.normal, TtsSpeechRate.slow]);
      expect(find.text('듣기 멈추기'), findsOneWidget);

      await tester.tap(find.byType(Switch));
      await tester.pumpAndSettle();
      expect(tts.rates.last, TtsSpeechRate.normal);
    });

    testWidgets('화면을 벗어나면 읽기를 멈춘다', (tester) async {
      final _FakeTts tts = _FakeTts();
      await tester.pumpWidget(_wrap(const ListenQuestionButton(text: '질문'), tts: tts));
      await tester.pumpAndSettle();
      await tester.tap(find.text('질문 듣기'));
      await tester.pump();
      expect(tts.stopCalls, 0);

      await tester.pumpWidget(_wrap(const SizedBox.shrink(), tts: tts));
      await tester.pumpAndSettle();
      expect(tts.stopCalls, greaterThanOrEqualTo(1));
    });

    testWidgets('한국어 음성이 없으면 버튼이 꺼지고 이유를 안내한다', (tester) async {
      final _FakeTts tts = _FakeTts()..koreanAvailable = false;
      await tester.pumpWidget(_wrap(const ListenQuestionButton(text: '질문'), tts: tts));
      await tester.pumpAndSettle();

      final ElevatedButton? elevated = tester.widgetList<ElevatedButton>(find.byType(ElevatedButton)).firstOrNull;
      expect(elevated, isNull); // 듣기 버튼은 outlined
      expect(tester.widget<OutlinedButton>(find.byType(OutlinedButton)).onPressed, isNull);
      expect(find.textContaining('한국어 음성이 없어서'), findsOneWidget);
    });
  });

  group('말해서 쓰기', () {
    Future<TextEditingController> pumpButton(WidgetTester tester, _FakeStt stt, {_FakePermission? permission}) async {
      final TextEditingController controller = TextEditingController(text: '오늘은');
      addTearDown(controller.dispose);
      await tester.pumpWidget(_wrap(SpeakToWriteButton(controller: controller), stt: stt, permission: permission));
      return controller;
    }

    testWidgets('부분·최종 결과가 입력창에 실시간 반영되고 기존 글은 지워지지 않고 이어 붙는다', (tester) async {
      final _FakeStt stt = _FakeStt();
      final TextEditingController controller = await pumpButton(tester, stt);

      await tester.tap(find.text('말해서 쓰기'));
      await tester.pumpAndSettle();
      expect(find.textContaining('듣는 중'), findsOneWidget);

      stt.results.add(const SttRecognitionResult(text: '손주', isFinal: false));
      await tester.pump();
      expect(controller.text, '오늘은 손주');

      stt.results.add(const SttRecognitionResult(text: '손주가 왔어요', isFinal: false));
      await tester.pump();
      expect(controller.text, '오늘은 손주가 왔어요'); // 부분 결과는 누적이 아니라 교체

      stt.results.add(const SttRecognitionResult(text: '손주가 왔어요', isFinal: true));
      await tester.pump();
      await stt.results.close();
      await tester.pumpAndSettle();
      expect(controller.text, '오늘은 손주가 왔어요');
      expect(find.text('말해서 쓰기'), findsOneWidget);
    });

    testWidgets('듣는 중에 다시 누르면 인식을 멈춘다', (tester) async {
      final _FakeStt stt = _FakeStt();
      await pumpButton(tester, stt);
      await tester.tap(find.text('말해서 쓰기'));
      await tester.pumpAndSettle();

      await tester.tap(find.textContaining('듣는 중'));
      await tester.pumpAndSettle();
      expect(stt.stopCalls, 1);
      expect(find.text('말해서 쓰기'), findsOneWidget);
    });

    testWidgets('권한 거부: 안내가 나오고 예외가 없으며 다시 눌러 볼 수 있다', (tester) async {
      final _FakeStt stt = _FakeStt()..availability = SttAvailability.permissionDenied;
      await pumpButton(tester, stt);
      await tester.tap(find.text('말해서 쓰기'));
      await tester.pumpAndSettle();

      expect(find.textContaining('마이크 사용을 허락해야'), findsOneWidget);
      expect(find.text('설정 열기'), findsNothing);
      expect(tester.takeException(), isNull);
      expect(tester.widget<OutlinedButton>(find.byType(OutlinedButton)).onPressed, isNotNull);
    });

    testWidgets('영구 거부: 설정 열기 안내와 버튼이 나오고 누르면 설정을 연다', (tester) async {
      final _FakeStt stt = _FakeStt()..availability = SttAvailability.permissionPermanentlyDenied;
      final _FakePermission permission = _FakePermission();
      await pumpButton(tester, stt, permission: permission);
      await tester.tap(find.text('말해서 쓰기'));
      await tester.pumpAndSettle();

      expect(find.textContaining('설정에서 이음의 마이크를 허락'), findsOneWidget);
      await tester.tap(find.text('설정 열기'));
      await tester.pump();
      expect(permission.openSettingsCalls, 1);
    });

    testWidgets('미지원 기기: 글자로 쓰라고 안내하고 예외가 없다', (tester) async {
      final _FakeStt stt = _FakeStt()..availability = SttAvailability.unsupported;
      await pumpButton(tester, stt);
      await tester.tap(find.text('말해서 쓰기'));
      await tester.pumpAndSettle();

      expect(find.textContaining('이 기기에서는 말로 쓰기를 쓸 수 없어요'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });

    testWidgets('인식 중 오류: 안내가 나오고 입력창의 글은 그대로이며 예외가 전파되지 않는다', (tester) async {
      final _FakeStt stt = _FakeStt();
      final TextEditingController controller = await pumpButton(tester, stt);
      await tester.tap(find.text('말해서 쓰기'));
      await tester.pumpAndSettle();

      stt.results.add(const SttRecognitionResult(text: '안녕', isFinal: false));
      await tester.pump();
      stt.results.addError(const SttException('error_network'));
      await tester.pumpAndSettle();

      expect(find.textContaining('말을 알아듣지 못했어요'), findsOneWidget);
      expect(controller.text, '오늘은 안녕');
      expect(find.text('말해서 쓰기'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  });

  group('답변 작성 화면 연동', () {
    testWidgets('말로 채운 글을 고친 뒤 저장하면 기존 텍스트 답변 API로 그대로 전달된다', (tester) async {
      final _FakeStt stt = _FakeStt();
      final _FakeRepo repo = _FakeRepo();
      final GoRouter router = GoRouter(routes: <RouteBase>[
        GoRoute(path: '/', builder: (c, s) => const Scaffold(body: Text('목록'))),
        GoRoute(
          path: '/answer',
          builder: (c, s) => const AnswerComposeScreen(groupId: 'g1', dailyQuestionId: 'dq1'),
        ),
      ]);
      await tester.pumpWidget(ProviderScope(
        overrides: [
          ttsServiceProvider.overrideWithValue(_FakeTts()),
          sttServiceProvider.overrideWithValue(stt),
          micPermissionServiceProvider.overrideWithValue(_FakePermission()),
          questionRepositoryProvider.overrideWithValue(repo),
        ],
        child: MaterialApp.router(routerConfig: router),
      ));
      router.push('/answer');
      await tester.pumpAndSettle();

      // 30자 미만이면 저장 버튼이 막힌 채로 남는다(서버 규칙 불변).
      await tester.tap(find.text('말해서 쓰기'));
      await tester.pumpAndSettle();
      stt.results.add(const SttRecognitionResult(text: '짧은 말', isFinal: true));
      await tester.pump();
      await stt.results.close();
      await tester.pumpAndSettle();
      expect(find.textContaining('이상 써 주세요'), findsOneWidget);
      await tester.ensureVisible(find.widgetWithText(ElevatedButton, '저장하기'));
      await tester.pump();
      expect(tester.widget<ElevatedButton>(find.widgetWithText(ElevatedButton, '저장하기')).onPressed, isNull);

      // 이어서 말해 30자를 넘기고, 직접 한 글자를 고친 뒤 저장한다.
      await tester.ensureVisible(find.text('말해서 쓰기'));
      await tester.pump();
      await tester.tap(find.text('말해서 쓰기'));
      await tester.pumpAndSettle();
      const String spoken = '오늘은 손주가 놀러 와서 하루 종일 함께 밥도 먹고 산책도 하고 정말 행복했어요';
      stt.results.add(const SttRecognitionResult(text: spoken, isFinal: true));
      await tester.pump();
      await stt.results.close();
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.byType(TextField));
      await tester.enterText(find.byType(TextField), '짧은 말 $spoken!');
      await tester.pump();

      await tester.ensureVisible(find.widgetWithText(ElevatedButton, '저장하기'));
      await tester.pump();
      await tester.tap(find.widgetWithText(ElevatedButton, '저장하기'));
      await tester.pumpAndSettle();
      expect(repo.saved, <String>['짧은 말 $spoken!']);
    });
  });
}
