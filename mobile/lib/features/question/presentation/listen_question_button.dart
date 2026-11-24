import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/core/voice/tts_service.dart';
import 'package:ieum_mobile/core/voice/voice_providers.dart';
import 'package:ieum_mobile/features/group/presentation/group_widgets.dart';

/// 질문을 소리로 읽어 주는 버튼. 재생/정지 토글과 느리게(0.85배) 전환을 함께 둔다.
/// 화면을 벗어나면 읽기를 멈추고, 한국어 음성이 없는 기기에서는 버튼을 끄고 이유를 알린다.
class ListenQuestionButton extends ConsumerStatefulWidget {
  const ListenQuestionButton({required this.text, super.key});

  final String text;

  @override
  ConsumerState<ListenQuestionButton> createState() => _ListenQuestionButtonState();
}

class _ListenQuestionButtonState extends ConsumerState<ListenQuestionButton> {
  late final TtsService _tts;
  bool _ready = false;
  bool _checked = false;
  bool _speaking = false;
  bool _slow = false;
  // 읽기 요청마다 올린다. 멈춘 요청의 완료가 새 요청의 상태를 덮어쓰지 않게 한다.
  int _generation = 0;

  @override
  void initState() {
    super.initState();
    _tts = ref.read(ttsServiceProvider);
    _tts.initialize().then((bool ok) {
      if (mounted) {
        setState(() {
          _ready = ok;
          _checked = true;
        });
      }
    });
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // 다른 화면(답변 쓰기 등)이 위에 열리면 이 화면은 dispose되지 않으므로 직접 멈춘다.
    final ModalRoute<dynamic>? route = ModalRoute.of(context);
    if (route != null && !route.isCurrent && _speaking) _stop();
  }

  @override
  void dispose() {
    _tts.stop();
    super.dispose();
  }

  double get _rate => _slow ? TtsSpeechRate.slow : TtsSpeechRate.normal;

  Future<void> _play() async {
    final int generation = ++_generation;
    setState(() => _speaking = true);
    await _tts.speak(widget.text, speechRate: _rate);
    if (mounted && generation == _generation) setState(() => _speaking = false);
  }

  Future<void> _stop() async {
    _generation++;
    await _tts.stop();
    if (mounted) setState(() => _speaking = false);
  }

  Future<void> _toggleSlow(bool slow) async {
    setState(() => _slow = slow);
    // 읽는 도중 속도를 바꾸면 처음부터 다시 읽는다.
    if (_speaking) {
      _generation++;
      await _tts.stop();
      if (mounted) await _play();
    }
  }

  @override
  Widget build(BuildContext context) {
    final bool unavailable = _checked && !_ready;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        LargeButton(
          label: _speaking ? '듣기 멈추기' : '질문 듣기',
          outlined: true,
          onPressed: !_ready ? null : (_speaking ? _stop : _play),
        ),
        if (unavailable) ...<Widget>[
          const SizedBox(height: 8),
          const Text('이 기기에는 한국어 음성이 없어서 질문을 들려드릴 수 없어요.', style: TextStyle(fontSize: 18)),
        ] else ...<Widget>[
          const SizedBox(height: 8),
          ConstrainedBox(
            constraints: const BoxConstraints(minHeight: 56),
            child: SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('천천히 읽기', style: TextStyle(fontSize: 18)),
              value: _slow,
              onChanged: _ready ? _toggleSlow : null,
            ),
          ),
        ],
      ],
    );
  }
}
