import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ieum_mobile/features/voice/application/recording_controller.dart';
import 'package:ieum_mobile/features/voice/application/voice_providers.dart';
import 'package:ieum_mobile/features/voice/domain/recording_models.dart';
import 'package:ieum_mobile/features/voice/domain/voice_ports.dart';

import 'fakes.dart';

void main() {
  late FakeRecorder recorder;
  late FakeClock clock;
  late ProviderContainer container;

  RecordingState read() => container.read(recordingControllerProvider);
  RecordingController ctl() => container.read(recordingControllerProvider.notifier);

  setUp(() {
    recorder = FakeRecorder();
    clock = FakeClock();
    container = ProviderContainer(overrides: [
      audioRecorderProvider.overrideWithValue(recorder),
      recordingClockProvider.overrideWithValue(clock),
    ]);
    // autoDispose provider가 테스트 중 사라지지 않도록 구독을 유지한다.
    container.listen(recordingControllerProvider, (_, __) {});
  });

  tearDown(() => container.dispose());

  group('시간 규칙', () {
    test('149초까지는 경고가 아니고 150초에 경고 상태가 된다', () async {
      await ctl().start();
      clock.advance(const Duration(seconds: 149));
      expect(read().isWarning, isFalse);
      expect(read().phase, RecordingPhase.recording);

      clock.advance(const Duration(seconds: 1));
      expect(read().isWarning, isTrue);
      expect(read().elapsed, const Duration(seconds: 150));
      expect(read().phase, RecordingPhase.recording);
    });

    test('180초에 자동 완료되고 파일 경로가 보존되며 전화 유도는 한 번만 소비된다', () async {
      await ctl().start();
      final String path = recorder.started.single;

      clock.advance(const Duration(seconds: 179));
      expect(read().phase, RecordingPhase.recording);
      clock.advance(const Duration(seconds: 1));
      await Future<void>.delayed(Duration.zero);

      expect(read().phase, RecordingPhase.completed);
      expect(read().reachedMax, isTrue);
      expect(read().elapsed, recordingMaxDuration);
      expect(read().filePath, path);
      expect(recorder.stopCalls, 1);
      expect(recorder.discarded, isEmpty);

      expect(read().callPromptPending, isTrue);
      expect(ctl().consumeCallPrompt(), isTrue);
      expect(ctl().consumeCallPrompt(), isFalse);

      // 이후 시간이 더 흘러도 다시 완료되거나 모달이 또 뜨지 않는다.
      clock.advance(const Duration(seconds: 30));
      await Future<void>.delayed(Duration.zero);
      expect(recorder.stopCalls, 1);
      expect(read().callPromptPending, isFalse);
      expect(read().elapsed, recordingMaxDuration);
    });

    test('일시정지 시간은 경과 시간에 포함되지 않는다', () async {
      await ctl().start();
      clock.advance(const Duration(seconds: 60));
      await ctl().pause();
      expect(read().phase, RecordingPhase.paused);
      expect(read().elapsed, const Duration(seconds: 60));

      clock.advance(const Duration(seconds: 200)); // 정지 중 흐른 시간
      expect(read().elapsed, const Duration(seconds: 60));
      expect(read().phase, RecordingPhase.paused);
      expect(read().reachedMax, isFalse);

      await ctl().resume();
      clock.advance(const Duration(seconds: 30));
      expect(read().phase, RecordingPhase.recording);
      expect(read().elapsed, const Duration(seconds: 90));
      expect(read().isWarning, isFalse);
    });

    test('일시정지를 여러 번 끼워도 150초는 녹음된 시간 기준이다', () async {
      await ctl().start();
      clock.advance(const Duration(seconds: 100));
      await ctl().pause();
      clock.advance(const Duration(seconds: 500));
      await ctl().resume();
      clock.advance(const Duration(seconds: 49));
      expect(read().isWarning, isFalse);
      clock.advance(const Duration(seconds: 1));
      expect(read().isWarning, isTrue);
      expect(read().elapsed, const Duration(seconds: 150));
    });
  });

  group('상태 전이', () {
    test('idle → recording → paused → recording → completed(수동)', () async {
      expect(read().phase, RecordingPhase.idle);
      await ctl().start();
      expect(read().phase, RecordingPhase.recording);
      clock.advance(const Duration(seconds: 10));
      await ctl().pause();
      expect(read().phase, RecordingPhase.paused);
      await ctl().resume();
      expect(read().phase, RecordingPhase.recording);
      clock.advance(const Duration(seconds: 5));
      await ctl().finish();

      expect(read().phase, RecordingPhase.completed);
      expect(read().reachedMax, isFalse);
      expect(read().callPromptPending, isFalse);
      expect(read().elapsed, const Duration(seconds: 15));
      expect(read().filePath, recorder.started.single);
    });

    test('일시정지 상태에서도 완료할 수 있다', () async {
      await ctl().start();
      clock.advance(const Duration(seconds: 20));
      await ctl().pause();
      await ctl().finish();
      expect(read().phase, RecordingPhase.completed);
      expect(read().elapsed, const Duration(seconds: 20));
    });

    test('다시 녹음: 새 녹음이 시작된 뒤에 이전 파일을 교체하고 시간은 0부터', () async {
      await ctl().start();
      clock.advance(const Duration(seconds: 30));
      await ctl().finish();
      final String first = read().filePath!;

      await ctl().start();
      expect(read().phase, RecordingPhase.recording);
      expect(read().elapsed, Duration.zero);
      expect(read().filePath, isNull);
      expect(recorder.discarded, <String>[first]);
      expect(recorder.started, hasLength(2));
    });

    test('녹음 중에는 start를 다시 눌러도 새 파일이 만들어지지 않는다', () async {
      await ctl().start();
      await ctl().start();
      expect(recorder.started, hasLength(1));
    });
  });

  group('실패 안내와 파일 보존', () {
    test('권한 거부: 안내만 띄우고 녹음 상태로 가지 않는다', () async {
      recorder.startError = permissionDenied;
      await ctl().start();
      expect(read().phase, RecordingPhase.idle);
      expect(read().notice, RecordingNotice.permissionDenied);
    });

    test('영구 거부와 다른 앱의 마이크 사용은 각각 다른 안내', () async {
      recorder.startError = const RecorderException(RecordingNotice.permissionPermanentlyDenied);
      await ctl().start();
      expect(read().notice, RecordingNotice.permissionPermanentlyDenied);

      recorder.startError = const RecorderException(RecordingNotice.micBusy);
      await ctl().start();
      expect(read().notice, RecordingNotice.micBusy);
      expect(read().phase, RecordingPhase.idle);
    });

    test('다시 녹음이 실패해도 이전 파일은 지워지지 않고 그대로 남는다', () async {
      await ctl().start();
      clock.advance(const Duration(seconds: 40));
      await ctl().finish();
      final RecordingState before = read();

      recorder.startError = const RecorderException(RecordingNotice.micBusy);
      await ctl().start();

      expect(recorder.discarded, isEmpty);
      expect(read().phase, RecordingPhase.completed);
      expect(read().filePath, before.filePath);
      expect(read().notice, RecordingNotice.micBusy);
    });

    test('저장 공간 부족으로 마무리가 실패해도 경로를 유지하고 삭제하지 않는다', () async {
      await ctl().start();
      final String path = recorder.started.single;
      recorder.stopError = const RecorderException(RecordingNotice.storageFull);

      clock.advance(const Duration(seconds: 180));
      await Future<void>.delayed(Duration.zero);

      expect(read().phase, RecordingPhase.completed);
      expect(read().filePath, path);
      expect(read().notice, RecordingNotice.storageFull);
      expect(recorder.discarded, isEmpty);
      // 자동 완료였으므로 전화 유도도 그대로 이어진다.
      expect(read().callPromptPending, isTrue);
    });

    test('통화 인터럽트가 시작되면 일시정지하고 안내하며 파일은 그대로다', () async {
      await ctl().start();
      clock.advance(const Duration(seconds: 25));

      recorder.interruptionController.add(InterruptionKind.begin);
      await Future<void>.delayed(Duration.zero);

      expect(read().phase, RecordingPhase.paused);
      expect(read().notice, RecordingNotice.interruptedByCall);
      expect(read().elapsed, const Duration(seconds: 25));
      expect(recorder.discarded, isEmpty);
      expect(recorder.stopCalls, 0);

      // 인터럽트가 끝나도 저절로 이어 녹음하지 않는다. 직접 이어서 누른다.
      recorder.interruptionController.add(InterruptionKind.end);
      await Future<void>.delayed(Duration.zero);
      expect(read().phase, RecordingPhase.paused);
      await ctl().resume();
      expect(read().phase, RecordingPhase.recording);
      expect(read().notice, RecordingNotice.none);
    });

    test('백그라운드로 가면 일시정지하고 안내한다', () async {
      await ctl().start();
      clock.advance(const Duration(seconds: 12));
      await ctl().onBackgrounded();
      expect(read().phase, RecordingPhase.paused);
      expect(read().notice, RecordingNotice.pausedInBackground);
      expect(read().elapsed, const Duration(seconds: 12));
    });

    test('이어서 녹음하지 못해도 지금까지의 녹음은 완료 상태로 보존한다', () async {
      await ctl().start();
      clock.advance(const Duration(seconds: 50));
      await ctl().pause();
      recorder.resumeError = const RecorderException(RecordingNotice.micBusy);

      await ctl().resume();

      expect(read().phase, RecordingPhase.completed);
      expect(read().filePath, recorder.started.single);
      expect(read().elapsed, const Duration(seconds: 50));
      expect(read().notice, RecordingNotice.micBusy);
      expect(recorder.discarded, isEmpty);
    });

    test('안내를 닫으면 notice만 지워진다', () async {
      recorder.startError = permissionDenied;
      await ctl().start();
      ctl().dismissNotice();
      expect(read().notice, RecordingNotice.none);
    });
  });
}
