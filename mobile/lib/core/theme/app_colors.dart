import 'package:flutter/material.dart';

abstract final class AppColors {
  // 일반 모드 팔레트 (명도 대비 >= 4.5:1 보장)
  static const Color warmBackground = Color(0xFFFAF8F5);
  static const Color warmSurface = Color(0xFFFFFFFF);
  static const Color warmTextPrimary = Color(0xFF1F1D1A); // 대비율 15.6:1
  static const Color warmTextSecondary = Color(0xFF4A453E); // 대비율 8.7:1
  static const Color warmPrimary = Color(0xFF8C3B2B); // 흰색 텍스트 대비율 7.2:1
  static const Color warmOnPrimary = Color(0xFFFFFFFF);
  static const Color warmSecondary = Color(0xFF2E5A44); // 흰색 텍스트 대비율 7.8:1
  static const Color warmOnSecondary = Color(0xFFFFFFFF);
  static const Color warmError = Color(0xFFB3261E);
  static const Color warmOnError = Color(0xFFFFFFFF);

  // 고대비 모드 팔레트 (WCAG AAA 명도 대비 >= 7.0:1 보장)
  static const Color highContrastBackground = Color(0xFFFFFFFF);
  static const Color highContrastSurface = Color(0xFFFFFFFF);
  static const Color highContrastTextPrimary = Color(0xFF111111); // 대비율 18.8:1
  static const Color highContrastTextSecondary = Color(0xFF222222); // 대비율 15.9:1
  static const Color highContrastPrimary = Color(0xFF7A1C00); // 흰색 텍스트 대비율 10.4:1
  static const Color highContrastOnPrimary = Color(0xFFFFFFFF);
  static const Color highContrastSecondary = Color(0xFF0B4F2C); // 흰색 텍스트 대비율 9.6:1
  static const Color highContrastOnSecondary = Color(0xFFFFFFFF);
  static const Color highContrastError = Color(0xFF8C0000); // 흰색 텍스트 대비율 10.3:1
  static const Color highContrastOnError = Color(0xFFFFFFFF);
}
