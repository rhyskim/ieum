import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/core/network/dio_provider.dart';
import 'package:ieum_mobile/features/archive/domain/archive_models.dart';
import 'package:ieum_mobile/features/archive/domain/archive_repository.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

part 'archive_repository_impl.g.dart';

class ArchiveRepositoryImpl implements ArchiveRepository {
  const ArchiveRepositoryImpl(this._dio);

  final Dio _dio;

  @override
  Future<ArchivePage> timeline(String groupId, {String? cursor}) async {
    final Object? data = await _send(
      () => _dio.get<Map<String, dynamic>>(
        '/api/v1/groups/$groupId/archive',
        queryParameters: <String, String>{if (cursor != null) 'cursor': cursor},
      ),
    );
    return ArchivePage.fromJson(data! as Map<String, dynamic>);
  }

  @override
  Future<List<EmojiReaction>> addReaction(String groupId, String answerId, EmojiCode emoji) async {
    final Object? data = await _send(
      () => _dio.put<Map<String, dynamic>>(_reactionUrl(groupId, answerId, emoji)),
    );
    return parseReactions((data! as Map<String, dynamic>)['reactions'] as List<dynamic>);
  }

  @override
  Future<List<EmojiReaction>> removeReaction(String groupId, String answerId, EmojiCode emoji) async {
    final Object? data = await _send(
      () => _dio.delete<Map<String, dynamic>>(_reactionUrl(groupId, answerId, emoji)),
    );
    return parseReactions((data! as Map<String, dynamic>)['reactions'] as List<dynamic>);
  }

  @override
  Future<List<EmojiReactors>> reactors(String groupId, String answerId) async {
    final Object? data = await _send(
      () => _dio.get<Map<String, dynamic>>('/api/v1/groups/$groupId/answers/$answerId/reactions'),
    );
    return parseReactors((data! as Map<String, dynamic>)['reactions'] as List<dynamic>);
  }

  String _reactionUrl(String groupId, String answerId, EmojiCode emoji) =>
      '/api/v1/groups/$groupId/answers/$answerId/reactions/${emoji.serverName}';

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
ArchiveRepository archiveRepository(Ref ref) => ArchiveRepositoryImpl(ref.watch(dioProvider));
