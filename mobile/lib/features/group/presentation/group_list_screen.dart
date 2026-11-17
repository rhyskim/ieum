import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:ieum_mobile/features/group/data/group_repository_impl.dart';
import 'package:ieum_mobile/features/group/domain/group_models.dart';
import 'package:ieum_mobile/features/group/presentation/group_widgets.dart';

/// 내가 속한 가족 목록. 한 사람이 여러 가족에 속할 수 있으므로 여기서 전환한다.
class GroupListScreen extends ConsumerWidget {
  const GroupListScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final AsyncValue<List<GroupSummary>> groups = ref.watch(myGroupsProvider);
    return Scaffold(
      appBar: AppBar(title: const Text('내 가족')),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: <Widget>[
              Expanded(
                child: groups.when(
                  data: (List<GroupSummary> items) => items.isEmpty
                      ? const Center(
                          child: Text(
                            '아직 가족이 없어요.\n가족을 만들거나 코드로 입장해 보세요.',
                            style: TextStyle(fontSize: 20),
                            textAlign: TextAlign.center,
                          ),
                        )
                      : ListView.separated(
                          itemCount: items.length,
                          separatorBuilder: (_, __) => const SizedBox(height: 12),
                          itemBuilder: (BuildContext context, int index) {
                            final GroupSummary group = items[index];
                            return SizedBox(
                              height: 64,
                              child: OutlinedButton(
                                onPressed: () => context.push('/groups/${group.id}'),
                                child: Row(
                                  children: <Widget>[
                                    Expanded(
                                      child: Text(group.name, style: const TextStyle(fontSize: 20)),
                                    ),
                                    if (group.owner)
                                      const Text('방장', style: TextStyle(fontSize: 18)),
                                  ],
                                ),
                              ),
                            );
                          },
                        ),
                  loading: () => const Center(child: CircularProgressIndicator()),
                  error: (Object e, StackTrace _) => Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: <Widget>[
                        const Text('가족 목록을 불러오지 못했어요.', style: TextStyle(fontSize: 18)),
                        const SizedBox(height: 12),
                        LargeButton(
                          label: '다시 시도',
                          onPressed: () => ref.invalidate(myGroupsProvider),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 16),
              LargeButton(label: '가족 만들기', onPressed: () => context.push('/groups/create')),
              const SizedBox(height: 12),
              LargeButton(
                label: '코드로 입장하기',
                outlined: true,
                onPressed: () => context.push('/groups/join'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
