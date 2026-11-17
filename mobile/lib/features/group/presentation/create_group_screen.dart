import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/features/group/data/group_repository_impl.dart';
import 'package:ieum_mobile/features/group/domain/group_models.dart';
import 'package:ieum_mobile/features/group/presentation/group_widgets.dart';

class CreateGroupScreen extends ConsumerStatefulWidget {
  const CreateGroupScreen({super.key});

  @override
  ConsumerState<CreateGroupScreen> createState() => _CreateGroupScreenState();
}

class _CreateGroupScreenState extends ConsumerState<CreateGroupScreen> {
  final TextEditingController _name = TextEditingController();
  final TextEditingController _nickname = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _name.dispose();
    _nickname.dispose();
    super.dispose();
  }

  Future<void> _create() async {
    if (_name.text.trim().isEmpty) {
      setState(() => _error = '가족 이름을 입력해 주세요.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final GroupSummary created = await ref
          .read(groupRepositoryProvider)
          .createGroup(_name.text.trim(), nickname: _nickname.text.trim());
      ref.invalidate(myGroupsProvider);
      if (mounted) context.go('/groups/${created.id}');
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('가족 만들기')),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: <Widget>[
              TextField(
                controller: _name,
                style: const TextStyle(fontSize: 20),
                decoration: const InputDecoration(labelText: '가족 이름', hintText: '예: 우리 가족'),
              ),
              const SizedBox(height: 16),
              TextField(
                controller: _nickname,
                style: const TextStyle(fontSize: 20),
                decoration: const InputDecoration(
                  labelText: '가족에게 불릴 내 이름',
                  hintText: '비워 두면 내 이름을 써요',
                ),
              ),
              ErrorText(_error),
              const SizedBox(height: 24),
              LargeButton(label: '가족 만들기', onPressed: _busy ? null : _create),
            ],
          ),
        ),
      ),
    );
  }
}
