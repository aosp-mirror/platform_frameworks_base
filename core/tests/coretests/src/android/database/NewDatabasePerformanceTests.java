/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.database;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemProperties;
import android.test.PerformanceTestCase;
import android.util.ArrayMap;
import android.util.Log;

import junit.framework.TestCase;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Database Performance Tests.
 *
 * <p>Usage:
 * <code>./frameworks/base/core/tests/coretests/src/android/database/run_newdb_perf_test.sh</code>
 * <p>Test with WAL journaling enabled:
 * <code>setprop debug.NewDatabasePerformanceTests.enable_wal 1</code>
 */
public class NewDatabasePerformanceTests {
    private static final String TAG = "NewDatabasePerformanceTests";

    private static final boolean DEBUG_ENABLE_WAL = SystemProperties
            .getBoolean("debug.NewDatabasePerformanceTests.enable_wal", false);

    private static final int DATASET_SIZE = 100; // Size of dataset to use for testing
    private static final int FAST_OP_MULTIPLIER = 25;
    private static final int FAST_OP_COUNT = FAST_OP_MULTIPLIER * DATASET_SIZE;

    private static Long sInitialWriteBytes;

    static {
        sInitialWriteBytes = getIoStats().get("write_bytes");
        if (DEBUG_ENABLE_WAL) {
            Log.i(TAG, "Testing with WAL enabled");
        }
    }

    public static class PerformanceBase extends TestCase
            implements PerformanceTestCase {
        protected static final int CURRENT_DATABASE_VERSION = 42;
        protected SQLiteDatabase mDatabase;
        protected File mDatabaseFile;
        private long mSetupFinishedTime;
        private Long mSetupWriteBytes;

        public void setUp() {
            long setupStarted = System.currentTimeMillis();
            mDatabaseFile = new File("/sdcard", "perf_database_test.db");
            if (mDatabaseFile.exists()) {
                SQLiteDatabase.deleteDatabase(mDatabaseFile);
            }
            SQLiteDatabase.OpenParams.Builder params = new SQLiteDatabase.OpenParams.Builder();
            params.addOpenFlags(SQLiteDatabase.CREATE_IF_NECESSARY);
            if (DEBUG_ENABLE_WAL) {
                params.addOpenFlags(SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING);
            }
            mDatabase = SQLiteDatabase.openDatabase(mDatabaseFile, params.build());
            if (DEBUG_ENABLE_WAL) {
                assertTrue("Cannot enable WAL", mDatabase.isWriteAheadLoggingEnabled());
            }
            mDatabase.setVersion(CURRENT_DATABASE_VERSION);
            mDatabase.beginTransactionNonExclusive();
            prepareForTest();
            mDatabase.setTransactionSuccessful();
            mDatabase.endTransaction();
            mSetupFinishedTime = System.currentTimeMillis();
            Log.i(TAG, "Setup for " + getClass().getSimpleName() + " took "
                    + (mSetupFinishedTime - setupStarted) + " ms");
            mSetupWriteBytes = getIoStats().get("write_bytes");
        }

        protected void prepareForTest() {
        }

        public void tearDown() {
            long duration = System.currentTimeMillis() - mSetupFinishedTime;
            Log.i(TAG, "Test " + getClass().getSimpleName() + " took " + duration + " ms");
            mDatabase.close();
            SQLiteDatabase.deleteDatabase(mDatabaseFile);
            Long writeBytes = getIoStats().get("write_bytes");
            if (writeBytes != null && sInitialWriteBytes != null) {
                long testWriteBytes = writeBytes - mSetupWriteBytes;
                long totalWriteBytes = (writeBytes - sInitialWriteBytes);
                Log.i(TAG, "Test " + getClass().getSimpleName() + " write_bytes=" + testWriteBytes
                        + ". Since tests started - totalWriteBytes=" + totalWriteBytes);
            }
        }

        public boolean isPerformanceOnly() {
            return true;
        }

        // These tests can only be run once.
        public int startPerformance(Intermediates intermediates) {
            return 0;
        }

        String numberName(int number) {
            String result = "";

            if (number >= 1000) {
                result += numberName((number / 1000)) + " thousand";
                number = (number % 1000);

                if (number > 0) result += " ";
            }

            if (number >= 100) {
                result += ONES[(number / 100)] + " hundred";
                number = (number % 100);

                if (number > 0) result += " ";
            }

            if (number >= 20) {
                result += TENS[(number / 10)];
                number = (number % 10);

                if (number > 0) result += " ";
            }

            if (number > 0) {
                result += ONES[number];
            }

            return result;
        }

        void checkCursor(Cursor c) {
            c.getColumnCount();
            c.close();
        }
    }

