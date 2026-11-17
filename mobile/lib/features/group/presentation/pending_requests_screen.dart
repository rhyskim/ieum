import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/features/group/data/group_repository_impl.dart';
import 'package:ieum_mobile/features/group/domain/group_models.dart';
import 'package:ieum_mobile/features/group/domain/group_repository.dart';
import 'package:ieum_mobile/features/group/presentation/group_widgets.dart';

/// 방장의 승인 대기 목록. 푸시 알림은 보조이고 이 목록이 기준이다.
/// 인원 상한(GROUP_FULL)에 걸리면 관리자 문의 화면으로 안내한다.
class PendingRequestsScreen extends ConsumerStatefulWidget {
  const PendingRequestsScreen({required this.groupId, super.key});

  final String groupId;

  @override
  ConsumerState<PendingRequestsScreen> createState() => _PendingRequestsScreenState();
}

class _PendingRequestsScreenState extends ConsumerState<PendingRequestsScreen> {
  String? _error;
  bool _busy = false;

  Future<void> _decide(PendingRequest request, {required bool approve}) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    final GroupRepository repository = ref.read(groupRepositoryProvider);
    try {
      if (approve) {
        await repository.approve(widget.groupId, request.userId);
      } else {
        await repository.reject(widget.groupId, request.userId);
      }
      ref
        ..invalidate(pendingRequestsProvider(widget.groupId))
        ..invalidate(groupDetailProvider(widget.groupId));
    } on ApiException catch (e) {
      if (!mounted) return;
      if (e.code == 'GROUP_FULL') {
        await context.push('/groups/${widget.groupId}/support');
      } else {
        setState(() => _error = e.message);
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final AsyncValue<List<PendingRequest>> requests =
        ref.watch(pendingRequestsProvider(widget.groupId));
    return Scaffold(
      appBar: AppBar(title: const Text('승인 대기')),
      body: SafeArea(
        child: requests.when(
          data: (List<PendingRequest> items) => ListView(
            padding: const EdgeInsets.all(24),
            children: <Widget>[
              if (items.isEmpty)
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 32),
                  child: Text(
                    '기다리는 입장 요청이 없어요.',
                    textAlign: TextAlign.center,
                    style: TextStyle(fontSize: 20),
                  ),
                ),
              for (final PendingRequest request in items) ...<Widget>[
                Text('${request.familyNickname}님이 가족이 되고 싶어 해요.', style: const TextStyle(fontSize: 20)),
                const SizedBox(height: 8),
                Row(
                  children: <Widget>[
                    Expanded(
                      child: LargeButton(
                        label: '승인',
                        onPressed: _busy ? null : () => _decide(request, approve: true),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: LargeButton(
                        label: '거절',
                        outlined: true,
                        onPressed: _busy ? null : () => _decide(request, approve: false),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 24),
              ],
              ErrorText(_error),
            ],
          ),
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (Object e, StackTrace _) => Center(
            child: Text(
              e is ApiException ? e.message : '승인 대기 목록을 불러오지 못했어요.',
              style: const TextStyle(fontSize: 18),
            ),
          ),
        ),
      ),
    );
  }
}
