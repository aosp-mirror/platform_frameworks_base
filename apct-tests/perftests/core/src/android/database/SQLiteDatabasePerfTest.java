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

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Random;

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
        mDatabase = mContext.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
        mDatabase.execSQL("CREATE TABLE T1 "
                + "(_ID INTEGER PRIMARY KEY, COL_A INTEGER, COL_B VARCHAR(100), COL_C REAL)");
        mDatabase.execSQL("CREATE TABLE T2 ("
                + "_ID INTEGER PRIMARY KEY, COL_A VARCHAR(100), T1_ID INTEGER,"
                + "FOREIGN KEY(T1_ID) REFERENCES T1 (_ID))");
    }

    @After
    public void tearDown() {
        mDatabase.close();
        mContext.deleteDatabase(DB_NAME);
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

    @Test
    public void testInsert() {
        insertT1TestDataSet();

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        ContentValues cv = new ContentValues();
        cv.put("_ID", DEFAULT_DATASET_SIZE);
        cv.put("COL_B", "NewValue");
        cv.put("COL_C", 1.1);
        String[] deleteArgs = new String[]{String.valueOf(DEFAULT_DATASET_SIZE)};
        while (state.keepRunning()) {
            assertEquals(DEFAULT_DATASET_SIZE, mDatabase.insert("T1", null, cv));
            state.pauseTiming();
            assertEquals(1, mDatabase.delete("T1", "_ID=?", deleteArgs));
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

    @Test
    public void testUpdate() {
        insertT1TestDataSet();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        Random rnd = new Random(0);
        int i = 0;
        ContentValues cv = new ContentValues();
        String[] argArray = new String[1];
        while (state.keepRunning()) {
            int id = rnd.nextInt(DEFAULT_DATASET_SIZE);
            cv.put("COL_A", i);
            cv.put("COL_B", "UpdatedValue");
            cv.put("COL_C", i);
            argArray[0] = String.valueOf(id);
            assertEquals(1, mDatabase.update("T1", cv, "_ID=?", argArray));
            i++;
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
}

