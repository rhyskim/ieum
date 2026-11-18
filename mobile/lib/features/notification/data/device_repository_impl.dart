import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/core/network/dio_provider.dart';
import 'package:ieum_mobile/features/notification/domain/device_repository.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

part 'device_repository_impl.g.dart';

class DeviceRepositoryImpl implements DeviceRepository {
  const DeviceRepositoryImpl(this._dio);

  final Dio _dio;

  @override
  Future<void> register(String fcmToken, DevicePlatform platform) async {
    try {
      await _dio.post<Map<String, dynamic>>(
        '/api/v1/devices',
        data: <String, String>{'fcmToken': fcmToken, 'deviceOs': platform.apiValue},
      );
    } on DioException catch (e) {
      throw ApiException.fromDioException(e);
    }
  }
}

@riverpod
DeviceRepository deviceRepository(Ref ref) => DeviceRepositoryImpl(ref.watch(dioProvider));
