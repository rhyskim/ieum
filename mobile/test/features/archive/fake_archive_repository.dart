import 'dart:async';

import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/features/archive/domain/archive_models.dart';
import 'package:ieum_mobile/features/archive/domain/archive_repository.dart';

/// 서버처럼 동작하는 가짜: 달기·취소는 멱등이고 응답은 항상 5종 집계다.
class FakeArchiveRepository implements ArchiveRepository {
  FakeArchiveRepository({this.pages = const <ArchivePage>[]});

  final List<ArchivePage> pages;
  final List<String?> requestedCursors = <String?>[];
  final List<String> calls = <String>[];
  final Map<EmojiCode, int> counts = <EmojiCode, int>{};
  final Set<EmojiCode> mine = <EmojiCode>{};
  Completer<void>? gate;
  ApiException? addError;

  List<EmojiReaction> _snapshot() => <EmojiReaction>[
        for (final EmojiCode c in EmojiCode.values)
          EmojiReaction(emoji: c, count: counts[c] ?? 0, mine: mine.contains(c)),
      ];

  @override
  Future<ArchivePage> timeline(String groupId, {String? cursor}) async {
    requestedCursors.add(cursor);
    return pages[requestedCursors.length - 1];
  }

  @override
  Future<List<EmojiReaction>> addReaction(String groupId, String answerId, EmojiCode emoji) async {
    calls.add('PUT ${emoji.serverName}');
    if (gate != null) await gate!.future;
    if (addError != null) throw addError!;
    if (mine.add(emoji)) counts[emoji] = (counts[emoji] ?? 0) + 1;
    return _snapshot();
  }

  @override
  Future<List<EmojiReaction>> removeReaction(String groupId, String answerId, EmojiCode emoji) async {
    calls.add('DELETE ${emoji.serverName}');
    if (mine.remove(emoji)) counts[emoji] = (counts[emoji] ?? 1) - 1;
    return _snapshot();
  }

  @override
  Future<List<EmojiReactors>> reactors(String groupId, String answerId) async => <EmojiReactors>[
        const EmojiReactors(
          emoji: EmojiCode.love,
          reactors: <Reactor>[
            Reactor(userId: 'u1', displayName: '엄마', mine: false),
            Reactor(userId: 'u2', displayName: '탈퇴한 사용자', mine: false),
          ],
        ),
        const EmojiReactors(emoji: EmojiCode.hug, reactors: <Reactor>[]),
      ];
}
