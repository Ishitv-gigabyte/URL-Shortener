package com.ishitv.urlshortener.shorturl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ishitv.urlshortener.support.IntegrationTest;

/** Guards the "port the schema exactly" requirement: columns, types, nullability and indexes. */
class FlywaySchemaIT extends IntegrationTest {

    @Autowired
    @Qualifier("primaryDataSource")
    private DataSource primary;

    @Test
    void codesTableMatchesThePythonSchema() {
        JdbcTemplate jdbc = new JdbcTemplate(primary);

        List<Map<String, Object>> columns = jdbc.queryForList("""
                select column_name, data_type, character_maximum_length, is_nullable, column_default
                from information_schema.columns
                where table_name = 'codes'
                order by ordinal_position
                """);

        assertThat(columns).extracting(c -> c.get("column_name"))
                .containsExactly("id", "clicks", "short_code_chars", "original_url", "created_at");
        assertThat(columns).extracting(c -> c.get("data_type"))
                .containsExactly("integer", "integer", "character varying", "character varying",
                        "timestamp without time zone");
        assertThat(columns).extracting(c -> c.get("is_nullable")).containsOnly("NO");
        assertThat(columns.get(3).get("character_maximum_length")).isEqualTo(1500);
        assertThat(columns.get(2).get("character_maximum_length")).isNull();
        // SQLAlchemy's default=0 was Python-side only; the column has no DB default.
        assertThat(columns.get(1).get("column_default")).isNull();
        assertThat((String) columns.get(0).get("column_default")).startsWith("nextval('codes_id_seq'");
    }

    @Test
    void indexesMatchThePythonSchema() {
        JdbcTemplate jdbc = new JdbcTemplate(primary);

        List<String> indexes = jdbc.queryForList(
                "select indexdef from pg_indexes where tablename = 'codes' order by indexname", String.class);

        assertThat(indexes).containsExactly(
                "CREATE UNIQUE INDEX codes_pkey ON public.codes USING btree (id)",
                "CREATE UNIQUE INDEX codes_short_code_chars_key ON public.codes USING btree (short_code_chars)");
    }
}
