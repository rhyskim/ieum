import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:ieum_mobile/features/bootstrap/presentation/phase0_bootstrap_screen.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

part 'app_router.g.dart';

@riverpod
GoRouter appRouter(Ref ref) {
  return GoRouter(
    initialLocation: '/',
    routes: [
      GoRoute(
        path: '/',
        builder: (context, state) => const Phase0BootstrapScreen(),
      ),
      GoRoute(
        path: '/invite',
        builder: (context, state) {
          final code = state.uri.queryParameters['code'] ?? '';
          // Phase 1-2에서 실제 그룹 초대 참여 화면으로 교체될 플레이스홀더
          return Scaffold(
            appBar: AppBar(title: const Text('가족 초대 연결')),
            body: Center(child: Text('초대 코드: $code (Phase 1에서 연결됩니다)')),
          );
        },
      ),
    ],
  );
}
