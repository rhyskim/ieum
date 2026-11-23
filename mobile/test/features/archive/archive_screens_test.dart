import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ieum_mobile/core/network/api_exception.dart';
import 'package:ieum_mobile/features/archive/data/archive_repository_impl.dart';
import 'package:ieum_mobile/features/archive/domain/archive_models.dart';
import 'package:ieum_mobile/features/archive/presentation/archive_screen.dart';
import 'package:ieum_mobile/features/question/domain/question_models.dart';

import 'fake_archive_repository.dart';

ArchiveItem _item(String id, DateTime date, {RevealStatus status = RevealStatus.revealedAll, bool answered = true}) =>
    ArchiveItem(
      dailyQuestionId: id,
      cycleDate: date,
      questionText: '$id의 질문',
      revealStatus: status,
      hasAnswered: answered,
      answers: status == RevealStatus.locked && !answered
          ? const <ArchiveAnswer>[]
          : <ArchiveAnswer>[
              ArchiveAnswer(
                answerId: 'a-$id',
                authorName: '아빠',
                mine: false,
                textContent: '$id의 아빠 답변',
                reactions: <EmojiReaction>[
                  for (final EmojiCode c in EmojiCode.values) EmojiReaction(emoji: c, count: 0, mine: false),
                ],
              ),
            ],
    );

Widget _app(FakeArchiveRepository repo) => ProviderScope(
      overrides: [archiveRepositoryProvider.overrideWithValue(repo)],
      child: const MaterialApp(home: ArchiveScreen(groupId: 'g1')),
    );

Finder _chip(String label) => find.textContaining(label);

