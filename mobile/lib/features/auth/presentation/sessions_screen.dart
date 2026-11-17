import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:ieum_mobile/features/auth/data/auth_repository_impl.dart';
import 'package:ieum_mobile/features/auth/domain/auth_models.dart';

final deviceSessionsProvider = FutureProvider.autoDispose<List<DeviceSession>>(
  (ref) => ref.watch(authRepositoryProvider).fetchSessions(),
);

class SessionsScreen extends ConsumerWidget {
  const SessionsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final AsyncValue<List<DeviceSession>> sessions = ref.watch(deviceSessionsProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('로그인된 기기')),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: <Widget>[
              Expanded(
                child: sessions.when(
                  loading: () => const Center(child: CircularProgressIndicator()),
                  error: (error, stackTrace) => const Center(
                    child: Text('기기 목록을 불러오지 못했습니다.', style: TextStyle(fontSize: 18)),
                  ),
                  data: (list) => ListView.separated(
                    itemCount: list.length,
                    separatorBuilder: (context, index) => const SizedBox(height: 12),
                    itemBuilder: (context, index) => _SessionTile(
                      session: list[index],
                      onRevoke: () async {
                        await ref.read(authRepositoryProvider).revokeSession(list[index].id);
                        ref.invalidate(deviceSessionsProvider);
                      },
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 16),
              SizedBox(
                height: 56,
                child: ElevatedButton(
                  onPressed: () async {
                    await ref.read(authRepositoryProvider).logout();
                    if (context.mounted) context.go('/login');
                  },
                  child: const Text('이 기기 로그아웃', style: TextStyle(fontSize: 20)),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _SessionTile extends StatelessWidget {
  const _SessionTile({required this.session, required this.onRevoke});

  final DeviceSession session;
  final VoidCallback onRevoke;

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
                  Text(session.deviceLabel, style: const TextStyle(fontSize: 20)),
                  const SizedBox(height: 4),
                  Text(
                    '로그인 ${_formatDate(session.createdAt)}',
                    style: const TextStyle(fontSize: 18),
                  ),
                ],
              ),
            ),
            SizedBox(
              height: 56,
              child: OutlinedButton(
                onPressed: onRevoke,
                child: const Text('로그아웃', style: TextStyle(fontSize: 18)),
              ),
            ),
          ],
        ),
      ),
    );
  }

  String _formatDate(DateTime utc) {
    final DateTime local = utc.toLocal();
    return '${local.year}.${local.month}.${local.day}';
  }
}
