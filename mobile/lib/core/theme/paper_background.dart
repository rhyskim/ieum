import 'dart:math';
import 'dart:ui' show PointMode;

import 'package:flutter/material.dart';
import 'package:ieum_mobile/core/theme/paper_theme.dart';

/// 앱 전체 뒤에 한 번만 깔리는 종이 배경. 그라데이션과 노이즈를 [RepaintBoundary]로
/// 한 번 그려 캐시하므로 스크롤·입력이 다시 그리지 않는다.
class PaperBackground extends StatelessWidget {
  const PaperBackground({required this.child, super.key});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Stack(
      fit: StackFit.expand,
      children: <Widget>[
        const RepaintBoundary(
          child: CustomPaint(painter: _PaperPainter(), isComplex: true, willChange: false),
        ),
        child,
      ],
    );
  }
}

class _PaperPainter extends CustomPainter {
  const _PaperPainter();

  @override
  void paint(Canvas canvas, Size size) {
    final Rect rect = Offset.zero & size;
    canvas.drawRect(
      rect,
      Paint()
        ..shader = const RadialGradient(
          radius: PaperTheme.gradientRadius,
          colors: <Color>[PaperTheme.paperCenter, PaperTheme.paperEdge],
        ).createShader(rect),
    );

    final Random random = Random(PaperTheme.noiseSeed);
    final Paint dot = Paint()
      ..color = PaperTheme.noiseColor.withValues(alpha: PaperTheme.noiseOpacity)
      ..strokeWidth = 1.5
      ..strokeCap = StrokeCap.round;
    final List<Offset> points = List<Offset>.generate(
      PaperTheme.noiseDotCount,
      (_) => Offset(random.nextDouble() * size.width, random.nextDouble() * size.height),
    );
    canvas.drawPoints(PointMode.points, points, dot);
  }

  @override
  bool shouldRepaint(_PaperPainter oldDelegate) => false;
}