void main() {
  testWidgets('타임라인에 날짜·질문·답변이 최신 순으로 보이고 5종 이모지 칩에 한글 라벨이 있다', (tester) async {
    final FakeArchiveRepository repo = FakeArchiveRepository(
      pages: <ArchivePage>[
        ArchivePage(items: <ArchiveItem>[_item('q2', DateTime(2026, 11, 20)), _item('q1', DateTime(2026, 11, 19))], nextCursor: null),
      ],
    );
    await tester.pumpWidget(_app(repo));
    await tester.pumpAndSettle();

    expect(find.text('2026년 11월 20일'), findsOneWidget);
    expect(find.text('q2의 질문'), findsOneWidget);
    expect(find.text('q1의 아빠 답변'), findsOneWidget);
    for (final EmojiCode c in EmojiCode.values) {
      expect(_chip(c.label), findsNWidgets(2)); // 항목 2개 × 칩 1개
    }
    expect(find.text('더 보기'), findsNothing); // 마지막 페이지
  });

  testWidgets('이모지 칩은 터치 48dp 이상이고 글자는 18sp 이상이다', (tester) async {
    final FakeArchiveRepository repo = FakeArchiveRepository(
      pages: <ArchivePage>[ArchivePage(items: <ArchiveItem>[_item('q1', DateTime(2026, 11, 19))], nextCursor: null)],
    );
    await tester.pumpWidget(_app(repo));
    await tester.pumpAndSettle();

    for (final EmojiCode c in EmojiCode.values) {
      final Finder button = find.ancestor(of: _chip(c.label), matching: find.byWidgetPredicate((Widget w) => w is ButtonStyleButton));
      expect(tester.getSize(button).height, greaterThanOrEqualTo(48), reason: c.label);
      expect(tester.getSize(button).width, greaterThanOrEqualTo(48), reason: c.label);
      expect(tester.widget<Text>(_chip(c.label)).style!.fontSize, greaterThanOrEqualTo(18));
    }
  });

  testWidgets('칩을 누르면 PUT, 내가 누른 칩을 다시 누르면 DELETE를 부르고 개수가 갱신된다', (tester) async {
    final FakeArchiveRepository repo = FakeArchiveRepository(
      pages: <ArchivePage>[ArchivePage(items: <ArchiveItem>[_item('q1', DateTime(2026, 11, 19))], nextCursor: null)],
    );
    await tester.pumpWidget(_app(repo));
    await tester.pumpAndSettle();

    await tester.tap(_chip('사랑해요'));
    await tester.pumpAndSettle();
    expect(repo.calls, <String>['PUT LOVE']);
    expect(find.textContaining('✓ ❤️ 사랑해요 1'), findsOneWidget);

    await tester.tap(_chip('사랑해요'));
    await tester.pumpAndSettle();
    expect(repo.calls, <String>['PUT LOVE', 'DELETE LOVE']);
    expect(find.textContaining('✓'), findsNothing);

    // 다른 종류는 함께 달 수 있다.
    await tester.tap(_chip('사랑해요'));
    await tester.tap(_chip('고마워요'));
    await tester.pumpAndSettle();
    expect(find.textContaining('✓'), findsNWidgets(2));
  });

  testWidgets('응답을 기다리는 동안 같은 칩을 또 눌러도 요청은 한 번만 나간다', (tester) async {
    final FakeArchiveRepository repo = FakeArchiveRepository(
      pages: <ArchivePage>[ArchivePage(items: <ArchiveItem>[_item('q1', DateTime(2026, 11, 19))], nextCursor: null)],
    )..gate = Completer<void>();
    await tester.pumpWidget(_app(repo));
    await tester.pumpAndSettle();

    await tester.tap(_chip('감동이에요'));
    await tester.pump();
    await tester.tap(_chip('감동이에요'), warnIfMissed: false);
    await tester.pump();
    expect(repo.calls, <String>['PUT TOUCHED']);

    repo.gate!.complete();
    await tester.pumpAndSettle();
    expect(repo.calls, <String>['PUT TOUCHED']);
  });

  testWidgets('서버가 거절하면 한글 안내를 보여 주고 상태는 바뀌지 않는다', (tester) async {
    final FakeArchiveRepository repo = FakeArchiveRepository(
      pages: <ArchivePage>[ArchivePage(items: <ArchiveItem>[_item('q1', DateTime(2026, 11, 19))], nextCursor: null)],
    )..addError = const ApiException(code: 'ANSWER_NOT_VISIBLE', message: '아직 볼 수 없는 답변이에요.');
    await tester.pumpWidget(_app(repo));
    await tester.pumpAndSettle();

    await tester.tap(_chip('웃음나요'));
    await tester.pumpAndSettle();
    expect(find.text('아직 볼 수 없는 답변이에요.'), findsOneWidget);
    expect(find.textContaining('✓'), findsNothing);
  });

  testWidgets('"누가 눌렀는지 보기"는 종류별 사람 목록을 보여 주고 탈퇴한 사용자 표기를 그대로 쓴다', (tester) async {
    final ArchiveItem base = _item('q1', DateTime(2026, 11, 19));
    final FakeArchiveRepository repo = FakeArchiveRepository(
      pages: <ArchivePage>[
        ArchivePage(
          items: <ArchiveItem>[
            ArchiveItem(
              dailyQuestionId: base.dailyQuestionId,
              cycleDate: base.cycleDate,
              questionText: base.questionText,
              revealStatus: base.revealStatus,
              hasAnswered: true,
              answers: <ArchiveAnswer>[
                const ArchiveAnswer(
                  answerId: 'a1',
                  authorName: '아빠',
                  mine: false,
                  textContent: '본문',
                  reactions: <EmojiReaction>[
                    EmojiReaction(emoji: EmojiCode.love, count: 2, mine: false),
                  ],
                ),
              ],
            ),
          ],
          nextCursor: null,
        ),
      ],
    );
    await tester.pumpWidget(_app(repo));
    await tester.pumpAndSettle();

    await tester.tap(find.text('누가 눌렀는지 보기'));
    await tester.pumpAndSettle();
    expect(find.text('엄마, 탈퇴한 사용자'), findsOneWidget);
    expect(find.text('❤️ 사랑해요'), findsOneWidget); // 시트의 소제목(칩 문구는 개수가 붙어 다르다)
  });

  testWidgets('LOCKED 항목은 이모지 바 없이 안내만 보이고, 타인 본문은 없다', (tester) async {
    final FakeArchiveRepository repo = FakeArchiveRepository(
      pages: <ArchivePage>[
        ArchivePage(
          items: <ArchiveItem>[_item('q1', DateTime(2026, 11, 19), status: RevealStatus.locked, answered: false)],
          nextCursor: null,
        ),
      ],
    );
    await tester.pumpWidget(_app(repo));
    await tester.pumpAndSettle();

    expect(find.text('아직 답변이 열리지 않았어요.'), findsOneWidget);
    expect(find.textContaining('사랑해요'), findsNothing);
    expect(find.textContaining('아빠 답변'), findsNothing);
  });

  testWidgets('전원 공개 질문에 내가 답하지 않았어도 "내 답변을 쓰면" 안내는 시간 초과 공개에서만 보인다', (tester) async {
    final FakeArchiveRepository repo = FakeArchiveRepository(
      pages: <ArchivePage>[
        ArchivePage(
          items: <ArchiveItem>[
            _item('q2', DateTime(2026, 11, 20), answered: false),
            _item('q1', DateTime(2026, 11, 19), status: RevealStatus.revealedTimeout, answered: false),
          ],
          nextCursor: null,
        ),
      ],
    );
    await tester.pumpWidget(_app(repo));
    await tester.pumpAndSettle();
    expect(find.text('내 답변을 쓰면 가족의 답변을 볼 수 있어요.'), findsOneWidget);
  });

  testWidgets('"더 보기"는 서버가 준 커서로 다음 페이지를 이어 붙인다', (tester) async {
    final FakeArchiveRepository repo = FakeArchiveRepository(
      pages: <ArchivePage>[
        ArchivePage(items: <ArchiveItem>[_item('q2', DateTime(2026, 11, 20))], nextCursor: 'CURSOR-1'),
        ArchivePage(items: <ArchiveItem>[_item('q1', DateTime(2026, 11, 19))], nextCursor: null),
      ],
    );
    await tester.pumpWidget(_app(repo));
    await tester.pumpAndSettle();
    expect(find.text('q1의 질문'), findsNothing);

    await tester.scrollUntilVisible(find.text('더 보기'), 300, scrollable: find.byType(Scrollable).first);
    await tester.tap(find.text('더 보기'));
    await tester.pumpAndSettle();

    expect(repo.requestedCursors, <String?>[null, 'CURSOR-1']);
    expect(find.text('q2의 질문'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('q1의 질문'), 300, scrollable: find.byType(Scrollable).first);
    expect(find.text('q1의 질문'), findsOneWidget);
    expect(find.text('더 보기'), findsNothing);
  });

  testWidgets('지난 질문이 없으면 빈 상태 문구를 보여 준다', (tester) async {
    final FakeArchiveRepository repo = FakeArchiveRepository(
      pages: <ArchivePage>[const ArchivePage(items: <ArchiveItem>[], nextCursor: null)],
    );
    await tester.pumpWidget(_app(repo));
    await tester.pumpAndSettle();
    expect(find.text('아직 지난 질문이 없어요.'), findsOneWidget);
  });
}
