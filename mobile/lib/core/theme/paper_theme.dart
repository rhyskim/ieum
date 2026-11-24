import 'package:flutter/material.dart';

/// 종이 질감 테마의 조정 가능한 값을 한 곳에 모은다. 위젯은 색·글꼴 이름·질감 강도를
/// 직접 쓰지 않고 이 클래스만 참조한다(글꼴 선택·다크 모드를 나중에 붙이기 쉽게).
abstract final class PaperTheme {
  // ── 글꼴 (pubspec.yaml의 family 이름과 같아야 한다) ──
  static const String titleFontFamily = 'Gaegu';
  static const String bodyFontFamily = 'GowunDodum';

  // ── 배경 그라데이션: 중앙은 밝은 종이, 테두리로 갈수록 세피아 ──
  static const Color paperCenter = Color(0xFFFBF5E6);
  static const Color paperEdge = Color(0xFFD9BF93);
  static const double gradientRadius = 1.15;

  // ── 노이즈 질감 (코드로 생성, 이미지 파일 없음) ──
  static const Color noiseColor = Color(0xFF6B4A22);
  static const double noiseOpacity = 0.06;
  static const int noiseDotCount = 2600;
  static const int noiseSeed = 7;

  // ── 글자·장식 색 (본문 글자는 종이색 대비 4.5:1 이상을 단위 테스트로 보장) ──
  static const Color textPrimary = Color(0xFF2B1D0E);
  static const Color textSecondary = Color(0xFF52391B);
  static const Color accent = Color(0xFF7A3E12);
  static const Color onAccent = Color(0xFFFFFFFF);
  static const Color surface = Color(0xFFFFFBF0);
  static const Color border = Color(0xFFB8996A);
  static const Color error = Color(0xFF8C2016);
  static const Color navIndicator = Color(0xFFE6D2A8);

  // ── 크기 불변식 (CLAUDE.md §5) ──
  static const double minBodySize = 18;
  static const double minTouchTarget = 48;
  static const double primaryButtonHeight = 56;

  /// 본문 글자와 배경의 대비 검증용: 배경 중 가장 어두운 곳(테두리) 기준.
  static const List<Color> backgroundSamples = <Color>[paperCenter, paperEdge];
}
