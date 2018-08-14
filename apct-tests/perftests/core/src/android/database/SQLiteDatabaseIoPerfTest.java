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

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.LargeTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.util.Preconditions;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Performance tests for measuring amount of data written during typical DB operations
 *
 * <p>To run: bit CorePerfTests:android.database.SQLiteDatabaseIoPerfTest
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class SQLiteDatabaseIoPerfTest {
    private static final String TAG = "SQLiteDatabaseIoPerfTest";
    private static final String DB_NAME = "db_io_perftest";
    private static final int DEFAULT_DATASET_SIZE = 500;

    private Long mWriteBytes;

    private SQLiteDatabase mDatabase;
    private Context mContext;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        mContext.deleteDatabase(DB_NAME);
        mDatabase = mContext.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
        mDatabase.execSQL("CREATE TABLE T1 "
                + "(_ID INTEGER PRIMARY KEY, COL_A INTEGER, COL_B VARCHAR(100), COL_C REAL)");
    }

    @After
    public void tearDown() {
        mDatabase.close();
        mContext.deleteDatabase(DB_NAME);
    }

    @Test
    public void testDatabaseModifications() {
        startMeasuringWrites();
        ContentValues cv = new ContentValues();
        String[] whereArg = new String[1];
        for (int i = 0; i < DEFAULT_DATASET_SIZE; i++) {
            cv.put("_ID", i);
            cv.put("COL_A", i);
            cv.put("COL_B", "NewValue");
            cv.put("COL_C", 1.0);
            assertEquals(i, mDatabase.insert("T1", null, cv));
        }
        cv = new ContentValues();
        for (int i = 0; i < DEFAULT_DATASET_SIZE; i++) {
            cv.put("COL_B", "UpdatedValue");
            cv.put("COL_C", 1.1);
            whereArg[0] = String.valueOf(i);
            assertEquals(1, mDatabase.update("T1", cv, "_ID=?", whereArg));
        }
        for (int i = 0; i < DEFAULT_DATASET_SIZE; i++) {
            whereArg[0] = String.valueOf(i);
            assertEquals(1, mDatabase.delete("T1", "_ID=?", whereArg));
        }
        // Make sure all changes are written to disk
        mDatabase.close();
        long bytes = endMeasuringWrites();
        sendResults("testDatabaseModifications" , bytes);
    }

    @Test
    public void testInsertsWithTransactions() {
        startMeasuringWrites();
        final int txSize = 10;
        ContentValues cv = new ContentValues();
        for (int i = 0; i < DEFAULT_DATASET_SIZE * 5; i++) {
            if (i % txSize == 0) {
                mDatabase.beginTransaction();
            }
            if (i % txSize == txSize-1) {
                mDatabase.setTransactionSuccessful();
                mDatabase.endTransaction();

            }
            cv.put("_ID", i);
            cv.put("COL_A", i);
            cv.put("COL_B", "NewValue");
            cv.put("COL_C", 1.0);
            assertEquals(i, mDatabase.insert("T1", null, cv));
        }
        // Make sure all changes are written to disk
        mDatabase.close();
        long bytes = endMeasuringWrites();
        sendResults("testInsertsWithTransactions" , bytes);
    }

    private void startMeasuringWrites() {
        Preconditions.checkState(mWriteBytes == null, "Measurement already started");
        mWriteBytes = getIoStats().get("write_bytes");
    }

    private long endMeasuringWrites() {
        Preconditions.checkState(mWriteBytes != null, "Measurement wasn't started");
        Long newWriteBytes = getIoStats().get("write_bytes");
        return newWriteBytes - mWriteBytes;
    }

    private void sendResults(String testName, long writeBytes) {
        Log.i(TAG, testName + " write_bytes: " + writeBytes);
        Bundle status = new Bundle();
        status.putLong("write_bytes", writeBytes);
        InstrumentationRegistry.getInstrumentation().sendStatus(Activity.RESULT_OK, status);
    }

    private static Map<String, Long> getIoStats() {
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
