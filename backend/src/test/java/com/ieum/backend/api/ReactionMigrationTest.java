package com.ieum.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** V7(이모지 유일 인덱스)이 V6 시점에 이미 중복이 쌓인 데이터에서도 실패하지 않고, 음성 행은 건드리지 않는다. */
@Testcontainers
class ReactionMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.6-alpine");

    private Flyway flyway(String target) {
        return Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .target(target)
            .load();
    }

    @Test
    @DisplayName("기존 중복 이모지 행이 있어도 V7이 성공하고, 가장 먼저 단 1건만 남기며 음성 행은 보존한다")
    void migratesWithExistingDuplicates() throws Exception {
        flyway("6").migrate();

        UUID group = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID dq = UUID.randomUUID();
        UUID answer = UUID.randomUUID();
        UUID firstLove = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = c.createStatement()) {
            st.execute("insert into users (id, name) values ('" + user + "', '테스트')");
            st.execute("insert into family_groups (id, name, creator_id) values ('" + group + "', '그룹', '" + user + "')");
            st.execute("insert into daily_questions (id, group_id, question_id, cycle_date, assigned_at, auto_reveal_at) "
                + "select '" + dq + "', '" + group + "', id, date '2026-11-19', now(), now() from questions limit 1");
            st.execute("insert into answers (id, daily_question_id, group_id, user_id, text_content, input_method) "
                + "values ('" + answer + "', '" + dq + "', '" + group + "', '" + user + "', '본문', 'TEXT_ONLY')");
            // 같은 (answer, user, LOVE) 3건 + 다른 종류 1건 + 음성 행 2건(emoji_code NULL)
            st.execute("insert into answer_reactions (id, answer_id, user_id, reaction_type, emoji_code, created_at) values "
                + "('" + firstLove + "', '" + answer + "', '" + user + "', 'EMOJI', 'LOVE', now() - interval '3 minute'), "
                + "(gen_random_uuid(), '" + answer + "', '" + user + "', 'EMOJI', 'LOVE', now() - interval '2 minute'), "
                + "(gen_random_uuid(), '" + answer + "', '" + user + "', 'EMOJI', 'LOVE', now() - interval '1 minute'), "
                + "(gen_random_uuid(), '" + answer + "', '" + user + "', 'EMOJI', 'HUG', now())");
            st.execute("insert into answer_reactions (answer_id, user_id, reaction_type, voice_object_key, voice_duration_sec, voice_status) "
                + "values ('" + answer + "', '" + user + "', 'VOICE_MESSAGE', 'k1', 5, 'ACTIVE'), "
                + "('" + answer + "', '" + user + "', 'VOICE_MESSAGE', 'k2', 5, 'ACTIVE')");
        }

        flyway("7").migrate();

        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = c.createStatement()) {
            List<String> emoji = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("select id, emoji_code from answer_reactions where reaction_type = 'EMOJI' order by emoji_code")) {
                while (rs.next()) {
                    emoji.add(rs.getString("emoji_code") + ":" + rs.getString("id"));
                }
            }
            assertThat(emoji).hasSize(2);
            assertThat(emoji.get(0)).startsWith("HUG:");
            assertThat(emoji.get(1)).isEqualTo("LOVE:" + firstLove);
            try (ResultSet rs = st.executeQuery("select count(*) from answer_reactions where reaction_type = 'VOICE_MESSAGE'")) {
                rs.next();
                assertThat(rs.getInt(1)).as("음성 행은 유일 인덱스의 대상이 아니다").isEqualTo(2);
            }
        }

        // 이후 같은 이모지의 직접 INSERT는 인덱스가 막고, ON CONFLICT DO NOTHING은 에러 없이 0행이다.
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            String insert = "insert into answer_reactions (answer_id, user_id, reaction_type, emoji_code) values (?, ?, 'EMOJI', 'LOVE')";
            try (PreparedStatement ps = c.prepareStatement(insert)) {
                ps.setObject(1, answer);
                ps.setObject(2, user);
                assertThatThrownBy(ps::executeUpdate).hasMessageContaining("uq_answer_reaction_emoji");
            }
            try (PreparedStatement ps = c.prepareStatement(insert + " on conflict do nothing")) {
                ps.setObject(1, answer);
                ps.setObject(2, user);
                assertThat(ps.executeUpdate()).isZero();
            }
        }
    }
}
