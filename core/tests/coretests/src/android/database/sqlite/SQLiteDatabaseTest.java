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
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.test.AndroidTestCase;
import android.util.Log;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class SQLiteDatabaseTest {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    private static final String TAG = "SQLiteDatabaseTest";

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

    @Test
    public void testStatementDDLEvictsCache() {
        // The following will be cached (key is SQL string)
        String selectQuery = "SELECT * FROM t1";

        mDatabase.beginTransaction();
        mDatabase.execSQL("CREATE TABLE `t1` (`c1` INTEGER NOT NULL PRIMARY KEY, data TEXT)");
        try (Cursor c = mDatabase.rawQuery(selectQuery, null)) {
            assertEquals(2, c.getColumnCount());
        }
        // Alter the schema in such a way that if the cached query is used it would produce wrong
        // results due to the change in column amounts.
        mDatabase.execSQL("ALTER TABLE `t1` RENAME TO `t1_old`");
        mDatabase.execSQL("CREATE TABLE `t1` (`c1` INTEGER NOT NULL PRIMARY KEY)");
        // Execute cached query (that should have been evicted), validating it sees the new schema.
        try (Cursor c = mDatabase.rawQuery(selectQuery, null)) {
            assertEquals(1, c.getColumnCount());
        }
        mDatabase.setTransactionSuccessful();
        mDatabase.endTransaction();
    }

    @Test
    public void testStressDDLEvicts() {
        mDatabase.enableWriteAheadLogging();
        mDatabase.execSQL("CREATE TABLE `t1` (`c1` INTEGER NOT NULL PRIMARY KEY, data TEXT)");
        final int iterations = 1000;
        ExecutorService exec = Executors.newFixedThreadPool(2);
        exec.execute(() -> {
                    boolean pingPong = true;
                    for (int i = 0; i < iterations; i++) {
                        mDatabase.beginTransaction();
                        if (pingPong) {
                            mDatabase.execSQL("ALTER TABLE `t1` RENAME TO `t1_old`");
                            mDatabase.execSQL("CREATE TABLE `t1` (`c1` INTEGER NOT NULL "
                                + "PRIMARY KEY)");
                            pingPong = false;
                        } else {
                            mDatabase.execSQL("DROP TABLE `t1`");
                            mDatabase.execSQL("ALTER TABLE `t1_old` RENAME TO `t1`");
                            pingPong = true;
                        }
                        mDatabase.setTransactionSuccessful();
                        mDatabase.endTransaction();
                    }
                });
        exec.execute(() -> {
                    for (int i = 0; i < iterations; i++) {
                        try (Cursor c = mDatabase.rawQuery("SELECT * FROM t1", null)) {
                            c.getCount();
                        }
                    }
                });
        try {
            exec.shutdown();
            assertTrue(exec.awaitTermination(1, TimeUnit.MINUTES));
        } catch (InterruptedException e) {
            fail("Timed out");
        }
    }

    /**
     * Create a database with one table with three columns.
     */
    private void createComplexDatabase() {
        mDatabase.beginTransaction();
        try {
            mDatabase.execSQL("CREATE TABLE t1 (i int, d double, t text);");
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }
    }

    /**
     * A three-value insert for the complex database.
     */
    private String createComplexInsert() {
        return "INSERT INTO t1 (i, d, t) VALUES (?1, ?2, ?3)";
    }

    @Test
    public void testAutomaticCounters() {
        final int size = 10;

        createComplexDatabase();

        // Put 10 lines in the database.
        mDatabase.beginTransaction();
        try {
            try (SQLiteRawStatement s = mDatabase.createRawStatement(createComplexInsert())) {
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
                    assertEquals(i + 1, mDatabase.getLastInsertRowId());
                    assertEquals(1, mDatabase.getLastChangedRowCount());
                    assertEquals(i + 2, mDatabase.getTotalChangedRowCount());
                }
            }
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }

        // Put a second 10 lines in the database.
        mDatabase.beginTransaction();
        try {
            try (SQLiteRawStatement s = mDatabase.createRawStatement(createComplexInsert())) {
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
                    assertEquals(size + i + 1, mDatabase.getLastInsertRowId());
                    assertEquals(1, mDatabase.getLastChangedRowCount());
                    assertEquals(size + i + 2, mDatabase.getTotalChangedRowCount());
                }
            }
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }
    }

    @Test
    public void testAutomaticCountersOutsideTransactions() {
        try {
            mDatabase.getLastChangedRowCount();
            fail("getLastChangedRowCount() succeeded outside a transaction");
        } catch (IllegalStateException e) {
            // This exception is expected.
        }

        try {
            mDatabase.getTotalChangedRowCount();
            fail("getTotalChangedRowCount() succeeded outside a transaction");
        } catch (IllegalStateException e) {
            // This exception is expected.
        }
    }

    /**
     * Count the number of rows in the database <count> times.  The answer must match <expected>
     * every time.  Any errors are reported back to the main thread through the <errors>
     * array. The ticker forces the database reads to be interleaved with database operations from
     * the sibling threads.
     */
    private void concurrentReadOnlyReader(SQLiteDatabase database, int count, long expected,
            List<Throwable> errors, Phaser ticker) {

        final String query = "--comment\nSELECT count(*) from t1";

        try {
            for (int i = count; i > 0; i--) {
                ticker.arriveAndAwaitAdvance();
                long r = DatabaseUtils.longForQuery(database, query, null);
                if (r != expected) {
                    // The type of the exception is not important.  Only the message matters.
                    throw new RuntimeException(
                        String.format("concurrentRead expected %d, got %d", expected, r));
                }
            }
        } catch (Throwable t) {
            errors.add(t);
        } finally {
            ticker.arriveAndDeregister();
        }
    }

    /**
     * Insert a new row <count> times.  Any errors are reported back to the main thread through
     * the <errors> array. The ticker forces the database reads to be interleaved with database
     * operations from the sibling threads.
     */
    private void concurrentImmediateWriter(SQLiteDatabase database, int count,
            List<Throwable> errors, Phaser ticker) {
        database.beginTransaction();
        try {
            int n = 100;
            for (int i = count; i > 0; i--) {
                ticker.arriveAndAwaitAdvance();
                database.execSQL(String.format("INSERT INTO t1 (i) VALUES (%d)", n++));
            }
            database.setTransactionSuccessful();
        } catch (Throwable t) {
            errors.add(t);
        } finally {
            database.endTransaction();
            ticker.arriveAndDeregister();
        }
    }

    /**
     * This test verifies that a read-only transaction can be started, and it is deferred.  A
     * deferred transaction does not take a database locks until the database is accessed.  This
     * test verifies that the implicit connection selection process correctly identifies
     * read-only transactions even when they are preceded by a comment.
     */
    @Test
    public void testReadOnlyTransaction() throws Exception {
        // Enable WAL for concurrent read and write transactions.
        mDatabase.enableWriteAheadLogging();

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

        // Threads install errors in this array.
        final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());

        // This forces the read and write threads to execute in a lock-step, round-robin fashion.
        Phaser ticker = new Phaser(3);

        // Create three threads that will perform transactions.  One thread is a writer and two
        // are readers.  The intent is that the readers begin before the writer commits, so the
        // readers always see a database with two rows.
        Thread readerA = new Thread(() -> {
              concurrentReadOnlyReader(mDatabase, 4, 2, errors, ticker);
        });
        Thread readerB = new Thread(() -> {
              concurrentReadOnlyReader(mDatabase, 4, 2, errors, ticker);
        });
        Thread writerC = new Thread(() -> {
              concurrentImmediateWriter(mDatabase, 4, errors, ticker);
        });

        readerA.start();
        readerB.start();
        writerC.start();

        // All three threads should have completed.  Give the total set 1s.  The 10ms delay for
        // the second and third threads is just a small, positive number.
        readerA.join(1000);
        assertFalse(readerA.isAlive());
        readerB.join(10);
        assertFalse(readerB.isAlive());
        writerC.join(10);
        assertFalse(writerC.isAlive());

        // The writer added 4 rows to the database.
        long r = DatabaseUtils.longForQuery(mDatabase, "SELECT count(*) from t1", null);
        assertEquals(6, r);

        assertTrue("ReadThread failed with errors: " + errors, errors.isEmpty());
    }

    @RequiresFlagsEnabled(Flags.FLAG_SQLITE_ALLOW_TEMP_TABLES)
    @Test
    public void testTempTable() {
        boolean allowed;
        allowed = true;
        mDatabase.beginTransactionReadOnly();
        try {
            mDatabase.execSQL("CREATE TEMP TABLE t1 (i int, j int);");
            mDatabase.execSQL("INSERT INTO t1 (i, j) VALUES (2, 20)");
            mDatabase.execSQL("INSERT INTO t1 (i, j) VALUES (3, 30)");

            final String sql = "SELECT i FROM t1 WHERE j = 30";
            try (SQLiteRawStatement s = mDatabase.createRawStatement(sql)) {
                assertTrue(s.step());
                assertEquals(3, s.getColumnInt(0));
            }

        } catch (SQLiteException e) {
            allowed = false;
        } finally {
            mDatabase.endTransaction();
        }
        assertTrue(allowed);

        // Repeat the test on the main schema.
        allowed = true;
        mDatabase.beginTransactionReadOnly();
        try {
            mDatabase.execSQL("CREATE TABLE t2 (i int, j int);");
            mDatabase.execSQL("INSERT INTO t2 (i, j) VALUES (2, 20)");
            mDatabase.execSQL("INSERT INTO t2 (i, j) VALUES (3, 30)");

            final String sql = "SELECT i FROM t2 WHERE j = 30";
            try (SQLiteRawStatement s = mDatabase.createRawStatement(sql)) {
                assertTrue(s.step());
                assertEquals(3, s.getColumnInt(0));
            }

        } catch (SQLiteException e) {
            allowed = false;
        } finally {
            mDatabase.endTransaction();
        }
        assertFalse(allowed);
    }
}
