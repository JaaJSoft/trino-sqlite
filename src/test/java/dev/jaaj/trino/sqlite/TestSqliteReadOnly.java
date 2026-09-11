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
import io.trino.testing.AbstractTestQueryFramework;
import io.trino.testing.QueryRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static io.trino.spi.connector.ConnectorMetadata.MODIFYING_ROWS_MESSAGE;

public class TestSqliteReadOnly
        extends AbstractTestQueryFramework
{
    private static final String READ_ONLY = SqliteClient.READ_ONLY_MESSAGE;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        Path database = SqliteTestDatabase.createInTemporaryDirectory(
                "CREATE TABLE t (id INTEGER, name TEXT)",
                "INSERT INTO t VALUES (1, 'a')");
        return SqliteQueryRunner.create(ImmutableMap.of("connection-url", "jdbc:sqlite:" + database));
    }

    @Test
    public void testInsert()
    {
        assertQueryFails("INSERT INTO t VALUES (2, 'b')", READ_ONLY);
    }

    @Test
    public void testCreateTable()
    {
        assertQueryFails("CREATE TABLE u (x bigint)", READ_ONLY);
    }

    @Test
    public void testCreateTableAsSelect()
    {
        assertQueryFails("CREATE TABLE u AS SELECT * FROM t", READ_ONLY);
    }

    @Test
    public void testDropTable()
    {
        assertQueryFails("DROP TABLE t", READ_ONLY);
    }

    @Test
    public void testAlterTable()
    {
        assertQueryFails("ALTER TABLE t ADD COLUMN y bigint", READ_ONLY);
        assertQueryFails("ALTER TABLE t DROP COLUMN name", READ_ONLY);
        assertQueryFails("ALTER TABLE t RENAME COLUMN name TO label", READ_ONLY);
        assertQueryFails("ALTER TABLE t RENAME TO u", READ_ONLY);
    }

    @Test
    public void testTruncate()
    {
        assertQueryFails("TRUNCATE TABLE t", READ_ONLY);
    }

    @Test
    public void testSchemas()
    {
        assertQueryFails("CREATE SCHEMA other", READ_ONLY);
        // without CASCADE the engine rejects the non-empty schema before reaching the connector
        assertQueryFails("DROP SCHEMA main CASCADE", READ_ONLY);
        assertQueryFails("ALTER SCHEMA main RENAME TO other", READ_ONLY);
    }

    @Test
    public void testDeleteAndUpdate()
    {
        assertQueryFails("DELETE FROM t WHERE id = 1", MODIFYING_ROWS_MESSAGE);
        assertQueryFails("UPDATE t SET name = 'b' WHERE id = 1", MODIFYING_ROWS_MESSAGE);
    }

    @Test
    public void testDataIsUntouched()
    {
        assertQuery("SELECT id, name FROM t", "VALUES (1, 'a')");
    }
}
