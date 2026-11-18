import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:ieum_mobile/features/notification/application/push_registrar.dart';

/// 시스템 권한 창을 띄우기 전에 알림이 필요한 이유를 알려 주는 사전 안내 화면.
/// 푸시를 받지 못해도 앱의 승인 대기 목록이 기준이라는 점을 함께 알린다.
class PushIntroScreen extends ConsumerStatefulWidget {
  const PushIntroScreen({super.key});

  @override
  ConsumerState<PushIntroScreen> createState() => _PushIntroScreenState();
}

class _PushIntroScreenState extends ConsumerState<PushIntroScreen> {
  bool _busy = false;

  Future<void> _enable() async {
    setState(() => _busy = true);
    await ref.read(pushRegistrarProvider).enable();
    if (mounted) context.go('/');
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('알림 안내')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(24),
          children: <Widget>[
            const Text(
              '가족 소식을 알림으로 받아 보세요',
              style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 16),
            const Text(
              '가족이 입장을 요청하거나 승인 결과가 나오면 알려 드려요.',
              style: TextStyle(fontSize: 20),
            ),
            const SizedBox(height: 12),
            const Text(
              '알림을 받지 못해도 앱의 \'승인 대기\' 화면에서 언제든 확인할 수 있어요.',
              style: TextStyle(fontSize: 18),
            ),
            const SizedBox(height: 32),
            FilledButton(
              style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(56)),
              onPressed: _busy ? null : _enable,
              child: const Text('알림 켜기', style: TextStyle(fontSize: 20)),
            ),
            const SizedBox(height: 12),
            TextButton(
              style: TextButton.styleFrom(minimumSize: const Size.fromHeight(56)),
              onPressed: _busy ? null : () => context.go('/'),
              child: const Text('나중에 할게요', style: TextStyle(fontSize: 18)),
            ),
          ],
        ),
      ),
    );
  }
}
