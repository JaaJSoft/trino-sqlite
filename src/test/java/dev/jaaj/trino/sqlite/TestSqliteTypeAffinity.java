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

import org.junit.jupiter.api.Test;

import static dev.jaaj.trino.sqlite.SqliteTypeAffinity.BLOB;
import static dev.jaaj.trino.sqlite.SqliteTypeAffinity.INTEGER;
import static dev.jaaj.trino.sqlite.SqliteTypeAffinity.NUMERIC;
import static dev.jaaj.trino.sqlite.SqliteTypeAffinity.REAL;
import static dev.jaaj.trino.sqlite.SqliteTypeAffinity.TEXT;
import static dev.jaaj.trino.sqlite.SqliteTypeAffinity.fromDeclaredType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestSqliteTypeAffinity
{
    @Test
    public void testIntegerAffinity()
    {
        assertThat(fromDeclaredType("INTEGER")).isEqualTo(INTEGER);
        assertThat(fromDeclaredType("int")).isEqualTo(INTEGER);
        assertThat(fromDeclaredType("int8")).isEqualTo(INTEGER);
        assertThat(fromDeclaredType("BIGINT")).isEqualTo(INTEGER);
        assertThat(fromDeclaredType("UNSIGNED BIG INT")).isEqualTo(INTEGER);
        // SQLite's own documented surprises: rule 1 wins whenever INT appears anywhere
        assertThat(fromDeclaredType("FLOATING POINT")).isEqualTo(INTEGER);
        assertThat(fromDeclaredType("POINT")).isEqualTo(INTEGER);
    }

    @Test
    public void testTextAffinity()
    {
        assertThat(fromDeclaredType("TEXT")).isEqualTo(TEXT);
        assertThat(fromDeclaredType("VARCHAR(20)")).isEqualTo(TEXT);
        assertThat(fromDeclaredType("varchar")).isEqualTo(TEXT);
        assertThat(fromDeclaredType("CHARACTER VARYING")).isEqualTo(TEXT);
        assertThat(fromDeclaredType("NCHAR(55)")).isEqualTo(TEXT);
        assertThat(fromDeclaredType("CLOB")).isEqualTo(TEXT);
    }

    @Test
    public void testBlobAffinity()
    {
        assertThat(fromDeclaredType("BLOB")).isEqualTo(BLOB);
        assertThat(fromDeclaredType("blob")).isEqualTo(BLOB);
        // a column declared without a type, as in CREATE TABLE t(a, b)
        assertThat(fromDeclaredType("")).isEqualTo(BLOB);
        assertThat(fromDeclaredType("   ")).isEqualTo(BLOB);
    }

    @Test
    public void testRealAffinity()
    {
        assertThat(fromDeclaredType("REAL")).isEqualTo(REAL);
        assertThat(fromDeclaredType("FLOAT")).isEqualTo(REAL);
        assertThat(fromDeclaredType("DOUBLE")).isEqualTo(REAL);
        assertThat(fromDeclaredType("DOUBLE PRECISION")).isEqualTo(REAL);
    }

    @Test
    public void testNumericAffinity()
    {
        assertThat(fromDeclaredType("NUMERIC")).isEqualTo(NUMERIC);
        assertThat(fromDeclaredType("DECIMAL(10,2)")).isEqualTo(NUMERIC);
        assertThat(fromDeclaredType("BOOLEAN")).isEqualTo(NUMERIC);
        assertThat(fromDeclaredType("DATE")).isEqualTo(NUMERIC);
        assertThat(fromDeclaredType("DATETIME")).isEqualTo(NUMERIC);
        assertThat(fromDeclaredType("TIMESTAMP")).isEqualTo(NUMERIC);
        // no rule matches, so rule 5 applies
        assertThat(fromDeclaredType("STRING")).isEqualTo(NUMERIC);
    }

    @Test
    public void testNullDeclaredTypeIsRejected()
    {
        assertThatThrownBy(() -> fromDeclaredType(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("declaredType is null");
    }
}
