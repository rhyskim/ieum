import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/core/voice/mic_permission.dart';
import 'package:ieum_mobile/core/voice/stt_recognition_result.dart';
import 'package:ieum_mobile/core/voice/stt_service.dart';
import 'package:ieum_mobile/core/voice/voice_providers.dart';
import 'package:ieum_mobile/features/group/presentation/group_widgets.dart';

enum _Notice { none, denied, permanentlyDenied, unsupported, error }

/// "말해서 쓰기". 인식 결과를 입력창에 실시간으로 채운다. 입력창의 기존 글은 지우지 않고 뒤에 이어 붙이며,
/// 채워진 글은 초안일 뿐이라 사용자가 고친 뒤 기존 텍스트 답변 API로 저장한다.
class SpeakToWriteButton extends ConsumerStatefulWidget {
  const SpeakToWriteButton({required this.controller, this.onTextChanged, super.key});

  final TextEditingController controller;

  /// 인식 결과로 입력창이 바뀔 때마다 부른다(글자 수 안내 갱신용).
  final VoidCallback? onTextChanged;

  @override
  ConsumerState<SpeakToWriteButton> createState() => _SpeakToWriteButtonState();
}

class _SpeakToWriteButtonState extends ConsumerState<SpeakToWriteButton> {
  // dispose에서 ref를 쓸 수 없으므로 시작할 때 미리 잡아 둔다.
  late final SttService _stt;
  late final MicPermissionService _permission;
  StreamSubscription<SttRecognitionResult>? _sub;
  bool _starting = false;
  bool _listening = false;
  _Notice _notice = _Notice.none;
  String _base = '';

  @override
  void initState() {
    super.initState();
    _stt = ref.read(sttServiceProvider);
    _permission = ref.read(micPermissionServiceProvider);
  }

  @override
  void dispose() {
    _sub?.cancel();
    _stt.stopListening();
    super.dispose();
  }

  Future<void> _start() async {
    setState(() {
      _starting = true;
      _notice = _Notice.none;
    });
    final SttAvailability availability = await _stt.initialize();
    if (!mounted) return;
    if (availability != SttAvailability.ready) {
      setState(() {
        _starting = false;
        _notice = switch (availability) {
          SttAvailability.permissionDenied => _Notice.denied,
          SttAvailability.permissionPermanentlyDenied => _Notice.permanentlyDenied,
          _ => _Notice.unsupported,
        };
      });
      return;
    }
    final String current = widget.controller.text;
    _base = current.isEmpty || current.endsWith(' ') || current.endsWith('\n') ? current : '$current ';
    setState(() {
      _starting = false;
      _listening = true;
    });
    _sub = _stt.startListening().listen(
      _onResult,
      onError: (Object _) => _finish(_Notice.error),
      onDone: () => _finish(_Notice.none),
      cancelOnError: true,
    );
  }

  void _onResult(SttRecognitionResult result) {
    if (!mounted) return;
    final String text = _base + result.text;
    widget.controller.value = TextEditingValue(
      text: text,
      selection: TextSelection.collapsed(offset: text.length),
    );
    widget.onTextChanged?.call();
  }

  void _finish(_Notice notice) {
    _sub?.cancel();
    _sub = null;
    if (mounted) {
      setState(() {
        _listening = false;
        _notice = notice;
      });
    }
  }

  Future<void> _stop() async {
    await _stt.stopListening();
    _finish(_Notice.none);
  }

  String? get _message => switch (_notice) {
        _Notice.none => null,
        _Notice.denied => '마이크 사용을 허락해야 말로 쓸 수 있어요. 다시 눌러서 허락해 주세요. 글자로 써도 괜찮아요.',
        _Notice.permanentlyDenied => '마이크 사용이 막혀 있어요. 설정에서 이음의 마이크를 허락해 주세요. 글자로 써도 괜찮아요.',
        _Notice.unsupported => '이 기기에서는 말로 쓰기를 쓸 수 없어요. 글자로 써 주세요.',
        _Notice.error => '말을 알아듣지 못했어요. 다시 눌러 주세요. 글자로 써도 괜찮아요.',
      };

  @override
  Widget build(BuildContext context) {
    final String? message = _message;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        LargeButton(
          label: _listening ? '듣는 중... 눌러서 끝내기' : '말해서 쓰기',
          outlined: true,
          onPressed: _starting ? null : (_listening ? _stop : _start),
        ),
        if (message != null) ...<Widget>[
          const SizedBox(height: 8),
          Semantics(liveRegion: true, child: Text(message, style: const TextStyle(fontSize: 18))),
        ],
        if (_notice == _Notice.permanentlyDenied) ...<Widget>[
          const SizedBox(height: 8),
          LargeButton(label: '설정 열기', outlined: true, onPressed: _permission.openSettings),
        ],
      ],
    );
  }
}
