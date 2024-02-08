/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.database.sqlite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.os.SystemClock;
import android.test.AndroidTestCase;
import android.util.Log;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class SQLiteRawStatementTest {

    private static final String TAG = "SQLiteRawStatementTest";

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();

    private SQLiteDatabase mDatabase;
    private File mDatabaseFile;
    private static final String DATABASE_FILE_NAME = "database_test.db";

    @Before
    public void setUp() throws Exception {
        assertNotNull(mContext);
        mContext.deleteDatabase(DATABASE_FILE_NAME);
        mDatabaseFile = mContext.getDatabasePath(DATABASE_FILE_NAME);
        mDatabaseFile.getParentFile().mkdirs(); // directory may not exist
        mDatabase = SQLiteDatabase.openOrCreateDatabase(mDatabaseFile, null);
        assertNotNull(mDatabase);
    }

    @After
    public void tearDown() throws Exception {
        closeAndDeleteDatabase();
    }

    private void closeAndDeleteDatabase() {
        mDatabase.close();
        SQLiteDatabase.deleteDatabase(mDatabaseFile);
    }

    /**
     * Create a database with a single table with one column and two rows.  Exceptions are allowed
     * to percolate out.
     */
    private void createSimpleDatabase() {
        // Create the t1 table and put some data in it.
        mDatabase.beginTransaction();
        try {
            mDatabase.execSQL("CREATE TABLE t1 (i int);");
            mDatabase.execSQL("INSERT INTO t1 (i) VALUES (2)");
            mDatabase.execSQL("INSERT INTO t1 (i) VALUES (3)");
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }
    }

    /**
     * A simple insert for the simple database.
     */
    private String createSimpleInsert() {
        return "INSERT INTO t1 (i) VALUES (1)";
    }

    /**
     * Create a database with one table with three columns.
     */
    private void createComplexDatabase() {
        mDatabase.beginTransaction();
        try {
            // Column "l" is used to test the long variants.  The underlying sqlite type is int,
            // which is the same as a java long.
            mDatabase.execSQL("CREATE TABLE t1 (i int, d double, t text, l int);");
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }
    }

    /**
     * A three-value insert for the complex database.
     */
    private String createComplexInsert() {
        return "INSERT INTO t1 (i, d, t, l) VALUES (?1, ?2, ?3, ?4)";
    }

    /**
     * Create a database with one table with 12 columns.
     */
    private void createWideDatabase() {
        StringBuilder sp = new StringBuilder();
        sp.append(String.format("i%d int", 0));
        for (int i = 1; i < 12; i++) {
            sp.append(String.format(", i%d int", i));
        }
        mDatabase.beginTransaction();
        try {
            mDatabase.execSQL("CREATE TABLE t1 (" + sp.toString() + ")");
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }
    }

    /**
     * A 12-value insert for the wide database.
     */
    private String createWideInsert() {
        StringBuilder sp = new StringBuilder();
        sp.append("INSERT INTO t1 (i0");
        for (int i = 1; i < 12; i++) {
            sp.append(String.format(", i%d", i));
        }
        sp.append(") VALUES (?");
        for (int i = 1; i < 12; i++) {
            sp.append(", ?");
        }
        sp.append(")");
        return sp.toString();
    }

    @Test
    public void testSingleTransaction() {
        createSimpleDatabase();

        mDatabase.beginTransaction();
        try {
            int found = 0;
            try (SQLiteRawStatement s = mDatabase.createRawStatement("SELECT i from t1")) {
                for (int i = 0; s.step() && i < 5; i++) {
                    found++;
                }
            }
            assertEquals(2, found);
            long r = DatabaseUtils.longForQuery(mDatabase, "SELECT count(*) from t1", null);
            assertEquals(2, r);
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }
    }

    @Test
    public void testMultipleTransactions() {
        createSimpleDatabase();

        mDatabase.beginTransaction();
        try {
            final String query = "SELECT i from t1";
            try (SQLiteRawStatement s = mDatabase.createRawStatement(query);
                 SQLiteRawStatement t = mDatabase.createRawStatement(query)) {
                int found = 0;
                for (int i = 0; s.step() && i < 5; i++) {
                    boolean r = t.step();
                    assertTrue(r);
                    assertEquals(t.getColumnInt(0), s.getColumnInt(0));
                    found++;
                }
                assertFalse(t.step());
                assertEquals(2, found);
                long r = DatabaseUtils.longForQuery(mDatabase, "SELECT count(*) from t1", null);
                assertEquals(2, r);
            }
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }
    }

    @Test
    public void testInsert() {
        createComplexDatabase();

        // Populate the database
        mDatabase.beginTransaction();
        try {
            try (SQLiteRawStatement s = mDatabase.createRawStatement(createComplexInsert())) {
                for (int row = 0; row < 9; row++) {
                    int vi = row * 3;
                    double vd = row * 2.5;
                    String vt = String.format("text%02dvalue", row);
                    long vl = Long.MAX_VALUE - row;
                    s.bindInt(1, vi);
                    s.bindDouble(2, vd);
                    s.bindText(3, vt);
                    s.bindLong(4, vl);
                    boolean r = s.step();
                    // No row is returned by this query.
                    assertFalse(r);
                    s.reset();
                }
                // The last row has a null double, null text, and null long.
                s.bindInt(1, 20);
                s.bindNull(2);
                s.bindNull(3);
                s.bindNull(4);
                assertFalse(s.step());
                s.reset();
            }
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }

        // Verify that 10 rows have been inserted.
        mDatabase.beginTransaction();
        try {
            final String query = "SELECT COUNT(*) FROM t1";
            try (SQLiteRawStatement s = mDatabase.createRawStatement(query)) {
                boolean r = s.step();
                assertTrue(r);
                int rows = s.getColumnInt(0);
                assertEquals(10, rows);
            }
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }

        // Verify that the element created with row == 3 is correct.
        mDatabase.beginTransactionReadOnly();
        try {
            final String query = "SELECT i, d, t, l FROM t1 WHERE t = 'text03value'";
            try (SQLiteRawStatement s = mDatabase.createRawStatement(query)) {
                assertTrue(s.step());
                assertEquals(4, s.getResultColumnCount());
                int vi = s.getColumnInt(0);
                double vd = s.getColumnDouble(1);
                String vt = s.getColumnText(2);
                long vl = s.getColumnLong(3);
                // The query extracted the third generated row.
                final int row = 3;
                assertEquals(3 * row, vi);
                assertEquals(2.5 * row, vd, 0.1);
                assertEquals("text03value", vt);
                assertEquals(Long.MAX_VALUE - row, vl);

                // Verify the column types.  Remember that sqlite integers are the same as Java
                // long, so the integer and long columns have type INTEGER.
                assertEquals(SQLiteRawStatement.SQLITE_DATA_TYPE_INTEGER, s.getColumnType(0));
                assertEquals(SQLiteRawStatement.SQLITE_DATA_TYPE_FLOAT, s.getColumnType(1));
                assertEquals(SQLiteRawStatement.SQLITE_DATA_TYPE_TEXT, s.getColumnType(2));
                assertEquals(SQLiteRawStatement.SQLITE_DATA_TYPE_INTEGER, s.getColumnType(3));

                // No more rows.
                assertFalse(s.step());
            }
        } finally {
            mDatabase.endTransaction();
        }

        // Verify that null columns are returned properly.
        mDatabase.beginTransactionReadOnly();
        try {
            final String query = "SELECT i, d, t, l FROM t1 WHERE i == 20";
            try (SQLiteRawStatement s = mDatabase.createRawStatement(query)) {
                assertTrue(s.step());
                assertEquals(4, s.getResultColumnCount());
                assertEquals(20, s.getColumnInt(0));
                assertEquals(0.0, s.getColumnDouble(1), 0.01);
                assertEquals(null, s.getColumnText(2));
                assertEquals(0, s.getColumnLong(3));

                // Verify the column types.
                assertEquals(SQLiteRawStatement.SQLITE_DATA_TYPE_INTEGER, s.getColumnType(0));
                assertEquals(SQLiteRawStatement.SQLITE_DATA_TYPE_NULL, s.getColumnType(1));
                assertEquals(SQLiteRawStatement.SQLITE_DATA_TYPE_NULL, s.getColumnType(2));
                assertEquals(SQLiteRawStatement.SQLITE_DATA_TYPE_NULL, s.getColumnType(3));

                // No more rows.
                assertFalse(s.step());
            }
        } finally {
            mDatabase.endTransaction();
        }
    }

    @Test
    public void testRequiresTransaction() {
        createSimpleDatabase();

        // Verify that a statement cannot be created outside a transaction.
        try {
            try (SQLiteRawStatement s = mDatabase.createRawStatement(createSimpleInsert())) {
                fail("created a statement outside a transaction");
                // Suppress warnings about unused variables.
                s.close();
            }
        } catch (IllegalStateException e) {
            // There is more than one source of this exception.  Scrape the message and look for
            // "no current transaction", which comes from
            // {@link SQLiteSession.throwIfNoTransaction}.
            if (!e.getMessage().contains("no current transaction")) {
                fail("unexpected IllegalStateException, got " + e);
            }
        } catch (AssertionError e) {
            // Pass on the fail from the try-block before the generic catch below can see it.
            throw e;
        } catch (Throwable e) {
            fail("expected IllegalStateException, got " + e);
        }
    }

    // Test a variety of conditions under which a SQLiteRawStatement should close.  These methods
    // deliberately do not use try/finally blocks on the statement to make sure a specific
    // behavior is being tested.
    @Test
    public void testAutoClose() {
        createSimpleDatabase();

        SQLiteRawStatement s;

        // Verify that calling close(), closes the statement.
        mDatabase.beginTransaction();
        try {
            s = mDatabase.createRawStatement(createSimpleInsert());
            assertTrue(s.isOpen());
            s.close();
            assertFalse(s.isOpen());
        } finally {
            mDatabase.endTransaction();
        }

        // Verify that a statement is closed automatically at the end of a try-with-resource
        // block.
        mDatabase.beginTransaction();
        try {
            try (var t = mDatabase.createRawStatement(createSimpleInsert())) {
                // Save a reference to t for examination ouside the try-with-resource block.
                s = t;
                assertTrue(s.isOpen());
            }
            assertFalse(s.isOpen());
        } finally {
            mDatabase.endTransaction();
        }
        assertFalse(s.isOpen());


        // Verify that a statement is closed implicitly when the transaction is marked
        // successful.
        mDatabase.beginTransaction();
        try {
            s = mDatabase.createRawStatement(createSimpleInsert());
            mDatabase.setTransactionSuccessful();
            assertFalse(s.isOpen());
        } finally {
            mDatabase.endTransaction();
        }
        assertFalse(s.isOpen());

        // Verify that a statement is closed implicitly when the transaction is closed without
        // being marked successful.  The try-with-resources pattern is not used here.
        mDatabase.beginTransaction();
        try {
            s = mDatabase.createRawStatement(createSimpleInsert());
        } finally {
            mDatabase.endTransaction();
        }
        assertFalse(s.isOpen());
    }

    @Test
    public void testMustBeOpen() {
        createSimpleDatabase();

        mDatabase.beginTransaction();
        try {
            SQLiteRawStatement s = mDatabase.createRawStatement(createSimpleInsert());
            assertTrue(s.isOpen());
            s.close();
            assertFalse(s.isOpen());

            // Verify that a statement cannot be accessed once closed.
            try {
                s.getResultColumnCount();
                fail("accessed closed statement");
            } catch (AssertionError e) {
                // Pass on the fail from the try-block before the generic catch below can see it.
                throw e;
            } catch (IllegalStateException e) {
                // There is more than one source of this exception.  Scrape the message and look for
                // the message from {@link SQLiteRawStatement.throwIfInvalid}.
                if (!e.getMessage().contains("method called on a closed statement")) {
                    fail("unexpected IllegalStateException, got " + e);
                }
            } catch (Throwable e) {
                fail("expected IllegalStateException, got " + e);
            }
        } finally {
            mDatabase.endTransaction();
        }
    }

    @Test
    public void testThreadRestrictions() throws Exception {
        createComplexDatabase();

        final ArrayList<String> errors = new ArrayList<>();
        errors.add("test failed to run");

        mDatabase.beginTransaction();
        try {
            SQLiteRawStatement s = mDatabase.createRawStatement("SELECT i FROM t1");

            Thread peerThread = new Thread(
                () -> {
                    try {
                        s.step();
                        errors.add("expected IllegalStateException");
                    } catch (IllegalStateException e) {
                        // There is more than one source of this exception.  Scrape the message
                        // and look for the message from {@link SQLiteRawStatement.throwIfInvalid}.
                        if (e.getMessage().contains("method called on a foreign thread")) {
                            // The test ran properly.  Remove the default "did-not-run" error.
                            errors.remove(0);
                        } else {
                            errors.add("unexpected IllegalStateException, got " + e);
                        }
                    } catch (Throwable e) {
                        errors.add("expected IllegalStateException, got " + e);
                    }
                });
            peerThread.start();
            peerThread.join(500L);
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }
        if (errors.size() > 0) {
            fail(errors.get(0));
        }
    }

    @Test
    public void testBlob() {
        mDatabase.beginTransaction();
        try {
            final String query = "CREATE TABLE t1 (i int, b blob)";
            try (SQLiteRawStatement s = mDatabase.createRawStatement(query)) {
                assertFalse(s.step());
            }
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }

        // Create a the reference copy of a byte array.
        byte[] src = new byte[32];
        for (int i = 0; i < src.length; i++) {
            src[i] = (byte) (i * 3);
        }

        // Insert data into the table.
        mDatabase.beginTransaction();
        try {
            final String query = "INSERT INTO t1 (i, b) VALUES (?1, ?2)";
            try (SQLiteRawStatement s = mDatabase.createRawStatement(query)) {
                // Bind the entire src array
                s.bindInt(1, 1);
                s.bindBlob(2, src);
                s.step();
                s.reset();
                // Bind the fragment starting at 4, length 8.
                s.bindInt(1, 2);
                s.bindBlob(2, src, 4, 8);
                s.step();
                s.reset();
                // Bind null
                s.clearBindings();
                s.bindInt(1, 3);
                s.step();
                s.reset();
            }
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }

        // Read back data and verify it against the reference copy.
        mDatabase.beginTransactionReadOnly();
        try {
            final String query = "SELECT (b) FROM t1 WHERE i = ?1";
            try (SQLiteRawStatement s = mDatabase.createRawStatement(query)) {
                // Fetch the entire reference array.
                s.bindInt(1, 1);
                assertTrue(s.step());
                assertEquals(SQLiteRawStatement.SQLITE_DATA_TYPE_BLOB, s.getColumnType(0));

                byte[] a = s.getColumnBlob(0);
                assertTrue(Arrays.equals(src, a));
                s.reset();

                // Fetch the fragment starting at 4, length 8.
                s.bindInt(1, 2);
                assertTrue(s.step());
                byte[] c = new byte[src.length];
                assertEquals(8, s.readColumnBlob(0, c, 0, c.length, 0));
                assertTrue(Arrays.equals(src, 4, 4+8, c, 0, 0+8));
                s.reset();

                // Fetch the null.
                s.bindInt(1, 3);
                assertTrue(s.step());
                assertEquals(null, s.getColumnBlob(0));
                s.reset();

                // Fetch the null and ensure the buffer is not modified.
                for (int i = 0; i < c.length; i++) c[i] = 0;
                s.bindInt(1, 3);
                assertTrue(s.step());
                assertEquals(0, s.readColumnBlob(0, c, 0, c.length, 0));
                for (int i = 0; i < c.length; i++) assertEquals(0, c[i]);
                s.reset();
            }
        } finally {
            mDatabase.endTransaction();
        }

        // Test NPE detection
        mDatabase.beginTransaction();
        try {
            final String query = "INSERT INTO t1 (i, b) VALUES (?1, ?2)";
            try (SQLiteRawStatement s = mDatabase.createRawStatement(query)) {
                s.bindBlob(2, null);
                fail("expected a NullPointerException");
            }
        } catch (NullPointerException e) {
            // Expected
        } catch (AssertionError e) {
            // Pass on the fail from the try-block before the generic catch below can see it.
            throw e;
        } finally {
            mDatabase.endTransaction();
        }
    }

    @Test
    public void testParameterMetadata() {
        createComplexDatabase();

        final String sql = "INSERT INTO t1 (i, d, t) VALUES (:1, ?2, @FOO)";

        // Start a transaction that allows updates.
        mDatabase.beginTransaction();
        try {
            try (SQLiteRawStatement s = mDatabase.createRawStatement(sql)) {
                assertEquals(3, s.getParameterCount());

                assertEquals(1, s.getParameterIndex(":1"));
                assertEquals(2, s.getParameterIndex("?2"));
                assertEquals(3, s.getParameterIndex("@FOO"));
                assertEquals(0, s.getParameterIndex("@BAR"));

                assertEquals(":1", s.getParameterName(1));
                assertEquals("?2", s.getParameterName(2));
                assertEquals("@FOO", s.getParameterName(3));
                assertEquals(null, s.getParameterName(4));
            }
        } finally {
            mDatabase.endTransaction();
        }

        // Start a transaction that allows updates.
        mDatabase.beginTransaction();
        try {
            try (SQLiteRawStatement s = mDatabase.createRawStatement(sql)) {
                // Error case.  The name is not supposed to be null.
                assertEquals(0, s.getParameterIndex(null));
                fail("expected a NullPointerException");
            }
        } catch (NullPointerException e) {
            // Expected
        } catch (AssertionError e) {
            // Pass on the fail from the try-block before the generic catch below can see it.
            throw e;
        } catch (Throwable e) {
            fail("expected NullPointerException, got " + e);
        } finally {
            mDatabase.endTransaction();
        }
    }

    // This test cannot fail, but the log messages report timing for the new SQLiteRawStatement APIs
    // vs the Cursor APIs.
    @Test
    public void testSpeedSimple() {
        final int size = 100000;

        createComplexDatabase();

        // Populate the database.
        mDatabase.beginTransaction();
        try {
            long start = SystemClock.uptimeMillis();
            try (var s = mDatabase.createRawStatement(createComplexInsert())) {
                for (int i = 0; i < size; i++) {
                    int vi = i * 3;
                    double vd = i * 2.5;
                    String vt = String.format("text%02dvalue", i);
                    s.bindInt(1, vi);
                    s.bindDouble(2, vd);
                    s.bindText(3, vt);
                    boolean r = s.step();
                    // No row is returned by this query.
                    assertFalse(r);
                    s.reset();
                }
            }
            long elapsed = SystemClock.uptimeMillis() - start;
            Log.i(TAG, "timing simple insert: " + elapsed + "ms");
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }

        final String query = "SELECT i, d, t FROM t1";

        // Iterate over the database.
        mDatabase.beginTransactionReadOnly();
        try {
            long start = SystemClock.uptimeMillis();
            int found = 0;
            try (var s = mDatabase.createRawStatement(query)) {
                for (int i = 0; s.step(); i++) {
                    int vi = s.getColumnInt(0);
                    int expected = i * 3;
                    assertEquals(expected, vi);
                    found = i;
                }
            }
            long elapsed = SystemClock.uptimeMillis() - start;
            Log.i(TAG, "timing statement simple: " + elapsed + "ms");
            assertEquals(size - 1, found);
        } finally {
            mDatabase.endTransaction();
        }

        // Iterate over the database using cursors.
        mDatabase.beginTransactionReadOnly();
        try {
            long start = SystemClock.uptimeMillis();
            try (Cursor c = mDatabase.rawQuery(query, null)) {
                c.moveToFirst();
                int found = 0;
                for (int i = 0; i < size; i++) {
                    int vi = c.getInt(0);
                    int expected = i * 3;
                    assertEquals(expected, vi);
                    c.moveToNext();
                    found = i;
                }
                assertEquals(size - 1, found);
            }
            long elapsed = SystemClock.uptimeMillis() - start;
            Log.i(TAG, "timing cursor simple: " + elapsed + "ms");
        } finally {
            mDatabase.endTransaction();
        }
    }

    @Test
    public void testSpeedSingleQuery() {
        final int size = 1000;
        final int loops = size;

        createComplexDatabase();

        // Populate the database.
        mDatabase.beginTransaction();
        try {
            try (var s = mDatabase.createRawStatement(createComplexInsert())) {
                for (int i = 0; i < size; i++) {
                    int vi = i * 3;
                    double vd = i * 2.5;
                    String vt = String.format("text%02dvalue", i);
                    s.bindInt(1, vi);
                    s.bindDouble(2, vd);
                    s.bindText(3, vt);
                    boolean r = s.step();
                    // No row is returned by this query.
                    assertFalse(r);
                    s.reset();
                }
            }
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }

        final String query = "SELECT i, d, t FROM t1";

        // Iterate over the database.
        mDatabase.beginTransactionReadOnly();
        try {
            long start = SystemClock.uptimeMillis();
            for (int i = 0; i < loops; i++) {
                try (var s = mDatabase.createRawStatement(query)) {
                    assertTrue(s.step());
                    int vi = s.getColumnInt(0);
                    int expected = 0;
                    assertEquals(expected, vi);
                }
            }
            long elapsed = SystemClock.uptimeMillis() - start;
            Log.i(TAG, "timing statement query: " + elapsed + "ms");
        } finally {
            mDatabase.endTransaction();
        }

        // Iterate over the database using cursors.
        mDatabase.beginTransactionReadOnly();
        try {
            long start = SystemClock.uptimeMillis();
            for (int i = 0; i < loops; i++) {
                try (Cursor c = mDatabase.rawQuery(query, null)) {
                    c.moveToFirst();
                    int vi = c.getInt(0);
                    int expected = 0;
                    assertEquals(expected, vi);
                }
            }
            long elapsed = SystemClock.uptimeMillis() - start;
            mDatabase.setTransactionSuccessful();
            Log.i(TAG, "timing cursor query: " + elapsed + "ms");
        } finally {
            mDatabase.endTransaction();
        }
    }

    private int wideVal(int i, int j) {
        return i + j;
    }

    @Test
    public void testSpeedWideQuery() {
        final int size = 100000;

        createWideDatabase();

        // Populate the database.
        mDatabase.beginTransaction();
        try {
            try (var s = mDatabase.createRawStatement(createWideInsert())) {
                for (int i = 0; i < size; i++) {
                    for (int j = 0; j < 12; j++) {
                        s.bindInt(j+1, wideVal(i, j));
                    }
                    boolean r = s.step();
                    // No row is returned by this query.
                    assertFalse(r);
                    s.reset();
                }
            }
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }

        final String query = "SELECT * FROM t1";

        // Iterate over the database.
        mDatabase.beginTransactionReadOnly();
        try {
            long start = SystemClock.uptimeMillis();
            try (var s = mDatabase.createRawStatement(query)) {
                for (int i = 0; i < size; i++) {
                    assertTrue(s.step());
                    for (int j = 0; j < 12; j++) {
                        assertEquals(s.getColumnInt(j), wideVal(i, j));
                    }
                }
            }
            long elapsed = SystemClock.uptimeMillis() - start;
            Log.i(TAG, "timing statement wide: " + elapsed + "ms");
        } finally {
            mDatabase.endTransaction();
        }

        // Iterate over the database using cursors.
        mDatabase.beginTransactionReadOnly();
        try {
            long start = SystemClock.uptimeMillis();
            try (Cursor c = mDatabase.rawQuery(query, null)) {
                c.moveToFirst();
                for (int i = 0; i < size; i++) {
                    for (int j = 0; j < 12; j++) {
                        assertEquals(c.getInt(j), wideVal(i, j));
                    }
                    c.moveToNext();
                }
            }
            long elapsed = SystemClock.uptimeMillis() - start;
            mDatabase.setTransactionSuccessful();
            Log.i(TAG, "timing cursor wide: " + elapsed + "ms");
        } finally {
            mDatabase.endTransaction();
        }
    }


    @Test
    public void testSpeedRecursive() {
        createComplexDatabase();

        final String query = "WITH RECURSIVE t1(i) AS "
                             + "(SELECT 123 UNION ALL SELECT i+1 FROM t1) "
                             + "SELECT * from t1 LIMIT 1000000";

        mDatabase.beginTransaction();
        try {
            long start = SystemClock.uptimeMillis();
            try (SQLiteRawStatement s = mDatabase.createRawStatement(query)) {
                while (s.step()) {
                    s.getColumnInt(0);
                }
            }
            long elapsed = SystemClock.uptimeMillis() - start;
            Log.i(TAG, "timing statement recursive: " + elapsed + "ms");
        } finally {
            mDatabase.endTransaction();
        }

        mDatabase.beginTransaction();
        try {
            long start = SystemClock.uptimeMillis();
            try (Cursor c = mDatabase.rawQuery(query, null)) {
                c.moveToFirst();
                while (c.moveToNext()) {
                    c.getInt(0);
                }
            }
            long elapsed = SystemClock.uptimeMillis() - start;
            Log.i(TAG, "timing cursor recursive: " + elapsed + "ms");
        } finally {
            mDatabase.endTransaction();
        }
    }

    @Test
    public void testUnicode() {
        // Create the t1 table and put some data in it.
        mDatabase.beginTransaction();
        try {
            mDatabase.execSQL("CREATE TABLE t1 (i int, j int);");
            mDatabase.execSQL("INSERT INTO t1 (i, j) VALUES (2, 20)");
            mDatabase.execSQL("INSERT INTO t1 (i, j) VALUES (3, 30)");
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }

        // Exploding Head Emoji
        final String head = ":\u1F92F";
        // Heart Eyes Cat Emoji
        final String cat = "\u1F63B";

        final String sql = "SELECT i AS " + cat + " FROM t1 WHERE j = " + head;

        mDatabase.beginTransactionReadOnly();
        try (SQLiteRawStatement s = mDatabase.createRawStatement(sql)) {
            assertEquals(1, s.getParameterIndex(head));
            assertEquals(head, s.getParameterName(1));
            s.bindInt(1, 20);
            assertTrue(s.step());
            assertEquals(2, s.getColumnInt(0));
            assertEquals(cat, s.getColumnName(0));
        } finally {
            mDatabase.endTransaction();
        }
    }
}
