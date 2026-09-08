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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

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

    private RemoteDatabaseFile newRemoteFile()
    {
        return new RemoteDatabaseFile(fileSystemFactory, LOCATION, cacheDirectory, INTERVAL, clock);
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
