import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/features/entitlement/domain/feature_gate.dart';

class EntitlementPolicyCard extends StatelessWidget {
  const EntitlementPolicyCard({
    required this.featureGateState,
    required this.onRetry,
    super.key,
  });

  final AsyncValue<FeatureGate> featureGateState;
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
              '음성 기록 및 이용 정책 (Feature Gate)',
              style: textTheme.titleLarge,
            ),
            const SizedBox(height: 12),
            featureGateState.when(
              loading: () => const Padding(
                padding: EdgeInsets.symmetric(vertical: 16),
                child: Center(child: CircularProgressIndicator()),
              ),
              error: (error, _) {
                final String message = error is ApiException
                    ? error.message
                    : '이용 정책을 불러오지 못했습니다.';
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
                      key: const ValueKey('retry_policy_button'),
                      onPressed: onRetry,
                      icon: const Icon(Icons.refresh),
                      label: const Text('정책 다시 불러오기'),
                    ),
                  ],
                );
              },
              data: (gate) => Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('현재 플랜: ${gate.currentPlan}', style: textTheme.bodyLarge),
                  const SizedBox(height: 4),
                  Text(
                    '원본 음성 전달: ${gate.canAttachOriginalVoice ? '사용 가능' : '제한됨'}',
                    style: textTheme.bodyLarge,
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '최대 녹음 시간: ${gate.maxVoiceDurationSeconds}초',
                    style: textTheme.bodyLarge,
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '서버 원본 보관 기간: ${gate.voiceServerRetentionDays}일 (텍스트 영구 보관)',
                    style: textTheme.bodyMedium,
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '내 휴대폰 영구 저장: ${gate.canDownloadVoiceToLocal ? '지원' : '미지원'}',
                    style: textTheme.bodyMedium,
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
