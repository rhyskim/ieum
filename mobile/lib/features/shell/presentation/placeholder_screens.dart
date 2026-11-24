import 'package:flutter/material.dart';

/// 목록 탭 자리. 지난 질문 타임라인은 archive 작업이 채운다.
class ListTabPlaceholder extends StatelessWidget {
  const ListTabPlaceholder({super.key});

  @override
  Widget build(BuildContext context) => const _Placeholder(title: '목록', message: '지난 질문을 모아 볼 수 있어요.');
}

/// 캘린더 탭 자리. 내용은 별도 작업으로 정한다.
class CalendarPlaceholderScreen extends StatelessWidget {
  const CalendarPlaceholderScreen({super.key});

  @override
  Widget build(BuildContext context) => const _Placeholder(title: '캘린더', message: '준비 중입니다.');
}

class _Placeholder extends StatelessWidget {
  const _Placeholder({required this.title, required this.message});

  final String title;
  final String message;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(title)),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Text(message, style: Theme.of(context).textTheme.bodyLarge, textAlign: TextAlign.center),
        ),
      ),
    );
  }
}
