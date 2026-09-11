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
import io.trino.testing.StandaloneQueryRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestSqlitePlugin
{
    @Test
    public void testCreateCatalogOnLocalFile()
            throws Exception
    {
        Path database = SqliteTestDatabase.createInTemporaryDirectory("CREATE TABLE t (x INTEGER)");
        try (StandaloneQueryRunner queryRunner = SqliteQueryRunner.create(ImmutableMap.of("connection-url", "jdbc:sqlite:" + database))) {
            assertThat(queryRunner.execute("SHOW SCHEMAS FROM sqlite").getOnlyColumnAsSet())
                    .contains("main");
        }
    }

    @Test
    public void testRejectsInvalidConnectionUrl()
    {
        assertThatThrownBy(() -> SqliteQueryRunner.create(ImmutableMap.of("connection-url", "jdbc:sqlite:data/app.db")))
                .hasStackTraceContaining("connection-url must be an absolute path");
    }
}
