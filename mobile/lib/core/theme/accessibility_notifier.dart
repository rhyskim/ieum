import 'package:ieum_mobile/core/theme/accessibility_config.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

part 'accessibility_notifier.g.dart';

@Riverpod(keepAlive: true)
class AccessibilityConfigNotifier extends _$AccessibilityConfigNotifier {
  @override
  AccessibilityConfig build() => const AccessibilityConfig();

  void setFontScaleMode(FontScaleMode mode) {
    state = state.copyWith(fontScaleMode: mode);
  }

  void toggleHighContrast(bool enabled) {
    state = state.copyWith(highContrastEnabled: enabled);
  }

  /// 부모님 역할 선택 시 큰 글씨 + 고대비 기본 활성화
  void applyParentDefaultPreset() {
    state = const AccessibilityConfig(
      fontScaleMode: FontScaleMode.large,
      highContrastEnabled: true,
    );
  }
}

/// ARCHITECTURE.md §2.1 표준 프로바이더 명칭 별칭 고정
final accessibilityConfigProvider = accessibilityConfigNotifierProvider;
