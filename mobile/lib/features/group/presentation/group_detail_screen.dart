import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/features/group/data/group_repository_impl.dart';
import 'package:ieum_mobile/features/group/domain/group_models.dart';
import 'package:ieum_mobile/features/group/domain/group_repository.dart';
import 'package:ieum_mobile/features/group/presentation/group_widgets.dart';

/// 그룹 멤버와 "내가 정한 호칭". 호칭은 나에게만 보이며 상대에게는 영향이 없다.
class GroupDetailScreen extends ConsumerWidget {
  const GroupDetailScreen({required this.groupId, super.key});

  final String groupId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final AsyncValue<GroupDetail> detail = ref.watch(groupDetailProvider(groupId));
    return Scaffold(
      appBar: AppBar(title: Text(detail.valueOrNull?.name ?? '가족')),
      body: SafeArea(
        child: detail.when(
          data: (GroupDetail group) => ListView(
            padding: const EdgeInsets.all(24),
            children: <Widget>[
              if (group.iAmOwner) ...<Widget>[
                LargeButton(
                  label: '입장 코드 보기',
                  onPressed: () => context.push('/groups/$groupId/code'),
                ),
                const SizedBox(height: 12),
                LargeButton(
                  label: '승인 대기 보기',
                  outlined: true,
                  onPressed: () => context.push('/groups/$groupId/requests'),
                ),
                const SizedBox(height: 24),
              ],
              Text('가족 구성원', style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 8),
              for (final GroupMemberInfo member in group.members)
                _MemberTile(
                  member: member,
                  onEdit: member.me ? null : () => _editAlias(context, ref, member),
                ),
              if (group.iAmOwner) ...<Widget>[
                const SizedBox(height: 24),
                LargeButton(
                  label: '관리자에게 문의하기',
                  outlined: true,
                  onPressed: () => context.push('/groups/$groupId/support'),
                ),
              ],
            ],
          ),
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (Object e, StackTrace _) => Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: <Widget>[
                  Text(
                    e is ApiException ? e.message : '가족 정보를 불러오지 못했어요.',
                    style: const TextStyle(fontSize: 18),
                  ),
                  const SizedBox(height: 12),
                  LargeButton(
                    label: '다시 시도',
                    onPressed: () => ref.invalidate(groupDetailProvider(groupId)),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _editAlias(BuildContext context, WidgetRef ref, GroupMemberInfo member) async {
    final _AliasResult? result = await showDialog<_AliasResult>(
      context: context,
      builder: (BuildContext dialogContext) => _AliasDialog(member: member),
    );
    if (result == null) return;
    final GroupRepository repository = ref.read(groupRepositoryProvider);
    try {
      if (result.choice == _AliasChoice.clear) {
        await repository.clearAlias(groupId, member.userId);
      } else if (result.text.isNotEmpty) {
        await repository.setAlias(groupId, member.userId, result.text);
      }
      ref.invalidate(groupDetailProvider(groupId));
    } on ApiException catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(e.message, style: const TextStyle(fontSize: 18))),
        );
      }
    }
  }
}

enum _AliasChoice { save, clear }

final ButtonStyle _dialogButtonStyle = TextButton.styleFrom(minimumSize: const Size(88, 56));

class _AliasResult {
  const _AliasResult(this.choice, this.text);

  final _AliasChoice choice;
  final String text;
}

/// 컨트롤러를 대화상자 State가 소유해 닫힘 애니메이션이 끝난 뒤에 정리되게 한다.
class _AliasDialog extends StatefulWidget {
  const _AliasDialog({required this.member});

  final GroupMemberInfo member;

  @override
  State<_AliasDialog> createState() => _AliasDialogState();
}

class _AliasDialogState extends State<_AliasDialog> {
  late final TextEditingController _controller = TextEditingController(text: widget.member.alias ?? '');

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text('${widget.member.familyNickname}님을 어떻게 부를까요?', style: const TextStyle(fontSize: 20)),
      content: TextField(
        controller: _controller,
        autofocus: true,
        style: const TextStyle(fontSize: 20),
        decoration: const InputDecoration(labelText: '내가 부르는 호칭', hintText: '예: 우리 엄마'),
      ),
      actions: <Widget>[
        if (widget.member.alias != null)
          TextButton(
            style: _dialogButtonStyle,
            onPressed: () => Navigator.of(context).pop(const _AliasResult(_AliasChoice.clear, '')),
            child: const Text('호칭 지우기', style: TextStyle(fontSize: 18)),
          ),
        TextButton(
          style: _dialogButtonStyle,
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('취소', style: TextStyle(fontSize: 18)),
        ),
        TextButton(
          style: _dialogButtonStyle,
          onPressed: () =>
              Navigator.of(context).pop(_AliasResult(_AliasChoice.save, _controller.text.trim())),
          child: const Text('저장', style: TextStyle(fontSize: 18)),
        ),
      ],
    );
  }
}

class _MemberTile extends StatelessWidget {
  const _MemberTile({required this.member, required this.onEdit});

  final GroupMemberInfo member;
  final VoidCallback? onEdit;

  @override
  Widget build(BuildContext context) {
    final List<String> tags = <String>[
      if (member.me) '나',
      if (member.owner) '방장',
    ];
    return ConstrainedBox(
      constraints: const BoxConstraints(minHeight: 56),
      child: Row(
        children: <Widget>[
          Expanded(
            child: Text(
              tags.isEmpty ? member.displayName : '${member.displayName} (${tags.join(', ')})',
              style: const TextStyle(fontSize: 20),
            ),
          ),
          if (onEdit != null)
            TextButton(
              onPressed: onEdit,
              style: TextButton.styleFrom(minimumSize: const Size(88, 56)),
              child: const Text('호칭 바꾸기', style: TextStyle(fontSize: 18)),
            ),
        ],
      ),
    );
  }
}
