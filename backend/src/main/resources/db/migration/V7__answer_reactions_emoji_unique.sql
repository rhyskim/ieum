-- 이모지 리액션 유일성(ADR-0008). 같은 사람이 같은 답변에 같은 이모지를 한 번만 달 수 있다.
--
-- 부분 유니크 인덱스인 이유: answer_reactions는 이모지 행과 음성 리액션 행(reaction_type=VOICE_MESSAGE)이 한 테이블을 쓴다.
-- 음성 행은 emoji_code가 NULL이고, 전체에 UNIQUE(answer_id, user_id, emoji_code)를 걸면 (NULL은 서로 다른 값이라)
-- 아무것도 막지 못하면서 Phase 2의 음성 리액션 규칙(몇 건까지 허용할지)을 지금 암묵적으로 정해 버린다.
-- 그래서 이모지 행에만 적용하고, 음성 행의 제약은 Phase 2에서 따로 정한다.

-- 1) 기존 중복 정리: 같은 (answer_id, user_id, emoji_code) 이모지 행 중 가장 먼저 달린 1건만 남긴다.
--    인덱스를 만들기 전에 실행해야 마이그레이션이 중복 데이터 때문에 실패하지 않는다.
DELETE FROM answer_reactions r
USING (
    SELECT id,
           row_number() OVER (
               PARTITION BY answer_id, user_id, emoji_code
               ORDER BY created_at ASC, id ASC
           ) AS rn
    FROM answer_reactions
    WHERE reaction_type = 'EMOJI'
) d
WHERE r.id = d.id AND d.rn > 1;

-- 2) 최종 방어선. 앱의 INSERT ... ON CONFLICT DO NOTHING이 이 인덱스 충돌을 에러 없는 무시로 바꾼다.
CREATE UNIQUE INDEX uq_answer_reaction_emoji
    ON answer_reactions (answer_id, user_id, emoji_code)
    WHERE reaction_type = 'EMOJI';
