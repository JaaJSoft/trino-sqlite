/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.jaaj.trino.sqlite;

import com.google.common.collect.ImmutableMap;
import io.trino.Session;
import io.trino.sql.planner.plan.FilterNode;
import io.trino.sql.planner.plan.TopNNode;
import io.trino.testing.AbstractTestQueryFramework;
import io.trino.testing.MaterializedResult;
import io.trino.testing.QueryRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.VarbinaryType.VARBINARY;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;

public class TestSqliteConnectorQueries
        extends AbstractTestQueryFramework
{
    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        Path database = SqliteTestDatabase.createInTemporaryDirectory(
                """
                CREATE TABLE all_types (
                    id INTEGER PRIMARY KEY,
                    int_col INT,
                    bigint_col BIGINT,
                    real_col REAL,
                    double_col DOUBLE PRECISION,
                    text_col TEXT,
                    varchar_col VARCHAR(20),
                    blob_col BLOB,
                    bool_col BOOLEAN,
                    numeric_col NUMERIC,
                    decimal_col DECIMAL(10,2),
                    datetime_col DATETIME)
                """,
                "INSERT INTO all_types VALUES (1, 42, 9007199254740993, 1.5, 2.25, 'hello', 'world', X'0102', 1, 3.14, 12.34, '2024-01-02 03:04:05')",
                "INSERT INTO all_types VALUES (2, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)",
                "CREATE TABLE untyped (a, b)",
                "INSERT INTO untyped VALUES ('text', 7)",
                "CREATE TABLE coerced (n INTEGER)",
                "INSERT INTO coerced VALUES ('abc')",
                "INSERT INTO coerced VALUES ('12')",
                "CREATE TABLE \"MixedCase\" (Id INTEGER, Name TEXT)",
                "INSERT INTO \"MixedCase\" VALUES (1, 'x')",
                "CREATE TABLE nocase (name TEXT COLLATE NOCASE)",
                "INSERT INTO nocase VALUES ('Alice'), ('alice'), ('Bob')",
                "CREATE TABLE autoinc (id INTEGER PRIMARY KEY AUTOINCREMENT, v TEXT)",
                "INSERT INTO autoinc (v) VALUES ('row')",
                "CREATE VIEW positive_ids AS SELECT id FROM all_types WHERE id > 0");
        return SqliteQueryRunner.create(ImmutableMap.of("connection-url", "jdbc:sqlite:" + database));
    }

    @Override
    protected Session getSession()
    {
        // QueryAssert.isFullyPushedDown()/isNotFullyPushedDown()/matches(PlanMatchPattern) plan a query
        // directly against this session, without going through the query-dispatch path that normally
        // rebuilds it against the engine's real SessionPropertyManager; the no-arg testSessionBuilder()
        // used by the default session leaves that manager empty, so any catalog session property read
        // during planning (as CachingJdbcClient does for every table) fails to resolve.
        return testSessionBuilder(getQueryRunner().getSessionPropertyManager())
                .setCatalog(SqliteQueryRunner.CATALOG)
                .setSchema(SqliteQueryRunner.SCHEMA)
                .build();
    }

    @Test
    public void testShowSchemas()
    {
        assertThat(computeActual("SHOW SCHEMAS").getOnlyColumnAsSet())
                .isEqualTo(Set.of("main", "information_schema"));
    }

    @Test
    public void testShowTablesHidesInternalTables()
    {
        // "MixedCase" is exposed lowercase: DefaultIdentifierMapping lowercases every remote table name,
        // the same behavior pinned by testMixedCaseTableIsReachableLowercase below.
        assertThat(computeActual("SHOW TABLES").getOnlyColumnAsSet())
                .containsExactlyInAnyOrder("all_types", "untyped", "coerced", "mixedcase", "nocase", "autoinc", "positive_ids");
    }

    @Test
    public void testUnknownSchemaHasNoTables()
    {
        assertQueryFails("SHOW TABLES FROM other", ".*Schema 'other' does not exist");
        // the analyzer checks schema existence before table existence, so a query against a table in a
        // nonexistent schema fails with the same "Schema ... does not exist" message, never reaching the
        // connector's getTableHandle
        assertQueryFails("SELECT * FROM other.all_types", ".*Schema 'other' does not exist");
    }

    @Test
    public void testDescribeMapsDeclaredTypesByAffinity()
    {
        MaterializedResult columns = computeActual("DESCRIBE all_types");
        assertThat(columns.getMaterializedRows().stream().map(row -> row.getField(0) + " " + row.getField(1)))
                .containsExactly(
                        "id bigint",
                        "int_col bigint",
                        "bigint_col bigint",
                        "real_col double",
                        "double_col double",
                        "text_col varchar",
                        "varchar_col varchar",
                        "blob_col varbinary",
                        "bool_col boolean");
    }

    @Test
    public void testUnsupportedColumnsConvertedToVarcharOnRequest()
    {
        Session convert = Session.builder(getSession())
                .setCatalogSessionProperty("sqlite", "unsupported_type_handling", "CONVERT_TO_VARCHAR")
                .build();
        assertThat(computeActual(convert, "DESCRIBE all_types").getMaterializedRows().stream().map(row -> row.getField(0) + " " + row.getField(1)))
                .contains("numeric_col varchar", "decimal_col varchar", "datetime_col varchar");
        assertQuery(
                convert,
                "SELECT numeric_col, decimal_col, datetime_col FROM all_types WHERE id = 1",
                "VALUES ('3.14', '12.34', '2024-01-02 03:04:05')");
    }

    @Test
    public void testSelectAllSupportedTypes()
    {
        MaterializedResult result = computeActual("SELECT int_col, bigint_col, real_col, double_col, text_col, varchar_col, blob_col, bool_col FROM all_types WHERE id = 1");
        assertThat(result.getTypes()).containsExactly(BIGINT, BIGINT, DOUBLE, DOUBLE, VARCHAR, VARCHAR, VARBINARY, BOOLEAN);
        // blob_col is compared through to_hex(): StandaloneQueryRunner materializes VARBINARY as SqlVarbinary
        // while the expected side (evaluated by H2) materializes it as byte[], so a direct VARBINARY comparison
        // through assertQuery never matches even when the bytes are identical.
        assertQuery(
                "SELECT int_col, bigint_col, real_col, double_col, text_col, varchar_col, to_hex(blob_col), bool_col FROM all_types WHERE id = 1",
                "VALUES (42, 9007199254740993, 1.5, 2.25, 'hello', 'world', '0102', true)");
    }

    @Test
    public void testNullsInEveryColumn()
    {
        assertQuery(
                "SELECT int_col, bigint_col, real_col, double_col, text_col, varchar_col, blob_col, bool_col FROM all_types WHERE id = 2",
                "VALUES (CAST(NULL AS BIGINT), CAST(NULL AS BIGINT), CAST(NULL AS DOUBLE), CAST(NULL AS DOUBLE), CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR), CAST(NULL AS VARBINARY), CAST(NULL AS BOOLEAN))");
    }

    @Test
    public void testUntypedColumnsAreVarbinary()
    {
        assertThat(computeActual("DESCRIBE untyped").getMaterializedRows().stream().map(row -> row.getField(0) + " " + row.getField(1)))
                .containsExactly("a varbinary", "b varbinary");
        // see the comment in testSelectAllSupportedTypes: VARBINARY values are compared through to_hex()
        assertQuery("SELECT to_hex(a), to_hex(b) FROM untyped", "VALUES ('74657874', '37')");
    }

    @Test
    public void testValueOfAnotherStorageClassIsCoercedBySqlite()
    {
        // 'abc' cannot become an integer, SQLite keeps it as text and sqlite3_column_int64 reads it as 0;
        // '12' is converted on insert because the column has INTEGER affinity
        assertQuery("SELECT n FROM coerced ORDER BY n", "VALUES 0, 12");
    }

    @Test
    public void testPushedDownPredicateSkipsValuesOfAnotherStorageClass()
    {
        // n = 0 is pushed down as a bigint-column equality; SQLite compares its stored TEXT 'abc'
        // against the INTEGER literal 0 by storage class, never as the coerced value Trino reads back,
        // so the row is silently dropped instead of matching like the Trino-evaluated form below.
        assertThat(query("SELECT n FROM coerced WHERE n = 0")).skipResultsCorrectnessCheckForPushdown().isFullyPushedDown();
        assertQuery("SELECT count(*) FROM coerced WHERE n = 0", "VALUES 0");
        // CAST(n AS varchar) is not a simple column predicate, so base-jdbc evaluates it in Trino,
        // where the coerced value of 0 is compared as read: this is the form users should filter on.
        assertThat(query("SELECT n FROM coerced WHERE CAST(n AS varchar) = '0'")).isNotFullyPushedDown(FilterNode.class);
        assertQuery("SELECT count(*) FROM coerced WHERE CAST(n AS varchar) = '0'", "VALUES 1");
    }

    @Test
    public void testMixedCaseTableIsReachableLowercase()
    {
        assertQuery("SELECT name FROM mixedcase", "VALUES 'x'");
    }

    @Test
    public void testView()
    {
        assertQuery("SELECT id FROM positive_ids ORDER BY id", "VALUES 1, 2");
    }

    @Test
    public void testNumericPredicateIsPushedDown()
    {
        assertThat(query("SELECT id FROM all_types WHERE int_col = 42")).isFullyPushedDown();
        assertThat(query("SELECT id FROM all_types WHERE real_col > 1")).isFullyPushedDown();
        assertThat(query("SELECT id FROM all_types WHERE bool_col = true")).isFullyPushedDown();
        assertQuery("SELECT id FROM all_types WHERE int_col = 42", "VALUES 1");
    }

    @Test
    public void testTextPredicateStaysInTrino()
    {
        assertThat(query("SELECT id FROM all_types WHERE text_col = 'hello'")).isNotFullyPushedDown(FilterNode.class);
        assertQuery("SELECT id FROM all_types WHERE text_col = 'hello'", "VALUES 1");
    }

    @Test
    public void testNocaseCollationDoesNotLeakIntoTrino()
    {
        // pushed down, SQLite's NOCASE collation would match both 'Alice' and 'alice'
        assertQuery("SELECT count(*) FROM nocase WHERE name = 'alice'", "VALUES 1");
    }

    @Test
    public void testLimitIsPushedDown()
    {
        assertThat(query("SELECT id FROM all_types LIMIT 1")).isFullyPushedDown();
        assertQuery("SELECT count(*) FROM (SELECT id FROM all_types LIMIT 1)", "VALUES 1");
    }

    @Test
    public void testOrderByLimitStaysInTrino()
    {
        assertThat(query("SELECT id FROM all_types ORDER BY id DESC LIMIT 1")).isNotFullyPushedDown(TopNNode.class);
        assertQuery("SELECT id FROM all_types ORDER BY id DESC LIMIT 1", "VALUES 2");
    }

    @Test
    public void testQueryTableFunction()
    {
        // sqlite-jdbc reports the type of a computed column such as count(*) as NUMERIC, which the
        // connector otherwise hides; CONVERT_TO_VARCHAR surfaces it instead of failing table-handle resolution
        Session convert = Session.builder(getSession())
                .setCatalogSessionProperty("sqlite", "unsupported_type_handling", "CONVERT_TO_VARCHAR")
                .build();
        assertQuery(
                convert,
                "SELECT * FROM TABLE(sqlite.system.query(query => 'SELECT count(*) AS c FROM all_types'))",
                "VALUES '2'");
        // a DELETE has no result columns, so table-handle resolution fails at analysis time, before the
        // read-only connection would itself reject the write; that earlier failure is what a user hits
        assertQueryFails(
                "SELECT * FROM TABLE(sqlite.system.query(query => 'DELETE FROM all_types'))",
                ".*column 1 out of bounds.*");
    }
}
