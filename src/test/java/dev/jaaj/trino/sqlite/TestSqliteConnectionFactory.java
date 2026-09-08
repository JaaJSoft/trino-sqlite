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

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static io.trino.testing.TestingConnectorSession.SESSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestSqliteConnectionFactory
{
    @Test
    public void testLocalFileIsReadable()
            throws Exception
    {
        Path database = SqliteTestDatabase.createInTemporaryDirectory(
                "CREATE TABLE t (x INTEGER)",
                "INSERT INTO t VALUES (7)");

        try (SqliteConnectionFactory factory = SqliteConnectionFactory.forLocalFile(database);
                Connection connection = factory.openConnection(SESSION);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT x FROM t")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getLong(1)).isEqualTo(7);
        }
    }

    @Test
    public void testLocalFileIsOpenedReadOnly()
            throws Exception
    {
        Path database = SqliteTestDatabase.createInTemporaryDirectory("CREATE TABLE t (x INTEGER)");

        try (SqliteConnectionFactory factory = SqliteConnectionFactory.forLocalFile(database);
                Connection connection = factory.openConnection(SESSION);
                Statement statement = connection.createStatement()) {
            assertThat(connection.isReadOnly()).isTrue();
            assertThatThrownBy(() -> statement.execute("INSERT INTO t VALUES (1)"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("readonly");
        }
    }
}
