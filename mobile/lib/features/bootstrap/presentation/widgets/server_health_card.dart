import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/features/bootstrap/domain/server_health.dart';

class ServerHealthCard extends StatelessWidget {
  const ServerHealthCard({
    required this.healthState,
    required this.onRetry,
    super.key,
  });

  final AsyncValue<ServerHealth> healthState;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final textTheme = Theme.of(context).textTheme;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              '서버 연결 상태 (Health Check)',
              style: textTheme.titleLarge,
            ),
            const SizedBox(height: 12),
            healthState.when(
              loading: () => const Padding(
                padding: EdgeInsets.symmetric(vertical: 16),
                child: Center(child: CircularProgressIndicator()),
              ),
              error: (error, _) {
                final String message = error is ApiException
                    ? error.message
                    : '서버 연결 확인 중 오류가 발생했습니다.';
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(
                      message,
                      style: textTheme.bodyLarge?.copyWith(
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ),
                    const SizedBox(height: 12),
                    FilledButton.icon(
                      key: const ValueKey('retry_health_button'),
                      onPressed: onRetry,
                      icon: const Icon(Icons.refresh),
                      label: const Text('다시 연결 확인'),
                    ),
                  ],
                );
              },
              data: (health) => Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('상태: ${health.status}', style: textTheme.bodyLarge),
                  const SizedBox(height: 4),
                  Text('서비스: ${health.application} (${health.profile})', style: textTheme.bodyMedium),
                  const SizedBox(height: 4),
                  Text('기준 타임존: ${health.timezone}', style: textTheme.bodyMedium),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
