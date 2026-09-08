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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static org.sqlite.JDBC.createConnection;

/**
 * Builds SQLite fixture files with the driver directly, since the connector cannot write.
 */
public final class SqliteTestDatabase
{
    private SqliteTestDatabase() {}

    public static Path create(Path file, String... statements)
            throws SQLException
    {
        try (Connection connection = createConnection("jdbc:sqlite:" + file.toAbsolutePath(), new Properties());
                Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
        return file;
    }

    public static Path createInTemporaryDirectory(String... statements)
            throws SQLException, IOException
    {
        Path directory = Files.createTempDirectory("trino-sqlite-test");
        return create(directory.resolve("fixture.db"), statements);
    }
}
