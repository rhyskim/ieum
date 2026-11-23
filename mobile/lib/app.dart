import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/core/router/app_router.dart';
import 'package:ieum_mobile/core/theme/accessibility_notifier.dart';
import 'package:ieum_mobile/core/theme/app_theme.dart';
import 'package:ieum_mobile/core/theme/paper_background.dart';
import 'package:ieum_mobile/core/theme/pinch_zoom_detector.dart';
import 'package:ieum_mobile/core/theme/text_scale.dart';
import 'package:ieum_mobile/core/theme/text_scale_sheet.dart';
import 'package:ieum_mobile/features/notification/application/push_registrar.dart';

class IeumApp extends ConsumerWidget {
  const IeumApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final accessibilityConfig = ref.watch(accessibilityConfigProvider);
    final router = ref.watch(appRouterProvider);
    ref.watch(pushBindingProvider);
    final userScale = ref.watch(userTextScaleProvider);

    return MaterialApp.router(
      title: '이음',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.buildTheme(accessibilityConfig),
      routerConfig: router,
      builder: (context, child) {
        final mediaQuery = MediaQuery.of(context);
        final systemScale = mediaQuery.textScaler.scale(1.0);
        final effective = TextScaleConfig.effective(systemScale, userScale);
        return MediaQuery(
          data: mediaQuery.copyWith(textScaler: TextScaler.linear(effective)),
          child: PaperBackground(
            child: PinchZoomDetector(
              onTrigger: () {
                final navContext = router.routerDelegate.navigatorKey.currentContext;
                if (navContext != null) showTextScaleSheet(navContext);
              },
              child: child ?? const SizedBox.shrink(),
            ),
          ),
        );
      },
    );
  }
}
