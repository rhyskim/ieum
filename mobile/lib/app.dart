import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/core/router/app_router.dart';
import 'package:ieum_mobile/core/theme/accessibility_notifier.dart';
import 'package:ieum_mobile/core/theme/app_scale_scope.dart';
import 'package:ieum_mobile/core/theme/app_theme.dart';
import 'package:ieum_mobile/features/notification/application/push_registrar.dart';

class IeumApp extends ConsumerWidget {
  const IeumApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final accessibilityConfig = ref.watch(accessibilityConfigProvider);
    final router = ref.watch(appRouterProvider);
    ref.watch(pushBindingProvider);

    return MaterialApp.router(
      title: '이음',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.buildTheme(accessibilityConfig),
      routerConfig: router,
      builder: (context, child) => AppScaleScope(
        navigatorKey: router.routerDelegate.navigatorKey,
        child: child ?? const SizedBox.shrink(),
      ),
    );
  }
}
