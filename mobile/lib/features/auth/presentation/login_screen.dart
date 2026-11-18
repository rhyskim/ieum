import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/features/auth/data/auth_repository_impl.dart';
import 'package:ieum_mobile/features/auth/domain/auth_models.dart';

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key});

  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen> {
  bool _busy = false;
  String? _errorMessage;

  Future<void> _login(SocialProvider provider) async {
    setState(() {
      _busy = true;
      _errorMessage = null;
    });
    try {
      final String socialToken = await ref.read(socialTokenSourceProvider).obtainToken(provider);
      await ref.read(authRepositoryProvider).login(provider, socialToken);
      if (mounted) context.go('/notifications/intro');
    } on ApiException catch (e) {
      if (mounted) setState(() => _errorMessage = e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('로그인')),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: <Widget>[
              Text('이음에 오신 것을 환영합니다', style: Theme.of(context).textTheme.headlineMedium),
              const SizedBox(height: 32),
              for (final SocialProvider provider in SocialProvider.values) ...<Widget>[
                _SocialLoginButton(
                  label: provider.label,
                  onPressed: _busy ? null : () => _login(provider),
                ),
                const SizedBox(height: 16),
              ],
              if (_errorMessage != null)
                Semantics(
                  liveRegion: true,
                  child: Text(
                    _errorMessage!,
                    style: TextStyle(
                      fontSize: 18,
                      color: Theme.of(context).colorScheme.error,
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _SocialLoginButton extends StatelessWidget {
  const _SocialLoginButton({required this.label, required this.onPressed});

  final String label;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 56,
      child: ElevatedButton(
        onPressed: onPressed,
        child: Text(label, style: const TextStyle(fontSize: 20)),
      ),
    );
  }
}
