import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/core/theme/text_scale.dart';

/// 글자 크기 슬라이더. 시트와 설정 화면이 같은 위젯을 쓴다.
class TextScaleSlider extends ConsumerWidget {
  const TextScaleSlider({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final double scale = ref.watch(userTextScaleProvider);
    final int index = TextScaleConfig.steps.indexOf(scale);
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        Text('글자 크기 ${(scale * 100).round()}%', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        Semantics(
          label: '글자 크기',
          value: '${(scale * 100).round()}%',
          child: Slider(
            key: const ValueKey('text_scale_slider'),
            value: index.toDouble(),
            min: 0,
            max: (TextScaleConfig.steps.length - 1).toDouble(),
            divisions: TextScaleConfig.steps.length - 1,
            label: '${(scale * 100).round()}%',
            semanticFormatterCallback: (double v) =>
                '${(TextScaleConfig.steps[v.round()] * 100).round()}%',
            onChanged: (double v) =>
                ref.read(userTextScaleProvider.notifier).set(TextScaleConfig.steps[v.round()]),
          ),
        ),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: <Widget>[
            Text('작게', style: Theme.of(context).textTheme.bodyMedium),
            Text('크게', style: Theme.of(context).textTheme.bodyMedium),
          ],
        ),
      ],
    );
  }
}

Future<void> showTextScaleSheet(BuildContext context) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    builder: (BuildContext sheetContext) => SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(24, 24, 24, 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            Text('화면을 확대하시겠습니까?', style: Theme.of(sheetContext).textTheme.titleLarge),
            const SizedBox(height: 16),
            const TextScaleSlider(),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: () => Navigator.of(sheetContext).pop(),
              child: const Text('확인'),
            ),
          ],
        ),
      ),
    ),
  );
}
