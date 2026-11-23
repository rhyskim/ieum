import 'package:ieum_mobile/features/archive/domain/archive_models.dart';

abstract interface class ArchiveRepository {
  /// 지난 질문 타임라인(최신 먼저). 다음 페이지는 이전 응답의 nextCursor를 그대로 넘긴다.
  Future<ArchivePage> timeline(String groupId, {String? cursor});

  /// 이모지 달기(멱등). 달린 뒤의 5종 집계를 돌려준다. 공개 전·열람 권한이 없으면
  /// [ApiException]의 code가 ANSWER_NOT_VISIBLE이다.
  Future<List<EmojiReaction>> addReaction(String groupId, String answerId, EmojiCode emoji);

  /// 이모지 취소(멱등). 취소된 뒤의 5종 집계를 돌려준다.
  Future<List<EmojiReaction>> removeReaction(String groupId, String answerId, EmojiCode emoji);

  /// 종류별로 누가 달았는지와 집계.
  Future<List<EmojiReactors>> reactors(String groupId, String answerId);
}
