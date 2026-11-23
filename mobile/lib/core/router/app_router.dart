import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:ieum_mobile/features/archive/presentation/archive_screen.dart';
import 'package:ieum_mobile/features/auth/presentation/identities_screen.dart';
import 'package:ieum_mobile/features/auth/presentation/login_screen.dart';
import 'package:ieum_mobile/features/auth/presentation/restore_screen.dart';
import 'package:ieum_mobile/features/auth/presentation/withdraw_screen.dart';
import 'package:ieum_mobile/features/auth/presentation/sessions_screen.dart';
import 'package:ieum_mobile/features/bootstrap/presentation/phase0_bootstrap_screen.dart';
import 'package:ieum_mobile/features/group/presentation/create_group_screen.dart';
import 'package:ieum_mobile/features/group/presentation/group_detail_screen.dart';
import 'package:ieum_mobile/features/group/presentation/group_list_screen.dart';
import 'package:ieum_mobile/features/group/presentation/invite_code_screen.dart';
import 'package:ieum_mobile/features/group/presentation/join_screen.dart';
import 'package:ieum_mobile/features/group/presentation/pending_requests_screen.dart';
import 'package:ieum_mobile/features/group/presentation/support_screen.dart';
import 'package:ieum_mobile/features/notification/presentation/push_intro_screen.dart';
import 'package:ieum_mobile/features/question/presentation/answer_compose_screen.dart';
import 'package:ieum_mobile/features/question/presentation/today_question_screen.dart';
import 'package:ieum_mobile/features/settings/presentation/settings_screen.dart';
import 'package:ieum_mobile/features/shell/presentation/main_shell.dart';
import 'package:ieum_mobile/features/shell/presentation/placeholder_screens.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

part 'app_router.g.dart';

@riverpod
GoRouter appRouter(Ref ref) {
  return GoRouter(
    initialLocation: '/',
    routes: [
      // 하단 4탭. 홈 탭은 그룹 목록이고, 그룹 하위 화면(/groups/...)은 탭 위에 전체 화면으로 쌓인다.
      StatefulShellRoute.indexedStack(
        builder: (context, state, shell) => MainShell(navigationShell: shell),
        branches: [
          StatefulShellBranch(routes: [
            GoRoute(path: '/home', builder: (context, state) => const GroupListScreen()),
          ]),
          StatefulShellBranch(routes: [
            GoRoute(path: '/list', builder: (context, state) => const ListTabPlaceholder()),
          ]),
          StatefulShellBranch(routes: [
            GoRoute(path: '/calendar', builder: (context, state) => const CalendarPlaceholderScreen()),
          ]),
          StatefulShellBranch(routes: [
            GoRoute(path: '/settings', builder: (context, state) => const SettingsScreen()),
          ]),
        ],
      ),
      GoRoute(
        path: '/settings/licenses',
        builder: (context, state) => const LicensePage(applicationName: '이음'),
      ),
      GoRoute(
        path: '/',
        builder: (context, state) => const Phase0BootstrapScreen(),
      ),
      GoRoute(
        path: '/login',
        builder: (context, state) => const LoginScreen(),
      ),
      GoRoute(
        path: '/notifications/intro',
        builder: (context, state) => const PushIntroScreen(),
      ),
      GoRoute(
        path: '/restore',
        builder: (context, state) {
          final RestoreArgs args = state.extra! as RestoreArgs;
          return RestoreScreen(provider: args.provider, restorableUntil: args.restorableUntil);
        },
      ),
      GoRoute(
        path: '/withdraw',
        builder: (context, state) => const WithdrawScreen(),
      ),
      GoRoute(
        path: '/identities',
        builder: (context, state) => const IdentitiesScreen(),
      ),
      GoRoute(
        path: '/sessions',
        builder: (context, state) => const SessionsScreen(),
      ),
      GoRoute(
        path: '/groups',
        redirect: (context, state) => state.uri.path == '/groups' ? '/home' : null,
        builder: (context, state) => const GroupListScreen(),
        routes: [
          GoRoute(
            path: 'create',
            builder: (context, state) => const CreateGroupScreen(),
          ),
          GoRoute(
            path: 'join',
            builder: (context, state) => const JoinScreen(),
          ),
          GoRoute(
            path: ':groupId',
            builder: (context, state) =>
                GroupDetailScreen(groupId: state.pathParameters['groupId']!),
            routes: [
              GoRoute(
                path: 'code',
                builder: (context, state) =>
                    InviteCodeScreen(groupId: state.pathParameters['groupId']!),
              ),
              GoRoute(
                path: 'requests',
                builder: (context, state) =>
                    PendingRequestsScreen(groupId: state.pathParameters['groupId']!),
              ),
              GoRoute(
                path: 'archive',
                builder: (context, state) =>
                    ArchiveScreen(groupId: state.pathParameters['groupId']!),
              ),
              GoRoute(
                path: 'support',
                builder: (context, state) =>
                    SupportScreen(groupId: state.pathParameters['groupId']!),
              ),
              GoRoute(
                path: 'question',
                builder: (context, state) =>
                    TodayQuestionScreen(groupId: state.pathParameters['groupId']!),
                routes: [
                  GoRoute(
                    path: ':dailyQuestionId/answer',
                    builder: (context, state) => AnswerComposeScreen(
                      groupId: state.pathParameters['groupId']!,
                      dailyQuestionId: state.pathParameters['dailyQuestionId']!,
                      initialText: state.extra as String?,
                    ),
                  ),
                ],
              ),
            ],
          ),
        ],
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
