import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:ieum_mobile/features/auth/data/auth_repository_impl.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/features/auth/domain/auth_models.dart';
import 'package:ieum_mobile/features/auth/domain/auth_repository.dart';
import 'package:ieum_mobile/features/auth/presentation/identities_screen.dart';
import 'package:ieum_mobile/features/auth/presentation/login_screen.dart';
import 'package:ieum_mobile/features/auth/presentation/restore_screen.dart';
import 'package:ieum_mobile/features/auth/presentation/sessions_screen.dart';

class _FakeRepository implements AuthRepository {
  final List<String> loggedIn = <String>[];
  final List<String> revoked = <String>[];
  int logoutCount = 0;
  final List<String> restored = <String>[];
  final List<String> linked = <String>[];
  final List<String> unlinked = <String>[];
  ApiException? loginError;
  ApiException? linkError;
  List<SocialProvider> identities = <SocialProvider>[SocialProvider.kakao];
  List<DeviceSession> sessions = <DeviceSession>[
    DeviceSession(id: 'a', deviceLabel: '내 아이폰', createdAt: DateTime.utc(2026, 11, 1)),
    DeviceSession(id: 'b', deviceLabel: '태블릿', createdAt: DateTime.utc(2026, 11, 2)),
  ];

  @override
  Future<bool> login(SocialProvider provider, String socialToken, {String? deviceLabel}) async {
    if (loginError != null) throw loginError!;
    loggedIn.add('${provider.pathSegment}:$socialToken');
    return true;
  }

  @override
  Future<void> restore(SocialProvider provider, String socialToken, {String? deviceLabel}) async {
    restored.add('${provider.pathSegment}:$socialToken');
  }

  @override
  Future<List<LinkedIdentity>> fetchIdentities() async => identities
      .map((p) => LinkedIdentity(provider: p, linkedAt: DateTime.utc(2026, 11, 1)))
      .toList();

  @override
  Future<void> linkIdentity(SocialProvider provider, String socialToken) async {
    if (linkError != null) throw linkError!;
    linked.add('${provider.pathSegment}:$socialToken');
    identities = <SocialProvider>[...identities, provider];
  }

  @override
  Future<void> unlinkIdentity(SocialProvider provider) async {
    unlinked.add(provider.pathSegment);
    identities = identities.where((p) => p != provider).toList();
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
      GoRoute(
        path: '/restore',
        builder: (context, state) {
          final RestoreArgs args = state.extra! as RestoreArgs;
          return RestoreScreen(provider: args.provider, restorableUntil: args.restorableUntil);
        },
      ),
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

  testWidgets('탈퇴 유예 계정으로 로그인하면 복구 화면으로 이동해 복구 마감일을 보여 준다', (tester) async {
    final _FakeRepository repo = _FakeRepository()
      ..loginError = const ApiException(
        code: 'ACCOUNT_PENDING_DELETION',
        message: '탈퇴 처리 중',
        statusCode: 403,
        data: <String, dynamic>{'restorableUntil': '2026-12-17T03:00:00Z'},
      );
    await tester.pumpWidget(_app(repo, const LoginScreen()));

    await tester.tap(find.text('카카오로 시작하기'));
    await tester.pumpAndSettle();

    expect(find.textContaining('12월 17일까지 복구할 수 있어요'), findsOneWidget);
    final Finder button = find.widgetWithText(ElevatedButton, '계정 복구하기');
    expect(button, findsOneWidget);
    expect(tester.getSize(button).height, greaterThanOrEqualTo(56));
  });

  testWidgets('복구 버튼을 누르면 소셜 토큰을 다시 받아 restore를 호출하고 홈으로 이동한다', (tester) async {
    final _FakeRepository repo = _FakeRepository();
    await tester.pumpWidget(
      _app(
        repo,
        RestoreScreen(provider: SocialProvider.kakao, restorableUntil: DateTime.utc(2026, 12, 17, 3)),
      ),
    );

    await tester.tap(find.text('계정 복구하기'));
    await tester.pumpAndSettle();

    expect(repo.restored, <String>['kakao:social-kakao']);
  });

  testWidgets('로그인 수단 화면: 연결된 수단은 해제, 없는 수단은 연결 버튼이 보이고 56dp 이상이다', (tester) async {
    final _FakeRepository repo = _FakeRepository()
      ..identities = <SocialProvider>[SocialProvider.kakao, SocialProvider.naver];
    await tester.pumpWidget(_app(repo, const IdentitiesScreen()));
    await tester.pumpAndSettle();

    expect(find.text('카카오'), findsOneWidget);
    expect(find.text('네이버'), findsOneWidget);
    expect(find.widgetWithText(OutlinedButton, '카카오 해제'), findsOneWidget);
    final Finder linkApple = find.widgetWithText(ElevatedButton, 'Apple 연결');
    expect(linkApple, findsOneWidget);
    expect(tester.getSize(linkApple).height, greaterThanOrEqualTo(56));
    expect(tester.getSize(find.widgetWithText(OutlinedButton, '네이버 해제')).height, greaterThanOrEqualTo(56));
  });

  testWidgets('로그인 수단이 하나뿐이면 해제 버튼이 비활성이고 안내 문구가 보인다', (tester) async {
    await tester.pumpWidget(_app(_FakeRepository(), const IdentitiesScreen()));
    await tester.pumpAndSettle();

    final OutlinedButton unlink =
        tester.widget<OutlinedButton>(find.widgetWithText(OutlinedButton, '카카오 해제'));
    expect(unlink.onPressed, isNull);
    expect(find.textContaining('마지막 로그인 수단'), findsOneWidget);
  });

  testWidgets('Apple 연결을 누르면 소셜 토큰을 받아 연결하고, 해제를 누르면 목록에서 사라진다', (tester) async {
    final _FakeRepository repo = _FakeRepository();
    await tester.pumpWidget(_app(repo, const IdentitiesScreen()));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Apple 연결'));
    await tester.pumpAndSettle();
    expect(repo.linked, <String>['apple:social-apple']);
    expect(find.widgetWithText(OutlinedButton, 'Apple 해제'), findsOneWidget);

    await tester.tap(find.text('Apple 해제'));
    await tester.pumpAndSettle();
    expect(repo.unlinked, <String>['apple']);
    expect(find.widgetWithText(ElevatedButton, 'Apple 연결'), findsOneWidget);
  });

  testWidgets('이미 다른 계정에 가입된 소셜 계정 연결이 409로 거절되면 서버 안내 문구를 보여 준다', (tester) async {
    final _FakeRepository repo = _FakeRepository()
      ..linkError = const ApiException(
        code: 'IDENTITY_ALREADY_REGISTERED',
        message: '이미 다른 이음 계정으로 가입된 소셜 계정이에요.',
        statusCode: 409,
      );
    await tester.pumpWidget(_app(repo, const IdentitiesScreen()));
    await tester.pumpAndSettle();

    await tester.tap(find.text('네이버 연결'));
    await tester.pumpAndSettle();

    expect(find.text('이미 다른 이음 계정으로 가입된 소셜 계정이에요.'), findsOneWidget);
  });
}
