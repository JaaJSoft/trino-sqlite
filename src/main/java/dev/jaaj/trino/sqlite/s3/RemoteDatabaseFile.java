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
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

import static io.trino.plugin.jdbc.JdbcErrorCode.JDBC_ERROR;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.util.Objects.requireNonNull;

/**
 * A local copy of a database object stored on a remote filesystem, refreshed at most once per
 * interval. The new version is downloaded under a fresh name and published before the old file
 * is unlinked. A copy retired by a refresh is not deleted until the refresh after that: a
 * connection that read {@link #current(ConnectorSession)} just before a refresh publishes a new
 * snapshot may still open the retired path. Two things keep such a copy readable: it survives
 * until the refresh after the one that retired it, and it is never deleted while a lease taken
 * by {@link #withCurrent(ConnectorSession, PathFunction)} is held on it. The lease is what
 * covers a zero refresh interval, where two refreshes can run back to back and the grace period
 * is empty. On Windows the delete is also refused while the file is still open and is retried on
 * a later refresh.
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
    // guarded by refreshLock: files retired by the previous refresh, safe to delete now
    private final List<Path> staleFiles = new ArrayList<>();
    // guarded by refreshLock: files retired by this refresh, deleted on the next one
    private final List<Path> pendingDeletion = new ArrayList<>();
    // number of in-flight opens per copy; an entry exists only while at least one lease is held
    private final ConcurrentMap<Path, Integer> leases = new ConcurrentHashMap<>();
    // guarded by refreshLock
    private Path directory;
    // guarded by refreshLock
    private boolean closed;
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

    /**
     * The path of the current copy, refreshing it first if the interval has elapsed. A caller that
     * is going to open the file must go through {@link #withCurrent(ConnectorSession, PathFunction)}
     * instead: a path returned here can be deleted by a refresh before the open reaches it.
     */
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
            if (closed) {
                throw new IllegalStateException("RemoteDatabaseFile for " + location + " is closed");
            }
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

    /**
     * Runs {@code action} on the current copy with a lease held on it, so the file cannot be
     * deleted by a concurrent refresh while the action is opening it.
     */
    public <T> T withCurrent(ConnectorSession session, PathFunction<T> action)
            throws SQLException
    {
        Path file = acquireLease(session);
        try {
            return action.apply(file);
        }
        finally {
            releaseLease(file);
        }
    }

    @FunctionalInterface
    public interface PathFunction<T>
    {
        T apply(Path file)
                throws SQLException;
    }

    private Path acquireLease(ConnectorSession session)
    {
        while (true) {
            Path file = current(session);
            leases.merge(file, 1, Integer::sum);
            Snapshot published = snapshot;
            if (published != null && published.file().equals(file)) {
                return file;
            }
            // the file was retired between the two reads; it may already be on its way out
            releaseLease(file);
        }
    }

    private void releaseLease(Path file)
    {
        leases.computeIfPresent(file, (_, count) -> count == 1 ? null : count - 1);
    }

    private boolean isDue(Snapshot current)
    {
        return !clock.instant().isBefore(current.checkedAt().plus(refreshInterval));
    }

    private Snapshot refresh(ConnectorSession session, Snapshot previous)
    {
        Instant now = clock.instant();
        // files this class retired last refresh have now outlived one full interval: safe to delete
        staleFiles.addAll(pendingDeletion);
        pendingDeletion.clear();
        try {
            TrinoInputFile inputFile = fileSystemFactory.create(session).newInputFile(location);
            long length = inputFile.length();
            Instant lastModified = inputFile.lastModified();
            if (previous != null && previous.length() == length && previous.lastModified().equals(lastModified)) {
                snapshot = new Snapshot(previous.file(), length, lastModified, now);
            }
            else {
                Path file = download(inputFile);
                snapshot = new Snapshot(file, length, lastModified, now);
                if (previous != null) {
                    pendingDeletion.add(previous.file());
                }
            }
        }
        catch (IOException | RuntimeException e) {
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
        try {
            try (InputStream stream = inputFile.newStream()) {
                Files.copy(stream, part);
            }
            Files.move(part, target, ATOMIC_MOVE);
            return target;
        }
        catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(part);
            }
            catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
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
            // A copy is deleted only once it is retired and carries no lease, and a lease is only
            // granted on a path still published after its count was raised. A deleter that read a
            // count of zero therefore cannot be racing a lease that read the path as published:
            // that lease's raise precedes its read of the retiring snapshot, which precedes the
            // write of that snapshot, which precedes this read of the counts.
            if (leases.containsKey(file)) {
                return false;
            }
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
            closed = true;
            Snapshot current = snapshot;
            snapshot = null;
            staleFiles.addAll(pendingDeletion);
            pendingDeletion.clear();
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
