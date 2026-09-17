package io.virinchi.yatra;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The schema checkpoint, automated.
 *
 * <p>The original hand-check was to eyeball this in MySQL Workbench: "Hibernate
 * auto-creates all tables correctly on startup — all foreign keys look right".
 * Eyeballing cannot be re-run after a refactor and cannot fail a build, so the
 * same three questions are asked here instead, through JDBC metadata (portable
 * — no MySQL-specific SQL, so a later move to MariaDB keeps working):
 *
 * <ol>
 *   <li>does every entity have a table?</li>
 *   <li>do the foreign keys match the modelled relationships?</li>
 *   <li>is the {@code (flight_id, seat_number)} unique key really enforced?</li>
 *   <li>is this schema Yatra's alone, and not the {@code test} database it used
 *       to share with the teacher's demo project?</li>
 *   <li>is money stored as {@code decimal(10,2)} rather than {@code double}?</li>
 *   <li>are the demo seed markers real boolean columns?</li>
 * </ol>
 *
 * <p>Needs a live database, like {@link YatraApplicationTests} already does —
 * {@code ddl-auto=validate} checks the entities against the committed
 * {@code db/schema.sql} on startup.
 */
@SpringBootTest
class EntitySchemaTest {

    /** `User` maps to `users` on purpose: `USER` is a MySQL keyword. */
    private static final Set<String> EXPECTED_TABLES = Set.of(
            "airline", "booking", "destination", "flight", "passenger",
            "payment", "seat", "ticket", "users");

    /**
     * The relationship list, written out as
     * {@code "<child>.<fk column> -> <parent>"} so a failure names the exact
     * arrow that broke.
     */
    private static final Set<String> EXPECTED_FOREIGN_KEYS = Set.of(
            "booking.user_id -> users",
            "booking.flight_id -> flight",
            "flight.airline_id -> airline",
            "flight.origin_id -> destination",
            "flight.destination_id -> destination",
            "seat.flight_id -> flight",
            "passenger.booking_id -> booking",
            "payment.booking_id -> booking",
            "ticket.booking_id -> booking");

    /**
     * Every column that holds money, written as
     * {@code "<table>.<column>"}.
     *
     * <p>`double` was the original choice (it matched the frontend's float
     * arithmetic) and it is wrong for money: a binary fraction cannot represent
     * `8299.99`, and the error propagates into the amount a customer is charged.
     * These four columns are the only ones, so a new money field must be added
     * here as well as to the entity — which is the point of asserting them.
     */
    private static final Set<String> EXPECTED_MONEY_COLUMNS = Set.of(
            "flight.fare",
            "booking.total_amount",
            "booking.product_amount",
            "payment.amount");

    /**
     * The demo seed markers (Phase 7), written as {@code "<table>.<column>"}.
     *
     * <p>{@code POST /api/admin/reset} deletes exactly the rows carrying one of these, so
     * the column has to be a real, non-nullable boolean: a nullable marker would make
     * "is this row seed data?" a three-valued question and a reset could leave a demo
     * booking behind (or take a real one). Destinations are deliberately absent — they
     * are reference data a reset keeps.
     */
    private static final Set<String> EXPECTED_SEED_MARKERS = Set.of(
            "airline.seeded",
            "flight.seeded",
            "users.seeded",
            "booking.seeded");

    @Autowired
    private DataSource dataSource;

