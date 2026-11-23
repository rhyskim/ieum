import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/features/archive/data/archive_repository_impl.dart';
import 'package:ieum_mobile/features/archive/domain/archive_models.dart';
import 'package:ieum_mobile/features/archive/presentation/reaction_bar.dart';
import 'package:ieum_mobile/features/group/presentation/group_widgets.dart';
import 'package:ieum_mobile/features/question/domain/question_models.dart';

/// 지난 질문 타임라인(최신 먼저). 서버가 열람 권한을 확인한 답변만 내려오므로 가려진 답변은 목록에 없다.
/// 다음 페이지는 "더 보기"로 서버 커서를 이어 받는다(새 질문이 생겨도 중복·누락이 없다).
class ArchiveScreen extends ConsumerStatefulWidget {
  const ArchiveScreen({required this.groupId, super.key});

  final String groupId;

  @override
  ConsumerState<ArchiveScreen> createState() => _ArchiveScreenState();
}

class _ArchiveScreenState extends ConsumerState<ArchiveScreen> {
  final List<ArchiveItem> _items = <ArchiveItem>[];
  String? _nextCursor;
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _loadMore();
  }

  Future<void> _loadMore() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final ArchivePage page =
          await ref.read(archiveRepositoryProvider).timeline(widget.groupId, cursor: _nextCursor);
      if (!mounted) return;
      setState(() {
        _items.addAll(page.items);
        _nextCursor = page.nextCursor;
        _loading = false;
      });
    } on ApiException catch (e) {
      if (mounted) {
        setState(() {
          _error = e.message;
          _loading = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('지난 질문')),
      body: SafeArea(
        child: _items.isEmpty && !_loading && _error == null
            ? const Center(
                child: Padding(
                  padding: EdgeInsets.all(24),
                  child: Text('아직 지난 질문이 없어요.', style: TextStyle(fontSize: 18)),
                ),
              )
            : ListView(
                padding: const EdgeInsets.all(24),
                children: <Widget>[
                  for (final ArchiveItem item in _items) _ArchiveItemCard(groupId: widget.groupId, item: item),
                  if (_loading)
                    const Padding(
                      padding: EdgeInsets.all(16),
                      child: Center(child: CircularProgressIndicator()),
                    ),
                  ErrorText(_error),
                  if (_error != null)
                    LargeButton(label: '다시 시도', onPressed: _loadMore)
                  else if (!_loading && _nextCursor != null)
                    LargeButton(label: '더 보기', outlined: true, onPressed: _loadMore),
                ],
              ),
      ),
    );
  }
}

String _formatDate(DateTime d) => '${d.year}년 ${d.month}월 ${d.day}일';

class _ArchiveItemCard extends StatelessWidget {
  const _ArchiveItemCard({required this.groupId, required this.item});

  final String groupId;
  final ArchiveItem item;

  @override
  Widget build(BuildContext context) {
    final bool opened = item.revealStatus != RevealStatus.locked;
    return Padding(
      padding: const EdgeInsets.only(bottom: 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text(_formatDate(item.cycleDate), style: const TextStyle(fontSize: 18)),
          const SizedBox(height: 4),
          Text(item.questionText, style: const TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
          const SizedBox(height: 8),
          if (!opened)
            const Text('아직 답변이 열리지 않았어요.', style: TextStyle(fontSize: 18))
          else if (!item.hasAnswered)
            const Text('내 답변을 쓰면 가족의 답변을 볼 수 있어요.', style: TextStyle(fontSize: 18)),
          for (final ArchiveAnswer a in item.answers)
            _AnswerCard(groupId: groupId, answer: a, showReactions: opened),
        ],
      ),
    );
  }
}

class _AnswerCard extends StatelessWidget {
  const _AnswerCard({required this.groupId, required this.answer, required this.showReactions});

  final String groupId;
  final ArchiveAnswer answer;
  final bool showReactions;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Text(
              answer.mine ? '${answer.authorName} (나)' : answer.authorName,
              style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            Text(answer.textContent, style: const TextStyle(fontSize: 18)),
            // 공개 전에는 서버가 리액션을 거절하므로 바를 두지 않는다.
            if (showReactions) ...<Widget>[
              const SizedBox(height: 12),
              ReactionBar(groupId: groupId, answerId: answer.answerId, initial: answer.reactions),
            ],
          ],
        ),
      ),
    );
  }
}
