import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/core/voice/mic_permission_gateway.dart';
import 'package:ieum_mobile/features/voice/data/just_audio_preview_player.dart';
import 'package:ieum_mobile/features/voice/data/record_audio_recorder.dart';
import 'package:ieum_mobile/features/voice/data/system_recording_clock.dart';
import 'package:ieum_mobile/features/voice/data/url_phone_launcher.dart';
import 'package:ieum_mobile/features/voice/domain/voice_ports.dart';

final Provider<MicPermissionGateway> micPermissionGatewayProvider =
    Provider<MicPermissionGateway>((Ref ref) => const PermissionHandlerMicGateway());

/// 화면이 닫히면 녹음기를 정리한다. 테스트에서는 가짜로 override한다.
final AutoDisposeProvider<AudioRecorderPort> audioRecorderProvider = Provider.autoDispose<AudioRecorderPort>((Ref ref) {
  final RecordAudioRecorder recorder = RecordAudioRecorder(ref.watch(micPermissionGatewayProvider));
  ref.onDispose(recorder.dispose);
  return recorder;
});

final Provider<RecordingClock> recordingClockProvider =
    Provider<RecordingClock>((Ref ref) => const SystemRecordingClock());

final AutoDisposeProvider<PreviewPlayerPort> previewPlayerProvider = Provider.autoDispose<PreviewPlayerPort>((Ref ref) {
  final JustAudioPreviewPlayer player = JustAudioPreviewPlayer();
  ref.onDispose(player.dispose);
  return player;
});

final Provider<PhoneLauncher> phoneLauncherProvider =
    Provider<PhoneLauncher>((Ref ref) => const UrlPhoneLauncher());