    @Test
    void everyEntityHasATable() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            Set<String> tables = tableNames(conn);
            assertThat(tables)
                    .as("tables Hibernate should have created (ddl-auto=update)")
                    .containsAll(EXPECTED_TABLES);
        }
    }

    @Test
    void foreignKeysMatchTheRelationships() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            Set<String> foreignKeys = importedKeys(conn);

            assertThat(foreignKeys)
                    .as("child table . fk column -> parent table")
                    .containsAll(EXPECTED_FOREIGN_KEYS);
        }
    }

    @Test
    void theSeatUniqueKeyIsReallyEnforced() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();

            Set<String> columns = new HashSet<>();
            try (ResultSet rs = meta.getIndexInfo(conn.getCatalog(), null, "seat", true, false)) {
                while (rs.next()) {
                    if ("uk_seat_flight_number".equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                        columns.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                    }
                }
            }

            // The whole double-booking rule rests on this unique key: it is
            // what makes the second request for the same seat fail in the DB
            // instead of depending on a Java check that can race.
            assertThat(columns)
                    .as("unique key uk_seat_flight_number should cover both columns")
                    .containsExactlyInAnyOrder("flight_id", "seat_number");
        }
    }

    /**
     * This schema must hold Yatra's tables and nothing else.
     *
     * <p>TiDB Cloud's default {@code test} database was originally shared with the
     * teacher's SpringWeb demo project, and **both apps run
     * {@code ddl-auto=update}** — so pointing the URL back at {@code test} would
     * let either project silently alter the other's tables. That regression is
     * invisible in any other test, so it gets its own.
     *
     * <p>If this fails, the fix is the datasource URL (does it end in
     * {@code /yatra}?), **not** the entities.
     */
    @Test
    void thisSchemaIsYatraOnlyAndNotTheSharedTestSchema() throws Exception {
        Set<String> springWebTables = Set.of(
                "user_tbl", "vir_img_table", "img_table",
                "userclass", "addressclass", "phoneclass");

        try (Connection conn = dataSource.getConnection()) {
            assertThat(conn.getCatalog())
                    .as("Yatra must not be pointed at TiDB's shared `test` database")
                    .isNotEqualToIgnoringCase("test");

            assertThat(tableNames(conn))
                    .as("tables belonging to the teacher's SpringWeb demo project")
                    .doesNotContainAnyElementsOf(springWebTables);
        }
    }

    /**
     * Money must be exact decimal, not binary floating point.
     *
     * <p>Why this is a build check and not a code-review note: `ddl-auto=validate`
     * compares column <i>types</i>, not precision or scale, so a
     * `decimal(38,2)` would satisfy Hibernate just as well as the intended
     * `decimal(10,2)`. This asserts the three things that actually matter — type,
     * precision 10, scale 2 — and that the column stays {@code NOT NULL}.
     */
    @Test
    void moneyColumnsAreExactDecimalsNotDoubles() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();

            for (String money : EXPECTED_MONEY_COLUMNS) {
                String table = money.substring(0, money.indexOf('.'));
                String column = money.substring(money.indexOf('.') + 1);

                try (ResultSet rs = meta.getColumns(conn.getCatalog(), null, table, column)) {
                    assertThat(rs.next())
                            .as(money + " should exist")
                            .isTrue();

                    assertThat(rs.getString("TYPE_NAME").toLowerCase(Locale.ROOT))
                            .as(money + " must be an exact decimal, not a floating-point column")
                            .isEqualTo("decimal");
                    assertThat(rs.getInt("COLUMN_SIZE")).as(money + " precision").isEqualTo(10);
                    assertThat(rs.getInt("DECIMAL_DIGITS")).as(money + " scale").isEqualTo(2);
                    assertThat(rs.getInt("NULLABLE"))
                            .as(money + " must not be nullable")
                            .isEqualTo(DatabaseMetaData.columnNoNulls);
                }
            }
        }
    }

    /**
     * The seed marker must be a {@code bit(1) NOT NULL} column on all four tables.
     *
     * <p>Checked here rather than trusted because the marker is what makes the reset
     * precise: see {@link #EXPECTED_SEED_MARKERS}. Hibernate itself only validates the
     * type loosely, so a {@code varchar} holding "true"/"false" would satisfy
     * {@code ddl-auto=validate} while quietly breaking the reset's {@code where
     * s.seeded = true} semantics.
     */
    @Test
    void theSeedMarkersAreRealBooleanColumns() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();

            for (String marker : EXPECTED_SEED_MARKERS) {
                String table = marker.substring(0, marker.indexOf('.'));
                String column = marker.substring(marker.indexOf('.') + 1);

                try (ResultSet rs = meta.getColumns(conn.getCatalog(), null, table, column)) {
                    assertThat(rs.next()).as(marker + " should exist").isTrue();

                    assertThat(rs.getString("TYPE_NAME").toLowerCase(Locale.ROOT))
                            .as(marker + " must be a boolean column")
                            .isEqualTo("bit");
                    assertThat(rs.getInt("COLUMN_SIZE")).as(marker + " width").isEqualTo(1);
                    assertThat(rs.getInt("NULLABLE"))
                            .as(marker + " must not be nullable — the reset depends on it")
                            .isEqualTo(DatabaseMetaData.columnNoNulls);
                }
            }
        }
    }

    /* ---------- metadata helpers ---------- */

    private static Set<String> tableNames(Connection conn) throws Exception {
        Set<String> tables = new HashSet<>();
        try (ResultSet rs = conn.getMetaData()
                .getTables(conn.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return tables;
    }

    /** Reads each known table's imported keys into "child.column -> parent" arrows. */
    private static Set<String> importedKeys(Connection conn) throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        Set<String> keys = new HashSet<>();

        for (String table : EXPECTED_TABLES) {
            try (ResultSet rs = meta.getImportedKeys(conn.getCatalog(), null, table)) {
                while (rs.next()) {
                    String child = rs.getString("FKTABLE_NAME").toLowerCase(Locale.ROOT);
                    String column = rs.getString("FKCOLUMN_NAME").toLowerCase(Locale.ROOT);
                    String parent = rs.getString("PKTABLE_NAME").toLowerCase(Locale.ROOT);
                    keys.add(child + "." + column + " -> " + parent);
                }
            }
        }
        return keys;
    }
}
