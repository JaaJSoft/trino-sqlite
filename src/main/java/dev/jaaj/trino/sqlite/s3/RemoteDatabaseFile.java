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
package dev.jaaj.trino.sqlite.s3;

import io.airlift.log.Logger;
import io.trino.filesystem.Location;
import io.trino.filesystem.TrinoFileSystemFactory;
import io.trino.filesystem.TrinoInputFile;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ConnectorSession;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import static io.trino.plugin.jdbc.JdbcErrorCode.JDBC_ERROR;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.util.Objects.requireNonNull;

/**
 * A local copy of a database object stored on a remote filesystem, refreshed at most once per
 * interval. A refresh never touches the file a connection may have open: the new version is
 * downloaded under a fresh name, published, and the old file is deleted afterwards.
 */
public final class RemoteDatabaseFile
        implements Closeable
{
    private static final Logger log = Logger.get(RemoteDatabaseFile.class);

    private final TrinoFileSystemFactory fileSystemFactory;
    private final Location location;
    private final Path cacheDirectory;
    private final Duration refreshInterval;
    private final Clock clock;

    private final ReentrantLock refreshLock = new ReentrantLock();
    // guarded by refreshLock
    private final List<Path> staleFiles = new ArrayList<>();
    // guarded by refreshLock
    private Path directory;
    private volatile Snapshot snapshot;

    private record Snapshot(Path file, long length, Instant lastModified, Instant checkedAt) {}

    public RemoteDatabaseFile(TrinoFileSystemFactory fileSystemFactory, Location location, Path cacheDirectory, Duration refreshInterval, Clock clock)
    {
        this.fileSystemFactory = requireNonNull(fileSystemFactory, "fileSystemFactory is null");
        this.location = requireNonNull(location, "location is null");
        this.cacheDirectory = requireNonNull(cacheDirectory, "cacheDirectory is null");
        this.refreshInterval = requireNonNull(refreshInterval, "refreshInterval is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public Path current(ConnectorSession session)
    {
        Snapshot current = snapshot;
        if (current != null && !isDue(current)) {
            return current.file();
        }
        if (current != null) {
            // another thread is refreshing: keep serving the copy rather than queue behind the download
            if (!refreshLock.tryLock()) {
                return current.file();
            }
        }
        else {
            refreshLock.lock();
        }
        try {
            current = snapshot;
            if (current != null && !isDue(current)) {
                return current.file();
            }
            return refresh(session, current).file();
        }
        finally {
            refreshLock.unlock();
        }
    }

    private boolean isDue(Snapshot current)
    {
        return !clock.instant().isBefore(current.checkedAt().plus(refreshInterval));
    }

    private Snapshot refresh(ConnectorSession session, Snapshot previous)
    {
        Instant now = clock.instant();
        TrinoInputFile inputFile = fileSystemFactory.create(session).newInputFile(location);
        try {
            long length = inputFile.length();
            Instant lastModified = inputFile.lastModified();
            if (previous != null && previous.length() == length && previous.lastModified().equals(lastModified)) {
                snapshot = new Snapshot(previous.file(), length, lastModified, now);
            }
            else {
                Path file = download(inputFile);
                snapshot = new Snapshot(file, length, lastModified, now);
                if (previous != null) {
                    staleFiles.add(previous.file());
                }
            }
        }
        catch (IOException e) {
            if (previous == null) {
                throw new TrinoException(JDBC_ERROR, "Failed to fetch SQLite database from " + location, e);
            }
            log.warn(e, "Failed to check SQLite database at %s, serving the existing copy", location);
            snapshot = new Snapshot(previous.file(), previous.length(), previous.lastModified(), now);
        }
        deleteStaleFiles();
        return snapshot;
    }

    private Path download(TrinoInputFile inputFile)
            throws IOException
    {
        Path target = directory().resolve(UUID.randomUUID() + ".db");
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try (InputStream stream = inputFile.newStream()) {
            Files.copy(stream, part);
        }
        Files.move(part, target, ATOMIC_MOVE);
        return target;
    }

    private Path directory()
            throws IOException
    {
        if (directory == null) {
            Files.createDirectories(cacheDirectory);
            directory = Files.createTempDirectory(cacheDirectory, "sqlite-");
        }
        return directory;
    }

    private void deleteStaleFiles()
    {
        staleFiles.removeIf(file -> {
            try {
                Files.deleteIfExists(file);
                return true;
            }
            catch (IOException e) {
                // Windows refuses to delete a file another connection still has open; try again next time
                log.debug(e, "Could not delete stale copy %s, will retry", file);
                return false;
            }
        });
    }

    @Override
    public void close()
    {
        refreshLock.lock();
        try {
            Snapshot current = snapshot;
            snapshot = null;
            if (current != null) {
                staleFiles.add(current.file());
            }
            deleteStaleFiles();
            if (directory != null && staleFiles.isEmpty()) {
                Files.deleteIfExists(directory);
                directory = null;
            }
        }
        catch (IOException e) {
            log.warn(e, "Could not remove cache directory %s", directory);
        }
        finally {
            refreshLock.unlock();
        }
    }
}
