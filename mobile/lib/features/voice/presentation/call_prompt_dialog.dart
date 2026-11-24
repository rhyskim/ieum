import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/features/group/presentation/group_widgets.dart';
import 'package:ieum_mobile/features/voice/application/voice_providers.dart';

/// 180초 자동 완료 직후 뜨는 전화 유도 모달. 경고창이 아니라 안내다.
class CallPromptDialog extends ConsumerStatefulWidget {
  const CallPromptDialog({required this.callName, required this.callPhone, super.key});

  /// 가족 호칭. 내가 정한 호칭(별명) 규칙을 따른 값을 호출하는 쪽이 넘긴다.
  final String callName;

  /// 상대 전화번호. 없거나 비어 있으면 전화 버튼이 비활성이 된다.
  final String? callPhone;

  @override
  ConsumerState<CallPromptDialog> createState() => _CallPromptDialogState();
}

class _CallPromptDialogState extends ConsumerState<CallPromptDialog> {
  bool _launchFailed = false;

  bool get _hasPhone => (widget.callPhone ?? '').trim().isNotEmpty;

  Future<void> _call() async {
    final bool ok = await ref.read(phoneLauncherProvider).call(widget.callPhone!.trim());
    if (!ok && mounted) setState(() => _launchFailed = true);
  }

  @override
  Widget build(BuildContext context) {
    const TextStyle body = TextStyle(fontSize: 20, height: 1.5);
    return AlertDialog(
      title: const Text('이야기가 참 많으시네요', style: TextStyle(fontSize: 22)),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Text(
              '오늘은 이 질문에 대해 나누고 싶은 이야기가 많은가 봐요. '
              '오늘 밤은 ${widget.callName}에게 직접 전화해서 목소리를 들으며 이 이야기를 나눠보는 건 어떨까요?\n\n'
              '3분까지 녹음한 목소리는 안전하게 저장되어 있어요.',
              style: body,
            ),
            if (!_hasPhone)
              const Padding(
                padding: EdgeInsets.only(top: 12),
                child: Text('등록된 전화번호가 없어서 지금은 전화를 걸 수 없어요.', style: TextStyle(fontSize: 18)),
              ),
            if (_launchFailed)
              const Padding(
                padding: EdgeInsets.only(top: 12),
                child: Text('전화 앱을 열지 못했어요. 직접 전화를 걸어 주세요.', style: TextStyle(fontSize: 18)),
              ),
          ],
        ),
      ),
      actionsOverflowDirection: VerticalDirection.down,
      actionsAlignment: MainAxisAlignment.center,
      actions: <Widget>[
        LargeButton(label: '${widget.callName}에게 지금 전화 걸기', onPressed: _hasPhone ? _call : null),
        const SizedBox(height: 8),
        LargeButton(
          label: '녹음한 3분 먼저 등록하기',
          outlined: true,
          onPressed: () => Navigator.of(context).pop(),
        ),
      ],
    );
  }
}
