import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ieum_mobile/core/theme/paper_theme.dart';

/// 글꼴 후보. [assetPath]의 파일이 앱에 없으면 비교 화면에서 건너뛴다.
class FontCandidate {
  const FontCandidate(this.family, this.label, this.assetPath);
  final String family;
  final String label;
  final String assetPath;
}

const List<FontCandidate> fontCandidates = <FontCandidate>[
  FontCandidate('Gaegu', 'Gaegu', 'assets/fonts/Gaegu-Regular.ttf'),
  FontCandidate('GamjaFlower', 'Gamja Flower', 'assets/fonts/GamjaFlower-Regular.ttf'),
  FontCandidate('HiMelody', 'Hi Melody', 'assets/fonts/HiMelody-Regular.ttf'),
  FontCandidate('PoorStory', 'Poor Story', 'assets/fonts/PoorStory-Regular.ttf'),
  FontCandidate('GowunDodum', 'Gowun Dodum(기준)', 'assets/fonts/GowunDodum-Regular.ttf'),
];

const String _sampleTitle = '오늘의 질문';
const String _sampleDigits = '0123456789  2026.11.23  오후 5시 30분';
const String _sampleParagraph =
    '어릴 적 여름방학마다 할머니 댁에 내려가 마루에서 수박을 먹던 기억이 가장 선명해요. '
    '매미 소리와 선풍기 바람, 할머니가 부채질해 주시던 손길까지 하나하나 떠오릅니다. '
    '그때 들려주신 이야기를 지금도 가끔 꺼내 봅니다.';

/// 앱 안 글꼴 비교 화면(개발용). 각 후보를 18sp 이상 본문·숫자·긴 문단으로 보여 주고,
/// 마지막에 "제목만 손글씨 + 본문 고운돋움" 조합을 보여 준다.
/// 글꼴 파일이 없는 후보는 건너뛰므로 파일 유무와 무관하게 열린다.
class FontCompareScreen extends StatelessWidget {
  const FontCompareScreen({this.available, super.key});

  /// 테스트 등에서 사용 가능한 후보를 직접 지정한다. null이면 에셋 목록으로 판단한다.
  final List<FontCandidate>? available;

  Future<List<FontCandidate>> _load() async {
    if (available != null) return available!;
    final AssetManifest manifest = await AssetManifest.loadFromAssetBundle(rootBundle);
    final Set<String> assets = manifest.listAssets().toSet();
    return fontCandidates.where((FontCandidate c) => assets.contains(c.assetPath)).toList();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('글꼴 비교')),
      body: FutureBuilder<List<FontCandidate>>(
        future: _load(),
        builder: (BuildContext context, AsyncSnapshot<List<FontCandidate>> snap) {
          if (!snap.hasData) return const Center(child: CircularProgressIndicator());
          final List<FontCandidate> items = snap.data!;
          return ListView(
            padding: const EdgeInsets.all(24),
            children: <Widget>[
              for (final FontCandidate c in items) _Sample(candidate: c),
              if (items.any((FontCandidate c) => c.family == PaperTheme.bodyFontFamily))
                const _ComboSample(),
            ],
          );
        },
      ),
    );
  }
}

class _Sample extends StatelessWidget {
  const _Sample({required this.candidate});
  final FontCandidate candidate;

  @override
  Widget build(BuildContext context) {
    final String f = candidate.family;
    return _Card(
      heading: candidate.label,
      children: <Widget>[
        Text(_sampleTitle, style: TextStyle(fontFamily: f, fontSize: 28, color: PaperTheme.textPrimary)),
        Text(_sampleDigits, style: TextStyle(fontFamily: f, fontSize: 20, color: PaperTheme.textPrimary)),
        Text(_sampleParagraph,
            style: TextStyle(fontFamily: f, fontSize: 18, height: 1.5, color: PaperTheme.textPrimary)),
      ],
    );
  }
}

class _ComboSample extends StatelessWidget {
  const _ComboSample();

  @override
  Widget build(BuildContext context) {
    return const _Card(
      heading: '조합: 제목 Gaegu + 본문 GowunDodum',
      children: <Widget>[
        Text(_sampleTitle,
            style: TextStyle(
                fontFamily: PaperTheme.titleFontFamily, fontSize: 28, color: PaperTheme.textPrimary)),
        Text(_sampleDigits,
            style: TextStyle(
                fontFamily: PaperTheme.bodyFontFamily, fontSize: 20, color: PaperTheme.textPrimary)),
        Text(_sampleParagraph,
            style: TextStyle(
                fontFamily: PaperTheme.bodyFontFamily,
                fontSize: 18,
                height: 1.5,
                color: PaperTheme.textPrimary)),
      ],
    );
  }
}

class _Card extends StatelessWidget {
  const _Card({required this.heading, required this.children});
  final String heading;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Text(heading, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
              const SizedBox(height: 8),
              ...children,
            ],
          ),
        ),
      ),
    );
  }
}

/// 개발 빌드에서만 설정 화면에 진입점을 보여 준다.
bool get showFontCompareEntry => kDebugMode;
