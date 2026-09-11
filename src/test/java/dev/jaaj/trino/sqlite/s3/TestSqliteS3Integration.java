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

import com.google.common.collect.ImmutableMap;
import dev.jaaj.trino.sqlite.SqliteQueryRunner;
import dev.jaaj.trino.sqlite.SqliteTestDatabase;
import io.trino.testing.AbstractTestQueryFramework;
import io.trino.testing.QueryRunner;
import io.trino.testing.containers.Minio;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static io.trino.testing.containers.Minio.MINIO_REGION;
import static io.trino.testing.containers.Minio.MINIO_ROOT_PASSWORD;
import static io.trino.testing.containers.Minio.MINIO_ROOT_USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class TestSqliteS3Integration
        extends AbstractTestQueryFramework
{
    private static final String BUCKET = "sqlite";
    private static final String KEY = "exports/app.db";

    private Minio minio;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");

        minio = closeAfterClass(Minio.builder().build());
        minio.start();
        minio.createBucket(BUCKET);
        minio.writeFile(fixture("first"), BUCKET, KEY);

        return SqliteQueryRunner.create(ImmutableMap.<String, String>builder()
                .put("connection-url", "jdbc:sqlite:s3://" + BUCKET + "/" + KEY)
                .put("sqlite.s3.refresh-interval", "1s")
                .put("s3.endpoint", minio.getMinioAddress())
                .put("s3.region", MINIO_REGION)
                .put("s3.aws-access-key", MINIO_ROOT_USER)
                .put("s3.aws-secret-key", MINIO_ROOT_PASSWORD)
                .put("s3.path-style-access", "true")
                .buildOrThrow());
    }

    @Test
    public void testReadsTheObjectAndPicksUpANewVersion()
            throws Exception
    {
        assertQuery("SELECT v FROM t", "VALUES 'first'");

        minio.writeFile(fixture("second version"), BUCKET, KEY);
        // still within the refresh interval right after the first query, or already past it: both are valid,
        // what matters is that the new version shows up once the interval has elapsed
        Thread.sleep(1500);
        assertThat(computeActual("SELECT v FROM t").getOnlyValue()).isEqualTo("second version");
    }

    @Test
    public void testWritesAreRejected()
    {
        assertQueryFails("INSERT INTO t VALUES ('x')", "The SQLite connector is read-only");
    }

    private static byte[] fixture(String value)
            throws Exception
    {
        Path database = SqliteTestDatabase.createInTemporaryDirectory(
                "CREATE TABLE t (v TEXT)",
                "INSERT INTO t VALUES ('" + value + "')");
        return Files.readAllBytes(database);
    }
}
