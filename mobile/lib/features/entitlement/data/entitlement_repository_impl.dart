import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/core/network/api_response.dart';
import 'package:ieum_mobile/core/network/dio_provider.dart';
import 'package:ieum_mobile/features/entitlement/domain/entitlement_repository.dart';
import 'package:ieum_mobile/features/entitlement/domain/feature_entitlement.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

part 'entitlement_repository_impl.g.dart';

class EntitlementRepositoryImpl implements EntitlementRepository {
  const EntitlementRepositoryImpl(this._dio);

  final Dio _dio;

  @override
  Future<FeatureEntitlement> fetchEntitlementPolicy() async {
    try {
      final response = await _dio.get<Map<String, dynamic>>('/api/v1/policies');
      final body = response.data;
      if (body == null) {
        throw ApiException(
          code: 'EMPTY_BODY',
          message: '서버 응답 본문이 비어 있습니다.',
          statusCode: response.statusCode,
        );
      }
      final apiResponse = ApiResponse<FeatureEntitlement>.fromJson(
        body,
        (json) => FeatureEntitlement.fromJson(json! as Map<String, dynamic>),
      );
      return apiResponse.unwrapOrThrow(statusCode: response.statusCode);
    } on DioException catch (e) {
      throw ApiException.fromDioException(e);
    }
  }
}

@riverpod
EntitlementRepository entitlementRepository(Ref ref) {
  final dio = ref.watch(dioProvider);
  return EntitlementRepositoryImpl(dio);
}
