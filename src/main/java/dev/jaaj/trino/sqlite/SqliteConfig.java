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

import io.airlift.configuration.Config;
import io.airlift.configuration.ConfigDescription;
import io.airlift.units.Duration;
import io.airlift.units.MinDuration;
import jakarta.validation.constraints.NotNull;

import java.io.File;
import java.nio.file.Path;

import static java.util.concurrent.TimeUnit.HOURS;

public class SqliteConfig
{
    private Duration s3RefreshInterval = new Duration(1, HOURS);
    private File s3CacheDirectory = Path.of(System.getProperty("java.io.tmpdir"), "trino-sqlite").toFile();

    @NotNull
    @MinDuration("0s")
    public Duration getS3RefreshInterval()
    {
        return s3RefreshInterval;
    }

    @Config("sqlite.s3.refresh-interval")
    @ConfigDescription("How long a copy downloaded from S3 is served before S3 is checked for a newer version; 0s checks on every connection")
    public SqliteConfig setS3RefreshInterval(Duration s3RefreshInterval)
    {
        this.s3RefreshInterval = s3RefreshInterval;
        return this;
    }

    @NotNull
    public File getS3CacheDirectory()
    {
        return s3CacheDirectory;
    }

    @Config("sqlite.s3.cache-directory")
    @ConfigDescription("Directory under which each catalog keeps its downloaded copy of the database")
    public SqliteConfig setS3CacheDirectory(File s3CacheDirectory)
    {
        this.s3CacheDirectory = s3CacheDirectory;
        return this;
    }
}
