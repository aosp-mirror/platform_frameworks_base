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
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.test.suitebuilder.annotation.Suppress;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;

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
        mDatabase.disableWriteAheadLogging();
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
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("less than the current max value"));
        }
        // set pool size to a valid value
        mDatabase.setConnectionPoolSize(10);
        assertEquals(10, mDatabase.mConnectionPool.getMaxPoolSize());
        // can't set pool size to < the value above
        try {
            mDatabase.setConnectionPoolSize(1);
            fail("IllegalStateException expected");
        } catch (IllegalArgumentException e) {
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
    @Suppress // run this test only if you need to collect the numbers from this test
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
    private static final int TIME_TO_RUN_WAL_TEST_FOR = 15; // num sec this test should run
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

    @SmallTest
    public void testLruCachingOfSqliteCompiledSqlObjs() {
        mDatabase.disableWriteAheadLogging();
        mDatabase.execSQL("CREATE TABLE test (i int, j int);");
        mDatabase.execSQL("insert into test values(1,1);");
        // set cache size
        int N = SQLiteDatabase.MAX_SQL_CACHE_SIZE;
        mDatabase.setMaxSqlCacheSize(N);

        // do N+1 queries - and when the 0th entry is removed from LRU cache due to the
        // insertion of (N+1)th entry, make sure 0th entry is closed
        ArrayList<Integer> stmtObjs = new ArrayList<Integer>();
        ArrayList<String> sqlStrings = new ArrayList<String>();
        SQLiteStatement stmt0 = null;
        for (int i = 0; i < N+1; i++) {
            String s = "select * from test where i = " + i + " and j = ?";
            sqlStrings.add(s);
            SQLiteStatement c = mDatabase.compileStatement(s);
            c.bindLong(1, 1);
            stmtObjs.add(i, c.getSqlStatementId());
            if (i == 0) {
                // save thie SQLiteStatement obj. we want to make sure it is thrown out of
                // the cache and its handle is 0'ed.
                stmt0 = c;
            }
            c.close();
        }
        // is 0'th entry out of the cache?
        assertEquals(0, stmt0.getSqlStatementId());
        for (int i = 1; i < N+1; i++) {
            SQLiteCompiledSql compSql = mDatabase.getCompiledStatementForSql(sqlStrings.get(i));
            assertNotNull(compSql);
            assertTrue(stmtObjs.contains(compSql.nStatement));
        }
    }

    @MediumTest
    public void testDbCloseReleasingAllCachedSql() {
        mDatabase.execSQL("CREATE TABLE test (_id INTEGER PRIMARY KEY, text1 TEXT, text2 TEXT, " +
                "num1 INTEGER, num2 INTEGER, image BLOB);");
        final String statement = "DELETE FROM test WHERE _id=?;";
        SQLiteStatement statementDoNotClose = mDatabase.compileStatement(statement);
        // SQl statement is compiled only at find bind or execute call
        assertTrue(statementDoNotClose.getSqlStatementId() == 0);
        statementDoNotClose.bindLong(1, 1);
        assertTrue(statementDoNotClose.getSqlStatementId() > 0);
        int nStatement = statementDoNotClose.getSqlStatementId();
        /* do not close statementDoNotClose object.
         * That should leave it in SQLiteDatabase.mPrograms.
         * mDatabase.close() in tearDown() should release it.
         */
    }

    /**
     * test to make sure the statement finalizations are not done right away but
     * piggy-backed onto the next sql statement execution on the same database.
     */
    @SmallTest
    public void testStatementClose() {
        mDatabase.execSQL("CREATE TABLE test (i int, j int);");
        // fill up statement cache in mDatabase\
        int N = 26;
        mDatabase.setMaxSqlCacheSize(N);
        SQLiteStatement stmt;
        int stmt0Id = 0;
        for (int i = 0; i < N; i ++) {
            stmt = mDatabase.compileStatement("insert into test values(" + i + ", ?);");
            stmt.bindLong(1, 1);
            // keep track of 0th entry
            if (i == 0) {
                stmt0Id = stmt.getSqlStatementId();
            }
            stmt.executeInsert();
            stmt.close();
        }

        // add one more to the cache - and the above 'stmt0Id' should fall out of cache
        SQLiteStatement stmt1 = mDatabase.compileStatement("insert into test values(100, ?);");
        stmt1.bindLong(1, 1);
        stmt1.close();

        // the above close() should have queuedUp the statement for finalization
        ArrayList<Integer> statementIds = mDatabase.getQueuedUpStmtList();
        assertTrue(statementIds.contains(stmt0Id));

        // execute something to see if this statement gets finalized
        mDatabase.execSQL("delete from test where i = 10;");
        statementIds = mDatabase.getQueuedUpStmtList();
        assertEquals(0, statementIds.size());
    }

    /**
     * same as above - except that the statement to be finalized is from Thread # 1.
     * and it is eventually finalized in Thread # 2 when it executes a SQL statement.
     * @throws InterruptedException
     */
    @LargeTest
    public void testStatementCloseDiffThread() throws InterruptedException {
        mDatabase.execSQL("CREATE TABLE test (i int, j int);");
        // fill up statement cache in mDatabase in a thread
        Thread t1 = new Thread() {
            @Override public void run() {
                int N = 26;
                mDatabase.setMaxSqlCacheSize(N);
                SQLiteStatement stmt;
                for (int i = 0; i < N; i ++) {
                    stmt = mDatabase.compileStatement("insert into test values(" + i + ", ?);");
                    stmt.bindLong(1,1);
                    // keep track of 0th entry
                    if (i == 0) {
                        setStmt0Id(stmt.getSqlStatementId());
                    }
                    stmt.executeInsert();
                    stmt.close();
                }
            }
        };
        t1.start();
        // wait for the thread to finish
        t1.join();

        // add one more to the cache - and the above 'stmt0Id' should fall out of cache
        // just for the heck of it, do it in a separate thread
        Thread t2 = new Thread() {
            @Override public void run() {
                SQLiteStatement stmt1 = mDatabase.compileStatement(
                        "insert into test values(100, ?);");
                stmt1.bindLong(1, 1);
                stmt1.close();
            }
        };
        t2.start();
        t2.join();

        // close() in the above thread should have queuedUp the statement for finalization
        ArrayList<Integer> statementIds = mDatabase.getQueuedUpStmtList();
        assertTrue(getStmt0Id() > 0);
        assertTrue(statementIds.contains(stmt0Id));
        assertEquals(1, statementIds.size());

        // execute something to see if this statement gets finalized
        // again do it in a separate thread
        Thread t3 = new Thread() {
            @Override public void run() {
                mDatabase.execSQL("delete from test where i = 10;");
            }
        };
        t3.start();
        t3.join();

        // is the statement finalized?
        statementIds = mDatabase.getQueuedUpStmtList();
        assertEquals(0, statementIds.size());
    }

    private volatile int stmt0Id = 0;
    private synchronized void setStmt0Id(int stmt0Id) {
        this.stmt0Id = stmt0Id;
    }
    private synchronized int getStmt0Id() {
        return this.stmt0Id;
    }

    /**
     * same as above - except that the queue of statements to be finalized are finalized
     * by database close() operation.
     */
    @LargeTest
    public void testStatementCloseByDbClose() throws InterruptedException {
        mDatabase.execSQL("CREATE TABLE test (i int, j int);");
        // fill up statement cache in mDatabase in a thread
        Thread t1 = new Thread() {
            @Override public void run() {
                int N = 26;
                mDatabase.setMaxSqlCacheSize(N);
                SQLiteStatement stmt;
                for (int i = 0; i < N; i ++) {
                    stmt = mDatabase.compileStatement("insert into test values(" + i + ", ?);");
                    stmt.bindLong(1, 1);
                    // keep track of 0th entry
                    if (i == 0) {
                        setStmt0Id(stmt.getSqlStatementId());
                    }
                    stmt.executeInsert();
                    stmt.close();
                }
            }
        };
        t1.start();
        // wait for the thread to finish
        t1.join();

        // add one more to the cache - and the above 'stmt0Id' should fall out of cache
        // just for the heck of it, do it in a separate thread
        Thread t2 = new Thread() {
            @Override public void run() {
                SQLiteStatement stmt1 = mDatabase.compileStatement(
                        "insert into test values(100, ?);");
                stmt1.bindLong(1, 1);
                stmt1.close();
            }
        };
        t2.start();
        t2.join();

        // close() in the above thread should have queuedUp the statement for finalization
        ArrayList<Integer> statementIds = mDatabase.getQueuedUpStmtList();
        assertTrue(getStmt0Id() > 0);
        assertTrue(statementIds.contains(stmt0Id));
        assertEquals(1, statementIds.size());

        // close the database. everything from mClosedStatementIds in mDatabase
        // should be finalized and cleared from the list
        // again do it in a separate thread
        Thread t3 = new Thread() {
            @Override public void run() {
                mDatabase.close();
            }
        };
        t3.start();
        t3.join();

        // check mClosedStatementIds in mDatabase. it should be empty
        statementIds = mDatabase.getQueuedUpStmtList();
        assertEquals(0, statementIds.size());
    }
}
