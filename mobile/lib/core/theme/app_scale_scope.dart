import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/core/theme/paper_background.dart';
import 'package:ieum_mobile/core/theme/pinch_zoom_detector.dart';
import 'package:ieum_mobile/core/theme/text_scale.dart';
import 'package:ieum_mobile/core/theme/text_scale_sheet.dart';

/// 앱 루트에서 (시스템 × 사용자) 글자 배율을 적용하고, 종이 배경을 깔고,
/// 두 손가락 확대 제스처로 글자 크기 시트를 띄운다. 시트는 한 번에 하나만 뜬다.
class AppScaleScope extends ConsumerStatefulWidget {
  const AppScaleScope({required this.navigatorKey, required this.child, super.key});

  /// 시트를 띄울 내비게이터(라우터의 navigatorKey).
  final GlobalKey<NavigatorState> navigatorKey;
  final Widget child;

  @override
  ConsumerState<AppScaleScope> createState() => _AppScaleScopeState();
}

class _AppScaleScopeState extends ConsumerState<AppScaleScope> {
  bool _sheetOpen = false;

  void _openSheet() {
    final BuildContext? navContext = widget.navigatorKey.currentContext;
    if (_sheetOpen || navContext == null) return;
    _sheetOpen = true;
    showTextScaleSheet(navContext).whenComplete(() => _sheetOpen = false);
  }

  @override
  Widget build(BuildContext context) {
    final double userScale = ref.watch(userTextScaleProvider);
    final MediaQueryData mediaQuery = MediaQuery.of(context);
    final double effective = TextScaleConfig.effective(mediaQuery.textScaler.scale(1.0), userScale);
    return MediaQuery(
      data: mediaQuery.copyWith(textScaler: TextScaler.linear(effective)),
      child: PaperBackground(
        child: PinchZoomDetector(onTrigger: _openSheet, child: widget.child),
      ),
    );
  }
}
