import 'package:ieum_mobile/features/voice/domain/voice_ports.dart';
import 'package:url_launcher/url_launcher.dart';

class UrlPhoneLauncher implements PhoneLauncher {
  const UrlPhoneLauncher();

  @override
  Future<bool> call(String phone) async {
    try {
      return await launchUrl(Uri(scheme: 'tel', path: phone));
    } on Object {
      return false;
    }
  }
}
