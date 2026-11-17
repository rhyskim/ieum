import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/features/group/data/group_repository_impl.dart';
import 'package:ieum_mobile/features/group/domain/group_models.dart';
import 'package:ieum_mobile/features/group/presentation/group_widgets.dart';

/// 코드 입력(숫자 키패드). 요청이 접수되면 승인 대기 안내로 바뀐다.
/// 입장 전에는 그룹 정보를 받지 않으므로 상태 문구만 보여 준다.
class JoinScreen extends ConsumerStatefulWidget {
  const JoinScreen({super.key});

  @override
  ConsumerState<JoinScreen> createState() => _JoinScreenState();
}

class _JoinScreenState extends ConsumerState<JoinScreen> {
  final TextEditingController _code = TextEditingController();
  final TextEditingController _nickname = TextEditingController();
  bool _busy = false;
  String? _error;
  JoinStatus? _result;
  String? _lockedMessage;

  @override
  void dispose() {
    _code.dispose();
    _nickname.dispose();
    super.dispose();
  }

  Future<void> _join() async {
    if (_code.text.length != 6) {
      setState(() => _error = '숫자 6자리를 모두 입력해 주세요.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final JoinStatus status = await ref
          .read(groupRepositoryProvider)
          .join(_code.text, nickname: _nickname.text.trim());
      ref.invalidate(myGroupsProvider);
      if (mounted) setState(() => _result = status);
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        if (e.code == 'JOIN_LOCKED') {
          _lockedMessage = e.message;
        } else {
          _error = e.message;
        }
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('코드로 입장하기')),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: _lockedMessage != null
              ? _lockedView(_lockedMessage!)
              : (_result == null ? _form() : _resultView(_result!)),
        ),
      ),
    );
  }

  Widget _form() {
    return SingleChildScrollView(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          const Text('가족에게 받은 숫자 6자리를 입력해 주세요.', style: TextStyle(fontSize: 20)),
          const SizedBox(height: 16),
          TextField(
            controller: _code,
            keyboardType: TextInputType.number,
            inputFormatters: <TextInputFormatter>[
              FilteringTextInputFormatter.digitsOnly,
              LengthLimitingTextInputFormatter(6),
            ],
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 36, letterSpacing: 8),
            decoration: const InputDecoration(labelText: '입장 코드'),
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
          LargeButton(label: '입장 요청하기', onPressed: _busy ? null : _join),
        ],
      ),
    );
  }

  Widget _lockedView(String message) {
    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        Semantics(
          liveRegion: true,
          child: Text(
            '잠시 쉬어 가요',
            style: Theme.of(context).textTheme.headlineMedium,
            textAlign: TextAlign.center,
          ),
        ),
        const SizedBox(height: 16),
        Text(message, style: const TextStyle(fontSize: 20), textAlign: TextAlign.center),
        const SizedBox(height: 16),
        const Text(
          '코드가 맞는지 가족에게 다시 확인해 보세요.',
          style: TextStyle(fontSize: 18),
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: 32),
        LargeButton(label: '확인', onPressed: () => context.go('/groups')),
      ],
    );
  }

  Widget _resultView(JoinStatus status) {
    final bool pending = status == JoinStatus.pending;
    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        Semantics(
          liveRegion: true,
          child: Text(
            pending ? '방장이 확인하고 있어요' : '이미 이 가족의 멤버예요',
            style: Theme.of(context).textTheme.headlineMedium,
            textAlign: TextAlign.center,
          ),
        ),
        const SizedBox(height: 16),
        Text(
          pending ? '방장이 승인하면 알려 드릴게요.\n잠시만 기다려 주세요.' : '내 가족 목록에서 확인해 보세요.',
          style: const TextStyle(fontSize: 20),
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: 32),
        LargeButton(label: '확인', onPressed: () => context.go('/groups')),
      ],
    );
  }
}
