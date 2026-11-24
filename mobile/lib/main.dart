import 'package:firebase_core/firebase_core.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/app.dart';
import 'package:ieum_mobile/core/push/push_service.dart';
import 'package:ieum_mobile/core/theme/text_scale.dart';
import 'package:ieum_mobile/features/settings/presentation/licenses_screen.dart';
import 'package:shared_preferences/shared_preferences.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  registerFontLicenses();
  final prefs = await SharedPreferences.getInstance();
  runApp(
    ProviderScope(
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        pushServiceProvider.overrideWithValue(await _initPushService()),
      ],
      child: const IeumApp(),
    ),
  );
}

/// Firebase 설정 파일(google-services.json / GoogleService-Info.plist)이 없으면 푸시 없이 앱을 띄운다.
Future<PushService> _initPushService() async {
  try {
    await Firebase.initializeApp();
    return FirebasePushService();
  } on Object catch (e) {
    debugPrint('Firebase 초기화 건너뜀(푸시 비활성): ${e.runtimeType}');
    return const NoopPushService();
  }
}
