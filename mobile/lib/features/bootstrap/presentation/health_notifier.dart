import 'package:ieum_mobile/features/bootstrap/data/health_repository_impl.dart';
import 'package:ieum_mobile/features/bootstrap/domain/server_health.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

part 'health_notifier.g.dart';

@riverpod
class HealthNotifier extends _$HealthNotifier {
  @override
  Future<ServerHealth> build() async {
    final repository = ref.watch(healthRepositoryProvider);
    return repository.fetchHealth();
  }

  Future<void> refreshHealth() async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(() async {
      final repository = ref.read(healthRepositoryProvider);
      return repository.fetchHealth();
    });
  }
}
