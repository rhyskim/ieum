import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/features/auth/application/social_auth_flow.dart';
import 'package:ieum_mobile/features/auth/data/auth_repository_impl.dart';
import 'package:ieum_mobile/features/auth/domain/auth_models.dart';

final linkedIdentitiesProvider = FutureProvider.autoDispose<List<LinkedIdentity>>(
  (ref) => ref.watch(authRepositoryProvider).fetchIdentities(),
);

/// 로그인 수단 목록·연결·해제. 마지막 하나는 해제할 수 없다(서버도 409로 막는다).
class IdentitiesScreen extends ConsumerStatefulWidget {
  const IdentitiesScreen({super.key});

  @override
  ConsumerState<IdentitiesScreen> createState() => _IdentitiesScreenState();
}

class _IdentitiesScreenState extends ConsumerState<IdentitiesScreen> {
  bool _busy = false;
  String? _errorMessage;

  Future<void> _run(Future<bool> Function() action) async {
    setState(() {
      _busy = true;
      _errorMessage = null;
    });
    try {
      if (await action()) ref.invalidate(linkedIdentitiesProvider);
    } on ApiException catch (e) {
      if (mounted) setState(() => _errorMessage = socialErrorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _link(SocialProvider provider) => _run(
        () => runWithSocialToken(
          ref.read(socialTokenSourceProvider),
          provider,
          (String token) => ref.read(authRepositoryProvider).linkIdentity(provider, token),
        ),
      );

  Future<void> _unlink(SocialProvider provider) => _run(() async {
        await ref.read(authRepositoryProvider).unlinkIdentity(provider);
        return true;
      });

  @override
  Widget build(BuildContext context) {
    final AsyncValue<List<LinkedIdentity>> identities = ref.watch(linkedIdentitiesProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('로그인 수단')),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: identities.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (error, stackTrace) => const Center(
              child: Text('로그인 수단을 불러오지 못했습니다.', style: TextStyle(fontSize: 18)),
            ),
            data: (list) {
              final Set<SocialProvider> linked = list.map((i) => i.provider).toSet();
              final bool isLast = linked.length <= 1;
              return ListView(
                children: <Widget>[
                  for (final SocialProvider provider in SocialProvider.values) ...<Widget>[
                    _IdentityTile(
                      provider: provider,
                      linked: linked.contains(provider),
                      unlinkEnabled: !_busy && !isLast,
                      linkEnabled: !_busy,
                      onLink: () => _link(provider),
                      onUnlink: () => _unlink(provider),
                    ),
                    const SizedBox(height: 12),
                  ],
                  if (isLast)
                    const Text(
                      '마지막 로그인 수단은 해제할 수 없어요. 다른 수단을 먼저 연결해 주세요.',
                      style: TextStyle(fontSize: 18),
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
              );
            },
          ),
        ),
      ),
    );
  }
}

class _IdentityTile extends StatelessWidget {
  const _IdentityTile({
    required this.provider,
    required this.linked,
    required this.unlinkEnabled,
    required this.linkEnabled,
    required this.onLink,
    required this.onUnlink,
  });

  final SocialProvider provider;
  final bool linked;
  final bool unlinkEnabled;
  final bool linkEnabled;
  final VoidCallback onLink;
  final VoidCallback onUnlink;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: <Widget>[
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  Text(provider.displayName, style: const TextStyle(fontSize: 20)),
                  Text(linked ? '연결됨' : '연결 안 됨', style: const TextStyle(fontSize: 18)),
                ],
              ),
            ),
            SizedBox(
              height: 56,
              child: linked
                  ? OutlinedButton(
                      onPressed: unlinkEnabled ? onUnlink : null,
                      child: Text('${provider.displayName} 해제', style: const TextStyle(fontSize: 18)),
                    )
                  : ElevatedButton(
                      onPressed: linkEnabled ? onLink : null,
                      child: Text('${provider.displayName} 연결', style: const TextStyle(fontSize: 18)),
                    ),
            ),
          ],
        ),
      ),
    );
  }
}
