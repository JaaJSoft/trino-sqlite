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

import io.trino.filesystem.Location;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestDatabaseLocation
{
    private static final Path ABSOLUTE = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("app.db");

    @Test
    public void testAbsoluteLocalPath()
    {
        assertThat(DatabaseLocation.parse("jdbc:sqlite:" + ABSOLUTE))
                .isEqualTo(new DatabaseLocation.Local(ABSOLUTE));
    }

    @Test
    public void testFileUri()
    {
        assertThat(DatabaseLocation.parse("jdbc:sqlite:" + ABSOLUTE.toUri()))
                .isEqualTo(new DatabaseLocation.Local(ABSOLUTE));
    }

    @Test
    public void testS3Schemes()
    {
        assertThat(DatabaseLocation.parse("jdbc:sqlite:s3://bucket/exports/app.db"))
                .isEqualTo(new DatabaseLocation.Remote(Location.of("s3://bucket/exports/app.db")));
        assertThat(DatabaseLocation.parse("jdbc:sqlite:s3a://bucket/app.db"))
                .isEqualTo(new DatabaseLocation.Remote(Location.of("s3a://bucket/app.db")));
        assertThat(DatabaseLocation.parse("jdbc:sqlite:s3n://bucket/app.db"))
                .isEqualTo(new DatabaseLocation.Remote(Location.of("s3n://bucket/app.db")));
    }

    @Test
    public void testUppercaseS3SchemeIsNormalized()
    {
        assertThat(DatabaseLocation.parse("jdbc:sqlite:S3://bucket/app.db"))
                .isEqualTo(new DatabaseLocation.Remote(Location.of("s3://bucket/app.db")));
    }

    @Test
    public void testRejectsWrongPrefix()
    {
        assertThatThrownBy(() -> DatabaseLocation.parse("jdbc:postgresql://host/db"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("connection-url must start with 'jdbc:sqlite:': jdbc:postgresql://host/db");
    }

    @Test
    public void testRejectsInMemoryDatabase()
    {
        assertThatThrownBy(() -> DatabaseLocation.parse("jdbc:sqlite::memory:"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("connection-url must name a database file, an in-memory database has nothing to read: jdbc:sqlite::memory:");
        assertThatThrownBy(() -> DatabaseLocation.parse("jdbc:sqlite:"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("connection-url must name a database file, an in-memory database has nothing to read: jdbc:sqlite:");
    }

    @Test
    public void testRejectsRelativePath()
    {
        assertThatThrownBy(() -> DatabaseLocation.parse("jdbc:sqlite:data/app.db"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("connection-url must be an absolute path, a relative path resolves differently on every node: jdbc:sqlite:data/app.db");
    }

    @Test
    public void testRejectsUrlParameters()
    {
        String url = "jdbc:sqlite:" + ABSOLUTE + "?open_mode=6";
        assertThatThrownBy(() -> DatabaseLocation.parse(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("connection-url must not carry parameters, the connector owns the open mode: " + url);
    }

    @Test
    public void testRejectsUnknownScheme()
    {
        assertThatThrownBy(() -> DatabaseLocation.parse("jdbc:sqlite:http://host/app.db"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("connection-url has unsupported scheme 'http', expected an absolute local path or an s3:// location: jdbc:sqlite:http://host/app.db");
    }
}
