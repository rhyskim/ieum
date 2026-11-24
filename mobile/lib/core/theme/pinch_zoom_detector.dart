import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:ieum_mobile/core/theme/text_scale.dart';

/// 두 손가락 확대/축소를 감지해 임계값을 넘으면 [onTrigger]를 부른다.
///
/// 제스처 경기장(gesture arena)에 참여하지 않고 포인터 이벤트만 관찰하므로
/// 스크롤·탭·한 손가락 동작과 충돌하지 않는다. 손가락이 정확히 둘일 때만 동작한다.
class PinchZoomDetector extends StatefulWidget {
  const PinchZoomDetector({required this.onTrigger, required this.child, super.key});

  final VoidCallback onTrigger;
  final Widget child;

  @override
  State<PinchZoomDetector> createState() => _PinchZoomDetectorState();
}

class _PinchZoomDetectorState extends State<PinchZoomDetector> {
  final Map<int, Offset> _pointers = <int, Offset>{};
  double? _startDistance;
  Timer? _holdTimer;
  bool _fired = false;

  double get _distance {
    final List<Offset> p = _pointers.values.toList();
    return (p[0] - p[1]).distance;
  }

  void _reset() {
    _holdTimer?.cancel();
    _holdTimer = null;
    _startDistance = null;
    _fired = false;
  }

  bool get _overThreshold {
    final double? start = _startDistance;
    if (start == null || start <= 0) return false;
    return (_distance / start - 1).abs() >= TextScaleConfig.pinchRatioThreshold;
  }

  void _onDown(PointerDownEvent e) {
    _pointers[e.pointer] = e.position;
    if (_pointers.length == 2) {
      _startDistance = _distance;
      _fired = false;
    } else {
      _reset();
    }
  }

  void _onMove(PointerMoveEvent e) {
    if (!_pointers.containsKey(e.pointer)) return;
    _pointers[e.pointer] = e.position;
    if (_pointers.length != 2 || _fired) return;
    if (_overThreshold) {
      _holdTimer ??= Timer(TextScaleConfig.pinchHoldDuration, () {
        _holdTimer = null;
        if (_pointers.length == 2 && _overThreshold && !_fired) {
          _fired = true;
          widget.onTrigger();
        }
      });
    } else {
      _holdTimer?.cancel();
      _holdTimer = null;
    }
  }

  void _onUp(PointerEvent e) {
    _pointers.remove(e.pointer);
    _reset();
  }

  @override
  void dispose() {
    _holdTimer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Listener(
      behavior: HitTestBehavior.translucent,
      onPointerDown: _onDown,
      onPointerMove: _onMove,
      onPointerUp: _onUp,
      onPointerCancel: _onUp,
      child: widget.child,
    );
  }
}
