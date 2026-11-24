import 'package:flutter/material.dart';
import 'package:ieum_mobile/core/theme/accessibility_config.dart';
import 'package:ieum_mobile/core/theme/app_colors.dart';
import 'package:ieum_mobile/core/theme/paper_theme.dart';

abstract final class AppTheme {
  static const Size minButtonSize = Size(88, 56);
  static const double lineHeight = 1.5;

  static ThemeData buildTheme(AccessibilityConfig config) {
    final bool isHighContrast = config.highContrastEnabled;
    final FontScaleMode scaleMode = config.fontScaleMode;

    final Color background = isHighContrast
        ? AppColors.highContrastBackground
        : Colors.transparent; // 종이 배경(PaperBackground)이 앱 루트에서 비친다
    final Color surface = isHighContrast
        ? AppColors.highContrastSurface
        : PaperTheme.surface;
    final Color textPrimary = isHighContrast
        ? AppColors.highContrastTextPrimary
        : PaperTheme.textPrimary;
    final Color textSecondary = isHighContrast
        ? AppColors.highContrastTextSecondary
        : PaperTheme.textSecondary;
    final Color primary = isHighContrast
        ? AppColors.highContrastPrimary
        : PaperTheme.accent;
    final Color onPrimary = isHighContrast
        ? AppColors.highContrastOnPrimary
        : PaperTheme.onAccent;
    final Color secondary = isHighContrast
        ? AppColors.highContrastSecondary
        : AppColors.warmSecondary;
    final Color onSecondary = isHighContrast
        ? AppColors.highContrastOnSecondary
        : AppColors.warmOnSecondary;
    final Color error = isHighContrast
        ? AppColors.highContrastError
        : PaperTheme.error;
    final Color onError = isHighContrast
        ? AppColors.highContrastOnError
        : AppColors.warmOnError;

    final ColorScheme colorScheme = ColorScheme(
      brightness: Brightness.light,
      primary: primary,
      onPrimary: onPrimary,
      secondary: secondary,
      onSecondary: onSecondary,
      error: error,
      onError: onError,
      surface: surface,
      onSurface: textPrimary,
    );

    final TextTheme textTheme = TextTheme(
      headlineMedium: TextStyle(
        fontFamily: PaperTheme.titleFontFamily,
        fontSize: scaleMode.titleSize + 2.0,
        height: lineHeight,
        fontWeight: FontWeight.w700,
        color: textPrimary,
      ),
      titleLarge: TextStyle(
        fontFamily: PaperTheme.titleFontFamily,
        fontSize: scaleMode.titleSize,
        height: lineHeight,
        fontWeight: FontWeight.w700,
        color: textPrimary,
      ),
      titleMedium: TextStyle(
        fontSize: scaleMode.bodySize + 2.0,
        height: lineHeight,
        fontWeight: FontWeight.w600,
        color: textPrimary,
      ),
      bodyLarge: TextStyle(
        fontSize: scaleMode.bodySize,
        height: lineHeight,
        fontWeight: FontWeight.w500,
        color: textPrimary,
      ),
      bodyMedium: TextStyle(
        fontSize: scaleMode.bodySize,
        height: lineHeight,
        fontWeight: FontWeight.w400,
        color: textSecondary,
      ),
      labelLarge: TextStyle(
        fontSize: scaleMode.bodySize,
        height: lineHeight,
        fontWeight: FontWeight.w600,
        color: onPrimary,
      ),
    );

    const EdgeInsets buttonPadding = EdgeInsets.symmetric(
      horizontal: 20,
      vertical: 14,
    );
    final OutlinedBorder buttonShape = RoundedRectangleBorder(
      borderRadius: BorderRadius.circular(14),
    );

    return ThemeData(
      useMaterial3: true,
      fontFamily: PaperTheme.bodyFontFamily,
      colorScheme: colorScheme,
      scaffoldBackgroundColor: background,
      textTheme: textTheme,
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: Colors.transparent,
        indicatorColor: isHighContrast ? null : PaperTheme.navIndicator,
        labelTextStyle: WidgetStatePropertyAll<TextStyle?>(textTheme.bodyMedium),
      ),
      appBarTheme: AppBarTheme(
        backgroundColor: isHighContrast ? surface : Colors.transparent,
        scrolledUnderElevation: 0,
        foregroundColor: textPrimary,
        elevation: 0,
        centerTitle: false,
        titleTextStyle: textTheme.titleLarge,
      ),
      cardTheme: CardThemeData(
        color: surface,
        elevation: isHighContrast ? 0 : 1,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(16),
          side: BorderSide(
            color: isHighContrast ? textPrimary : PaperTheme.border,
            width: isHighContrast ? 2.0 : 1.0,
          ),
        ),
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: primary,
          foregroundColor: onPrimary,
          minimumSize: minButtonSize,
          padding: buttonPadding,
          shape: buttonShape,
          textStyle: textTheme.labelLarge,
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: primary,
          foregroundColor: onPrimary,
          minimumSize: minButtonSize,
          padding: buttonPadding,
          shape: buttonShape,
          textStyle: textTheme.labelLarge,
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: textPrimary,
          minimumSize: minButtonSize,
          padding: buttonPadding,
          side: BorderSide(
            color: isHighContrast ? textPrimary : primary,
            width: isHighContrast ? 2.0 : 1.5,
          ),
          shape: buttonShape,
          textStyle: textTheme.bodyLarge?.copyWith(fontWeight: FontWeight.w600),
        ),
      ),
    );
  }
}
