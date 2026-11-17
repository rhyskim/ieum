import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/features/bootstrap/presentation/health_notifier.dart';
import 'package:ieum_mobile/features/bootstrap/presentation/widgets/accessibility_control_card.dart';
import 'package:ieum_mobile/features/bootstrap/presentation/widgets/entitlement_policy_card.dart';
import 'package:ieum_mobile/features/bootstrap/presentation/widgets/server_health_card.dart';
import 'package:ieum_mobile/features/entitlement/presentation/entitlement_notifier.dart';

class Phase0BootstrapScreen extends ConsumerWidget {
  const Phase0BootstrapScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final healthState = ref.watch(healthNotifierProvider);
    final featureGateState = ref.watch(featureGateProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('이음 (Ieum) 준비 화면'),
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const AccessibilityControlCard(),
              const SizedBox(height: 16),
              ServerHealthCard(
                healthState: healthState,
                onRetry: () {
                  ref.read(healthNotifierProvider.notifier).refreshHealth();
                },
              ),
              const SizedBox(height: 16),
              EntitlementPolicyCard(
                featureGateState: featureGateState,
                onRetry: () {
                  ref.read(entitlementNotifierProvider.notifier).refreshPolicy();
                },
              ),
              const SizedBox(height: 20),
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  key: const ValueKey('refresh_all_button'),
                  onPressed: () {
                    ref.read(healthNotifierProvider.notifier).refreshHealth();
                    ref.read(entitlementNotifierProvider.notifier).refreshPolicy();
                  },
                  icon: const Icon(Icons.sync),
                  label: const Text('연결 상태 및 정책 새로고침'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
