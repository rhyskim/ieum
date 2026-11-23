import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:ieum_mobile/core/theme/text_scale_sheet.dart';
import 'package:ieum_mobile/features/settings/presentation/font_compare_screen.dart';

/// 설정 탭: 글자 크기와 오픈소스 라이선스.
class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('설정')),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: <Widget>[
          const TextScaleSlider(),
          const SizedBox(height: 24),
          SizedBox(
            height: 56,
            child: OutlinedButton.icon(
              key: const ValueKey('open_licenses_button'),
              onPressed: () => context.push('/settings/licenses'),
              icon: const Icon(Icons.article_outlined),
              label: const Text('오픈소스 라이선스'),
            ),
          ),
          if (showFontCompareEntry) ...<Widget>[
            const SizedBox(height: 12),
            SizedBox(
              height: 56,
              child: OutlinedButton.icon(
                onPressed: () => context.push('/settings/font-compare'),
                icon: const Icon(Icons.text_fields),
                label: const Text('글꼴 비교(개발용)'),
              ),
            ),
          ],
        ],
      ),
    );
  }
}