    /**
     * Test CREATE SIZE tables with 1 row.
     */
    public static class CreateTable100 extends PerformanceBase {
        public void testRun() {
            for (int i = 0; i < DATASET_SIZE; i++) {
                String t = "t" + i;
                mDatabase.execSQL("CREATE TABLE " + t + "(a INTEGER, b INTEGER, c VARCHAR(100))");
                mDatabase.execSQL("INSERT INTO " + t + " VALUES(" + i + "," + i + ",'"
                        + numberName(i) + "')");
            }
        }
    }

    /**
     * Test 100 inserts.
     */
    public static class Insert100 extends PerformanceBase {
        private String[] mStatements = new String[DATASET_SIZE];

        @Override
        protected void prepareForTest() {
            Random random = new Random(42);

            for (int i = 0; i < DATASET_SIZE; i++) {
                int r = random.nextInt(100000);
                mStatements[i] =
                        "INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                                + numberName(r) + "')";
            }

            mDatabase.execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
        }

        public void testRun() {
            for (int i = 0; i < DATASET_SIZE; i++) {
                mDatabase.execSQL(mStatements[i]);
            }
        }
    }

    /**
     * Test 100 inserts into an indexed table.
     */

    public static class InsertIndexed100 extends PerformanceBase {
        private String[] mStatements = new String[DATASET_SIZE];

        @Override
        protected void prepareForTest() {
            Random random = new Random(42);

            for (int i = 0; i < DATASET_SIZE; i++) {
                int r = random.nextInt(100000);
                mStatements[i] = "INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')";
            }

            mDatabase.execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
            mDatabase.execSQL("CREATE INDEX i1c ON t1(c)");
        }

        public void testRun() {
            for (int i = 0; i < DATASET_SIZE; i++) {
                mDatabase.execSQL(mStatements[i]);
            }
        }
    }

    /**
     * 100 SELECTs without an index
     */
    public static class Select100 extends PerformanceBase {
        private static final String[] COLUMNS = {"count(*)", "avg(b)"};

        private String[] mWhere = new String[DATASET_SIZE];

        @Override
        protected void prepareForTest() {
            Random random = new Random(42);

            mDatabase
            .execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");

            for (int i = 0; i < DATASET_SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < DATASET_SIZE; i++) {
                int lower = i * 100;
                int upper = (i + 10) * 100;
                mWhere[i] = "b >= " + lower + " AND b < " + upper;
            }
        }

        public void testRun() {
            for (int i = 0; i < FAST_OP_COUNT; i++) {
                checkCursor(mDatabase
                        .query("t1", COLUMNS, mWhere[i % DATASET_SIZE], null, null, null, null));
            }
        }
    }

    /**
     * 100 SELECTs on a string comparison
     */
    public static class SelectStringComparison100 extends PerformanceBase {
        private static final String[] COLUMNS = {"count(*)", "avg(b)"};

        private String[] mWhere = new String[DATASET_SIZE];

        @Override
        protected void prepareForTest() {
            Random random = new Random(42);

            mDatabase
            .execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");

            for (int i = 0; i < DATASET_SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < DATASET_SIZE; i++) {
                mWhere[i] = "c LIKE '" + numberName(i) + "'";
            }
        }

        public void testRun() {
            for (int i = 0; i < FAST_OP_COUNT; i++) {
                checkCursor(mDatabase
                        .query("t1", COLUMNS, mWhere[i % DATASET_SIZE], null, null, null, null));
            }
        }
    }

