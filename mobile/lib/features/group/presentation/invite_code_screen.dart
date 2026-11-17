import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/features/group/data/group_repository_impl.dart';
import 'package:ieum_mobile/features/group/domain/group_models.dart';
import 'package:ieum_mobile/features/group/presentation/group_widgets.dart';

/// 방장이 코드를 보여 주고 공유 시트로 전달하는 화면. 코드는 짧게 유효하므로 남은 시간을 함께 보여 준다.
class InviteCodeScreen extends ConsumerStatefulWidget {
  const InviteCodeScreen({required this.groupId, super.key});

  final String groupId;

  @override
  ConsumerState<InviteCodeScreen> createState() => _InviteCodeScreenState();
}

class _InviteCodeScreenState extends ConsumerState<InviteCodeScreen> {
  InviteCode? _code;
  bool _loading = true;
  String? _error;
  Timer? _ticker;
  Duration _remaining = Duration.zero;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _ticker?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    await _run(() => ref.read(groupRepositoryProvider).currentCode(widget.groupId));
  }

  Future<void> _reissue() async {
    await _run(() => ref.read(groupRepositoryProvider).issueCode(widget.groupId));
  }

  Future<void> _run(Future<InviteCode?> Function() action) async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final InviteCode? code = await action();
      if (!mounted) return;
      _code = code;
      _startTicker();
    } on ApiException catch (e) {
      if (mounted) _error = e.message;
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  void _startTicker() {
    _ticker?.cancel();
    _tick();
    _ticker = Timer.periodic(const Duration(seconds: 1), (_) => _tick());
  }

  void _tick() {
    final InviteCode? code = _code;
    if (code == null) return;
    final Duration left = code.expiresAt.difference(DateTime.now());
    setState(() => _remaining = left.isNegative ? Duration.zero : left);
    if (left.isNegative) _ticker?.cancel();
  }

  String get _remainingLabel {
    final String mm = _remaining.inMinutes.toString().padLeft(2, '0');
    final String ss = (_remaining.inSeconds % 60).toString().padLeft(2, '0');
    return '남은 시간 $mm:$ss';
  }

  Future<void> _share(String code) async {
    await ref.read(codeSharerProvider).share('이음 가족 입장 코드: $code');
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('코드를 복사했어요. 가족에게 붙여넣어 보내 주세요.', style: TextStyle(fontSize: 18))),
    );
  }

  @override
  Widget build(BuildContext context) {
    final InviteCode? code = _code;
    final bool valid = code != null && _remaining > Duration.zero;
    return Scaffold(
      appBar: AppBar(title: const Text('입장 코드')),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: <Widget>[
              if (_loading)
                const Center(child: CircularProgressIndicator())
              else if (valid) ...<Widget>[
                Semantics(
                  label: '입장 코드 ${code.code.split('').join(' ')}',
                  child: ExcludeSemantics(
                    child: Text(
                      code.code,
                      textAlign: TextAlign.center,
                      style: const TextStyle(fontSize: 64, fontWeight: FontWeight.w700, letterSpacing: 8),
                    ),
                  ),
                ),
                const SizedBox(height: 12),
                Text(_remainingLabel, textAlign: TextAlign.center, style: const TextStyle(fontSize: 22)),
                const SizedBox(height: 24),
                LargeButton(label: '코드 복사하기', onPressed: () => _share(code.code)),
              ] else
                const Text(
                  '지금 유효한 코드가 없어요.\n새 코드를 만들어 주세요.',
                  textAlign: TextAlign.center,
                  style: TextStyle(fontSize: 20),
                ),
              ErrorText(_error),
              const SizedBox(height: 16),
              LargeButton(label: '새 코드 만들기', outlined: valid, onPressed: _loading ? null : _reissue),
            ],
          ),
        ),
      ),
    );
  }
}
