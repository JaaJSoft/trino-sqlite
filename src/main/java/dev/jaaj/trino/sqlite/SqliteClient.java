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

import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import io.trino.plugin.base.mapping.IdentifierMapping;
import io.trino.plugin.jdbc.BaseJdbcClient;
import io.trino.plugin.jdbc.BaseJdbcConfig;
import io.trino.plugin.jdbc.ColumnMapping;
import io.trino.plugin.jdbc.ConnectionFactory;
import io.trino.plugin.jdbc.JdbcTableHandle;
import io.trino.plugin.jdbc.JdbcTypeHandle;
import io.trino.plugin.jdbc.QueryBuilder;
import io.trino.plugin.jdbc.RemoteTableName;
import io.trino.plugin.jdbc.WriteMapping;
import io.trino.plugin.jdbc.logging.RemoteQueryModifier;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.type.Type;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Optional;

import static io.trino.plugin.jdbc.PredicatePushdownController.DISABLE_PUSHDOWN;
import static io.trino.plugin.jdbc.StandardColumnMappings.bigintColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.booleanColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.doubleColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.varbinaryColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.varcharReadFunction;
import static io.trino.plugin.jdbc.StandardColumnMappings.varcharWriteFunction;
import static io.trino.plugin.jdbc.TypeHandlingJdbcSessionProperties.getUnsupportedTypeHandling;
import static io.trino.plugin.jdbc.UnsupportedTypeHandling.CONVERT_TO_VARCHAR;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.trino.spi.type.VarcharType.createUnboundedVarcharType;

public class SqliteClient
        extends BaseJdbcClient
{
    public static final String SCHEMA_NAME = "main";
    static final String READ_ONLY_MESSAGE = "The SQLite connector is read-only";

    private static final String[] TABLE_TYPES = {"TABLE", "VIEW"};

    @Inject
    public SqliteClient(BaseJdbcConfig config, ConnectionFactory connectionFactory, QueryBuilder queryBuilder, IdentifierMapping identifierMapping, RemoteQueryModifier queryModifier)
    {
        super("\"", connectionFactory, queryBuilder, config.getJdbcTypesMappedToVarchar(), identifierMapping, queryModifier, false);
    }

    @Override
    public Collection<String> listSchemas(Connection connection)
    {
        return ImmutableSet.of(SCHEMA_NAME);
    }

    @Override
    public Optional<JdbcTableHandle> getTableHandle(ConnectorSession session, SchemaTableName schemaTableName)
    {
        // the driver ignores the schema in its metadata queries, so any schema name would
        // otherwise resolve to the tables of main
        if (!schemaTableName.getSchemaName().equals(SCHEMA_NAME)) {
            return Optional.empty();
        }
        return super.getTableHandle(session, schemaTableName);
    }

    @Override
    public ResultSet getTables(Connection connection, Optional<String> remoteSchemaName, Optional<String> remoteTableName)
            throws SQLException
    {
        DatabaseMetaData metadata = connection.getMetaData();
        return metadata.getTables(
                null,
                null,
                escapeObjectNameForMetadataQuery(remoteTableName, metadata.getSearchStringEscape()).orElse(null),
                TABLE_TYPES);
    }

    @Override
    protected String getTableRemoteSchemaName(ResultSet resultSet)
    {
        // sqlite-jdbc reports TABLE_SCHEM as null
        return SCHEMA_NAME;
    }

    @Override
    protected ResultSet getColumns(RemoteTableName remoteTableName, DatabaseMetaData metadata)
            throws SQLException
    {
        return metadata.getColumns(
                null,
                null,
                escapeObjectNameForMetadataQuery(remoteTableName.getTableName(), metadata.getSearchStringEscape()),
                null);
    }

    @Override
    public Optional<ColumnMapping> toColumnMapping(ConnectorSession session, Connection connection, JdbcTypeHandle typeHandle)
    {
        Optional<ColumnMapping> forcedMapping = getForcedMappingToVarchar(typeHandle);
        if (forcedMapping.isPresent()) {
            return forcedMapping;
        }

        String declaredType = typeHandle.jdbcTypeName().orElse("");
        if (declaredType.equalsIgnoreCase("BOOLEAN") || declaredType.equalsIgnoreCase("BOOL")) {
            return Optional.of(booleanColumnMapping());
        }

        Optional<ColumnMapping> mapping = switch (SqliteTypeAffinity.fromDeclaredType(declaredType)) {
            case INTEGER -> Optional.of(bigintColumnMapping());
            case REAL -> Optional.of(doubleColumnMapping());
            // a text column may carry COLLATE NOCASE, which would make SQLite's comparison differ from Trino's
            case TEXT -> Optional.of(ColumnMapping.sliceMapping(
                    createUnboundedVarcharType(),
                    varcharReadFunction(createUnboundedVarcharType()),
                    varcharWriteFunction(),
                    DISABLE_PUSHDOWN));
            case BLOB -> Optional.of(varbinaryColumnMapping());
            case NUMERIC -> Optional.empty();
        };
        if (mapping.isEmpty() && getUnsupportedTypeHandling(session) == CONVERT_TO_VARCHAR) {
            return mapToUnboundedVarchar(typeHandle);
        }
        return mapping;
    }

    @Override
    public WriteMapping toWriteMapping(ConnectorSession session, Type type)
    {
        throw new TrinoException(NOT_SUPPORTED, READ_ONLY_MESSAGE);
    }
}
