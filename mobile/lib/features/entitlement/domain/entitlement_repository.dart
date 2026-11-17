import 'package:ieum_mobile/features/entitlement/domain/feature_entitlement.dart';

abstract interface class EntitlementRepository {
  Future<FeatureEntitlement> fetchEntitlementPolicy();
}
