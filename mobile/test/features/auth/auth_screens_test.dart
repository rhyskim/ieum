import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:ieum_mobile/features/auth/data/auth_repository_impl.dart';
import 'package:ieum_mobile/features/auth/domain/auth_models.dart';
import 'package:ieum_mobile/features/auth/domain/auth_repository.dart';
import 'package:ieum_mobile/features/auth/presentation/login_screen.dart';
import 'package:ieum_mobile/features/auth/presentation/sessions_screen.dart';

class _FakeRepository implements AuthRepository {
  final List<String> loggedIn = <String>[];
  final List<String> revoked = <String>[];
  int logoutCount = 0;
  List<DeviceSession> sessions = <DeviceSession>[
    DeviceSession(id: 'a', deviceLabel: '내 아이폰', createdAt: DateTime.utc(2026, 11, 1)),
    DeviceSession(id: 'b', deviceLabel: '태블릿', createdAt: DateTime.utc(2026, 11, 2)),
  ];

  @override
  Future<bool> login(SocialProvider provider, String socialToken, {String? deviceLabel}) async {
    loggedIn.add('${provider.pathSegment}:$socialToken');
    return true;
  }

  @override
  Future<void> logout() async => logoutCount++;

  @override
  Future<List<DeviceSession>> fetchSessions() async => List<DeviceSession>.of(sessions);

  @override
  Future<void> revokeSession(String sessionId) async {
    revoked.add(sessionId);
    sessions = sessions.where((s) => s.id != sessionId).toList();
  }
}

class _FakeTokenSource implements SocialTokenSource {
  @override
  Future<String> obtainToken(SocialProvider provider) async => 'social-${provider.name}';
}

Widget _app(_FakeRepository repo, Widget home) {
  final GoRouter router = GoRouter(
    routes: <RouteBase>[
      GoRoute(path: '/', builder: (context, state) => home),
      GoRoute(path: '/login', builder: (context, state) => const Text('로그인 화면으로 이동')),
    ],
  );
  return ProviderScope(
    overrides: [
      authRepositoryProvider.overrideWithValue(repo),
      socialTokenSourceProvider.overrideWithValue(_FakeTokenSource()),
    ],
    child: MaterialApp.router(routerConfig: router),
  );
}

void main() {
  testWidgets('로그인 화면에 카카오·네이버·Apple 버튼이 한글 라벨과 48dp 이상 높이로 표시된다', (tester) async {
    await tester.pumpWidget(_app(_FakeRepository(), const LoginScreen()));

    for (final SocialProvider p in SocialProvider.values) {
      final Finder button = find.widgetWithText(ElevatedButton, p.label);
      expect(button, findsOneWidget);
      expect(tester.getSize(button).height, greaterThanOrEqualTo(48));
    }
  });

  testWidgets('카카오 버튼을 누르면 소셜 토큰을 받아 서버 로그인을 호출한다', (tester) async {
    final _FakeRepository repo = _FakeRepository();
    await tester.pumpWidget(_app(repo, const LoginScreen()));

    await tester.tap(find.text('카카오로 시작하기'));
    await tester.pumpAndSettle();

    expect(repo.loggedIn, <String>['kakao:social-kakao']);
  });

  testWidgets('기기 목록에서 다른 기기를 로그아웃하면 목록에서 사라진다', (tester) async {
    final _FakeRepository repo = _FakeRepository();
    await tester.pumpWidget(_app(repo, const SessionsScreen()));
    await tester.pumpAndSettle();

    expect(find.text('내 아이폰'), findsOneWidget);
    expect(find.text('태블릿'), findsOneWidget);

    await tester.tap(find.widgetWithText(OutlinedButton, '로그아웃').last);
    await tester.pumpAndSettle();

    expect(repo.revoked, <String>['b']);
    expect(find.text('태블릿'), findsNothing);
  });

  testWidgets('이 기기 로그아웃을 누르면 로그아웃 후 로그인 화면으로 이동한다', (tester) async {
    final _FakeRepository repo = _FakeRepository();
    await tester.pumpWidget(_app(repo, const SessionsScreen()));
    await tester.pumpAndSettle();

    await tester.tap(find.text('이 기기 로그아웃'));
    await tester.pumpAndSettle();

    expect(repo.logoutCount, 1);
    expect(find.text('로그인 화면으로 이동'), findsOneWidget);
  });
}
