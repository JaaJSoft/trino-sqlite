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

import com.google.inject.Binder;
import com.google.inject.Inject;
import com.google.inject.Scopes;
import dev.jaaj.trino.sqlite.s3.RemoteDatabaseFile;
import io.airlift.configuration.AbstractConfigurationAwareModule;
import io.trino.filesystem.TrinoFileSystemFactory;
import io.trino.filesystem.s3.FileSystemS3;
import io.trino.filesystem.s3.S3FileSystemModule;
import io.trino.plugin.jdbc.BaseJdbcConfig;
import io.trino.plugin.jdbc.ConnectionFactory;
import io.trino.plugin.jdbc.ForBaseJdbc;
import io.trino.plugin.jdbc.JdbcClient;
import io.trino.plugin.jdbc.ptf.Query;
import io.trino.spi.function.table.ConnectorTableFunction;
import jakarta.inject.Provider;

import java.time.Clock;

import static com.google.inject.multibindings.Multibinder.newSetBinder;
import static io.airlift.configuration.ConfigBinder.configBinder;
import static java.util.Objects.requireNonNull;

public class SqliteClientModule
        extends AbstractConfigurationAwareModule
{
    @Override
    protected void setup(Binder binder)
    {
        binder.bind(JdbcClient.class).annotatedWith(ForBaseJdbc.class).to(SqliteClient.class).in(Scopes.SINGLETON);
        configBinder(binder).bindConfig(SqliteConfig.class);
        newSetBinder(binder, ConnectorTableFunction.class).addBinding().toProvider(Query.class).in(Scopes.SINGLETON);

        DatabaseLocation location = DatabaseLocation.parse(buildConfigObject(BaseJdbcConfig.class).getConnectionUrl());
        switch (location) {
            case DatabaseLocation.Local local -> binder.bind(ConnectionFactory.class).annotatedWith(ForBaseJdbc.class)
                    .toInstance(SqliteConnectionFactory.forLocalFile(local.path()));
            case DatabaseLocation.Remote remote -> {
                install(new S3FileSystemModule());
                binder.bind(DatabaseLocation.Remote.class).toInstance(remote);
                binder.bind(ConnectionFactory.class).annotatedWith(ForBaseJdbc.class)
                        .toProvider(RemoteConnectionFactoryProvider.class).in(Scopes.SINGLETON);
            }
        }
    }

    private static final class RemoteConnectionFactoryProvider
            implements Provider<ConnectionFactory>
    {
        private final DatabaseLocation.Remote remote;
        private final SqliteConfig config;
        private final TrinoFileSystemFactory fileSystemFactory;

        @Inject
        public RemoteConnectionFactoryProvider(DatabaseLocation.Remote remote, SqliteConfig config, @FileSystemS3 TrinoFileSystemFactory fileSystemFactory)
        {
            this.remote = requireNonNull(remote, "remote is null");
            this.config = requireNonNull(config, "config is null");
            this.fileSystemFactory = requireNonNull(fileSystemFactory, "fileSystemFactory is null");
        }

        @Override
        public ConnectionFactory get()
        {
            return SqliteConnectionFactory.forRemoteFile(new RemoteDatabaseFile(
                    fileSystemFactory,
                    remote.location(),
                    config.getS3CacheDirectory().toPath(),
                    config.getS3RefreshInterval().toJavaTime(),
                    Clock.systemUTC()));
        }
    }
}
