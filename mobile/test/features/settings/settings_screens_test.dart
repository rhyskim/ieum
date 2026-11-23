import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ieum_mobile/features/settings/presentation/font_compare_screen.dart';
import 'package:ieum_mobile/features/settings/presentation/licenses_screen.dart';

void main() {
  testWidgets('라이선스 화면에 등록한 글꼴 라이선스가 나온다', (tester) async {
    registerFontLicenses();
    await tester.pumpWidget(const MaterialApp(home: LicensePage(applicationName: '이음')));
    await tester.pumpAndSettle();
    for (final FontLicense f in bundledFontLicenses) {
      expect(find.text(f.name), findsOneWidget, reason: '${f.name} 항목이 목록에 있어야 한다');
    }
  });

  testWidgets('글꼴 비교 화면은 파일이 없는 후보를 건너뛴다', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: FontCompareScreen(available: <FontCandidate>[fontCandidates.first]),
    ));
    await tester.pumpAndSettle();
    expect(find.text('Gaegu'), findsOneWidget);
    expect(find.text('Hi Melody'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('글꼴 비교 화면: 후보가 하나도 없어도 열린다', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: FontCompareScreen(available: <FontCandidate>[])));
    await tester.pumpAndSettle();
    expect(find.text('글꼴 비교'), findsOneWidget);
  });
}
