import 'dart:ui' show ImageFilter;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/features/archive/presentation/reaction_bar.dart';
import 'package:ieum_mobile/features/group/presentation/group_widgets.dart';
import 'package:ieum_mobile/features/question/data/question_repository_impl.dart';
import 'package:ieum_mobile/features/question/domain/question_models.dart';

/// 오늘의 질문과 가족의 답변 현황. 가려진 답변은 서버가 본문을 주지 않으므로 이 화면의 블러는
/// 자리표시를 꾸민 연출일 뿐이다(블러를 풀어도 볼 수 있는 내용이 없다).
class TodayQuestionScreen extends ConsumerWidget {
  const TodayQuestionScreen({required this.groupId, super.key});

  final String groupId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final AsyncValue<TodayQuestion> today = ref.watch(todayQuestionProvider(groupId));
    return Scaffold(
      appBar: AppBar(title: const Text('오늘의 질문')),
      body: SafeArea(
        child: today.when(
          data: (TodayQuestion question) => _Board(groupId: groupId, question: question),
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (Object e, StackTrace _) => _ErrorView(
            message: e is ApiException && e.code == 'RESOURCE_NOT_FOUND'
                ? '아직 오늘의 질문이 도착하지 않았어요.\n저녁 7시에 새 질문이 와요.'
                : (e is ApiException ? e.message : '질문을 불러오지 못했어요.'),
            onRetry: () => ref.invalidate(todayQuestionProvider(groupId)),
          ),
        ),
      ),
    );
  }
}

class _Board extends ConsumerWidget {
  const _Board({required this.groupId, required this.question});

  final String groupId;
  final TodayQuestion question;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final AsyncValue<QuestionBoard> board =
        ref.watch(questionBoardProvider(groupId, question.dailyQuestionId));
    return board.when(
      data: (QuestionBoard b) => _BoardBody(groupId: groupId, board: b),
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (Object e, StackTrace _) => _ErrorView(
        message: e is ApiException ? e.message : '답변 현황을 불러오지 못했어요.',
        onRetry: () => ref.invalidate(questionBoardProvider(groupId, question.dailyQuestionId)),
      ),
    );
  }
}

class _BoardBody extends StatelessWidget {
  const _BoardBody({required this.groupId, required this.board});

  final String groupId;
  final QuestionBoard board;

  @override
  Widget build(BuildContext context) {
    final QuestionDetail detail = board.detail;
    final Map<String, AnswerView> visibleByAuthor = <String, AnswerView>{
      for (final AnswerView a in board.answers.answers) a.authorUserId: a,
    };
    // 공개 전에는 서버가 리액션을 거절하므로 바를 두지 않는다.
    final bool reactable = board.answers.revealStatus != RevealStatus.locked;
    return ListView(
      padding: const EdgeInsets.all(24),
      children: <Widget>[
        Text(detail.questionText, style: const TextStyle(fontSize: 24, fontWeight: FontWeight.bold)),
        const SizedBox(height: 16),
        // 상세와 목록은 순차 호출이라 17시 경계에서 상태가 달라질 수 있다. 나중에 받은 목록의 상태가 최신이며,
        // 아래에 보이는 답변 목록과 같은 시점의 값이라 안내와 목록이 어긋나지 않는다.
        _RevealNotice(status: board.answers.revealStatus, iAnswered: board.answers.hasAnswered),
        const SizedBox(height: 16),
        LargeButton(
          label: detail.hasAnswered ? '내 답변 고치기' : '답변하기',
          onPressed: () => context.push(
            '/groups/$groupId/question/${detail.dailyQuestionId}/answer',
            extra: board.mine?.textContent,
          ),
        ),
        const SizedBox(height: 24),
        const Text('가족의 답변', style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
        const SizedBox(height: 8),
        for (final MemberAnswerStatus member in detail.members)
          _MemberAnswerTile(
            groupId: groupId,
            member: member,
            answer: visibleByAuthor[member.userId],
            showReactions: reactable,
          ),
        // 탈퇴·나간 사람의 공개된 답변은 멤버 목록에 없으므로 따로 보여 준다.
        for (final AnswerView a in board.answers.answers)
          if (!detail.members.any((MemberAnswerStatus m) => m.userId == a.authorUserId))
            _AnswerCard(
              groupId: groupId,
              answerId: a.answerId,
              name: a.authorName,
              text: a.textContent,
              showReactions: reactable,
            ),
      ],
    );
  }
}

class _RevealNotice extends StatelessWidget {
  const _RevealNotice({required this.status, required this.iAnswered});

  final RevealStatus status;
  final bool iAnswered;

  @override
  Widget build(BuildContext context) {
    final String text = switch (status) {
      RevealStatus.locked => '가족이 모두 답하거나 오후 5시가 되면 서로의 답변이 열려요.',
      RevealStatus.revealedAll => '가족 모두가 답을 마쳐서 답변이 열렸어요.',
      RevealStatus.revealedTimeout =>
        iAnswered ? '시간이 지나 답변이 열렸어요.' : '시간이 지나 답변이 열렸어요. 내 답변을 쓰면 가족의 답변을 볼 수 있어요.',
    };
    return Semantics(
      liveRegion: true,
      child: Text(text, style: const TextStyle(fontSize: 18)),
    );
  }
}

class _MemberAnswerTile extends StatelessWidget {
  const _MemberAnswerTile({
    required this.groupId,
    required this.member,
    required this.answer,
    required this.showReactions,
  });

  final String groupId;
  final MemberAnswerStatus member;
  final AnswerView? answer;
  final bool showReactions;

  @override
  Widget build(BuildContext context) {
    final String name = member.mine ? '${member.displayName} (나)' : member.displayName;
    if (answer != null) {
      return _AnswerCard(
        groupId: groupId,
        answerId: answer!.answerId,
        name: name,
        text: answer!.textContent,
        showReactions: showReactions,
      );
    }
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Row(
              children: <Widget>[
                Expanded(child: Text(name, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold))),
                Text(member.hasAnswered ? '답변 완료' : '아직 안 썼어요', style: const TextStyle(fontSize: 18)),
              ],
            ),
            if (member.hasAnswered) ...<Widget>[
              const SizedBox(height: 8),
              // 자리표시 문구일 뿐 실제 답변이 아니다. 화면 낭독기에는 "가려진 답변"으로만 읽힌다.
              Semantics(
                label: '가려진 답변',
                excludeSemantics: true,
                child: ImageFiltered(
                  imageFilter: ImageFilter.blur(sigmaX: 6, sigmaY: 6),
                  child: const Text('가려진 답변이에요 가려진 답변이에요', style: TextStyle(fontSize: 18)),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _AnswerCard extends StatelessWidget {
  const _AnswerCard({
    required this.groupId,
    required this.answerId,
    required this.name,
    required this.text,
    required this.showReactions,
  });

  final String groupId;
  final String answerId;
  final String name;
  final String text;
  final bool showReactions;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Text(name, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            Text(text, style: const TextStyle(fontSize: 18)),
            if (showReactions) ...<Widget>[
              const SizedBox(height: 12),
              ReactionBar(key: ValueKey<String>(answerId), groupId: groupId, answerId: answerId),
            ],
          ],
        ),
      ),
    );
  }
}

class _ErrorView extends StatelessWidget {
  const _ErrorView({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            Text(message, style: const TextStyle(fontSize: 18), textAlign: TextAlign.center),
            const SizedBox(height: 12),
            LargeButton(label: '다시 시도', onPressed: onRetry),
          ],
        ),
      ),
    );
  }
}
