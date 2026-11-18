import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/features/auth/application/social_auth_flow.dart';
import 'package:ieum_mobile/features/auth/data/auth_repository_impl.dart';
import 'package:ieum_mobile/features/auth/domain/auth_models.dart';
import 'package:ieum_mobile/features/notification/application/push_registrar.dart';

/// 로그인 화면이 `/restore`로 넘기는 값(go_router `extra`).
class RestoreArgs {
  const RestoreArgs(this.provider, this.restorableUntil);

  final SocialProvider provider;
  final DateTime restorableUntil;
}

/// 탈퇴 유예 중인 계정의 복구 확인 화면. 자동 복구하지 않고 이 화면의 버튼으로만 복구한다.
class RestoreScreen extends ConsumerStatefulWidget {
  const RestoreScreen({required this.provider, required this.restorableUntil, super.key});

  final SocialProvider provider;
  final DateTime restorableUntil;

  @override
  ConsumerState<RestoreScreen> createState() => _RestoreScreenState();
}

class _RestoreScreenState extends ConsumerState<RestoreScreen> {
  bool _busy = false;
  String? _errorMessage;

  Future<void> _restore() async {
    setState(() {
      _busy = true;
      _errorMessage = null;
    });
    try {
      final bool done = await runWithSocialToken(
        ref.read(socialTokenSourceProvider),
        widget.provider,
        (String token) => ref.read(authRepositoryProvider).restore(widget.provider, token),
      );
      if (!done) return; // 사용자가 취소함
      // 탈퇴할 때 서버가 기기 토큰을 지웠으므로, 일반 로그인처럼 허용 상태면 조용히 다시 등록하고
      // 허용 전이면 사전 안내 화면으로 보낸다.
      final bool registered = await ref.read(pushRegistrarProvider).registerIfGranted();
      if (mounted) context.go(registered ? '/' : '/notifications/intro');
    } on ApiException catch (e) {
      if (mounted) setState(() => _errorMessage = socialErrorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// 일일 사이클과 같은 기준(Asia/Seoul, UTC+9)으로 날짜를 보여 준다.
  String _deadlineText() {
    final DateTime kst = widget.restorableUntil.toUtc().add(const Duration(hours: 9));
    return '${kst.month}월 ${kst.day}일까지 복구할 수 있어요';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('계정 복구')),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: <Widget>[
              const Text('탈퇴를 신청한 계정이에요', style: TextStyle(fontSize: 24)),
              const SizedBox(height: 16),
              Text(_deadlineText(), style: const TextStyle(fontSize: 20)),
              const SizedBox(height: 8),
              const Text(
                '복구하면 가족 그룹에 다시 보이고, 이전처럼 쓸 수 있어요. '
                '방장이었다면 방장 자리는 돌아오지 않아요.',
                style: TextStyle(fontSize: 18),
              ),
              const SizedBox(height: 32),
              SizedBox(
                height: 56,
                child: ElevatedButton(
                  onPressed: _busy ? null : _restore,
                  child: const Text('계정 복구하기', style: TextStyle(fontSize: 20)),
                ),
              ),
              const SizedBox(height: 16),
              SizedBox(
                height: 56,
                child: OutlinedButton(
                  onPressed: _busy ? null : () => context.go('/login'),
                  child: const Text('복구하지 않을래요', style: TextStyle(fontSize: 20)),
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
      ),
    );
  }
}
