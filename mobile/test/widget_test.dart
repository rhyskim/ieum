import 'package:flutter_test/flutter_test.dart';
import 'package:ieum_mobile/core/theme/accessibility_config.dart';

void main() {
  test('FontScaleMode 기본값 검증', () {
    expect(FontScaleMode.normal.isSeniorMode, isFalse);
    expect(FontScaleMode.large.isSeniorMode, isTrue);
    expect(FontScaleMode.xlarge.isSeniorMode, isTrue);
  });
}