    /**
     * 100 SELECTs with an index
     */
    public static class SelectIndex100 extends PerformanceBase {
        private static final int TABLE_SIZE = 100;
        private static final String[] COLUMNS = {"count(*)", "avg(b)"};

        private String[] mWhere = new String[TABLE_SIZE];

        @Override
        protected void prepareForTest() {
            Random random = new Random(42);

            mDatabase.execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
            mDatabase.execSQL("CREATE INDEX i1b ON t1(b)");

            for (int i = 0; i < TABLE_SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < TABLE_SIZE; i++) {
                int lower = i * 100;
                int upper = (i + 10) * 100;
                mWhere[i] = "b >= " + lower + " AND b < " + upper;
            }
        }

        public void testRun() {
            for (int i = 0; i < FAST_OP_COUNT; i++) {
                checkCursor(mDatabase
                        .query("t1", COLUMNS, mWhere[i % TABLE_SIZE], null, null, null, null));
            }
        }
    }

    /**
     *  INNER JOIN without an index
     */
    public static class InnerJoin100 extends PerformanceBase {
        private static final String[] COLUMNS = {"t1.a"};

        @Override
        protected void prepareForTest() {
            Random random = new Random(42);

            mDatabase.execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
            mDatabase.execSQL("CREATE TABLE t2(a INTEGER, b INTEGER, c VARCHAR(100))");

            for (int i = 0; i < DATASET_SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < DATASET_SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t2 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }
        }

        public void testRun() {
            for (int i = 0; i < FAST_OP_COUNT; i++) {
                checkCursor(mDatabase.query("t1 INNER JOIN t2 ON t1.b = t2.b", COLUMNS, null,
                        null, null, null, null));
            }
        }
    }

    /**
     *  INNER JOIN without an index on one side
     */

    public static class InnerJoinOneSide100 extends PerformanceBase {
        private static final String[] COLUMNS = {"t1.a"};

        @Override
        protected void prepareForTest() {
            Random random = new Random(42);

            mDatabase.execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
            mDatabase.execSQL("CREATE TABLE t2(a INTEGER, b INTEGER, c VARCHAR(100))");

            mDatabase.execSQL("CREATE INDEX i1b ON t1(b)");

            for (int i = 0; i < DATASET_SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < DATASET_SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t2 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }
        }

        public void testRun() {
            for (int i = 0; i < FAST_OP_COUNT; i++) {
                checkCursor(mDatabase.query("t1 INNER JOIN t2 ON t1.b = t2.b", COLUMNS, null,
                        null, null, null, null));
            }
        }
    }

    /**
     *  INNER JOIN without an index on one side
     */
    public static class InnerJoinNoIndex100 extends PerformanceBase {
        private static final String[] COLUMNS = {"t1.a"};

        @Override
        protected void prepareForTest() {
            Random random = new Random(42);

            mDatabase.execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
            mDatabase.execSQL("CREATE TABLE t2(a INTEGER, b INTEGER, c VARCHAR(100))");

            mDatabase.execSQL("CREATE INDEX i1b ON t1(b)");

            for (int i = 0; i < DATASET_SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < DATASET_SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t2 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }
        }

        public void testRun() {
            for (int i = 0; i < FAST_OP_COUNT; i++) {
                checkCursor(mDatabase
                        .query("t1 INNER JOIN t2 ON t1.c = t2.c", COLUMNS, null, null, null, null,
                                null));
            }
        }
    }

    /**
     *  100 SELECTs with subqueries. Subquery is using an index
     */
    public static class SelectSubQIndex100 extends PerformanceBase {
        private static final String[] COLUMNS = {"t1.a"};

        private String[] mWhere = new String[DATASET_SIZE];

