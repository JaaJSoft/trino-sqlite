# Trino-sqlite

[Trino](https://trino.io) connector reading SQLite database files, on the local filesystem
or stored as an object in S3. One catalog is one file. The connector is read-only.

## Configuration

```properties
connector.name=sqlite

# a local file, opened in place
connection-url=jdbc:sqlite:/data/app.db
```

```properties
connector.name=sqlite

# an S3 object, downloaded to a local cache and refreshed on an interval
connection-url=jdbc:sqlite:s3://my-bucket/exports/app.db
sqlite.s3.refresh-interval=15m
sqlite.s3.cache-directory=/var/cache/trino-sqlite
s3.region=eu-west-3
s3.aws-access-key=...
s3.aws-secret-key=...
```

| Property | Default | Description |
| --- | --- | --- |
| `connection-url` | required | `jdbc:sqlite:` followed by an absolute local path, a `file:` URI or an `s3://`, `s3a://` or `s3n://` location |
| `sqlite.s3.refresh-interval` | `1h` | How long a downloaded copy is served before S3 is checked for a newer version. `0s` checks on every connection. |
| `sqlite.s3.cache-directory` | `${java.io.tmpdir}/trino-sqlite` | Parent of the per-catalog directory holding the downloaded copy |
| `s3.*` | | The standard Trino S3 properties: `s3.endpoint`, `s3.region`, `s3.path-style-access`, credentials, IAM roles |

So `jdbc:sqlite:/data/app.db`, `jdbc:sqlite:file:///data/app.db` and
`jdbc:sqlite:s3a://my-bucket/exports/app.db` all name a database, while relative paths (they
resolve against each node's working directory), `:memory:` and URL parameters are rejected:
the connector owns the open mode.

The usual base-jdbc properties apply, in particular `case-insensitive-name-matching`,
`metadata.cache-ttl`, `unsupported-type-handling` and `jdbc-types-mapped-to-varchar`.

## Schema and tables

The catalog exposes one schema, `main`, with the tables and views of the file. Internal
`sqlite_*` tables are hidden. Table names are lowercased by base-jdbc's default identifier
mapping, so a table declared `MixedCase` is listed and queried as `mixedcase`. Set
`case-insensitive-name-matching=true` if the file has table names containing upper-case
letters and they should be reachable under their original case.

## Type mapping

SQLite stores values dynamically; the declared column type only decides the column's
affinity. The connector applies SQLite's affinity rules to the declared type name:

| Affinity | Declared type contains | Trino type |
| --- | --- | --- |
| INTEGER | `INT` | `bigint` |
| TEXT | `CHAR`, `CLOB`, `TEXT` | `varchar` |
| BLOB | `BLOB`, or no declared type | `varbinary` |
| REAL | `REAL`, `FLOA`, `DOUB` | `double` |
| NUMERIC | anything else | unsupported |

Rules apply in that order: `FLOATING POINT` contains `INT`, so it is a `bigint`. A column
declared `BOOLEAN` or `BOOL` maps to `boolean`.

Unsupported columns (`NUMERIC`, `DECIMAL`, `DATE`, `DATETIME`, `TIMESTAMP`) are hidden by
default and exposed as `varchar` with `unsupported-type-handling=CONVERT_TO_VARCHAR`. SQLite
has no date type and applications store dates as ISO text, unix epochs or julian days, so no
automatic conversion is attempted.

A value whose storage class differs from the column's affinity is coerced by SQLite, not by
the connector: text that is not a number in an `INTEGER` column reads as `0`.

A predicate pushed into SQLite compares against the value as stored, not against the value
Trino reads back: a text value in an `INTEGER` column reads as `0`, but `WHERE n = 0` pushed
down never matches it, because SQLite compares the literal `0` against the stored text by
storage class. Users with mixed storage classes in a column should filter on an expression
Trino evaluates itself, for example `WHERE CAST(n AS varchar) = '0'`.

## Pushdown

Predicates on `bigint`, `double` and `boolean` columns and `LIMIT` are pushed into SQLite.
Predicates on text and binary columns are evaluated by Trino, because a text column may carry
`COLLATE NOCASE` and SQLite would then match differently than Trino. Aggregations, joins and
`ORDER BY ... LIMIT` are not pushed down.

## Query pass-through

```sql
SELECT * FROM TABLE(sqlite.system.query(query => 'SELECT date(''now'')'));
```

(`sqlite` is the catalog name as configured; substitute whatever name the `connector.name=sqlite`
catalog was given.) The query is executed by SQLite itself, as a subquery over a read-only
connection, so it is useful for SQLite-specific SQL such as date functions or the `json1`
extension that the connector does not otherwise expose, but it cannot write: the connection is
read-only regardless of the statement passed to it.

## S3

The object is downloaded on the first connection into the cache directory. After the refresh
interval, the object's size and modification time are compared with the copy and a new
version is downloaded if either changed. Queries in flight keep the file they opened. If S3
cannot be reached and a copy exists, the copy is served and a warning is logged; without a
copy the query fails.

A query whose planning and execution straddle a refresh may see two versions of the file.
This produces an SQL error at worst, never a wrong result.

The object is downloaded using the S3 credentials of the session that happens to trigger the
download, and the resulting local copy is then served to every session of the catalog. Per-user
S3 authorization (for example a security mapping keyed on the querying user) therefore does not
apply to the catalog's data access; use Trino's access control on the catalog itself to restrict
who can query it.

When the connector is closed, its cache directory is removed on a best-effort basis: on Windows
a copy that a connection still has open cannot be deleted, and closing the connector does not
retry the deletion afterward.

## Read-only

Every connection is opened read-only at the SQLite level. DDL, `INSERT` and
`CREATE TABLE ... AS SELECT` are rejected by the connector itself with "The SQLite connector
is read-only". `DELETE` and `UPDATE` are rejected by Trino's engine-level row-modification
check with "This connector does not support modifying table rows".

## Compatibility

Trino 483, Java 25.

## Installation

```bash
./mvnw clean package
```

Copy the contents of `target/trino-sqlite-<version>/` into `<trino>/plugin/sqlite/`, then
restart the server.

## Status

v1 reads one file per catalog. Multi-file catalogs, writes and temporal type parsing are
possible follow-ups.

## License

Apache License 2.0
