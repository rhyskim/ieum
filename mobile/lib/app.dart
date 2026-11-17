import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/core/router/app_router.dart';
import 'package:ieum_mobile/core/theme/accessibility_notifier.dart';
import 'package:ieum_mobile/core/theme/app_theme.dart';

class IeumApp extends ConsumerWidget {
  const IeumApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final accessibilityConfig = ref.watch(accessibilityConfigProvider);
    final router = ref.watch(appRouterProvider);

    return MaterialApp.router(
      title: '이음',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.buildTheme(accessibilityConfig),
      routerConfig: router,
      builder: (context, child) {
        final mediaQuery = MediaQuery.of(context);
        final clampedScaler = mediaQuery.textScaler.clamp(
          minScaleFactor: 1.0,
          maxScaleFactor: 1.5,
        );
        return MediaQuery(
          data: mediaQuery.copyWith(textScaler: clampedScaler),
          child: child ?? const SizedBox.shrink(),
        );
      },
    );
  }
}
