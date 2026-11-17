import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/features/entitlement/data/entitlement_repository_impl.dart';
import 'package:ieum_mobile/features/entitlement/domain/feature_entitlement.dart';
import 'package:ieum_mobile/features/entitlement/domain/feature_gate.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

part 'entitlement_notifier.g.dart';

@riverpod
class EntitlementNotifier extends _$EntitlementNotifier {
  @override
  Future<FeatureEntitlement> build() async {
    final repository = ref.watch(entitlementRepositoryProvider);
    return repository.fetchEntitlementPolicy();
  }

  Future<void> refreshPolicy() async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(() async {
      final repository = ref.read(entitlementRepositoryProvider);
      return repository.fetchEntitlementPolicy();
    });
  }
}

@riverpod
AsyncValue<FeatureGate> featureGate(Ref ref) {
  final entitlementAsync = ref.watch(entitlementNotifierProvider);
  return entitlementAsync.whenData(FeatureGate.new);
}
