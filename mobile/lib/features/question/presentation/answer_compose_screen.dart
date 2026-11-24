import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/features/group/presentation/group_widgets.dart';
import 'package:ieum_mobile/features/question/data/question_repository_impl.dart';
import 'package:ieum_mobile/features/question/domain/question_models.dart';

/// 텍스트 답변 작성·수정. 공백 제외 30자 미만이면 저장 버튼을 막고 남은 글자 수를 알려 준다(서버도 같은 규칙으로 거절).
class AnswerComposeScreen extends ConsumerStatefulWidget {
  const AnswerComposeScreen({
    required this.groupId,
    required this.dailyQuestionId,
    this.initialText,
    super.key,
  });

  final String groupId;
  final String dailyQuestionId;

  /// 이미 쓴 답변이 있으면 수정 화면으로 열린다.
  final String? initialText;

  @override
  ConsumerState<AnswerComposeScreen> createState() => _AnswerComposeScreenState();
}

class _AnswerComposeScreenState extends ConsumerState<AnswerComposeScreen> {
  late final TextEditingController _controller = TextEditingController(text: widget.initialText ?? '');
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  int get _count => countNonWhitespace(_controller.text);

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await ref
          .read(questionRepositoryProvider)
          .saveAnswer(widget.groupId, widget.dailyQuestionId, _controller.text);
      ref.invalidate(todayQuestionProvider(widget.groupId));
      ref.invalidate(questionBoardProvider(widget.groupId, widget.dailyQuestionId));
      if (mounted) context.pop();
    } on ApiException catch (e) {
      if (mounted) {
        setState(() {
          _saving = false;
          _error = e.message;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final int count = _count;
    final bool enough = count >= minAnswerLength;
    return Scaffold(
      appBar: AppBar(title: Text(widget.initialText == null ? '답변 쓰기' : '답변 고치기')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(24),
          children: <Widget>[
            TextField(
              controller: _controller,
              minLines: 6,
              maxLines: 12,
              style: const TextStyle(fontSize: 20),
              keyboardType: TextInputType.multiline,
              maxLength: maxAnswerLength,
              decoration: const InputDecoration(
                labelText: '내 답변',
                hintText: '떠오르는 대로 편하게 써 주세요.',
                border: OutlineInputBorder(),
                // 글자 수 안내(예: 120/2000)도 본문 기준 18sp 이상으로 보여 준다.
                counterStyle: TextStyle(fontSize: 18),
              ),
              onChanged: (_) => setState(() => _error = null),
            ),
            const SizedBox(height: 8),
            Semantics(
              liveRegion: true,
              child: Text(
                enough ? '충분히 썼어요. (공백 빼고 $count자)' : '공백을 빼고 $minAnswerLength자 이상 써 주세요. 지금 $count자예요.',
                style: const TextStyle(fontSize: 18),
              ),
            ),
            ErrorText(_error),
            const SizedBox(height: 16),
            LargeButton(
              label: _saving ? '저장하는 중...' : '저장하기',
              onPressed: enough && !_saving ? _save : null,
            ),
            const SizedBox(height: 12),
            LargeButton(
              label: '목소리로 답하기',
              outlined: true,
              onPressed: () => context.push(
                '/groups/${widget.groupId}/question/${widget.dailyQuestionId}/voice',
              ),
            ),
          ],
        ),
      ),
    );
  }
}
