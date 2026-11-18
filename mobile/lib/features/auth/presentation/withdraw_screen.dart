import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/features/auth/data/auth_repository_impl.dart';
import 'package:ieum_mobile/features/auth/domain/auth_models.dart';

/// 탈퇴 화면. 내가 쓴 글·목소리를 어떻게 할지 쉬운 말로 고르게 하고, 확인 단계를 한 번 더 거친다.
/// 실제 삭제는 30일 뒤에 일어나며 그 전에는 로그인해서 복구할 수 있다.
class WithdrawScreen extends ConsumerStatefulWidget {
  const WithdrawScreen({super.key});

  @override
  ConsumerState<WithdrawScreen> createState() => _WithdrawScreenState();
}

class _WithdrawScreenState extends ConsumerState<WithdrawScreen> {
  ContentChoice? _choice;
  bool _busy = false;
  String? _errorMessage;

  Future<void> _confirmAndWithdraw() async {
    final ContentChoice? choice = _choice;
    if (choice == null) return;
    final bool? confirmed = await showDialog<bool>(
      context: context,
      builder: (BuildContext dialogContext) => AlertDialog(
        title: const Text('정말 탈퇴할까요?', style: TextStyle(fontSize: 20)),
        content: Text(
          '${_summary(choice)}\n\n30일 안에 다시 로그인하면 복구할 수 있어요.',
          style: const TextStyle(fontSize: 18),
        ),
        actions: <Widget>[
          TextButton(
            style: TextButton.styleFrom(minimumSize: const Size(88, 56)),
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('계속 이용하기', style: TextStyle(fontSize: 18)),
          ),
          TextButton(
            style: TextButton.styleFrom(minimumSize: const Size(88, 56)),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('탈퇴하기', style: TextStyle(fontSize: 18)),
          ),
        ],
      ),
    );
    if (confirmed != true) return;

    setState(() {
      _busy = true;
      _errorMessage = null;
    });
    try {
      await ref.read(authRepositoryProvider).withdraw(choice);
      if (mounted) context.go('/login');
    } on ApiException catch (e) {
      if (mounted) setState(() => _errorMessage = e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  static String _summary(ContentChoice choice) => switch (choice) {
        ContentChoice.keep => '내가 쓴 글과 목소리는 가족에게 계속 보여요. 이름은 "탈퇴한 사용자"로 바뀌어요.',
        ContentChoice.delete => '내가 쓴 글과 목소리가 모두 지워지고, 되돌릴 수 없어요.',
      };

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('탈퇴하기')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(24),
          children: <Widget>[
            const Text('탈퇴하면 가족에게 내 모습이 보이지 않아요.', style: TextStyle(fontSize: 20)),
            const SizedBox(height: 8),
            const Text(
              '30일 안에 다시 로그인하면 복구할 수 있어요. 30일이 지나면 내 정보가 지워져요.',
              style: TextStyle(fontSize: 18),
            ),
            const SizedBox(height: 24),
            const Text('내가 쓴 글과 목소리는 어떻게 할까요?', style: TextStyle(fontSize: 20)),
            const SizedBox(height: 8),
            _ChoiceTile(
              title: '가족에게 계속 보여 주기',
              description: '내가 쓴 글과 목소리가 그대로 남아요.',
              selected: _choice == ContentChoice.keep,
              onTap: _busy ? null : () => setState(() => _choice = ContentChoice.keep),
            ),
            const SizedBox(height: 12),
            _ChoiceTile(
              title: '모두 지우기',
              description: '내가 쓴 글과 목소리를 모두 지워요. 되돌릴 수 없어요.',
              selected: _choice == ContentChoice.delete,
              onTap: _busy ? null : () => setState(() => _choice = ContentChoice.delete),
            ),
            const SizedBox(height: 24),
            SizedBox(
              height: 56,
              child: ElevatedButton(
                onPressed: _choice == null || _busy ? null : _confirmAndWithdraw,
                child: const Text('탈퇴하기', style: TextStyle(fontSize: 20)),
              ),
            ),
            if (_errorMessage != null) ...<Widget>[
              const SizedBox(height: 16),
              Semantics(
                liveRegion: true,
                child: Text(
                  _errorMessage!,
                  style: TextStyle(fontSize: 18, color: Theme.of(context).colorScheme.error),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _ChoiceTile extends StatelessWidget {
  const _ChoiceTile({
    required this.title,
    required this.description,
    required this.selected,
    required this.onTap,
  });

  final String title;
  final String description;
  final bool selected;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      selected: selected,
      child: Card(
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
          side: BorderSide(
            color: selected ? Theme.of(context).colorScheme.primary : Colors.transparent,
            width: 3,
          ),
        ),
        child: InkWell(
          onTap: onTap,
          child: ConstrainedBox(
            constraints: const BoxConstraints(minHeight: 56),
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                children: <Widget>[
                  Icon(selected ? Icons.check_circle : Icons.circle_outlined),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: <Widget>[
                        Text(title, style: const TextStyle(fontSize: 20)),
                        Text(description, style: const TextStyle(fontSize: 18)),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
