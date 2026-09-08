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

import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

/**
 * SQLite column affinity, derived from the declared type name with the rules of
 * https://www.sqlite.org/datatype3.html section 3.1. The rules are ordered and the first
 * match wins, which is why FLOATING POINT has INTEGER affinity.
 */
public enum SqliteTypeAffinity
{
    INTEGER,
    TEXT,
    BLOB,
    REAL,
    NUMERIC;

    public static SqliteTypeAffinity fromDeclaredType(String declaredType)
    {
        requireNonNull(declaredType, "declaredType is null");
        String type = declaredType.toUpperCase(ENGLISH);
        if (type.contains("INT")) {
            return INTEGER;
        }
        if (type.contains("CHAR") || type.contains("CLOB") || type.contains("TEXT")) {
            return TEXT;
        }
        if (type.isBlank() || type.contains("BLOB")) {
            return BLOB;
        }
        if (type.contains("REAL") || type.contains("FLOA") || type.contains("DOUB")) {
            return REAL;
        }
        return NUMERIC;
    }
}
