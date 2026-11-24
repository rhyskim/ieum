import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ieum_mobile/core/theme/accessibility_config.dart';
import 'package:ieum_mobile/core/theme/app_theme.dart';
import 'package:ieum_mobile/core/theme/paper_theme.dart';

double _contrastRatio(Color a, Color b) {
  final double lumA = a.computeLuminance();
  final double lumB = b.computeLuminance();
  final double brightest = math.max(lumA, lumB);
  final double darkest = math.min(lumA, lumB);
  return (brightest + 0.05) / (darkest + 0.05);
}

void main() {
  group('AppTheme 부모 세대 접근성 타이포그래피 및 명도 대비 검증', () {
    test('FontScaleMode 별 본문(18/20/22sp), 제목(22/24/26sp), 줄간격(1.5), 버튼 최소 크기(88x56) 검증', () {
      final normalTheme = AppTheme.buildTheme(
        const AccessibilityConfig(fontScaleMode: FontScaleMode.normal),
      );
      final largeTheme = AppTheme.buildTheme(
        const AccessibilityConfig(fontScaleMode: FontScaleMode.large),
      );
      final xlargeTheme = AppTheme.buildTheme(
        const AccessibilityConfig(fontScaleMode: FontScaleMode.xlarge),
      );

      expect(normalTheme.textTheme.bodyLarge?.fontSize, 18.0);
      expect(normalTheme.textTheme.titleLarge?.fontSize, 22.0);
      expect(normalTheme.textTheme.bodyLarge?.height, 1.5);

      expect(largeTheme.textTheme.bodyLarge?.fontSize, 20.0);
      expect(largeTheme.textTheme.titleLarge?.fontSize, 24.0);
      expect(largeTheme.textTheme.bodyLarge?.height, 1.5);

      expect(xlargeTheme.textTheme.bodyLarge?.fontSize, 22.0);
      expect(xlargeTheme.textTheme.titleLarge?.fontSize, 26.0);
      expect(xlargeTheme.textTheme.bodyLarge?.height, 1.5);

      final minSize = largeTheme.filledButtonTheme.style?.minimumSize?.resolve(<WidgetState>{});
      expect(minSize, const Size(88, 56));
    });

    test('일반 모드 명도 대비율이 CLAUDE.md §1.1 기준(4.5:1 이상)을 만족한다', () {
      final theme = AppTheme.buildTheme(
        const AccessibilityConfig(highContrastEnabled: false),
      );
      final cs = theme.colorScheme;

      // 일반 모드의 Scaffold는 투명이고 종이 배경이 비친다. 배경 그라데이션 양 끝 색으로 검증한다.
      for (final Color bg in PaperTheme.backgroundSamples) {
        expect(_contrastRatio(cs.onSurface, bg), greaterThanOrEqualTo(4.5));
      }
      expect(_contrastRatio(cs.onSurface, cs.surface), greaterThanOrEqualTo(4.5));
      expect(_contrastRatio(cs.onPrimary, cs.primary), greaterThanOrEqualTo(4.5));
      expect(_contrastRatio(cs.onSecondary, cs.secondary), greaterThanOrEqualTo(4.5));
    });

    test('고대비 모드 명도 대비율이 WCAG AAA 기준(7.0:1 이상)을 만족한다', () {
      final theme = AppTheme.buildTheme(
        const AccessibilityConfig(
          fontScaleMode: FontScaleMode.large,
          highContrastEnabled: true,
        ),
      );
      final cs = theme.colorScheme;

      expect(_contrastRatio(cs.onSurface, theme.scaffoldBackgroundColor), greaterThanOrEqualTo(7.0));
      expect(_contrastRatio(cs.onSurface, cs.surface), greaterThanOrEqualTo(7.0));
      expect(_contrastRatio(cs.onPrimary, cs.primary), greaterThanOrEqualTo(7.0));
      expect(_contrastRatio(cs.onSecondary, cs.secondary), greaterThanOrEqualTo(7.0));
    });
  });
}
