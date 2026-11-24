import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 사용자 글자 크기 배율과 확대 제스처 감도 상수.
abstract final class TextScaleConfig {
  /// 슬라이더 단계 (100/115/130/150/170%).
  static const List<double> steps = <double>[1.0, 1.15, 1.3, 1.5, 1.7];

  /// 시스템 배율 × 사용자 배율의 상한. 이 값을 넘으면 레이아웃이 깨지므로 자른다.
  static const double maxEffectiveScale = 1.7;

  /// 두 손가락 간격이 시작 대비 이만큼(25%) 이상 변해야 한다.
  static const double pinchRatioThreshold = 0.25;

  /// 위 변화가 이 시간 이상 유지돼야 시트를 띄운다(짧은 접촉 무시).
  static const Duration pinchHoldDuration = Duration(milliseconds: 300);

  static const String prefsKey = 'text_scale_user';

  /// 저장값이 단계에 없으면 가장 가까운 단계로 보정한다.
  static double snap(double value) {
    double best = steps.first;
    for (final double s in steps) {
      if ((s - value).abs() < (best - value).abs()) best = s;
    }
    return best;
  }

  static double effective(double systemScale, double userScale) =>
      (systemScale * userScale).clamp(1.0, maxEffectiveScale).toDouble();
}

final Provider<SharedPreferences?> sharedPreferencesProvider =
    Provider<SharedPreferences?>((Ref ref) => null);

/// 사용자 글자 배율. 기기에 저장하고 앱 시작 시 복원한다.
class UserTextScaleNotifier extends Notifier<double> {
  @override
  double build() {
    final double? saved = ref.watch(sharedPreferencesProvider)?.getDouble(TextScaleConfig.prefsKey);
    return saved == null ? 1.0 : TextScaleConfig.snap(saved);
  }

  Future<void> set(double scale) async {
    final double snapped = TextScaleConfig.snap(scale);
    state = snapped;
    await ref.read(sharedPreferencesProvider)?.setDouble(TextScaleConfig.prefsKey, snapped);
  }
}

final NotifierProvider<UserTextScaleNotifier, double> userTextScaleProvider =
    NotifierProvider<UserTextScaleNotifier, double>(UserTextScaleNotifier.new);
