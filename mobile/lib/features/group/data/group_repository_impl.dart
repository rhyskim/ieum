import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/core/network/dio_provider.dart';
import 'package:ieum_mobile/features/group/domain/group_models.dart';
import 'package:ieum_mobile/features/group/domain/group_repository.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

part 'group_repository_impl.g.dart';

class GroupRepositoryImpl implements GroupRepository {
  const GroupRepositoryImpl(this._dio);

  final Dio _dio;

  @override
  Future<List<GroupSummary>> myGroups() async {
    final Object? data = await _send(() => _dio.get<Map<String, dynamic>>('/api/v1/groups'));
    return (data! as List<dynamic>)
        .map((dynamic e) => GroupSummary.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  @override
  Future<GroupSummary> createGroup(String name, {String? nickname}) async {
    final Object? data = await _send(
      () => _dio.post<Map<String, dynamic>>(
        '/api/v1/groups',
        data: <String, dynamic>{'name': name, 'nickname': nickname},
      ),
    );
    return GroupSummary.fromJson(data! as Map<String, dynamic>);
  }

  @override
  Future<GroupDetail> detail(String groupId) async {
    final Object? data =
        await _send(() => _dio.get<Map<String, dynamic>>('/api/v1/groups/$groupId'));
    return GroupDetail.fromJson(data! as Map<String, dynamic>);
  }

  @override
  Future<InviteCode?> currentCode(String groupId) async {
    final Object? data =
        await _send(() => _dio.get<Map<String, dynamic>>('/api/v1/groups/$groupId/invite-code'));
    return data == null ? null : InviteCode.fromJson(data as Map<String, dynamic>);
  }

  @override
  Future<InviteCode> issueCode(String groupId) async {
    final Object? data =
        await _send(() => _dio.post<Map<String, dynamic>>('/api/v1/groups/$groupId/invite-code'));
    return InviteCode.fromJson(data! as Map<String, dynamic>);
  }

  @override
  Future<JoinStatus> join(String code, {String? nickname}) async {
    final Object? data = await _send(
      () => _dio.post<Map<String, dynamic>>(
        '/api/v1/groups/join',
        data: <String, dynamic>{'code': code, 'nickname': nickname},
      ),
    );
    return JoinStatus.fromServer((data! as Map<String, dynamic>)['status'] as String);
  }

  @override
  Future<List<PendingRequest>> pendingRequests(String groupId) async {
    final Object? data = await _send(
      () => _dio.get<Map<String, dynamic>>('/api/v1/groups/$groupId/join-requests'),
    );
    return (data! as List<dynamic>)
        .map((dynamic e) => PendingRequest.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  @override
  Future<void> approve(String groupId, String targetUserId) async {
    await _send(
      () => _dio.post<Map<String, dynamic>>(
        '/api/v1/groups/$groupId/join-requests/$targetUserId/approve',
      ),
    );
  }

  @override
  Future<void> reject(String groupId, String targetUserId) async {
    await _send(
      () => _dio.post<Map<String, dynamic>>(
        '/api/v1/groups/$groupId/join-requests/$targetUserId/reject',
      ),
    );
  }

  @override
  Future<void> setAlias(String groupId, String targetUserId, String alias) async {
    await _send(
      () => _dio.put<Map<String, dynamic>>(
        '/api/v1/groups/$groupId/aliases/$targetUserId',
        data: <String, String>{'alias': alias},
      ),
    );
  }

  @override
  Future<void> clearAlias(String groupId, String targetUserId) async {
    await _send(
      () => _dio.delete<Map<String, dynamic>>('/api/v1/groups/$groupId/aliases/$targetUserId'),
    );
  }

  @override
  Future<void> submitSupportRequest(String groupId, String message) async {
    await _send(
      () => _dio.post<Map<String, dynamic>>(
        '/api/v1/groups/$groupId/support-requests',
        data: <String, String>{'message': message},
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

/// 공유 시트(share_plus)는 flutter_secure_storage 11과 의존성이 충돌하고 share_plus 13은
/// 현재 build_runner 분석기에서 깨져서, 우선 클립보드 복사로 구현했다(PR 본문 참조).
class ClipboardCodeSharer implements CodeSharer {
  const ClipboardCodeSharer();

  @override
  Future<void> share(String text) => Clipboard.setData(ClipboardData(text: text));
}

@riverpod
GroupRepository groupRepository(Ref ref) => GroupRepositoryImpl(ref.watch(dioProvider));

@riverpod
CodeSharer codeSharer(Ref ref) => const ClipboardCodeSharer();

@riverpod
Future<List<GroupSummary>> myGroups(Ref ref) => ref.watch(groupRepositoryProvider).myGroups();

@riverpod
Future<List<PendingRequest>> pendingRequests(Ref ref, String groupId) =>
    ref.watch(groupRepositoryProvider).pendingRequests(groupId);

@riverpod
Future<GroupDetail> groupDetail(Ref ref, String groupId) =>
    ref.watch(groupRepositoryProvider).detail(groupId);
