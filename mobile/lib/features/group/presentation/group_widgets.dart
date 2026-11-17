import 'package:flutter/material.dart';

/// 부모 세대 기준 최소 터치 높이(56dp)와 본문 18sp 이상을 지키는 공용 버튼.
class LargeButton extends StatelessWidget {
  const LargeButton({
    required this.label,
    required this.onPressed,
    this.outlined = false,
    super.key,
  });

  final String label;
  final VoidCallback? onPressed;
  final bool outlined;

  @override
  Widget build(BuildContext context) {
    const TextStyle style = TextStyle(fontSize: 20);
    return SizedBox(
      height: 56,
      child: outlined
          ? OutlinedButton(onPressed: onPressed, child: Text(label, style: style))
          : ElevatedButton(onPressed: onPressed, child: Text(label, style: style)),
    );
  }
}

class ErrorText extends StatelessWidget {
  const ErrorText(this.message, {super.key});

  final String? message;

  @override
  Widget build(BuildContext context) {
    if (message == null) return const SizedBox.shrink();
    return Semantics(
      liveRegion: true,
      child: Padding(
        padding: const EdgeInsets.only(top: 12),
        child: Text(
          message!,
          style: TextStyle(fontSize: 18, color: Theme.of(context).colorScheme.error),
        ),
      ),
    );
  }
}
