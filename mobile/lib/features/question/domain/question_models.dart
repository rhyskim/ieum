/// 서버의 reveal_status. 모르는 값이 오면 가장 보수적인 locked로 본다(본문을 열지 않는다).
enum RevealStatus {
  locked,
  revealedAll,
  revealedTimeout;

  static RevealStatus fromServer(String value) => switch (value) {
        'REVEALED_ALL' => RevealStatus.revealedAll,
        'REVEALED_TIMEOUT' => RevealStatus.revealedTimeout,
        _ => RevealStatus.locked,
      };
}

/// 답변 최소 글자 수(공백 제외). 서버가 최종 검사하며 화면은 미리 안내만 한다.
const int minAnswerLength = 30;

/// 답변 최대 글자 수(공백 포함, 오너 확정). 서버도 같은 값으로 거절한다.
const int maxAnswerLength = 2000;

/// 공백을 뺀 글자 수. 서버와 같은 기준(공백·줄바꿈 제외)으로 센다.
int countNonWhitespace(String text) =>
    text.runes.where((int r) => !RegExp(r'\s').hasMatch(String.fromCharCode(r))).length;

class TodayQuestion {
  const TodayQuestion({
    required this.dailyQuestionId,
    required this.groupId,
    required this.questionText,
    required this.revealStatus,
    required this.hasAnswered,
  });

  factory TodayQuestion.fromJson(Map<String, dynamic> json) => TodayQuestion(
        dailyQuestionId: json['dailyQuestionId'] as String,
        groupId: json['groupId'] as String,
        questionText: json['questionText'] as String,
        revealStatus: RevealStatus.fromServer(json['revealStatus'] as String),
        hasAnswered: json['hasAnswered'] as bool,
      );

  final String dailyQuestionId;
  final String groupId;
  final String questionText;
  final RevealStatus revealStatus;
  final bool hasAnswered;
}

/// 멤버별 답변 여부. 본문은 없다(서버 마스킹).
class MemberAnswerStatus {
  const MemberAnswerStatus({
    required this.userId,
    required this.displayName,
    required this.hasAnswered,
    required this.mine,
  });

  factory MemberAnswerStatus.fromJson(Map<String, dynamic> json) => MemberAnswerStatus(
        userId: json['userId'] as String,
        displayName: json['displayName'] as String,
        hasAnswered: json['hasAnswered'] as bool,
        mine: json['mine'] as bool,
      );

  final String userId;
  final String displayName;
  final bool hasAnswered;
  final bool mine;
}

class QuestionDetail {
  const QuestionDetail({
    required this.dailyQuestionId,
    required this.questionText,
    required this.revealStatus,
    required this.hasAnswered,
    required this.members,
  });

  factory QuestionDetail.fromJson(Map<String, dynamic> json) => QuestionDetail(
        dailyQuestionId: json['dailyQuestionId'] as String,
        questionText: json['questionText'] as String,
        revealStatus: RevealStatus.fromServer(json['revealStatus'] as String),
        hasAnswered: json['hasAnswered'] as bool,
        members: (json['members'] as List<dynamic>)
            .map((dynamic e) => MemberAnswerStatus.fromJson(e as Map<String, dynamic>))
            .toList(),
      );

  final String dailyQuestionId;
  final String questionText;
  final RevealStatus revealStatus;
  final bool hasAnswered;
  final List<MemberAnswerStatus> members;
}

class AnswerView {
  const AnswerView({
    required this.answerId,
    required this.authorUserId,
    required this.authorName,
    required this.mine,
    required this.textContent,
  });

  factory AnswerView.fromJson(Map<String, dynamic> json) => AnswerView(
        answerId: json['answerId'] as String,
        authorUserId: json['authorUserId'] as String,
        authorName: json['authorName'] as String,
        mine: json['mine'] as bool,
        textContent: json['textContent'] as String? ?? '',
      );

  final String answerId;
  final String authorUserId;
  final String authorName;
  final bool mine;
  final String textContent;
}

/// 열람 권한이 있는 답변만 담긴다. 가려진 타인의 답변은 목록에 없다.
class AnswerList {
  const AnswerList({required this.revealStatus, required this.hasAnswered, required this.answers});

  factory AnswerList.fromJson(Map<String, dynamic> json) => AnswerList(
        revealStatus: RevealStatus.fromServer(json['revealStatus'] as String),
        hasAnswered: json['hasAnswered'] as bool,
        answers: (json['answers'] as List<dynamic>)
            .map((dynamic e) => AnswerView.fromJson(e as Map<String, dynamic>))
            .toList(),
      );

  final RevealStatus revealStatus;
  final bool hasAnswered;
  final List<AnswerView> answers;
}

/// 화면이 한 번에 쓰는 묶음.
class QuestionBoard {
  const QuestionBoard({required this.detail, required this.answers});

  final QuestionDetail detail;
  final AnswerList answers;

  AnswerView? get mine {
    for (final AnswerView a in answers.answers) {
      if (a.mine) return a;
    }
    return null;
  }
}
