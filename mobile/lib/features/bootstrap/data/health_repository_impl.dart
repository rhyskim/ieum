import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/core/network/api_response.dart';
import 'package:ieum_mobile/core/network/dio_provider.dart';
import 'package:ieum_mobile/features/bootstrap/domain/health_repository.dart';
import 'package:ieum_mobile/features/bootstrap/domain/server_health.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

part 'health_repository_impl.g.dart';

class HealthRepositoryImpl implements HealthRepository {
  const HealthRepositoryImpl(this._dio);

  final Dio _dio;

  @override
  Future<ServerHealth> fetchHealth() async {
    try {
      final response = await _dio.get<Map<String, dynamic>>('/api/v1/health');
      final body = response.data;
      if (body == null) {
        throw ApiException(
          code: 'EMPTY_BODY',
          message: '서버 응답 본문이 비어 있습니다.',
          statusCode: response.statusCode,
        );
      }
      final apiResponse = ApiResponse<ServerHealth>.fromJson(
        body,
        (json) => ServerHealth.fromJson(json! as Map<String, dynamic>),
      );
      return apiResponse.unwrapOrThrow(statusCode: response.statusCode);
    } on DioException catch (e) {
      throw ApiException.fromDioException(e);
    }
  }
}

@riverpod
HealthRepository healthRepository(Ref ref) {
  final dio = ref.watch(dioProvider);
  return HealthRepositoryImpl(dio);
}
