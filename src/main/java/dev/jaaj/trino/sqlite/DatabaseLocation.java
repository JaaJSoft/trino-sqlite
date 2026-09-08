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
import io.trino.filesystem.Location;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

/**
 * The database named by {@code connection-url}. base-jdbc requires the property to start with
 * {@code jdbc:<driver>:}, so the S3 location travels inside it: {@code jdbc:sqlite:s3://bucket/key}.
 */
public sealed interface DatabaseLocation
{
    String JDBC_PREFIX = "jdbc:sqlite:";
    Set<String> S3_SCHEMES = ImmutableSet.of("s3", "s3a", "s3n");
    // at least two characters, so a Windows drive letter such as C: is not taken for a scheme
    Pattern SCHEME = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]+):");

    record Local(Path path)
            implements DatabaseLocation
    {
        public Local
        {
            requireNonNull(path, "path is null");
        }
    }

    record Remote(Location location)
            implements DatabaseLocation
    {
        public Remote
        {
            requireNonNull(location, "location is null");
        }
    }

    static DatabaseLocation parse(String connectionUrl)
    {
        requireNonNull(connectionUrl, "connectionUrl is null");
        if (!connectionUrl.startsWith(JDBC_PREFIX)) {
            throw new IllegalArgumentException("connection-url must start with '" + JDBC_PREFIX + "': " + connectionUrl);
        }
        String target = connectionUrl.substring(JDBC_PREFIX.length());
        if (target.isEmpty() || target.equals(":memory:")) {
            throw new IllegalArgumentException("connection-url must name a database file, an in-memory database has nothing to read: " + connectionUrl);
        }
        if (target.contains("?")) {
            throw new IllegalArgumentException("connection-url must not carry parameters, the connector owns the open mode: " + connectionUrl);
        }

        Optional<String> scheme = schemeOf(target);
        if (scheme.isPresent() && S3_SCHEMES.contains(scheme.orElseThrow())) {
            return new Remote(Location.of(target));
        }
        if (scheme.isPresent() && !scheme.orElseThrow().equals("file")) {
            throw new IllegalArgumentException("connection-url has unsupported scheme '" + scheme.orElseThrow() + "', expected an absolute local path or an s3:// location: " + connectionUrl);
        }

        Path path = scheme.isPresent() ? Path.of(URI.create(target)) : Path.of(target);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("connection-url must be an absolute path, a relative path resolves differently on every node: " + connectionUrl);
        }
        return new Local(path);
    }

    private static Optional<String> schemeOf(String target)
    {
        Matcher matcher = SCHEME.matcher(target);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(matcher.group(1).toLowerCase(ENGLISH));
    }
}
