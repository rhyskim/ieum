import 'package:ieum_mobile/features/question/domain/question_models.dart';

/// 이모지 리액션 5종(서버 EmojiCode와 같은 이름). 선언 순서가 화면 표시 순서다.
enum EmojiCode {
  love('LOVE', '❤️', '사랑해요'),
  touched('TOUCHED', '🥹', '감동이에요'),
  thanks('THANKS', '🙏', '고마워요'),
  funny('FUNNY', '😊', '웃음나요'),
  hug('HUG', '🫂', '안아줄래요');

  const EmojiCode(this.serverName, this.symbol, this.label);

  final String serverName;
  final String symbol;

  /// 아이콘만 두지 않도록 항상 함께 보여 주는 한글 라벨.
  final String label;

  /// 모르는 값(서버가 새 종류를 추가한 경우)은 null이라 화면에서 건너뛴다.
  static EmojiCode? fromServer(String value) {
    for (final EmojiCode code in EmojiCode.values) {
      if (code.serverName == value) return code;
    }
    return null;
  }
}

/// 한 답변의 이모지 한 종류 집계.
class EmojiReaction {
  const EmojiReaction({required this.emoji, required this.count, required this.mine});

  final EmojiCode emoji;
  final int count;
  final bool mine;
}

List<EmojiReaction> parseReactions(List<dynamic> json) => <EmojiReaction>[
      for (final dynamic e in json)
        if (EmojiCode.fromServer((e as Map<String, dynamic>)['emojiCode'] as String) case final EmojiCode code)
          EmojiReaction(emoji: code, count: e['count'] as int, mine: e['mine'] as bool),
    ];

class Reactor {
  const Reactor({required this.userId, required this.displayName, required this.mine});

  factory Reactor.fromJson(Map<String, dynamic> json) => Reactor(
        userId: json['userId'] as String,
        displayName: json['displayName'] as String,
        mine: json['mine'] as bool,
      );

  final String userId;
  final String displayName;
  final bool mine;
}

/// 한 종류를 누가 달았는지.
class EmojiReactors {
  const EmojiReactors({required this.emoji, required this.reactors});

  final EmojiCode emoji;
  final List<Reactor> reactors;
}

List<EmojiReactors> parseReactors(List<dynamic> json) => <EmojiReactors>[
      for (final dynamic e in json)
        if (EmojiCode.fromServer((e as Map<String, dynamic>)['emojiCode'] as String) case final EmojiCode code)
          EmojiReactors(
            emoji: code,
            reactors: (e['reactors'] as List<dynamic>)
                .map((dynamic r) => Reactor.fromJson(r as Map<String, dynamic>))
                .toList(),
          ),
    ];

/// 타임라인 항목 안의 답변. 서버가 열람 권한을 확인한 답변만 온다.
class ArchiveAnswer {
  const ArchiveAnswer({
    required this.answerId,
    required this.authorName,
    required this.mine,
    required this.textContent,
    required this.reactions,
  });

  factory ArchiveAnswer.fromJson(Map<String, dynamic> json) => ArchiveAnswer(
        answerId: json['answerId'] as String,
        authorName: json['authorName'] as String,
        mine: json['mine'] as bool,
        textContent: json['textContent'] as String? ?? '',
        reactions: parseReactions(json['reactions'] as List<dynamic>),
      );

  final String answerId;
  final String authorName;
  final bool mine;
  final String textContent;
  final List<EmojiReaction> reactions;
}

class ArchiveItem {
  const ArchiveItem({
    required this.dailyQuestionId,
    required this.cycleDate,
    required this.questionText,
    required this.revealStatus,
    required this.hasAnswered,
    required this.answers,
  });

  factory ArchiveItem.fromJson(Map<String, dynamic> json) => ArchiveItem(
        dailyQuestionId: json['dailyQuestionId'] as String,
        cycleDate: DateTime.parse(json['cycleDate'] as String),
        questionText: json['questionText'] as String,
        revealStatus: RevealStatus.fromServer(json['revealStatus'] as String),
        hasAnswered: json['hasAnswered'] as bool,
        answers: (json['answers'] as List<dynamic>)
            .map((dynamic e) => ArchiveAnswer.fromJson(e as Map<String, dynamic>))
            .toList(),
      );

  final String dailyQuestionId;
  final DateTime cycleDate;
  final String questionText;
  final RevealStatus revealStatus;
  final bool hasAnswered;
  final List<ArchiveAnswer> answers;
}

/// [nextCursor]가 null이면 마지막 페이지다.
class ArchivePage {
  const ArchivePage({required this.items, required this.nextCursor});

  factory ArchivePage.fromJson(Map<String, dynamic> json) => ArchivePage(
        items: (json['items'] as List<dynamic>)
            .map((dynamic e) => ArchiveItem.fromJson(e as Map<String, dynamic>))
            .toList(),
        nextCursor: json['nextCursor'] as String?,
      );

  final List<ArchiveItem> items;
  final String? nextCursor;
}
