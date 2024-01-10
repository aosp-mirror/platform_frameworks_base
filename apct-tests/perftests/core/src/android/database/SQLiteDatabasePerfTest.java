/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package android.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.util.Log;
import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;
import java.io.File;
import java.util.ArrayList;
import java.util.Random;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Performance tests for typical CRUD operations and loading rows into the Cursor
 *
 * <p>To run: bit CorePerfTests:android.database.SQLiteDatabasePerfTest
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class SQLiteDatabasePerfTest {
    // TODO b/64262688 Add Concurrency tests to compare WAL vs DELETE read/write
    private static final String DB_NAME = "dbperftest";
    private static final int DEFAULT_DATASET_SIZE = 1000;

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();
    private SQLiteDatabase mDatabase;
    private Context mContext;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        mContext.deleteDatabase(DB_NAME);

        createOrOpenTestDatabase(
                SQLiteDatabase.JOURNAL_MODE_TRUNCATE, SQLiteDatabase.SYNC_MODE_FULL);
    }

    @After
    public void tearDown() {
        mDatabase.close();
        mContext.deleteDatabase(DB_NAME);
    }

    private void createOrOpenTestDatabase(String journalMode, String syncMode) {
        SQLiteDatabase.OpenParams.Builder paramsBuilder = new SQLiteDatabase.OpenParams.Builder();
        File dbFile = mContext.getDatabasePath(DB_NAME);
        if (journalMode != null) {
            paramsBuilder.setJournalMode(journalMode);
        }
        if (syncMode != null) {
            paramsBuilder.setSynchronousMode(syncMode);
        }
        paramsBuilder.addOpenFlags(SQLiteDatabase.CREATE_IF_NECESSARY);

        mDatabase = SQLiteDatabase.openDatabase(dbFile, paramsBuilder.build());
        mDatabase.execSQL("CREATE TABLE T1 "
                + "(_ID INTEGER PRIMARY KEY, COL_A INTEGER, COL_B VARCHAR(100), COL_C REAL)");
        mDatabase.execSQL("CREATE TABLE T2 ("
                + "_ID INTEGER PRIMARY KEY, COL_A VARCHAR(100), T1_ID INTEGER,"
                + "FOREIGN KEY(T1_ID) REFERENCES T1 (_ID))");
    }

    @Test
    public void testSelect() {
        insertT1TestDataSet();

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        Random rnd = new Random(0);
        while (state.keepRunning()) {
            int index = rnd.nextInt(DEFAULT_DATASET_SIZE);
            try (Cursor cursor = mDatabase.rawQuery("SELECT _ID, COL_A, COL_B, COL_C FROM T1 "
                    + "WHERE _ID=?", new String[]{String.valueOf(index)})) {
                assertTrue(cursor.moveToNext());
                assertEquals(index, cursor.getInt(0));
                assertEquals(index, cursor.getInt(1));
                assertEquals("T1Value" + index, cursor.getString(2));
                assertEquals(1.1 * index, cursor.getDouble(3), 0.0000001d);
            }
        }
    }

    @Test
    public void testSelectCacheMissRate() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        insertT1TestDataSet();

        ArrayList<String> queryPool = new ArrayList<>();
        queryPool.add("SELECT _ID, COL_A, COL_B, COL_C FROM T1 WHERE _ID=?");
        queryPool.add("SELECT _ID FROM T1 WHERE _ID=?");
        queryPool.add("SELECT COL_A FROM T1 WHERE _ID=?");
        queryPool.add("SELECT COL_B FROM T1 WHERE _ID=?");
        queryPool.add("SELECT COL_C FROM T1 WHERE _ID=?");
        queryPool.add("SELECT _ID, COL_A FROM T1 WHERE _ID=?");
        queryPool.add("SELECT _ID, COL_B FROM T1 WHERE _ID=?");
        queryPool.add("SELECT _ID, COL_C FROM T1 WHERE _ID=?");
        queryPool.add("SELECT COL_A, COL_B FROM T1 WHERE _ID=?");
        queryPool.add("SELECT COL_A, COL_C FROM T1 WHERE _ID=?");
        queryPool.add("SELECT COL_B, COL_C FROM T1 WHERE _ID=?");
        while (state.keepRunning()) {
            Random rnd = new Random(0);

            int queries = 1000;
            for (int iQuery = 0; iQuery < queries; ++iQuery) {
                int queryIndex = rnd.nextInt(queryPool.size());
                int index = rnd.nextInt(DEFAULT_DATASET_SIZE);

                try (Cursor cursor = mDatabase.rawQuery(
                             queryPool.get(queryIndex), new String[] {String.valueOf(index)})) {
                    assertTrue(cursor.moveToNext());
                }
            }
        }

        Log.d("testSelectMemory",
                "cacheMissRate: " + mDatabase.getStatementCacheMissRate()
                        + "Total Statements: " + mDatabase.getTotalPreparedStatements()
                        + ". Misses: " + mDatabase.getTotalStatementCacheMisses());

        // Make sure caching is working and our miss rate should definitely be less than 100%
        // however, we would expect this number to be actually closer to 0.
        assertTrue(mDatabase.getStatementCacheMissRate() < 1);
        mDatabase.close();
        mContext.deleteDatabase(DB_NAME);
    }

    @Test
    public void testSelectMultipleRows() {
        insertT1TestDataSet();

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        Random rnd = new Random(0);
        final int querySize = 50;
        while (state.keepRunning()) {
            int index = rnd.nextInt(DEFAULT_DATASET_SIZE - querySize - 1);
            try (Cursor cursor = mDatabase.rawQuery("SELECT _ID, COL_A, COL_B, COL_C FROM T1 "
                            + "WHERE _ID BETWEEN ? and ? ORDER BY _ID",
                    new String[]{String.valueOf(index), String.valueOf(index + querySize - 1)})) {
                int i = 0;
                while(cursor.moveToNext()) {
                    assertEquals(index, cursor.getInt(0));
                    assertEquals(index, cursor.getInt(1));
                    assertEquals("T1Value" + index, cursor.getString(2));
                    assertEquals(1.1 * index, cursor.getDouble(3), 0.0000001d);
                    index++;
                    i++;
                }
                assertEquals(querySize, i);
            }
        }
    }

    @Test
    public void testCursorIterateForward() {
        // A larger dataset is needed to exceed default CursorWindow size
        int datasetSize = DEFAULT_DATASET_SIZE * 50;
        insertT1TestDataSet(datasetSize);

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            try (Cursor cursor = mDatabase
                    .rawQuery("SELECT _ID, COL_A, COL_B, COL_C FROM T1 ORDER BY _ID", null)) {
                int i = 0;
                while(cursor.moveToNext()) {
                    assertEquals(i, cursor.getInt(0));
                    assertEquals(i, cursor.getInt(1));
                    assertEquals("T1Value" + i, cursor.getString(2));
                    assertEquals(1.1 * i, cursor.getDouble(3), 0.0000001d);
                    i++;
                }
                assertEquals(datasetSize, i);
            }
        }
    }

    @Test
    public void testCursorIterateBackwards() {
        // A larger dataset is needed to exceed default CursorWindow size
        int datasetSize = DEFAULT_DATASET_SIZE * 50;
        insertT1TestDataSet(datasetSize);

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            try (Cursor cursor = mDatabase
                    .rawQuery("SELECT _ID, COL_A, COL_B, COL_C FROM T1 ORDER BY _ID", null)) {
                int i = datasetSize - 1;
                while(cursor.moveToPosition(i)) {
                    assertEquals(i, cursor.getInt(0));
                    assertEquals(i, cursor.getInt(1));
                    assertEquals("T1Value" + i, cursor.getString(2));
                    assertEquals(1.1 * i, cursor.getDouble(3), 0.0000001d);
                    i--;
                }
                assertEquals(-1, i);
            }
        }
    }

    @Test
    public void testInnerJoin() {
        mDatabase.setForeignKeyConstraintsEnabled(true);
        mDatabase.beginTransaction();
        insertT1TestDataSet();
        insertT2TestDataSet();
        mDatabase.setTransactionSuccessful();
        mDatabase.endTransaction();

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        Random rnd = new Random(0);
        while (state.keepRunning()) {
            int index = rnd.nextInt(1000);
            try (Cursor cursor = mDatabase.rawQuery(
                    "SELECT T1._ID, T1.COL_A, T1.COL_B, T1.COL_C, T2.COL_A FROM T1 "
                    + "INNER JOIN T2 on T2.T1_ID=T1._ID WHERE T1._ID = ?",
                    new String[]{String.valueOf(index)})) {
                assertTrue(cursor.moveToNext());
                assertEquals(index, cursor.getInt(0));
                assertEquals(index, cursor.getInt(1));
                assertEquals("T1Value" + index, cursor.getString(2));
                assertEquals(1.1 * index, cursor.getDouble(3), 0.0000001d);
                assertEquals("T2Value" + index, cursor.getString(4));
            }
        }
    }

    /**
     * This test measures the insertion of a single row into a database using DELETE journal and
     * synchronous modes.
     */
    @Test
    public void testInsert() {
        insertT1TestDataSet();

        testInsertInternal("testInsert");
    }

    @Test
    public void testInsertWithPersistFull() {
        recreateTestDatabase(SQLiteDatabase.JOURNAL_MODE_PERSIST, SQLiteDatabase.SYNC_MODE_FULL);
        insertT1TestDataSet();
        testInsertInternal("testInsertWithPersistFull");
    }

    private void testInsertInternal(String traceTag) {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        ContentValues cv = new ContentValues();
        cv.put("_ID", DEFAULT_DATASET_SIZE);
        cv.put("COL_B", "NewValue");
        cv.put("COL_C", 1.1);
        String[] deleteArgs = new String[] {String.valueOf(DEFAULT_DATASET_SIZE)};

        while (state.keepRunning()) {
            android.os.Trace.beginSection(traceTag);
            assertEquals(DEFAULT_DATASET_SIZE, mDatabase.insert("T1", null, cv));
            state.pauseTiming();
            assertEquals(1, mDatabase.delete("T1", "_ID=?", deleteArgs));
            state.resumeTiming();
            android.os.Trace.endSection();
        }
    }

    /**
     * This test measures the insertion of a single row into a database using WAL journal mode and
     * NORMAL synchronous mode.
     */
    @Test
    public void testInsertWithWalNormalMode() {
        recreateTestDatabase(SQLiteDatabase.JOURNAL_MODE_WAL, SQLiteDatabase.SYNC_MODE_NORMAL);
        insertT1TestDataSet();

        testInsertInternal("testInsertWithWalNormalMode");
    }

    /**
     * This test measures the insertion of a single row into a database using WAL journal mode and
     * FULL synchronous mode. The goal is to see the difference between NORMAL vs FULL sync modes.
     */
    @Test
    public void testInsertWithWalFullMode() {
        recreateTestDatabase(SQLiteDatabase.JOURNAL_MODE_WAL, SQLiteDatabase.SYNC_MODE_FULL);

        insertT1TestDataSet();

        testInsertInternal("testInsertWithWalFullMode");
    }

    /**
     * This test measures the insertion of a multiple rows in a single transaction using WAL journal
     * mode and NORMAL synchronous mode.
     */
    @Test
    public void testBulkInsertWithWalNormalMode() {
        recreateTestDatabase(SQLiteDatabase.JOURNAL_MODE_WAL, SQLiteDatabase.SYNC_MODE_NORMAL);
        testBulkInsertInternal("testBulkInsertWithWalNormalMode");
    }

    @Test
    public void testBulkInsertWithPersistFull() {
        recreateTestDatabase(SQLiteDatabase.JOURNAL_MODE_PERSIST, SQLiteDatabase.SYNC_MODE_FULL);
        testBulkInsertInternal("testBulkInsertWithPersistFull");
    }

    /**
     * This test measures the insertion of a multiple rows in a single transaction using TRUNCATE
     * journal mode and FULL synchronous mode.
     */
    @Test
    public void testBulkInsert() {
        testBulkInsertInternal("testBulkInsert");
    }

    private void testBulkInsertInternal(String traceTag) {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        String[] statements = new String[DEFAULT_DATASET_SIZE];
        for (int i = 0; i < DEFAULT_DATASET_SIZE; ++i) {
            statements[i] = "INSERT INTO T1 VALUES (?,?,?,?)";
        }

        while (state.keepRunning()) {
            android.os.Trace.beginSection(traceTag);
            mDatabase.beginTransaction();
            for (int i = 0; i < DEFAULT_DATASET_SIZE; ++i) {
                mDatabase.execSQL(statements[i], new Object[] {i, i, "T1Value" + i, i * 1.1});
            }
            mDatabase.setTransactionSuccessful();
            mDatabase.endTransaction();
            android.os.Trace.endSection();

            state.pauseTiming();
            mDatabase.execSQL("DELETE FROM T1");
            state.resumeTiming();
        }
    }

    @Test
    public void testDelete() {
        insertT1TestDataSet();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        String[] deleteArgs = new String[]{String.valueOf(DEFAULT_DATASET_SIZE)};
        Object[] insertsArgs = new Object[]{DEFAULT_DATASET_SIZE, DEFAULT_DATASET_SIZE,
                "ValueToDelete", 1.1};

        while (state.keepRunning()) {
            state.pauseTiming();
            mDatabase.execSQL("INSERT INTO T1 VALUES (?, ?, ?, ?)", insertsArgs);
            state.resumeTiming();
            assertEquals(1, mDatabase.delete("T1", "_ID=?", deleteArgs));
        }
    }

    /**
     * This test measures the update of a random row in a database.
     */
    @Test
    public void testUpdateWithWalNormalMode() {
        recreateTestDatabase(SQLiteDatabase.JOURNAL_MODE_WAL, SQLiteDatabase.SYNC_MODE_NORMAL);
        insertT1TestDataSet();
        testUpdateInternal("testUpdateWithWalNormalMode");
    }

    @Test
    public void testUpdateWithPersistFull() {
        recreateTestDatabase(SQLiteDatabase.JOURNAL_MODE_PERSIST, SQLiteDatabase.SYNC_MODE_FULL);
        insertT1TestDataSet();
        testUpdateInternal("testUpdateWithPersistFull");
    }

    @Test
    public void testUpdate() {
        insertT1TestDataSet();
        testUpdateInternal("testUpdate");
    }

    private void testUpdateInternal(String traceTag) {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        Random rnd = new Random(0);
        int i = 0;
        ContentValues cv = new ContentValues();
        String[] argArray = new String[1];
        while (state.keepRunning()) {
            android.os.Trace.beginSection(traceTag);
            int id = rnd.nextInt(DEFAULT_DATASET_SIZE);
            cv.put("COL_A", i);
            cv.put("COL_B", "UpdatedValue");
            cv.put("COL_C", i);
            argArray[0] = String.valueOf(id);
            assertEquals(1, mDatabase.update("T1", cv, "_ID=?", argArray));
            i++;
            android.os.Trace.endSection();
        }
    }

    /**
     * This test measures a multi-threaded read-write environment where there are 2 readers and
     * 1 writer in the database using TRUNCATE journal mode and FULL syncMode.
     */
    @Test
    public void testMultithreadedReadWrite() {
        insertT1TestDataSet();
        performMultithreadedReadWriteTest();
    }

    /**
     * This test measures a multi-threaded read-write environment where there are 2 readers and
     * 1 writer in the database using WAL journal mode and NORMAL syncMode.
     */
    @Test
    public void testMultithreadedReadWriteWithWalNormal() {
        recreateTestDatabase(SQLiteDatabase.JOURNAL_MODE_WAL, SQLiteDatabase.SYNC_MODE_NORMAL);
        insertT1TestDataSet();
        performMultithreadedReadWriteTest();
    }

    private void doReadLoop(int totalIterations) {
        Random rnd = new Random(0);
        int currentIteration = 0;
        while (currentIteration < totalIterations) {
            android.os.Trace.beginSection("ReadDatabase");
            int index = rnd.nextInt(DEFAULT_DATASET_SIZE);
            try (Cursor cursor = mDatabase.rawQuery("SELECT _ID, COL_A, COL_B, COL_C FROM T1 "
                                 + "WHERE _ID=?",
                         new String[] {String.valueOf(index)})) {
                cursor.moveToNext();
                cursor.getInt(0);
                cursor.getInt(1);
                cursor.getString(2);
                cursor.getDouble(3);
            }
            ++currentIteration;
            android.os.Trace.endSection();
        }
    }

    private void doReadLoop(BenchmarkState state) {
        Random rnd = new Random(0);
        while (state.keepRunning()) {
            android.os.Trace.beginSection("ReadDatabase");
            int index = rnd.nextInt(DEFAULT_DATASET_SIZE);
            try (Cursor cursor = mDatabase.rawQuery("SELECT _ID, COL_A, COL_B, COL_C FROM T1 "
                                 + "WHERE _ID=?",
                         new String[] {String.valueOf(index)})) {
                cursor.moveToNext();
                cursor.getInt(0);
                cursor.getInt(1);
                cursor.getString(2);
                cursor.getDouble(3);
            }
            android.os.Trace.endSection();
        }
    }

    private void doUpdateLoop(int totalIterations) {
        Random rnd = new Random(0);
        int i = 0;
        ContentValues cv = new ContentValues();
        String[] argArray = new String[1];

        while (i < totalIterations) {
            android.os.Trace.beginSection("UpdateDatabase");
            int id = rnd.nextInt(DEFAULT_DATASET_SIZE);
            cv.put("COL_A", i);
            cv.put("COL_B", "UpdatedValue");
            cv.put("COL_C", i);
            argArray[0] = String.valueOf(id);
            mDatabase.update("T1", cv, "_ID=?", argArray);
            i++;
            android.os.Trace.endSection();
        }
    }

    private void performMultithreadedReadWriteTest() {
        int totalBGIterations = 10000;
        // Writer - Fixed iterations to avoid consuming cycles from mainloop benchmark iterations
        Thread updateThread = new Thread(() -> { doUpdateLoop(totalBGIterations); });

        // Reader 1 - Fixed iterations to avoid consuming cycles from mainloop benchmark iterations
        Thread readerThread = new Thread(() -> { doReadLoop(totalBGIterations); });

        updateThread.start();
        readerThread.start();

        // Reader 2
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        doReadLoop(state);

        try {
            updateThread.join();
            readerThread.join();
        } catch (Exception e) {
        }
    }

    private void insertT1TestDataSet() {
        insertT1TestDataSet(DEFAULT_DATASET_SIZE);
    }

    private void insertT1TestDataSet(int size) {
        mDatabase.beginTransaction();
        for (int i = 0; i < size; i++) {
            mDatabase.execSQL("INSERT INTO T1 VALUES (?, ?, ?, ?)",
                    new Object[]{i, i, "T1Value" + i, i * 1.1});
        }
        mDatabase.setTransactionSuccessful();
        mDatabase.endTransaction();
    }

    private void insertT2TestDataSet() {
        mDatabase.beginTransaction();
        for (int i = 0; i < DEFAULT_DATASET_SIZE; i++) {
            mDatabase.execSQL("INSERT INTO T2 VALUES (?, ?, ?)",
                    new Object[]{i, "T2Value" + i, i});
        }
        mDatabase.setTransactionSuccessful();
        mDatabase.endTransaction();
    }

    private void recreateTestDatabase(String journalMode, String syncMode) {
        mDatabase.close();
        mContext.deleteDatabase(DB_NAME);
        createOrOpenTestDatabase(journalMode, syncMode);
    }
}
