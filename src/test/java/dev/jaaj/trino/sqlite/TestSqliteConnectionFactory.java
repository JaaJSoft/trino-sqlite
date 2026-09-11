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

import dev.jaaj.trino.sqlite.s3.RemoteDatabaseFile;
import io.trino.filesystem.Location;
import io.trino.filesystem.memory.MemoryFileSystemFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;

import static io.trino.testing.TestingConnectorSession.SESSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    @Test
    public void testLocalFileNameWithQuestionMark()
            throws Exception
    {
        // sqlite-jdbc cuts a plain file name at the first '?' and reads the rest as parameters
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "Windows forbids '?' in file names");
        assertSingleRowIsReadable(fixtureNamed("app?cache_size=1.db"));
    }

    @Test
    public void testLocalFileNameWithSpaceAndHash()
            throws Exception
    {
        assertSingleRowIsReadable(fixtureNamed("app db#1.db"));
    }

    @Test
    public void testLocalFileUrlKeepsUncPathWithoutAuthority()
    {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "UNC paths are a Windows notion");
        assertThat(SqliteConnectionFactory.localFileUrl(Path.of("\\\\server\\share\\app.db")))
                .isEqualTo("jdbc:sqlite:file:////server/share/app.db");
    }

    @Test
    public void testLocalFileUrlEncodesReservedCharacters()
            throws Exception
    {
        Path path = Files.createTempDirectory("trino-sqlite-test").resolve("app db#1.db");
        assertThat(SqliteConnectionFactory.localFileUrl(path))
                .startsWith("jdbc:sqlite:file:///")
                .contains("app%20db%231.db")
                .doesNotContain(" ", "#");
    }

    @Test
    public void testRemoteFileIsOpenedImmutable()
            throws Exception
    {
        Path database = SqliteTestDatabase.createInTemporaryDirectory(
                "CREATE TABLE t (x INTEGER)",
                "INSERT INTO t VALUES (7)");
        MemoryFileSystemFactory fileSystemFactory = new MemoryFileSystemFactory();
        Location location = Location.of("memory:///exports/app.db");
        fileSystemFactory.create(SESSION.getIdentity()).newOutputFile(location).createOrOverwrite(Files.readAllBytes(database));
        Path cacheDirectory = Files.createTempDirectory("trino-sqlite-cache");
        RemoteDatabaseFile remote = new RemoteDatabaseFile(fileSystemFactory, location, cacheDirectory, Duration.ofHours(1), Clock.systemUTC());

        Path copy;
        try (SqliteConnectionFactory factory = SqliteConnectionFactory.forRemoteFile(remote);
                Connection connection = factory.openConnection(SESSION);
                Statement statement = connection.createStatement()) {
            copy = remote.current(SESSION);
            assertThat(connection.isReadOnly()).isTrue();
            try (ResultSet resultSet = statement.executeQuery("SELECT x FROM t")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getLong(1)).isEqualTo(7);
            }
            assertThatThrownBy(() -> statement.execute("INSERT INTO t VALUES (1)"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("readonly");
        }
        // closing the factory closes the remote file, which removes its copy; the connection
        // was closed first (resources close in reverse order), so Windows lets the delete through
        assertThat(copy).doesNotExist();
        assertThat(cacheDirectory).isEmptyDirectory();
    }

    private static Path fixtureNamed(String fileName)
            throws Exception
    {
        Path fixture = SqliteTestDatabase.createInTemporaryDirectory(
                "CREATE TABLE t (x INTEGER)",
                "INSERT INTO t VALUES (7)");
        // the fixture is built under a plain name and renamed, so the driver never has to open
        // the awkward name for writing
        Path renamed = Files.move(fixture, fixture.resolveSibling(fileName));
        renamed.toFile().deleteOnExit();
        return renamed;
    }

    private static void assertSingleRowIsReadable(Path database)
            throws Exception
    {
        try (SqliteConnectionFactory factory = SqliteConnectionFactory.forLocalFile(database);
                Connection connection = factory.openConnection(SESSION);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT x FROM t")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getLong(1)).isEqualTo(7);
        }
    }
}
