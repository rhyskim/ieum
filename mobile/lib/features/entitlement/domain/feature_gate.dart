import 'package:ieum_mobile/features/entitlement/domain/feature_entitlement.dart';

/// CLAUDE.md §1-3 준수: 유료화 후보 기능을 하드코딩하지 않고 서버 정책 객체로만 판별하는 게이트
class FeatureGate {
  const FeatureGate(this.entitlement);

  final FeatureEntitlement entitlement;

  bool get canAttachOriginalVoice => entitlement.canAttachOriginalVoice;

  int get maxVoiceDurationSeconds => entitlement.maxVoiceDurationSeconds;

  Duration get maxVoiceDuration => Duration(seconds: entitlement.maxVoiceDurationSeconds);

  int get voiceServerRetentionDays => entitlement.voiceServerRetentionDays;

  bool get canDownloadVoiceToLocal => entitlement.canDownloadVoiceToLocal;

  String get currentPlan => entitlement.currentPlan;

  String get sttEngineType => entitlement.sttEngineType;
}
