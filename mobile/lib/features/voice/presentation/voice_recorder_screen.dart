import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/features/group/presentation/group_widgets.dart';
import 'package:ieum_mobile/features/voice/application/recording_controller.dart';
import 'package:ieum_mobile/features/voice/application/voice_providers.dart';
import 'package:ieum_mobile/features/voice/domain/recording_models.dart';
import 'package:ieum_mobile/features/voice/presentation/call_prompt_dialog.dart';
import 'package:ieum_mobile/features/voice/presentation/preview_player.dart';
import 'package:ieum_mobile/features/voice/presentation/waveform.dart';

/// 목소리로 답하기. 기기 안에서만 끝나며 서버로 보내지 않는다(업로드·저장 연동은 후속).
class VoiceRecorderScreen extends ConsumerStatefulWidget {
  const VoiceRecorderScreen({this.callName = '가족', this.callPhone, super.key});

  /// 전화 유도 모달의 가족 호칭. 호칭 규칙 연결은 후속 작업에서 한다.
  final String callName;
  final String? callPhone;

  @override
  ConsumerState<VoiceRecorderScreen> createState() => _VoiceRecorderScreenState();
}

class _VoiceRecorderScreenState extends ConsumerState<VoiceRecorderScreen> with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused || state == AppLifecycleState.hidden) {
      unawaited(ref.read(recordingControllerProvider.notifier).onBackgrounded());
    }
  }

  Future<void> _showCallPrompt() async {
    if (!ref.read(recordingControllerProvider.notifier).consumeCallPrompt()) return;
    await showDialog<void>(
      context: context,
      builder: (BuildContext _) => CallPromptDialog(callName: widget.callName, callPhone: widget.callPhone),
    );
  }

  @override
  Widget build(BuildContext context) {
    ref.listen<RecordingState>(recordingControllerProvider, (RecordingState? prev, RecordingState next) {
      if (next.callPromptPending && !(prev?.callPromptPending ?? false)) {
        // 빌드 중 다이얼로그를 띄우지 않도록 프레임 뒤에 실행한다.
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted) unawaited(_showCallPrompt());
        });
      }
    });
    final RecordingState state = ref.watch(recordingControllerProvider);
    final RecordingController controller = ref.read(recordingControllerProvider.notifier);
    final ColorScheme colors = Theme.of(context).colorScheme;
    final Color timerColor = state.isWarning ? colors.error : colors.onSurface;

    return Scaffold(
      appBar: AppBar(title: const Text('목소리로 답하기')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(24),
          children: <Widget>[
            Semantics(
              liveRegion: state.isWarning,
              child: Text(
                _format(state.elapsed),
                textAlign: TextAlign.center,
                style: TextStyle(fontSize: 56, fontWeight: FontWeight.bold, color: timerColor),
              ),
            ),
            const Text(
              '최대 3분까지 녹음할 수 있어요',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 18),
            ),
            if (state.isWarning)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Semantics(
                  liveRegion: true,
                  child: Text(
                    '30초 뒤 녹음이 마무리됩니다',
                    textAlign: TextAlign.center,
                    style: TextStyle(fontSize: 20, fontWeight: FontWeight.w600, color: colors.error),
                  ),
                ),
              ),
            const SizedBox(height: 16),
            if (state.phase == RecordingPhase.recording || state.phase == RecordingPhase.paused)
              Waveform(
                active: state.phase == RecordingPhase.recording,
                levels: ref.watch(audioRecorderProvider).levels,
              ),
            _NoticeBanner(
              notice: state.notice,
              onDismiss: controller.dismissNotice,
              onOpenSettings: ref.read(micPermissionGatewayProvider).openSettings,
            ),
            const SizedBox(height: 16),
            ..._actions(state, controller),
          ],
        ),
      ),
    );
  }

  List<Widget> _actions(RecordingState state, RecordingController controller) {
    switch (state.phase) {
      case RecordingPhase.idle:
        return <Widget>[LargeButton(label: '녹음 시작', onPressed: controller.start)];
      case RecordingPhase.recording:
        return <Widget>[
          LargeButton(label: '잠깐 멈추기', outlined: true, onPressed: controller.pause),
          const SizedBox(height: 12),
          LargeButton(label: '녹음 끝내기', onPressed: controller.finish),
        ];
      case RecordingPhase.paused:
        return <Widget>[
          LargeButton(label: '이어서 녹음', onPressed: controller.resume),
          const SizedBox(height: 12),
          LargeButton(label: '녹음 끝내기', outlined: true, onPressed: controller.finish),
        ];
      case RecordingPhase.completed:
        return <Widget>[
          if (state.filePath != null) PreviewPlayer(path: state.filePath!),
          const SizedBox(height: 16),
          // 업로드·답변 저장은 설계 확정 후 별도 작업이라 자리표시로만 둔다. 파일은 임시 폴더에 보존된다.
          const LargeButton(label: '답변으로 등록하기 (준비 중)', onPressed: null),
          const SizedBox(height: 12),
          LargeButton(label: '다시 녹음', outlined: true, onPressed: controller.start),
        ];
    }
  }

  static String _format(Duration d) {
    final String mm = d.inMinutes.remainder(60).toString().padLeft(2, '0');
    final String ss = d.inSeconds.remainder(60).toString().padLeft(2, '0');
    return '$mm:$ss';
  }
}

