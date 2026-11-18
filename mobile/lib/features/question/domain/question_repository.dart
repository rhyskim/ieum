import 'package:ieum_mobile/features/question/domain/question_models.dart';

abstract interface class QuestionRepository {
  /// 오늘의 질문. 아직 배정 전이면 [ApiException]의 code가 RESOURCE_NOT_FOUND다.
  Future<TodayQuestion> today(String groupId);

  Future<QuestionDetail> detail(String groupId, String dailyQuestionId);

  Future<AnswerList> answers(String groupId, String dailyQuestionId);

  /// 내 답변 저장·수정. 공백 제외 30자 미만이면 [ApiException]의 code가 ANSWER_TOO_SHORT다.
  Future<void> saveAnswer(String groupId, String dailyQuestionId, String text);
}
