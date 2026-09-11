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
import org.sqlite.JDBC;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

public class TestSqliteJdbcDriver
{
    @Test
    public void testNativeLibraryLoads()
            throws Exception
    {
        try (Connection connection = JDBC.createConnection("jdbc:sqlite::memory:", new Properties());
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT sqlite_version()")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).startsWith("3.");
        }
    }
}