        @Override
        protected void prepareForTest() {
            Random random = new Random(42);

            mDatabase.execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
            mDatabase.execSQL("CREATE TABLE t2(a INTEGER, b INTEGER, c VARCHAR(100))");

            mDatabase.execSQL("CREATE INDEX i2b ON t2(b)");

            for (int i = 0; i < DATASET_SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < DATASET_SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t2 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < DATASET_SIZE; i++) {
                int lower = i * 100;
                int upper = (i + 10) * 100;
                mWhere[i] =
                        "t1.b IN (SELECT t2.b FROM t2 WHERE t2.b >= " + lower
                        + " AND t2.b < " + upper + ")";
            }
        }

        public void testRun() {
            for (int i = 0; i < FAST_OP_COUNT; i++) {
                checkCursor(mDatabase
                        .query("t1", COLUMNS, mWhere[i % DATASET_SIZE], null, null, null, null));
            }
        }
    }

    /**
     *  100 SELECTs on string comparison with Index
     */
    public static class SelectIndexStringComparison100 extends PerformanceBase {
        private static final String[] COLUMNS = {"count(*)", "avg(b)"};

        private String[] mWhere = new String[DATASET_SIZE];

        @Override
        protected void prepareForTest() {
            Random random = new Random(42);

            mDatabase.execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
            mDatabase.execSQL("CREATE INDEX i3c ON t1(c)");

            for (int i = 0; i < DATASET_SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < DATASET_SIZE; i++) {
                mWhere[i] = "c LIKE '" + numberName(i) + "'";
            }
        }

        public void testRun() {
            for (int i = 0; i < FAST_OP_COUNT; i++) {
                checkCursor(mDatabase
                        .query("t1", COLUMNS, mWhere[i % DATASET_SIZE], null, null, null, null));
            }
        }
    }

    /**
     *  100 SELECTs on integer
     */
    public static class SelectInteger100 extends PerformanceBase {
        private static final String[] COLUMNS = {"b"};

        @Override
        protected void prepareForTest() {
            Random random = new Random(42);

            mDatabase.execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");

            for (int i = 0; i < DATASET_SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

        }

        public void testRun() {
            for (int i = 0; i < FAST_OP_COUNT; i++) {
                checkCursor(mDatabase.query("t1", COLUMNS, null, null, null, null, null));
            }
        }
    }

    /**
     *  100 SELECTs on String
     */
    public static class SelectString100 extends PerformanceBase {
        private static final String[] COLUMNS = {"c"};

        @Override
        protected void prepareForTest() {
            Random random = new Random(42);

            mDatabase
            .execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");

            for (int i = 0; i < DATASET_SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }
        }

        public void testRun() {
            for (int i = 0; i < FAST_OP_COUNT; i++) {
                mDatabase.query("t1", COLUMNS, null, null, null, null, null);
            }
        }
    }

    /**
     *  100 SELECTs on integer with index
     */
    public static class SelectIntegerIndex100 extends PerformanceBase {
        private static final String[] COLUMNS = {"b"};

        @Override
        protected void prepareForTest() {
            Random random = new Random(42);

            mDatabase
            .execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
            mDatabase.execSQL("CREATE INDEX i1b on t1(b)");

            for (int i = 0; i < DATASET_SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

        }

        public void testRun() {
            for (int i = 0; i < FAST_OP_COUNT; i++) {
                mDatabase.query("t1", COLUMNS, null, null, null, null, null);
            }
        }
    }

    /**
     *  100 SELECTs on String with index
     */
    public static class SelectIndexString100 extends PerformanceBase {
        private static final String[] COLUMNS = {"c"};

        @Override
        protected void prepareForTest() {
            Random random = new Random(42);

            mDatabase.execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
            mDatabase.execSQL("CREATE INDEX i1c ON t1(c)");

            for (int i = 0; i < DATASET_SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }
        }

        public void testRun() {
            for (int i = 0; i < FAST_OP_COUNT; i++) {
                checkCursor(mDatabase.query("t1", COLUMNS, null, null, null, null, null));
            }
        }

    }

    /**
     *  100 SELECTs on String with starts with
     */
    public static class SelectStringStartsWith100 extends PerformanceBase {
        private static final String[] COLUMNS = {"c"};
        private String[] mWhere = new String[DATASET_SIZE];

