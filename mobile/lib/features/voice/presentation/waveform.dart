import 'dart:async';

import 'package:flutter/material.dart';

/// 입력 음량을 막대로 보여 주는 파형. 보조 표시라 스크린리더에는 숨기고 상태는 타이머 문구로 전달한다.
class Waveform extends StatefulWidget {
  const Waveform({required this.levels, required this.active, super.key});

  final Stream<double> levels;
  final bool active;

  @override
  State<Waveform> createState() => _WaveformState();
}

class _WaveformState extends State<Waveform> {
  static const int _bars = 32;
  final List<double> _values = List<double>.filled(_bars, 0.05, growable: true);
  StreamSubscription<double>? _sub;

  @override
  void initState() {
    super.initState();
    _sub = widget.levels.listen((double v) {
      if (!widget.active || !mounted) return;
      setState(() {
        _values
          ..removeAt(0)
          ..add(v.clamp(0.05, 1.0));
      });
    });
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final Color color = Theme.of(context).colorScheme.primary;
    return ExcludeSemantics(
      child: SizedBox(
        height: 72,
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: <Widget>[
            for (final double v in _values)
              Expanded(
                child: Center(
                  child: Container(
                    width: 4,
                    height: 8 + 60 * v,
                    decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(2)),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
