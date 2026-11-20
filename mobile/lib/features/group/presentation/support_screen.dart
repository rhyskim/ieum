import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/features/group/data/group_repository_impl.dart';
import 'package:ieum_mobile/features/group/presentation/group_widgets.dart';

/// 인원 상한(20명)에 닿았을 때 관리자에게 보내는 문의. 서버는 저장까지만 하고 운영자가 직접 확인한다.
class SupportScreen extends ConsumerStatefulWidget {
  const SupportScreen({required this.groupId, super.key});

  final String groupId;

  @override
  ConsumerState<SupportScreen> createState() => _SupportScreenState();
}

class _SupportScreenState extends ConsumerState<SupportScreen> {
  final TextEditingController _message = TextEditingController();
  bool _busy = false;
  bool _sent = false;
  String? _error;

  @override
  void dispose() {
    _message.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_message.text.trim().isEmpty) {
      setState(() => _error = '문의 내용을 입력해 주세요.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(groupRepositoryProvider).submitSupportRequest(widget.groupId, _message.text.trim());
      if (mounted) setState(() => _sent = true);
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('관리자에게 문의')),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: _sent
              ? Center(
                  child: Semantics(
                    liveRegion: true,
                    child: const Text(
                      '문의가 접수되었어요.\n확인하는 대로 연락드릴게요.',
                      textAlign: TextAlign.center,
                      style: TextStyle(fontSize: 22),
                    ),
                  ),
                )
              : SingleChildScrollView(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: <Widget>[
                      const Text(
                        '가족이 20명을 넘으셨나요?\n사정을 적어 주시면 확인 후 도와드릴게요.',
                        style: TextStyle(fontSize: 20),
                      ),
                      const SizedBox(height: 16),
                      TextField(
                        controller: _message,
                        maxLines: 6,
                        maxLength: 2000,
                        style: const TextStyle(fontSize: 20),
                        decoration: const InputDecoration(labelText: '문의 내용'),
                      ),
                      ErrorText(_error),
                      const SizedBox(height: 16),
                      LargeButton(label: '문의 보내기', onPressed: _busy ? null : _submit),
                    ],
                  ),
                ),
        ),
      ),
    );
  }
}
