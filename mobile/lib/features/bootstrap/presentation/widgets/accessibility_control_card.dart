import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/core/theme/accessibility_config.dart';
import 'package:ieum_mobile/core/theme/accessibility_notifier.dart';

class AccessibilityControlCard extends ConsumerWidget {
  const AccessibilityControlCard({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final config = ref.watch(accessibilityConfigProvider);
    final notifier = ref.read(accessibilityConfigProvider.notifier);
    final textTheme = Theme.of(context).textTheme;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              '글자 크기 및 고대비 설정',
              style: textTheme.titleLarge,
            ),
            const SizedBox(height: 8),
            Text(
              '부모님께서 편안하게 읽으실 수 있도록 글자 크기와 화면 대비를 조절합니다.',
              style: textTheme.bodyMedium,
            ),
            const SizedBox(height: 16),
            for (final mode in FontScaleMode.values) ...[
              Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: config.fontScaleMode == mode
                    ? FilledButton.icon(
                        key: ValueKey('font_scale_${mode.name}'),
                        onPressed: () => notifier.setFontScaleMode(mode),
                        icon: const Icon(Icons.check_circle_outline),
                        label: Text(mode.label),
                      )
                    : OutlinedButton.icon(
                        key: ValueKey('font_scale_${mode.name}'),
                        onPressed: () => notifier.setFontScaleMode(mode),
                        icon: const Icon(Icons.text_fields),
                        label: Text(mode.label),
                      ),
              ),
            ],
            const SizedBox(height: 8),
            OutlinedButton.icon(
              key: const ValueKey('toggle_high_contrast_button'),
              onPressed: () => notifier.toggleHighContrast(!config.highContrastEnabled),
              icon: Icon(
                config.highContrastEnabled
                    ? Icons.contrast
                    : Icons.brightness_medium,
              ),
              label: Text(
                config.highContrastEnabled
                    ? '고대비 모드 켜짐 (끄기)'
                    : '고대비 모드 켜기 (부모님 권장)',
              ),
            ),
          ],
        ),
      ),
    );
  }
}
