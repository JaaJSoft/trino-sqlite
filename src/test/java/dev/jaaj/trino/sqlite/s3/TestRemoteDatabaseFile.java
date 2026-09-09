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

import io.trino.filesystem.Location;
import io.trino.filesystem.TrinoFileSystem;
import io.trino.filesystem.TrinoFileSystemFactory;
import io.trino.filesystem.memory.MemoryFileSystemFactory;
import io.trino.spi.TrinoException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import static com.google.common.io.MoreFiles.deleteRecursively;
import static com.google.common.io.RecursiveDeleteOption.ALLOW_INSECURE;
import static io.trino.testing.TestingConnectorSession.SESSION;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestRemoteDatabaseFile
{
    private static final Location LOCATION = Location.of("memory:///exports/app.db");
    private static final Duration INTERVAL = Duration.ofMinutes(10);

    private TrinoFileSystemFactory fileSystemFactory;
    private TrinoFileSystem fileSystem;
    private MutableClock clock;
    private Path cacheDirectory;

    @BeforeEach
    public void setUp()
            throws IOException
    {
        fileSystemFactory = new MemoryFileSystemFactory();
        fileSystem = fileSystemFactory.create(SESSION.getIdentity());
        clock = new MutableClock(Instant.parse("2026-09-08T10:00:00Z"));
        cacheDirectory = Files.createTempDirectory("trino-sqlite-cache");
    }

    @AfterEach
    public void tearDown()
            throws IOException
    {
        deleteRecursively(cacheDirectory, ALLOW_INSECURE);
    }

    @Test
    public void testFirstCallDownloadsTheFile()
            throws Exception
    {
        upload("version one");
        try (RemoteDatabaseFile remote = newRemoteFile()) {
            Path copy = remote.current(SESSION);
            assertThat(copy).exists();
            assertThat(copy.getParent().getParent()).isEqualTo(cacheDirectory);
            assertThat(Files.readString(copy)).isEqualTo("version one");
        }
    }

    @Test
    public void testWithinIntervalNoCheckIsMade()
            throws Exception
    {
        upload("version one");
        try (RemoteDatabaseFile remote = newRemoteFile()) {
            Path copy = remote.current(SESSION);
            upload("version two");
            clock.advance(INTERVAL.minusSeconds(1));
            assertThat(remote.current(SESSION)).isEqualTo(copy);
            assertThat(Files.readString(copy)).isEqualTo("version one");
        }
    }

    @Test
    public void testUnchangedObjectAfterIntervalKeepsTheCopy()
            throws Exception
    {
        upload("version one");
        try (RemoteDatabaseFile remote = newRemoteFile()) {
            Path copy = remote.current(SESSION);
            clock.advance(INTERVAL);
            assertThat(remote.current(SESSION)).isEqualTo(copy);
        }
    }

    @Test
    public void testChangedLengthAfterIntervalDownloadsAgain()
            throws Exception
    {
        upload("version one");
        try (RemoteDatabaseFile remote = newRemoteFile()) {
            Path first = remote.current(SESSION);
            upload("version two, longer");
            clock.advance(INTERVAL);
            Path second = remote.current(SESSION);
            assertThat(second).isNotEqualTo(first);
            assertThat(Files.readString(second)).isEqualTo("version two, longer");
            assertThat(first).exists();
        }
    }

    @Test
    public void testRetiredCopySurvivesOneRefresh()
            throws Exception
    {
        upload("version one");
        RemoteDatabaseFile remote = newRemoteFile();
        Path first = remote.current(SESSION);
        upload("version two, longer");
        clock.advance(INTERVAL);
        Path second = remote.current(SESSION);
        assertThat(second).isNotEqualTo(first);
        assertThat(first).exists();

        clock.advance(INTERVAL);
        Path third = remote.current(SESSION);
        assertThat(third).isEqualTo(second);
        assertThat(first).doesNotExist();

        remote.close();
        assertThat(second).doesNotExist();
    }

    @Test
    public void testLeasedCopySurvivesRefreshes()
            throws Exception
    {
        upload("version one");
        try (RemoteDatabaseFile remote = newRemoteFile(Duration.ZERO)) {
            Path leased = remote.withCurrent(SESSION, file -> {
                try {
                    upload("version two, longer");
                    // the interval is zero, so both calls refresh: the first publishes version two
                    // and retires the leased copy, the second is the one that would delete it
                    remote.current(SESSION);
                    remote.current(SESSION);
                    assertThat(file).exists();
                    assertThat(Files.readString(file)).isEqualTo("version one");
                    return file;
                }
                catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            remote.current(SESSION);
            assertThat(leased).doesNotExist();
        }
    }

    @Test
    public void testLeaseIsReleasedOnActionFailure()
            throws Exception
    {
        upload("version one");
        try (RemoteDatabaseFile remote = newRemoteFile()) {
            Path first = remote.current(SESSION);
            assertThatThrownBy(() -> remote.withCurrent(SESSION, _ -> {
                throw new SQLException("the connection could not be opened");
            }))
                    .isInstanceOf(SQLException.class)
                    .hasMessage("the connection could not be opened");

            upload("version two, longer");
            clock.advance(INTERVAL);
            assertThat(remote.current(SESSION)).isNotEqualTo(first);
            clock.advance(INTERVAL);
            remote.current(SESSION);
            assertThat(first).doesNotExist();
        }
    }

    @Test
    public void testChangedModificationTimeWithSameLengthDownloadsAgain()
            throws Exception
    {
        upload("version one");
        try (RemoteDatabaseFile remote = newRemoteFile()) {
            Path first = remote.current(SESSION);
            Instant firstModified = fileSystem.newInputFile(LOCATION).lastModified();
            // the memory filesystem stamps blobs with Instant.now(); make sure the second write is later
            while (!Instant.now().isAfter(firstModified)) {
                Thread.onSpinWait();
            }
            upload("version two");
            clock.advance(INTERVAL);
            Path second = remote.current(SESSION);
            assertThat(second).isNotEqualTo(first);
            assertThat(Files.readString(second)).isEqualTo("version two");
        }
    }

    @Test
    public void testUnreachableObjectWithCopyServesTheCopy()
            throws Exception
    {
        upload("version one");
        try (RemoteDatabaseFile remote = newRemoteFile()) {
            Path copy = remote.current(SESSION);
            fileSystem.deleteFile(LOCATION);
            clock.advance(INTERVAL);
            assertThat(remote.current(SESSION)).isEqualTo(copy);
            // the failed check counts as a check: no retry until the next interval
            upload("version two, longer");
            clock.advance(INTERVAL.minusSeconds(1));
            assertThat(remote.current(SESSION)).isEqualTo(copy);
            clock.advance(Duration.ofSeconds(1));
            assertThat(Files.readString(remote.current(SESSION))).isEqualTo("version two, longer");
        }
    }

    @Test
    public void testUnreachableObjectWithoutCopyFails()
    {
        try (RemoteDatabaseFile remote = newRemoteFile()) {
            assertThatThrownBy(() -> remote.current(SESSION))
                    .isInstanceOf(TrinoException.class)
                    .hasMessage("Failed to fetch SQLite database from memory:///exports/app.db");
        }
    }

    @Test
    public void testCloseRemovesTheCopy()
            throws Exception
    {
        upload("version one");
        RemoteDatabaseFile remote = newRemoteFile();
        Path copy = remote.current(SESSION);
        remote.close();
        assertThat(copy).doesNotExist();
        assertThat(copy.getParent()).doesNotExist();
    }

    @Test
    public void testCurrentAfterCloseFails()
            throws Exception
    {
        upload("version one");
        RemoteDatabaseFile remote = newRemoteFile();
        remote.current(SESSION);
        remote.close();
        assertThatThrownBy(() -> remote.current(SESSION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RemoteDatabaseFile for memory:///exports/app.db is closed");
        try (Stream<Path> entries = Files.list(cacheDirectory)) {
            assertThat(entries).isEmpty();
        }
    }

    private RemoteDatabaseFile newRemoteFile()
    {
        return newRemoteFile(INTERVAL);
    }

    private RemoteDatabaseFile newRemoteFile(Duration refreshInterval)
    {
        return new RemoteDatabaseFile(fileSystemFactory, LOCATION, cacheDirectory, refreshInterval, clock);
    }

    private void upload(String content)
            throws IOException
    {
        fileSystem.newOutputFile(LOCATION).createOrOverwrite(content.getBytes(UTF_8));
    }

    private static class MutableClock
            extends Clock
    {
        private Instant now;

        MutableClock(Instant now)
        {
            this.now = now;
        }

        void advance(Duration duration)
        {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone()
        {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant()
        {
            return now;
        }
    }
}
