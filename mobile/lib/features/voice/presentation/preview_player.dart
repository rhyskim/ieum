import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/features/group/presentation/group_widgets.dart';
import 'package:ieum_mobile/features/voice/application/voice_providers.dart';
import 'package:ieum_mobile/features/voice/domain/voice_ports.dart';

/// 녹음 직후 미리 듣기: 재생/정지와 탐색 바.
class PreviewPlayer extends ConsumerStatefulWidget {
  const PreviewPlayer({required this.path, super.key});

  final String path;

  @override
  ConsumerState<PreviewPlayer> createState() => _PreviewPlayerState();
}

class _PreviewPlayerState extends ConsumerState<PreviewPlayer> {
  late final PreviewPlayerPort _player = ref.read(previewPlayerProvider);
  final List<StreamSubscription<Object?>> _subs = <StreamSubscription<Object?>>[];
  Duration _position = Duration.zero;
  Duration _duration = Duration.zero;
  bool _playing = false;
  bool _loadFailed = false;

  @override
  void initState() {
    super.initState();
    // autoDispose provider가 마운트 중 정리되지 않도록 구독을 유지한다.
    ref.listenManual(previewPlayerProvider, (_, __) {});
    _subs
      ..add(_player.position.listen((Duration p) => setState(() => _position = p)))
      ..add(_player.duration.listen((Duration? d) => setState(() => _duration = d ?? Duration.zero)))
      ..add(_player.playing.listen((bool p) => setState(() => _playing = p)))
      ..add(_player.completed.listen((bool done) {
        if (done) {
          unawaited(_player.pause());
          unawaited(_player.seek(Duration.zero));
        }
      }));
    unawaited(_load());
  }

  Future<void> _load() async {
    try {
      await _player.load(widget.path);
    } on Object {
      if (mounted) setState(() => _loadFailed = true);
    }
  }

  @override
  void dispose() {
    for (final StreamSubscription<Object?> s in _subs) {
      s.cancel();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (_loadFailed) {
      return const Text('녹음은 저장됐지만 지금은 들려드리지 못해요.', style: TextStyle(fontSize: 18));
    }
    final double max = _duration.inMilliseconds <= 0 ? 1 : _duration.inMilliseconds.toDouble();
    final double value = _position.inMilliseconds.clamp(0, max.toInt()).toDouble();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        LargeButton(
          label: _playing ? '멈추기' : '들어 보기',
          onPressed: () => _playing ? _player.pause() : _player.play(),
        ),
        Semantics(
          label: '재생 위치',
          child: SizedBox(
            height: 56,
            child: Slider(
              value: value,
              max: max,
              onChanged: (double v) => _player.seek(Duration(milliseconds: v.round())),
            ),
          ),
        ),
        Text(
          '${_fmt(_position)} / ${_fmt(_duration)}',
          textAlign: TextAlign.center,
          style: const TextStyle(fontSize: 18),
        ),
      ],
    );
  }

  static String _fmt(Duration d) =>
      '${d.inMinutes.toString().padLeft(2, '0')}:${d.inSeconds.remainder(60).toString().padLeft(2, '0')}';
}
