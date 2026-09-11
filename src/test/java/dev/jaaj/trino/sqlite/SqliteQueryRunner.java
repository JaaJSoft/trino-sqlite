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

import io.trino.testing.StandaloneQueryRunner;

import java.util.Map;

import static io.trino.testing.TestingSession.testSessionBuilder;

public final class SqliteQueryRunner
{
    public static final String CATALOG = "sqlite";
    public static final String SCHEMA = "main";

    private SqliteQueryRunner() {}

    public static StandaloneQueryRunner create(Map<String, String> catalogProperties)
    {
        StandaloneQueryRunner queryRunner = new StandaloneQueryRunner(testSessionBuilder()
                .setCatalog(CATALOG)
                .setSchema(SCHEMA)
                .build());
        try {
            queryRunner.installPlugin(new SqlitePlugin());
            queryRunner.createCatalog(CATALOG, "sqlite", catalogProperties);
            return queryRunner;
        }
        catch (RuntimeException e) {
            queryRunner.close();
            throw e;
        }
    }
}