/// 실패 사유를 에러 대신 다음 행동이 보이는 안내로 보여 준다.
class _NoticeBanner extends StatelessWidget {
  const _NoticeBanner({required this.notice, required this.onDismiss, required this.onOpenSettings});

  final RecordingNotice notice;
  final VoidCallback onDismiss;
  final VoidCallback onOpenSettings;

  static String? message(RecordingNotice notice) => switch (notice) {
        RecordingNotice.none => null,
        RecordingNotice.permissionDenied => '마이크 사용을 허용해 주셔야 녹음할 수 있어요. 다시 눌러 허용해 주세요.',
        RecordingNotice.permissionPermanentlyDenied => '마이크가 꺼져 있어요. 휴대폰 설정에서 이음의 마이크를 켜 주세요.',
        RecordingNotice.micBusy => '다른 앱이 마이크를 쓰고 있는 것 같아요. 그 앱을 끄고 다시 눌러 주세요.',
        RecordingNotice.storageFull => '휴대폰 저장 공간이 부족해요. 공간을 비운 뒤 다시 녹음해 주세요. 지금까지 녹음한 내용은 남아 있어요.',
        RecordingNotice.interruptedByCall => '전화가 와서 잠시 멈췄어요. 준비되면 이어서 녹음해 주세요.',
        RecordingNotice.pausedInBackground => '앱을 나가서 잠시 멈췄어요. 이어서 녹음할 수 있어요.',
        RecordingNotice.unknown => '녹음을 마무리하지 못했어요. 지금까지 녹음한 내용은 남아 있어요.',
      };

  @override
  Widget build(BuildContext context) {
    final String? text = message(notice);
    if (text == null) return const SizedBox.shrink();
    return Semantics(
      liveRegion: true,
      child: Card(
        margin: const EdgeInsets.only(top: 8),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Text(text, style: const TextStyle(fontSize: 18, height: 1.4)),
              const SizedBox(height: 8),
              Wrap(
                spacing: 12,
                children: <Widget>[
                  if (notice == RecordingNotice.permissionPermanentlyDenied)
                    SizedBox(
                      height: 56,
                      child: ElevatedButton(
                        onPressed: onOpenSettings,
                        child: const Text('설정 열기', style: TextStyle(fontSize: 20)),
                      ),
                    ),
                  SizedBox(
                    height: 56,
                    child: TextButton(
                      onPressed: onDismiss,
                      child: const Text('확인', style: TextStyle(fontSize: 20)),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