        @Override
        protected void prepareForTest() {
            Random random = new Random(42);

            mDatabase.execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
            mDatabase.execSQL("CREATE INDEX i1c ON t1(c)");

            for (int i = 0; i < DATASET_SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < DATASET_SIZE; i++) {
                int r = random.nextInt(100000);
                mWhere[i] = "c LIKE '" + numberName(r).substring(0, 1) + "*'";

            }

        }

        public void testRun() {
            for (int i = 0; i < FAST_OP_COUNT; i++) {
                mDatabase.query("t1", COLUMNS, mWhere[i % DATASET_SIZE], null, null, null, null);
            }
        }
    }

    /**
     *  100 Deletes on an indexed table
     */
    public static class DeleteIndexed100 extends PerformanceBase {

        @Override
        protected void prepareForTest() {
            Random random = new Random(42);

            mDatabase.execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
            mDatabase.execSQL("CREATE INDEX i3c ON t1(c)");

            for (int i = 0; i < DATASET_SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

        }

        public void testRun() {
            for (int i = 0; i < DATASET_SIZE; i++) {
                mDatabase.delete("t1", null, null);
            }
        }
    }

    /**
     *  100 Deletes
     */
    public static class Delete100 extends PerformanceBase {
        @Override
        protected void prepareForTest() {
            Random random = new Random(42);

            mDatabase.execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");

            for (int i = 0; i < DATASET_SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

        }

        public void testRun() {
            for (int i = 0; i < DATASET_SIZE; i++) {
                mDatabase.delete("t1", null, null);
            }
        }
    }

    /**
     *  100 DELETE's without an index with where clause
     */
    public static class DeleteWhere100 extends PerformanceBase {
        private String[] mWhere = new String[DATASET_SIZE];

        @Override
        protected void prepareForTest() {
            Random random = new Random(42);

            mDatabase.execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");

            for (int i = 0; i < DATASET_SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < DATASET_SIZE; i++) {
                int lower = i * 100;
                int upper = (i + 10) * 100;
                mWhere[i] = "b >= " + lower + " AND b < " + upper;
            }
        }

        public void testRun() {
            for (int i = 0; i < DATASET_SIZE; i++) {
                mDatabase.delete("t1", mWhere[i], null);
            }
        }
    }

    /**
     * 100 DELETE's with an index with where clause
     */
    public static class DeleteIndexWhere100 extends PerformanceBase {
        private String[] mWhere = new String[DATASET_SIZE];

        @Override
        protected void prepareForTest() {
            Random random = new Random(42);

            mDatabase.execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
            mDatabase.execSQL("CREATE INDEX i1b ON t1(b)");

            for (int i = 0; i < DATASET_SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < DATASET_SIZE; i++) {
                int lower = i * 100;
                int upper = (i + 10) * 100;
                mWhere[i] = "b >= " + lower + " AND b < " + upper;
            }
        }

        public void testRun() {
            for (int i = 0; i < DATASET_SIZE; i++) {
                mDatabase.delete("t1", mWhere[i], null);
            }
        }
    }

    /**
     * 100 update's with an index with where clause
     */
    public static class UpdateIndexWhere100 extends PerformanceBase {
        private String[] mWhere = new String[DATASET_SIZE];
        ContentValues[] mValues = new ContentValues[DATASET_SIZE];

        @Override
        protected void prepareForTest() {
            Random random = new Random(42);

            mDatabase.execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");
            mDatabase.execSQL("CREATE INDEX i1b ON t1(b)");

            for (int i = 0; i < DATASET_SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < DATASET_SIZE; i++) {
                int lower = i * 100;
                int upper = (i + 10) * 100;
                mWhere[i] = "b >= " + lower + " AND b < " + upper;
                ContentValues b = new ContentValues(1);
                b.put("b", upper);
                mValues[i] = b;

            }
        }

        public void testRun() {
            for (int i = 0; i < DATASET_SIZE; i++) {
                mDatabase.update("t1", mValues[i], mWhere[i], null);
            }
        }
    }

