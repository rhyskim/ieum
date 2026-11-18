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
import 'package:ieum_mobile/features/auth/presentation/withdraw_screen.dart';
import 'package:ieum_mobile/features/auth/presentation/sessions_screen.dart';
import 'package:ieum_mobile/features/notification/application/push_registrar.dart';

class _FakeRepository implements AuthRepository {
  final List<String> loggedIn = <String>[];
  final List<String> revoked = <String>[];
  int logoutCount = 0;
  final List<String> restored = <String>[];
  final List<ContentChoice> withdrawn = <ContentChoice>[];
  ApiException? withdrawError;
  ApiException? restoreError;
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
    if (restoreError != null) throw restoreError!;
    restored.add('${provider.pathSegment}:$socialToken');
  }

  @override
  Future<void> withdraw(ContentChoice choice) async {
    if (withdrawError != null) throw withdrawError!;
    withdrawn.add(choice);
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

/// 푸시 토큰 등록 호출만 세는 대역. 실제 Firebase 없이 registerIfGranted 호출 여부를 검증한다.
class _SpyPushRegistrar implements PushRegistrar {
  _SpyPushRegistrar({this.granted = true});

  final bool granted;
  int registerIfGrantedCalls = 0;

  @override
  Future<bool> registerIfGranted() async {
    registerIfGrantedCalls++;
    return granted;
  }

  @override
  Future<bool> enable() async => false;

  @override
  Future<void> registerCurrentToken() async {}

  @override
  Future<void> register(String token) async {}
}

Widget _app(_FakeRepository repo, Widget home, {_SpyPushRegistrar? registrar}) {
  final GoRouter router = GoRouter(
    routes: <RouteBase>[
      GoRoute(path: '/', builder: (context, state) => home),
      GoRoute(path: '/login', builder: (context, state) => const Text('로그인 화면으로 이동')),
      GoRoute(
        path: '/notifications/intro',
        builder: (context, state) => const Text('알림 안내 화면'),
      ),
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
      if (registrar != null) pushRegistrarProvider.overrideWithValue(registrar),
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

  testWidgets('복구 성공 시 push 토큰을 다시 등록하려고 registerIfGranted를 호출하고, 허용 상태면 안내 화면 없이 끝난다', (tester) async {
    // 탈퇴할 때 서버가 기기 토큰을 지우므로 복구 뒤에 다시 등록해야 푸시를 받는다.
    final _FakeRepository repo = _FakeRepository();
    final _SpyPushRegistrar registrar = _SpyPushRegistrar();
    await tester.pumpWidget(
      _app(
        repo,
        RestoreScreen(provider: SocialProvider.kakao, restorableUntil: DateTime.utc(2026, 12, 17, 3)),
        registrar: registrar,
      ),
    );

    await tester.tap(find.text('계정 복구하기'));
    await tester.pumpAndSettle();

    expect(repo.restored, <String>['kakao:social-kakao']);
    expect(registrar.registerIfGrantedCalls, 1);
    expect(find.text('알림 안내 화면'), findsNothing);
  });

  testWidgets('복구 성공 후 알림 권한이 아직 허용 전이면 알림 사전 안내 화면으로 이동한다', (tester) async {
    final _SpyPushRegistrar registrar = _SpyPushRegistrar(granted: false);
    await tester.pumpWidget(
      _app(
        _FakeRepository(),
        RestoreScreen(provider: SocialProvider.kakao, restorableUntil: DateTime.utc(2026, 12, 17, 3)),
        registrar: registrar,
      ),
    );

    await tester.tap(find.text('계정 복구하기'));
    await tester.pumpAndSettle();

    expect(registrar.registerIfGrantedCalls, 1);
    expect(find.text('알림 안내 화면'), findsOneWidget);
  });

  testWidgets('복구가 실패하면 push 토큰 등록을 하지 않고 안내 문구를 보여 준다', (tester) async {
    final _FakeRepository repo = _FakeRepository()
      ..restoreError = const ApiException(
        code: 'ACCOUNT_RESTORE_EXPIRED',
        message: '복구 가능 기간이 지난 계정입니다.',
        statusCode: 403,
      );
    final _SpyPushRegistrar registrar = _SpyPushRegistrar();
    await tester.pumpWidget(
      _app(
        repo,
        RestoreScreen(provider: SocialProvider.kakao, restorableUntil: DateTime.utc(2026, 12, 17, 3)),
        registrar: registrar,
      ),
    );

    await tester.tap(find.text('계정 복구하기'));
    await tester.pumpAndSettle();

    expect(registrar.registerIfGrantedCalls, 0);
    expect(find.text('복구 가능 기간이 지난 계정입니다.'), findsOneWidget);
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

  testWidgets('탈퇴 화면: 쉬운 말 선택지·30일 복구 안내가 보이고, 선택 전에는 탈퇴 버튼이 비활성이다', (tester) async {
    await tester.pumpWidget(_app(_FakeRepository(), const WithdrawScreen()));

    expect(find.textContaining('30일 안에'), findsOneWidget);
    expect(find.text('가족에게 계속 보여 주기'), findsOneWidget);
    expect(find.text('모두 지우기'), findsOneWidget);
    final Finder button = find.widgetWithText(ElevatedButton, '탈퇴하기');
    expect(tester.getSize(button).height, greaterThanOrEqualTo(56));
    expect(tester.widget<ElevatedButton>(button).onPressed, isNull);
  });

  testWidgets('탈퇴 화면: 선택 후 확인 단계를 거쳐야 서버를 호출하고, 취소하면 호출하지 않는다', (tester) async {
    final _FakeRepository repo = _FakeRepository();
    await tester.pumpWidget(_app(repo, const WithdrawScreen()));

    await tester.tap(find.text('모두 지우기'));
    await tester.pump();
    await tester.tap(find.widgetWithText(ElevatedButton, '탈퇴하기'));
    await tester.pumpAndSettle();

    // 확인 단계: 아직 서버 호출 전
    expect(find.text('정말 탈퇴할까요?'), findsOneWidget);
    expect(repo.withdrawn, isEmpty);
    await tester.tap(find.text('계속 이용하기'));
    await tester.pumpAndSettle();
    expect(repo.withdrawn, isEmpty);

    await tester.tap(find.widgetWithText(ElevatedButton, '탈퇴하기'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(TextButton, '탈퇴하기'));
    await tester.pumpAndSettle();

    expect(repo.withdrawn, <ContentChoice>[ContentChoice.delete]);
    expect(find.text('로그인 화면으로 이동'), findsOneWidget);
  });

  testWidgets('탈퇴 화면: 서버가 거절하면 안내 문구를 보여 주고 화면에 남는다', (tester) async {
    final _FakeRepository repo = _FakeRepository()
      ..withdrawError = const ApiException(code: 'INTERNAL_ERROR', message: '잠시 후 다시 시도해 주세요.', statusCode: 500);
    await tester.pumpWidget(_app(repo, const WithdrawScreen()));

    await tester.tap(find.text('가족에게 계속 보여 주기'));
    await tester.pump();
    await tester.tap(find.widgetWithText(ElevatedButton, '탈퇴하기'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(TextButton, '탈퇴하기'));
    await tester.pumpAndSettle();

    expect(find.text('잠시 후 다시 시도해 주세요.'), findsOneWidget);
    expect(find.text('로그인 화면으로 이동'), findsNothing);
  });
}
