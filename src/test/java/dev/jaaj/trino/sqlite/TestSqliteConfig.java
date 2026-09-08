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
import io.airlift.units.Duration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;

public class TestSqliteConfig
{
    @Test
    public void testDefaults()
    {
        assertRecordedDefaults(recordDefaults(SqliteConfig.class)
                .setS3RefreshInterval(new Duration(1, HOURS))
                .setS3CacheDirectory(Path.of(System.getProperty("java.io.tmpdir"), "trino-sqlite").toFile()));
    }

    @Test
    public void testExplicitPropertyMappings()
    {
        Map<String, String> properties = ImmutableMap.<String, String>builder()
                .put("sqlite.s3.refresh-interval", "15m")
                .put("sqlite.s3.cache-directory", "/var/cache/trino-sqlite")
                .buildOrThrow();

        SqliteConfig expected = new SqliteConfig()
                .setS3RefreshInterval(new Duration(15, MINUTES))
                .setS3CacheDirectory(Path.of("/var/cache/trino-sqlite").toFile());

        assertFullMapping(properties, expected);
    }
}
