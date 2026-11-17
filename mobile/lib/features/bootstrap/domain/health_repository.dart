import 'package:ieum_mobile/features/bootstrap/domain/server_health.dart';

abstract interface class HealthRepository {
  Future<ServerHealth> fetchHealth();
}
