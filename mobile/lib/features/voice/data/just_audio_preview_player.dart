import 'package:ieum_mobile/features/voice/domain/voice_ports.dart';
import 'package:just_audio/just_audio.dart';

class JustAudioPreviewPlayer implements PreviewPlayerPort {
  final AudioPlayer _player = AudioPlayer();

  @override
  Future<void> load(String path) async {
    await _player.setFilePath(path);
  }

  @override
  Future<void> play() => _player.play();

  @override
  Future<void> pause() => _player.pause();

  @override
  Future<void> seek(Duration position) => _player.seek(position);

  @override
  Stream<Duration> get position => _player.positionStream;

  @override
  Stream<Duration?> get duration => _player.durationStream;

  @override
  Stream<bool> get playing => _player.playingStream;

  @override
  Stream<bool> get completed =>
      _player.processingStateStream.map((ProcessingState s) => s == ProcessingState.completed);

  @override
  Future<void> dispose() => _player.dispose();
}
