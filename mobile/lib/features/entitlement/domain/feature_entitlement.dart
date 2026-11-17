import 'package:freezed_annotation/freezed_annotation.dart';

part 'feature_entitlement.freezed.dart';
part 'feature_entitlement.g.dart';

@freezed
abstract class FeatureEntitlement with _$FeatureEntitlement {
  const factory FeatureEntitlement({
    required String currentPlan,
    required bool canAttachOriginalVoice,
    required int maxVoiceDurationSeconds,
    required int voiceServerRetentionDays,
    required bool canDownloadVoiceToLocal,
    required String sttEngineType,
  }) = _FeatureEntitlement;

  factory FeatureEntitlement.fromJson(Map<String, dynamic> json) =>
      _$FeatureEntitlementFromJson(json);
}
