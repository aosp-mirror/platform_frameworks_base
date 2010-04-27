/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.content.Context;
import android.database.DatabaseUtils;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import java.io.File;

public class SQLiteDatabaseTest extends AndroidTestCase {
    private static final String TAG = "DatabaseGeneralTest";

    private static final int CURRENT_DATABASE_VERSION = 42;
    private SQLiteDatabase mDatabase;
    private File mDatabaseFile;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        dbSetUp();
    }

    @Override
    protected void tearDown() throws Exception {
        dbTeardown();
        super.tearDown();
    }

    private void dbTeardown() throws Exception {
        mDatabase.close();
        mDatabaseFile.delete();
    }

    private void dbSetUp() throws Exception {
        File dbDir = getContext().getDir(this.getClass().getName(), Context.MODE_PRIVATE);
        mDatabaseFile = new File(dbDir, "database_test.db");
        if (mDatabaseFile.exists()) {
            mDatabaseFile.delete();
        }
        mDatabase = SQLiteDatabase.openOrCreateDatabase(mDatabaseFile.getPath(), null, null);
        assertNotNull(mDatabase);
        mDatabase.setVersion(CURRENT_DATABASE_VERSION);
    }

    @SmallTest
    public void testEnableWriteAheadLogging() {
        assertNull(mDatabase.mConnectionPool);
        mDatabase.enableWriteAheadLogging();
        DatabaseConnectionPool pool = mDatabase.mConnectionPool;
        assertNotNull(pool);
        // make the same call again and make sure the pool already setup is not re-created
        mDatabase.enableWriteAheadLogging();
        assertEquals(pool, mDatabase.mConnectionPool);
    }

    @SmallTest
    public void testSetConnectionPoolSize() {
        mDatabase.enableWriteAheadLogging();
        // can't set pool size to zero
        try {
            mDatabase.setConnectionPoolSize(0);
            fail("IllegalStateException expected");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("less than the current max value"));
        }
        // set pool size to a valid value
        mDatabase.setConnectionPoolSize(10);
        assertEquals(10, mDatabase.mConnectionPool.getMaxPoolSize());
        // can't set pool size to < the value above
        try {
            mDatabase.setConnectionPoolSize(1);
            fail("IllegalStateException expected");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("less than the current max value"));
        }
    }

    /**
     * Test to ensure that readers are able to read the database data (old versions)
     * EVEN WHEN the writer is in a transaction on the same database.
     *<p>
     * This test starts 1 Writer and 2 Readers and sets up connection pool for readers
     * by calling the method {@link SQLiteDatabase#enableWriteAheadLogging()}.
     * <p>
     * Writer does the following in a tight loop
     * <pre>
     *     begin transaction
     *     insert into table_1
     *     insert into table_2
     *     commit
     * </pre>
     * <p>
     * As long a the writer is alive, Readers do the following in a tight loop at the same time
     * <pre>
     *     Reader_K does "select count(*) from table_K"  where K = 1 or 2
     * </pre>
     * <p>
     * The test is run for TIME_TO_RUN_WAL_TEST_FOR sec.
     * <p>
     * The test is repeated for different connection-pool-sizes (1..3)
     * <p>
     * And at the end of of each test, the following statistics are printed
     * <ul>
     *    <li>connection-pool-size</li>
     *    <li>number-of-transactions by writer</li>
     *    <li>number of reads by reader_K while the writer is IN or NOT-IN xaction</li>
     * </ul>
     */
    @LargeTest
    public void testConcurrencyEffectsOfConnPool() throws Exception {
        // run the test with sqlite WAL enable
        runConnectionPoolTest(true);

        // run the same test WITHOUT sqlite WAL enabled
        runConnectionPoolTest(false);
    }

    private void runConnectionPoolTest(boolean useWal) throws Exception {
        int M = 3;
        StringBuilder[] buff = new StringBuilder[M];
        for (int i = 0; i < M; i++) {
            if (useWal) {
                // set up connection pool
                mDatabase.enableWriteAheadLogging();
                mDatabase.setConnectionPoolSize(i + 1);
            }
            mDatabase.execSQL("CREATE TABLE t1 (i int, j int);");
            mDatabase.execSQL("CREATE TABLE t2 (i int, j int);");
            mDatabase.beginTransaction();
            for (int k = 0; k < 5; k++) {
                mDatabase.execSQL("insert into t1 values(?,?);", new String[] {k+"", k+""});
                mDatabase.execSQL("insert into t2 values(?,?);", new String[] {k+"", k+""});
            }
            mDatabase.setTransactionSuccessful();
            mDatabase.endTransaction();

            // start a writer
            Writer w = new Writer(mDatabase);

            // initialize an array of counters to be passed to the readers
            Reader r1 = new Reader(mDatabase, "t1", w, 0);
            Reader r2 = new Reader(mDatabase, "t2", w, 1);
            w.start();
            r1.start();
            r2.start();

            // wait for all threads to die
            w.join();
            r1.join();
            r2.join();

            // print the stats
            int[][] counts = getCounts();
            buff[i] = new StringBuilder();
            buff[i].append("connpool-size = ");
            buff[i].append(i + 1);
            buff[i].append(", num xacts by writer = ");
            buff[i].append(getNumXacts());
            buff[i].append(", num-reads-in-xact/NOT-in-xact by reader1 = ");
            buff[i].append(counts[0][1] + "/" + counts[0][0]);
            buff[i].append(", by reader2 = ");
            buff[i].append(counts[1][1] + "/" + counts[1][0]);

            Log.i(TAG, "done testing for conn-pool-size of " + (i+1));

            dbTeardown();
            dbSetUp();
        }
        Log.i(TAG, "duration of test " + TIME_TO_RUN_WAL_TEST_FOR + " sec");
        for (int i = 0; i < M; i++) {
            Log.i(TAG, buff[i].toString());
        }
    }

    private boolean inXact = false;
    private int numXacts;
    private static final int TIME_TO_RUN_WAL_TEST_FOR = 15; // num sec this test shoudl run
    private int[][] counts = new int[2][2];

    private synchronized boolean inXact() {
        return inXact;
    }

    private synchronized void setInXactFlag(boolean flag) {
        inXact = flag;
    }

    private synchronized void setCounts(int readerNum, int[] numReads) {
        counts[readerNum][0] = numReads[0];
        counts[readerNum][1] = numReads[1];
    }

    private synchronized int[][] getCounts() {
        return counts;
    }

    private synchronized void setNumXacts(int num) {
        numXacts = num;
    }

    private synchronized int getNumXacts() {
        return numXacts;
    }

    private class Writer extends Thread {
        private SQLiteDatabase db = null;
        public Writer(SQLiteDatabase db) {
            this.db = db;
        }
        @Override public void run() {
            // in a loop, for N sec, do the following
            //    BEGIN transaction
            //    insert into table t1, t2
            //    Commit
            long now = System.currentTimeMillis();
            int k;
            for (k = 0;(System.currentTimeMillis() - now) / 1000 < TIME_TO_RUN_WAL_TEST_FOR; k++) {
                db.beginTransactionNonExclusive();
                setInXactFlag(true);
                for (int i = 0; i < 10; i++) {
                    db.execSQL("insert into t1 values(?,?);", new String[] {i+"", i+""});
                    db.execSQL("insert into t2 values(?,?);", new String[] {i+"", i+""});
                }
                db.setTransactionSuccessful();
                setInXactFlag(false);
                db.endTransaction();
            }
            setNumXacts(k);
        }
    }

    private class Reader extends Thread {
        private SQLiteDatabase db = null;
        private String table = null;
        private Writer w = null;
        private int readerNum;
        private int[] numReads = new int[2];
        public Reader(SQLiteDatabase db, String table, Writer w, int readerNum) {
            this.db = db;
            this.table = table;
            this.w = w;
            this.readerNum = readerNum;
        }
        @Override public void run() {
            // while the write is alive, in a loop do the query on a table
            while (w.isAlive()) {
                for (int i = 0; i < 10; i++) {
                    DatabaseUtils.longForQuery(db, "select count(*) from " + this.table, null);
                    // update count of reads
                    numReads[inXact() ? 1 : 0] += 1;
                }
            }
            setCounts(readerNum, numReads);
        }
    }
}
