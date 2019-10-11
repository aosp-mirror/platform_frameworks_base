/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.database.sqlite.SQLiteDatabase;
import android.test.AndroidTestCase;
import android.util.Log;

import androidx.test.filters.Suppress;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

/* 
 * This is a series of unit tests for database locks.
 *
 * Suppress these tests for now, since they have has inconsistent results.
 * This should be turned into a performance tracking test.
 */
@Suppress
public class DatabaseLockTest extends AndroidTestCase {

    private static final int NUM_ITERATIONS = 100;
    private static final int SLEEP_TIME = 30;
    private static final int MAX_ALLOWED_LATENCY_TIME = 30;
    private SQLiteDatabase mDatabase;
    private File mDatabaseFile;
    private AtomicInteger mCounter = new AtomicInteger();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        File parentDir = getContext().getFilesDir();
        mDatabaseFile = new File(parentDir, "database_test.db");
        
        if (mDatabaseFile.exists()) {
            mDatabaseFile.delete();
        }
        mDatabase = SQLiteDatabase.openOrCreateDatabase(mDatabaseFile.getPath(), null);
        assertNotNull(mDatabase);
    }

    @Override
    protected void tearDown() throws Exception {
        mDatabase.close();
        mDatabaseFile.delete();
        super.tearDown();
    }

    /*
     * testLockFairness() tests the fairness of prioritizing multiple threads 
     * attempting to access a database concurrently.
     * This test is intended to verify that, when two threads are accessing the
     * same database at the same time with the same prioritization, neither thread 
     * is locked out and prevented from accessing the database.
     */
    @Suppress
    public void testLockFairness() {
        startDatabaseFairnessThread();
        int previous = 0;
        for (int i = 0; i < NUM_ITERATIONS; i++) { 
            mDatabase.beginTransaction();
            int val = mCounter.get();
            if (i == 0) {
                previous = val - i;
            }
            assertTrue(previous == (val - i));
            try {
                Thread.currentThread().sleep(SLEEP_TIME); 
            } catch (InterruptedException e) {
                // ignore
            }
            mDatabase.endTransaction();
        }
    }
    
    /*
     * This function is to create the second thread for testLockFairness() test.
     */
    private void startDatabaseFairnessThread() {
        Thread thread = new DatabaseFairnessThread();
        thread.start();
    }

    private class DatabaseFairnessThread extends Thread {
        @Override
        public void run() {
            for (int i = 0; i < NUM_ITERATIONS; i++) {
                mDatabase.beginTransaction();
                mCounter.incrementAndGet();
                try {
                    Thread.sleep(SLEEP_TIME);
                } catch (InterruptedException e) {
                    // ignore
                }
                mDatabase.endTransaction();
            }
        }
    }    
    
    /*
     * testLockLatency() tests the latency of database locks.
     * This test is intended to verify that, even when two threads are accessing
     * the same database, the locking/unlocking of the database is done within an
     * appropriate amount of time (MAX_ALLOWED_LATENCY_TIME).
     */
    @Suppress
    public void testLockLatency() {
        startDatabaseLatencyThread();
        long sumTime = 0;
        long maxTime = 0;
        for (int i = 0; i < NUM_ITERATIONS; i++) { 
            long startTime = System.currentTimeMillis();
            mDatabase.beginTransaction();
            long endTime = System.currentTimeMillis();
            long elapsedTime = endTime - startTime;
            if (maxTime < elapsedTime) {
                maxTime = elapsedTime;
            }
            sumTime += elapsedTime;
            try {
                Thread.sleep(SLEEP_TIME); 
            } catch (InterruptedException e) {
                // ignore
            }   
            mDatabase.endTransaction();
        }
        long averageTime = sumTime/NUM_ITERATIONS;
        Log.i("DatabaseLockLatency", "AverageTime: " + averageTime);
        Log.i("DatabaseLockLatency", "MaxTime: " + maxTime);
        assertTrue( (averageTime - SLEEP_TIME) <= MAX_ALLOWED_LATENCY_TIME);
    }
    
    /*
     * This function is to create the second thread for testLockLatency() test.
     */
    private void startDatabaseLatencyThread() {
        Thread thread = new DatabaseLatencyThread();
        thread.start();
    }

    private class DatabaseLatencyThread extends Thread {
        @Override
        public void run() {
            for (int i = 0; i < NUM_ITERATIONS; i++) 
            {
                mDatabase.beginTransaction();
                try {
                    Thread.sleep(SLEEP_TIME);
                } catch (InterruptedException e) {
                    // ignore
                } 
                mDatabase.endTransaction();
            }
        }
    }        
}
