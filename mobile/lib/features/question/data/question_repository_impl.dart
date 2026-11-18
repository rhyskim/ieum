import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/core/network/dio_provider.dart';
import 'package:ieum_mobile/features/question/domain/question_models.dart';
import 'package:ieum_mobile/features/question/domain/question_repository.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

part 'question_repository_impl.g.dart';

class QuestionRepositoryImpl implements QuestionRepository {
  const QuestionRepositoryImpl(this._dio);

  final Dio _dio;

  @override
  Future<TodayQuestion> today(String groupId) async {
    final Object? data =
        await _send(() => _dio.get<Map<String, dynamic>>('/api/v1/groups/$groupId/questions/today'));
    return TodayQuestion.fromJson(data! as Map<String, dynamic>);
  }

  @override
  Future<QuestionDetail> detail(String groupId, String dailyQuestionId) async {
    final Object? data = await _send(
      () => _dio.get<Map<String, dynamic>>('/api/v1/groups/$groupId/questions/$dailyQuestionId'),
    );
    return QuestionDetail.fromJson(data! as Map<String, dynamic>);
  }

  @override
  Future<AnswerList> answers(String groupId, String dailyQuestionId) async {
    final Object? data = await _send(
      () => _dio.get<Map<String, dynamic>>(
        '/api/v1/groups/$groupId/questions/$dailyQuestionId/answers',
      ),
    );
    return AnswerList.fromJson(data! as Map<String, dynamic>);
  }

  @override
  Future<void> saveAnswer(String groupId, String dailyQuestionId, String text) async {
    await _send(
      () => _dio.put<Map<String, dynamic>>(
        '/api/v1/groups/$groupId/questions/$dailyQuestionId/answers/me',
        data: <String, String>{'textContent': text},
      ),
    );
  }

  Future<Object?> _send(Future<Response<Map<String, dynamic>>> Function() request) async {
    try {
      final Response<Map<String, dynamic>> response = await request();
      return response.data?['data'];
    } on DioException catch (e) {
      throw ApiException.fromDioException(e);
    }
  }
}

@riverpod
QuestionRepository questionRepository(Ref ref) => QuestionRepositoryImpl(ref.watch(dioProvider));

@riverpod
Future<TodayQuestion> todayQuestion(Ref ref, String groupId) =>
    ref.watch(questionRepositoryProvider).today(groupId);

@riverpod
Future<QuestionBoard> questionBoard(Ref ref, String groupId, String dailyQuestionId) async {
  final QuestionRepository repository = ref.watch(questionRepositoryProvider);
  // 시간 초과 전환은 조회 시점에 일어나므로 목록과 상세를 같은 시점에 받는다.
  final QuestionDetail detail = await repository.detail(groupId, dailyQuestionId);
  final AnswerList answers = await repository.answers(groupId, dailyQuestionId);
  return QuestionBoard(detail: detail, answers: answers);
}
