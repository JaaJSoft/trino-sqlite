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
import io.trino.plugin.jdbc.ConnectionFactory;
import io.trino.spi.connector.ConnectorSession;
import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

import static java.util.Objects.requireNonNull;

/**
 * Opens sqlite-jdbc connections with the open mode owned by the connector, never by the URL.
 * {@link io.trino.plugin.jdbc.DriverConnectionFactory} is not used because it takes one fixed
 * URL, and the path of a copy downloaded from S3 changes on every refresh.
 */
public final class SqliteConnectionFactory
        implements ConnectionFactory
{
    private final ConnectionOpener opener;
    private final Runnable onClose;

    @FunctionalInterface
    private interface ConnectionOpener
    {
        Connection open(ConnectorSession session)
                throws SQLException;
    }

    private SqliteConnectionFactory(ConnectionOpener opener, Runnable onClose)
    {
        this.opener = requireNonNull(opener, "opener is null");
        this.onClose = requireNonNull(onClose, "onClose is null");
    }

    public static SqliteConnectionFactory forLocalFile(Path path)
    {
        String url = JDBC.PREFIX + path.toAbsolutePath();
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        Properties properties = config.toProperties();
        return new SqliteConnectionFactory(_ -> JDBC.createConnection(url, properties), () -> {});
    }

    /**
     * The copy is private to this catalog and nobody writes to it, so {@code immutable=1} lets
     * SQLite skip locking and journal checks on it. The URI form is required for that parameter,
     * hence OPEN_URI. The open runs under a lease so a refresh cannot delete the copy between the
     * moment its path is read and the moment the driver opens it.
     */
    public static SqliteConnectionFactory forRemoteFile(RemoteDatabaseFile remote)
    {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setOpenMode(SQLiteOpenMode.OPEN_URI);
        Properties properties = config.toProperties();
        return new SqliteConnectionFactory(
                session -> remote.withCurrent(session, file -> JDBC.createConnection(JDBC.PREFIX + file.toUri() + "?immutable=1", properties)),
                remote::close);
    }

    @Override
    public Connection openConnection(ConnectorSession session)
            throws SQLException
    {
        return opener.open(session);
    }

    @Override
    public void close()
    {
        onClose.run();
    }
}
