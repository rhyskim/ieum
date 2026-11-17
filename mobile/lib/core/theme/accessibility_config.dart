import 'package:freezed_annotation/freezed_annotation.dart';

part 'accessibility_config.freezed.dart';
part 'accessibility_config.g.dart';

@JsonEnum(alwaysCreate: true)
enum FontScaleMode {
  @JsonValue('NORMAL')
  normal(bodySize: 18.0, titleSize: 22.0, label: '보통 (자녀 기본)'),
  @JsonValue('LARGE')
  large(bodySize: 20.0, titleSize: 24.0, label: '크게 (부모님 권장)'),
  @JsonValue('XLARGE')
  xlarge(bodySize: 22.0, titleSize: 26.0, label: '아주 크게');

  const FontScaleMode({
    required this.bodySize,
    required this.titleSize,
    required this.label,
  });

  final double bodySize;
  final double titleSize;
  final String label;

  bool get isSeniorMode => this != FontScaleMode.normal;
}

@freezed
abstract class AccessibilityConfig with _$AccessibilityConfig {
  const factory AccessibilityConfig({
    @Default(FontScaleMode.normal) FontScaleMode fontScaleMode,
    @Default(false) bool highContrastEnabled,
  }) = _AccessibilityConfig;

  factory AccessibilityConfig.fromJson(Map<String, dynamic> json) =>
      _$AccessibilityConfigFromJson(json);
}
