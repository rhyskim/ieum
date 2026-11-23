import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/features/archive/data/archive_repository_impl.dart';
import 'package:ieum_mobile/features/archive/domain/archive_models.dart';
import 'package:ieum_mobile/features/archive/domain/archive_repository.dart';
import 'package:ieum_mobile/features/group/presentation/group_widgets.dart';

/// 답변 카드 하단의 이모지 바. 서버 API는 멱등한 PUT(달기)/DELETE(취소)라서, 화면이 현재 상태("내가 눌렀는가")를
/// 보고 어느 쪽을 부를지 고른다. 같은 칩을 빠르게 두 번 눌러도 응답이 올 때까지 한 요청만 나간다.
/// [initial]이 없으면(오늘의 질문 화면) 서버에서 집계를 받아 온다.
class ReactionBar extends ConsumerStatefulWidget {
  const ReactionBar({
    required this.groupId,
    required this.answerId,
    this.initial,
    super.key,
  });

  final String groupId;
  final String answerId;
  final List<EmojiReaction>? initial;

  @override
  ConsumerState<ReactionBar> createState() => _ReactionBarState();
}

class _ReactionBarState extends ConsumerState<ReactionBar> {
  Map<EmojiCode, EmojiReaction>? _reactions;
  final Set<EmojiCode> _pending = <EmojiCode>{};
  String? _error;

  @override
  void initState() {
    super.initState();
    if (widget.initial != null) {
      _reactions = _byEmoji(widget.initial!);
    } else {
      _load();
    }
  }

  Map<EmojiCode, EmojiReaction> _byEmoji(List<EmojiReaction> list) =>
      <EmojiCode, EmojiReaction>{for (final EmojiReaction r in list) r.emoji: r};

  Future<void> _load() async {
    try {
      final List<EmojiReactors> groups =
          await ref.read(archiveRepositoryProvider).reactors(widget.groupId, widget.answerId);
      if (!mounted) return;
      setState(() {
        _reactions = <EmojiCode, EmojiReaction>{
          for (final EmojiReactors g in groups)
            g.emoji: EmojiReaction(
              emoji: g.emoji,
              count: g.reactors.length,
              mine: g.reactors.any((Reactor r) => r.mine),
            ),
        };
      });
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e.message);
    }
  }

  Future<void> _toggle(EmojiCode emoji) async {
    if (_pending.contains(emoji)) return;
    final bool mine = _reactions?[emoji]?.mine ?? false;
    final ArchiveRepository repo = ref.read(archiveRepositoryProvider);
    setState(() {
      _pending.add(emoji);
      _error = null;
    });
    try {
      final List<EmojiReaction> updated = mine
          ? await repo.removeReaction(widget.groupId, widget.answerId, emoji)
          : await repo.addReaction(widget.groupId, widget.answerId, emoji);
      if (!mounted) return;
      setState(() => _reactions = _byEmoji(updated));
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e.message);
    } finally {
      if (mounted) setState(() => _pending.remove(emoji));
    }
  }

  @override
  Widget build(BuildContext context) {
    final Map<EmojiCode, EmojiReaction>? reactions = _reactions;
    if (reactions == null) {
      return _error == null ? const SizedBox(height: 48) : ErrorText(_error);
    }
    final int total = reactions.values.fold<int>(0, (int sum, EmojiReaction r) => sum + r.count);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: <Widget>[
            for (final EmojiCode emoji in EmojiCode.values)
              _EmojiChip(
                emoji: emoji,
                reaction: reactions[emoji],
                busy: _pending.contains(emoji),
                onPressed: () => _toggle(emoji),
              ),
          ],
        ),
        ErrorText(_error),
        if (total > 0)
          TextButton(
            style: TextButton.styleFrom(minimumSize: const Size(48, 48)),
            onPressed: () => _showReactors(context),
            child: const Text('누가 눌렀는지 보기', style: TextStyle(fontSize: 18)),
          ),
      ],
    );
  }

  void _showReactors(BuildContext context) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (BuildContext sheetContext) => _ReactorsSheet(
        future: ref.read(archiveRepositoryProvider).reactors(widget.groupId, widget.answerId),
      ),
    );
  }
}

class _EmojiChip extends StatelessWidget {
  const _EmojiChip({
    required this.emoji,
    required this.reaction,
    required this.busy,
    required this.onPressed,
  });

  final EmojiCode emoji;
  final EmojiReaction? reaction;
  final bool busy;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    final int count = reaction?.count ?? 0;
    final bool mine = reaction?.mine ?? false;
    final String text = '${emoji.symbol} ${emoji.label}${count > 0 ? ' $count' : ''}';
    const TextStyle style = TextStyle(fontSize: 18);
    final ButtonStyle buttonStyle = ButtonStyle(minimumSize: WidgetStateProperty.all(const Size(48, 48)));
    return Semantics(
      selected: mine,
      child: mine
          ? FilledButton.tonal(
              style: buttonStyle,
              onPressed: busy ? null : onPressed,
              child: Text('✓ $text', style: style),
            )
          : OutlinedButton(
              style: buttonStyle,
              onPressed: busy ? null : onPressed,
              child: Text(text, style: style),
            ),
    );
  }
}

class _ReactorsSheet extends StatelessWidget {
  const _ReactorsSheet({required this.future});

  final Future<List<EmojiReactors>> future;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: FutureBuilder<List<EmojiReactors>>(
          future: future,
          builder: (BuildContext context, AsyncSnapshot<List<EmojiReactors>> snapshot) {
            if (snapshot.hasError) {
              final Object e = snapshot.error!;
              return Text(
                e is ApiException ? e.message : '목록을 불러오지 못했어요.',
                style: const TextStyle(fontSize: 18),
              );
            }
            if (!snapshot.hasData) {
              return const SizedBox(height: 96, child: Center(child: CircularProgressIndicator()));
            }
            final List<EmojiReactors> groups =
                snapshot.data!.where((EmojiReactors g) => g.reactors.isNotEmpty).toList();
            if (groups.isEmpty) {
              return const Text('아직 누른 사람이 없어요.', style: TextStyle(fontSize: 18));
            }
            return SingleChildScrollView(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: <Widget>[
                  for (final EmojiReactors g in groups) ...<Widget>[
                    Text(
                      '${g.emoji.symbol} ${g.emoji.label}',
                      style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      g.reactors.map((Reactor r) => r.mine ? '${r.displayName} (나)' : r.displayName).join(', '),
                      style: const TextStyle(fontSize: 18),
                    ),
                    const SizedBox(height: 16),
                  ],
                ],
              ),
            );
          },
        ),
      ),
    );
  }
}
