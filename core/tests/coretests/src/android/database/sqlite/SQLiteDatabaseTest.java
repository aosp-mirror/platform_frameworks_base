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
import android.database.DefaultDatabaseErrorHandler;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.Printer;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        database.beginTransactionReadOnly();
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
            database.endTransaction();
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

            mDatabase.execSQL("DROP TABLE t1");

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

    /** Dumpsys information about a single database. */

    /**
     * Collect and parse dumpsys output.  This is not a full parser.  It is only enough to support
     * the unit tests.
     */
    private static class Dumpsys {
        // Regular expressions for parsing the output.  Reportedly, regular expressions are
        // expensive, so these are created only if a dumpsys object is created.
        private static final Object sLock = new Object();
        static Pattern mPool;
        static Pattern mConnection;
        static Pattern mEntry;
        static Pattern mSingleWord;
        static Pattern mNone;

        // The raw strings read from dumpsys.  Once loaded, this list never changes.
        final ArrayList<String> mRaw = new ArrayList<>();

        // Parsed dumpsys.  This contains only the bits that are being tested.
        static class Connection {
            ArrayList<String> mRecent = new ArrayList<>();
            ArrayList<String> mLong = new ArrayList<>();
        }
        static class Database {
            String mPath;
            ArrayList<Connection> mConnection = new ArrayList<>();
        }
        ArrayList<Database> mDatabase;
        ArrayList<String> mConcurrent;

        Dumpsys() {
            SQLiteDebug.dump(
                new Printer() { public void println(String x) { mRaw.add(x); } },
                new String[0]);
            parse();
        }

        /** Parse the raw text. Return true if no errors were detected. */
        boolean parse() {
            initialize();

            // Reset the parsed information.  This method may be called repeatedly.
            mDatabase = new ArrayList<>();
            mConcurrent = new ArrayList<>();

            Database current = null;
            Connection connection = null;
            Matcher matcher;
            for (int i = 0; i < mRaw.size(); i++) {
                final String line = mRaw.get(i);
                matcher = mPool.matcher(line);
                if (matcher.lookingAt()) {
                    current = new Database();
                    mDatabase.add(current);
                    current.mPath = matcher.group(1);
                    continue;
                }
                matcher = mConnection.matcher(line);
                if (matcher.lookingAt()) {
                    connection = new Connection();
                    current.mConnection.add(connection);
                    continue;
                }

                if (line.contains("Most recently executed operations")) {
                    i += readTable(connection.mRecent, i, mEntry);
                    continue;
                }

                if (line.contains("Operations exceeding 2000ms")) {
                    i += readTable(connection.mLong, i, mEntry);
                    continue;
                }
                if (line.contains("Concurrently opened database files")) {
                    i += readTable(mConcurrent, i, mSingleWord);
                    continue;
                }
            }
            return true;
        }

        /**
         * Read a series of lines following a header.  Return the number of lines read.  The input
         * line number is the number of the header.
         */
        private int readTable(List<String> s, int header, Pattern p) {
            // Special case: if the first line is "<none>" then there are no more lines to the
            // table.
            if (lookingAt(header+1, mNone)) return 1;

            int i;
            for (i = header + 1; i < mRaw.size() && lookingAt(i, p); i++) {
                s.add(mRaw.get(i).trim());
            }
            return i - header;
        }

        /** Return true if the n'th raw line matches the pattern. */
        boolean lookingAt(int n, Pattern p) {
            return p.matcher(mRaw.get(n)).lookingAt();
        }

        /** Compile the regular expressions the first time. */
        private static void initialize() {
            synchronized (sLock) {
                if (mPool != null) return;
                mPool = Pattern.compile("Connection pool for (\\S+):");
                mConnection = Pattern.compile("\\s+Connection #(\\d+):");
                mEntry = Pattern.compile("\\s+(\\d+): ");
                mSingleWord = Pattern.compile("  (\\S+)$");
                mNone = Pattern.compile("\\s+<none>$");
            }
        }
    }

    @Test
    public void testDumpsys() throws Exception {
        Dumpsys dumpsys = new Dumpsys();

        assertEquals(1, dumpsys.mDatabase.size());
        // Note: cannot test mConcurrent because that attribute is not hermitic with respect to
        // the tests.

        Dumpsys.Database db = dumpsys.mDatabase.get(0);

        // Work with normalized paths.
        String wantPath = mDatabaseFile.toPath().toRealPath().toString();
        String realPath = new File(db.mPath).toPath().toRealPath().toString();
        assertEquals(wantPath, realPath);

        assertEquals(1, db.mConnection.size());
    }

    // Create and open the database, allowing or disallowing double-quoted strings.
    private void createDatabase(boolean noDoubleQuotedStrs) throws Exception {
        // The open-flags that do not change in this test.
        int flags = SQLiteDatabase.CREATE_IF_NECESSARY | SQLiteDatabase.OPEN_READWRITE;

        // The flag to be tested.
        int flagUnderTest = SQLiteDatabase.NO_DOUBLE_QUOTED_STRS;

        if (noDoubleQuotedStrs) {
            flags |= flagUnderTest;
        } else {
            flags &= ~flagUnderTest;
        }
        mDatabase = SQLiteDatabase.openDatabase(mDatabaseFile.getPath(), null, flags, null);
    }

    /**
     * This test verifies that the NO_DOUBLE_QUOTED_STRS flag works as expected when opening a
     * database.  This does not test that the flag is initialized as expected from the system
     * properties.
     */
    @Test
    public void testNoDoubleQuotedStrings() throws Exception {
        closeAndDeleteDatabase();
        createDatabase(/* noDoubleQuotedStrs */ false);

        mDatabase.beginTransaction();
        try {
            mDatabase.execSQL("CREATE TABLE t1 (t text);");
            // Insert a value in double-quotes.  This is invalid but accepted.
            mDatabase.execSQL("INSERT INTO t1 (t) VALUES (\"foo\")");
        } finally {
            mDatabase.endTransaction();
        }

        closeAndDeleteDatabase();
        createDatabase(/* noDoubleQuotedStrs */ true);

        mDatabase.beginTransaction();
        try {
            mDatabase.execSQL("CREATE TABLE t1 (t text);");
            try {
                // Insert a value in double-quotes.  This is invalid and must throw.
                mDatabase.execSQL("INSERT INTO t1 (t) VALUES (\"foo\")");
                fail("expected an exception");
            } catch (SQLiteException e) {
                assertTrue(e.toString().contains("no such column"));
            }
        } finally {
            mDatabase.endTransaction();
        }
        closeAndDeleteDatabase();
    }

    @Test
    public void testCloseCorruptionReport() throws Exception {
        mDatabase.beginTransaction();
        try {
            mDatabase.execSQL("CREATE TABLE t2 (i int, j int);");
            mDatabase.execSQL("INSERT INTO t2 (i, j) VALUES (2, 20)");
            mDatabase.execSQL("INSERT INTO t2 (i, j) VALUES (3, 30)");
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }

        // Start a transaction and announce that the DB is corrupted.
        DefaultDatabaseErrorHandler errorHandler = new DefaultDatabaseErrorHandler();

        // Do not bother with endTransaction; the database will have been closed in the corruption
        // handler.
        mDatabase.beginTransaction();
        try {
            errorHandler.onCorruption(mDatabase);
            mDatabase.execSQL("INSERT INTO t2 (i, j) VALUES (4, 40)");
            fail("expected an exception");
        } catch (IllegalStateException e) {
            final Throwable cause = e.getCause();
            assertNotNull(cause);
            boolean found = false;
            for (StackTraceElement s : cause.getStackTrace()) {
                if (s.getMethodName().contains("onCorruption")) {
                    found = true;
                }
            }
            assertTrue(found);
        }
    }

    @Test
    public void testCloseReport() throws Exception {
        mDatabase.beginTransaction();
        try {
            mDatabase.execSQL("CREATE TABLE t2 (i int, j int);");
            mDatabase.execSQL("INSERT INTO t2 (i, j) VALUES (2, 20)");
            mDatabase.execSQL("INSERT INTO t2 (i, j) VALUES (3, 30)");
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }

        mDatabase.close();
        try {
            // Do not bother with endTransaction; the database has already been close.
            mDatabase.beginTransaction();
            fail("expected an exception");
        } catch (IllegalStateException e) {
            assertTrue(e.toString().contains("attempt to re-open an already-closed object"));
            final Throwable cause = e.getCause();
            assertNotNull(cause);
            boolean found = false;
            for (StackTraceElement s : cause.getStackTrace()) {
                if (s.getMethodName().contains("testCloseReport")) {
                    found = true;
                }
            }
            assertTrue(found);
        }
    }
}