    /**
     * 100 update's without an index with where clause
     */
    public static class UpdateWhere100 extends PerformanceBase {
        private String[] mWhere = new String[DATASET_SIZE];
        ContentValues[] mValues = new ContentValues[DATASET_SIZE];

        @Override
        protected void prepareForTest() {
            Random random = new Random(42);

            mDatabase.execSQL("CREATE TABLE t1(a INTEGER, b INTEGER, c VARCHAR(100))");

            for (int i = 0; i < DATASET_SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t1 VALUES(" + i + "," + r + ",'"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < DATASET_SIZE; i++) {

                int lower = i * 100;
                int upper = (i + 10) * 100;
                mWhere[i] = "b >= " + lower + " AND b < " + upper;
                ContentValues b = new ContentValues(1);
                b.put("b", upper);
                mValues[i] = b;
            }
        }

        public void testRun() {
            for (int i = 0; i < DATASET_SIZE; i++) {
                mDatabase.update("t1", mValues[i], mWhere[i], null);
            }
        }
    }

    /**
     * 100 selects for a String - contains 'e'
     */
    public static class SelectStringContains100 extends PerformanceBase {
        private static final String[] COLUMNS = {"t3.a"};
        private String[] mWhere = new String[DATASET_SIZE];

        @Override
        protected void prepareForTest() {
            Random random = new Random(42);

            mDatabase.execSQL("CREATE TABLE t3(a VARCHAR(100))");

            for (int i = 0; i < DATASET_SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t3 VALUES('"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < DATASET_SIZE; i++) {
                mWhere[i] = "a LIKE '*e*'";

            }
        }

        public void testRun() {
            for (int i = 0; i < FAST_OP_COUNT; i++) {
                checkCursor(mDatabase
                        .query("t3", COLUMNS, mWhere[i % DATASET_SIZE], null, null, null, null));
            }
        }
    }

    /**
     * 100 selects for a String - contains 'e'-indexed table
     */
    public static class SelectStringIndexedContains100 extends PerformanceBase {
        private static final String[] COLUMNS = {"t3.a"};
        private String[] mWhere = new String[DATASET_SIZE];

        @Override
        protected void prepareForTest() {
            Random random = new Random(42);

            mDatabase.execSQL("CREATE TABLE t3(a VARCHAR(100))");
            mDatabase.execSQL("CREATE INDEX i3a ON t3(a)");

            for (int i = 0; i < DATASET_SIZE; i++) {
                int r = random.nextInt(100000);
                mDatabase.execSQL("INSERT INTO t3 VALUES('"
                        + numberName(r) + "')");
            }

            for (int i = 0; i < DATASET_SIZE; i++) {
                mWhere[i] = "a LIKE '*e*'";

            }
        }

        public void testRun() {
            for (int i = 0; i < FAST_OP_COUNT; i++) {
                checkCursor(mDatabase
                        .query("t3", COLUMNS, mWhere[i % DATASET_SIZE], null, null, null, null));
            }
        }
    }

    static final String[] ONES =
        {"zero", "one", "two", "three", "four", "five", "six", "seven",
        "eight", "nine", "ten", "eleven", "twelve", "thirteen",
        "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
        "nineteen"};

    static final String[] TENS =
        {"", "ten", "twenty", "thirty", "forty", "fifty", "sixty",
        "seventy", "eighty", "ninety"};

    static Map<String, Long> getIoStats() {
        String ioStat = "/proc/self/io";
        Map<String, Long> results = new ArrayMap<>();
        try {
            List<String> lines = Files.readAllLines(new File(ioStat).toPath());
            for (String line : lines) {
                line = line.trim();
                String[] split = line.split(":");
                if (split.length == 2) {
                    try {
                        String key = split[0].trim();
                        Long value = Long.valueOf(split[1].trim());
                        results.put(key, value);
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "Cannot parse number from " + line);
                    }
                } else if (line.isEmpty()) {
                    Log.e(TAG, "Cannot parse line " + line);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Can't read: " + ioStat, e);
        }
        return results;
    }

}
