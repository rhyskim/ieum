import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// 앱에 포함한 글꼴의 라이선스. 패키지 라이선스는 Flutter가 [LicenseRegistry]로 자동 수집한다.
class FontLicense {
  const FontLicense(this.name, this.assetPath);
  final String name;
  final String assetPath;
}

const List<FontLicense> bundledFontLicenses = <FontLicense>[
  FontLicense('Gaegu', 'assets/licenses/Gaegu-OFL.txt'),
  FontLicense('Gowun Dodum', 'assets/licenses/GowunDodum-OFL.txt'),
];

/// 앱 시작 시 한 번 호출해 글꼴 라이선스를 등록한다.
bool _registered = false;

void registerFontLicenses() {
  if (_registered) return;
  _registered = true;
  LicenseRegistry.addLicense(() async* {
    for (final FontLicense f in bundledFontLicenses) {
      final String text = await rootBundle.loadString(f.assetPath);
      yield LicenseEntryWithLineBreaks(<String>[f.name], text);
    }
  });
}
