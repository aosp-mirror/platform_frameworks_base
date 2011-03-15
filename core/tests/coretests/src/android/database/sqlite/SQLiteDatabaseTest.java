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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.DatabaseUtils;
import android.database.DefaultDatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.database.sqlite.SQLiteStatement;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.test.suitebuilder.annotation.Suppress;
import android.util.Log;
import android.util.Pair;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SQLiteDatabaseTest extends AndroidTestCase {
    private static final String TAG = "DatabaseGeneralTest";
    private static final String TEST_TABLE = "test";
    private static final int CURRENT_DATABASE_VERSION = 42;
    private SQLiteDatabase mDatabase;
    private File mDatabaseFile;
    private static final int INSERT = 1;
    private static final int UPDATE = 2;
    private static final int DELETE = 3;
    private static final String DB_NAME = "database_test.db";

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
        mDatabaseFile = new File(dbDir, DB_NAME);
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
    public void testDisableWriteAheadLogging() {
        mDatabase.execSQL("create table test (i int);");
        mDatabase.enableWriteAheadLogging();
        assertNotNull(mDatabase.mConnectionPool);
        // get a pooled database connection
        SQLiteDatabase db = mDatabase.getDbConnection("select * from test");
        assertNotNull(db);
        assertFalse(mDatabase.equals(db));
        assertTrue(db.isOpen());
        // disable WAL - which should close connection pool and all pooled connections
        mDatabase.disableWriteAheadLogging();
        assertNull(mDatabase.mConnectionPool);
        assertFalse(db.isOpen());
    }

    @SmallTest
    public void testCursorsWithClosedDbConnAfterDisableWriteAheadLogging() {
        mDatabase.disableWriteAheadLogging();
        mDatabase.beginTransactionNonExclusive();
        mDatabase.execSQL("create table test (i int);");
        mDatabase.execSQL("insert into test values(1);");
        mDatabase.setTransactionSuccessful();
        mDatabase.endTransaction();
        mDatabase.enableWriteAheadLogging();
        assertNotNull(mDatabase.mConnectionPool);
        assertEquals(0, mDatabase.mConnectionPool.getSize());
        assertEquals(0, mDatabase.mConnectionPool.getFreePoolSize());
        // get a cursor which should use pooled database connection
        Cursor c = mDatabase.rawQuery("select * from test", null);
        assertEquals(1, c.getCount());
        assertEquals(1, mDatabase.mConnectionPool.getSize());
        assertEquals(1, mDatabase.mConnectionPool.getFreePoolSize());
        SQLiteDatabase db = mDatabase.mConnectionPool.getConnectionList().get(0);
        assertTrue(mDatabase.mConnectionPool.isDatabaseObjFree(db));
        // disable WAL - which should close connection pool and all pooled connections
        mDatabase.disableWriteAheadLogging();
        assertNull(mDatabase.mConnectionPool);
        assertFalse(db.isOpen());
        // cursor data should still be accessible because it is fetching data from CursorWindow
        c.moveToNext();
        assertEquals(1, c.getInt(0));
        c.requery();
        assertEquals(1, c.getCount());
        c.moveToNext();
        assertEquals(1, c.getInt(0));
        c.close();
    }

    /**
     * a transaction should be started before a standalone-update/insert/delete statement
     */
    @SmallTest
    public void testStartXactBeforeUpdateSql() throws InterruptedException {
        runTestForStartXactBeforeUpdateSql(INSERT);
        runTestForStartXactBeforeUpdateSql(UPDATE);
        runTestForStartXactBeforeUpdateSql(DELETE);
    }
    private void runTestForStartXactBeforeUpdateSql(int stmtType) throws InterruptedException {
        createTableAndClearCache();

        ContentValues values = new ContentValues();
        // make some changes to data in TEST_TABLE
        for (int i = 0; i < 5; i++) {
            values.put("i", i);
            values.put("j", "i" + System.currentTimeMillis());
            mDatabase.insert(TEST_TABLE, null, values);
            switch (stmtType) {
                case UPDATE:
                    values.put("j", "u" + System.currentTimeMillis());
                    mDatabase.update(TEST_TABLE, values, "i = " + i, null);
                    break;
                case DELETE:
                    mDatabase.delete(TEST_TABLE, "i = 1", null);
                    break;
            }
        }
        // do a query. even though query uses a different database connection,
        // it should still see the above changes to data because the above standalone
        // insert/update/deletes are done in transactions automatically.
        String sql = "select count(*) from " + TEST_TABLE;
        SQLiteStatement stmt = mDatabase.compileStatement(sql);
        final int expectedValue = (stmtType == DELETE) ? 4 : 5;
        assertEquals(expectedValue, stmt.simpleQueryForLong());
        stmt.close();
        Cursor c = mDatabase.rawQuery(sql, null);
        assertEquals(1, c.getCount());
        c.moveToFirst();
        assertEquals(expectedValue, c.getLong(0));
        c.close();

        // do 5 more changes in a transaction but do a query before and after the commit
        mDatabase.beginTransaction();
        for (int i = 10; i < 15; i++) {
            values.put("i", i);
            values.put("j", "i" + System.currentTimeMillis());
            mDatabase.insert(TEST_TABLE, null, values);
            switch (stmtType) {
                case UPDATE:
                    values.put("j", "u" + System.currentTimeMillis());
                    mDatabase.update(TEST_TABLE, values, "i = " + i, null);
                    break;
                case DELETE:
                    mDatabase.delete(TEST_TABLE, "i = 1", null);
                    break;
            }
        }
        mDatabase.setTransactionSuccessful();
        // do a query before commit - should still have 5 rows
        // this query should run in a different thread to force it to use a different database
        // connection
        Thread t = new Thread() {
            @Override public void run() {
                String sql = "select count(*) from " + TEST_TABLE;
                SQLiteStatement stmt = getDb().compileStatement(sql);
                assertEquals(expectedValue, stmt.simpleQueryForLong());
                stmt.close();
                Cursor c = getDb().rawQuery(sql, null);
                assertEquals(1, c.getCount());
                c.moveToFirst();
                assertEquals(expectedValue, c.getLong(0));
                c.close();
            }
        };
        t.start();
        // wait until the above thread is done
        t.join();
        // commit and then query. should see changes from the transaction
        mDatabase.endTransaction();
        stmt = mDatabase.compileStatement(sql);
        final int expectedValue2 = (stmtType == DELETE) ? 9 : 10;
        assertEquals(expectedValue2, stmt.simpleQueryForLong());
        stmt.close();
        c = mDatabase.rawQuery(sql, null);
        assertEquals(1, c.getCount());
        c.moveToFirst();
        assertEquals(expectedValue2, c.getLong(0));
        c.close();
    }
    private synchronized SQLiteDatabase getDb() {
        return mDatabase;
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
                mDatabase.mConnectionPool.setMaxPoolSize(i + 1);
            } else {
                mDatabase.disableWriteAheadLogging();
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

    public static class ClassToTestSqlCompilationAndCaching extends SQLiteProgram {
        private ClassToTestSqlCompilationAndCaching(SQLiteDatabase db, String sql) {
            super(db, sql);
        }
        public static ClassToTestSqlCompilationAndCaching create(SQLiteDatabase db, String sql) {
            db.lock();
            try {
                return new ClassToTestSqlCompilationAndCaching(db, sql);
            } finally {
                db.unlock();
            }
        }
    }

    @SmallTest
    public void testLruCachingOfSqliteCompiledSqlObjs() {
        createTableAndClearCache();
        // set cache size
        int N = SQLiteDatabase.MAX_SQL_CACHE_SIZE;
        mDatabase.setMaxSqlCacheSize(N);

        // do N+1 queries - and when the 0th entry is removed from LRU cache due to the
        // insertion of (N+1)th entry, make sure 0th entry is closed
        ArrayList<Integer> stmtObjs = new ArrayList<Integer>();
        ArrayList<String> sqlStrings = new ArrayList<String>();
        int stmt0 = 0;
        for (int i = 0; i < N+1; i++) {
            String s = "insert into test values(" + i + ",?);";
            sqlStrings.add(s);
            ClassToTestSqlCompilationAndCaching c =
                    ClassToTestSqlCompilationAndCaching.create(mDatabase, s);
            int n = c.getSqlStatementId();
            stmtObjs.add(i, n);
            if (i == 0) {
                // save the statementId of this obj. we want to make sure it is thrown out of
                // the cache at the end of this test.
                stmt0 = n;
            }
            c.close();
        }
        // is 0'th entry out of the cache? it should be in the list of statementIds
        // corresponding to the pre-compiled sql statements to be finalized.
        assertTrue(mDatabase.getQueuedUpStmtList().contains(stmt0));
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
        statementDoNotClose.bindLong(1, 1);
        /* do not close statementDoNotClose object.
         * That should leave it in SQLiteDatabase.mPrograms.
         * mDatabase.close() in tearDown() should release it.
         */
    }

    private void createTableAndClearCache() {
        mDatabase.disableWriteAheadLogging();
        mDatabase.execSQL("DROP TABLE IF EXISTS " + TEST_TABLE);
        mDatabase.execSQL("CREATE TABLE " + TEST_TABLE + " (i int, j int);");
        mDatabase.enableWriteAheadLogging();
        mDatabase.lock();
        // flush the above statement from cache and close all the pending statements to be released
        mDatabase.deallocCachedSqlStatements();
        mDatabase.closePendingStatements();
        mDatabase.unlock();
        assertEquals(0, mDatabase.getQueuedUpStmtList().size());
    }

    /**
     * test to make sure the statement finalizations are not done right away but
     * piggy-backed onto the next sql statement execution on the same database.
     */
    @SmallTest
    public void testStatementClose() {
        createTableAndClearCache();
        // fill up statement cache in mDatabase
        int N = SQLiteDatabase.MAX_SQL_CACHE_SIZE;
        mDatabase.setMaxSqlCacheSize(N);
        SQLiteStatement stmt;
        int stmt0Id = 0;
        for (int i = 0; i < N; i ++) {
            ClassToTestSqlCompilationAndCaching c =
                    ClassToTestSqlCompilationAndCaching.create(mDatabase,
                            "insert into test values(" + i + ", ?);");
            // keep track of 0th entry
            if (i == 0) {
                stmt0Id = c.getSqlStatementId();
            }
            c.close();
        }

        // add one more to the cache - and the above 'stmt0Id' should fall out of cache
        ClassToTestSqlCompilationAndCaching stmt1 =
                ClassToTestSqlCompilationAndCaching.create(mDatabase,
                        "insert into test values(100, ?);");
        stmt1.close();

        // the above close() should have queuedUp the statement for finalization
        ArrayList<Integer> statementIds = mDatabase.getQueuedUpStmtList();
        assertTrue(statementIds.contains(stmt0Id));

        // execute something to see if this statement gets finalized
        mDatabase.execSQL("delete from test where i = 10;");
        statementIds = mDatabase.getQueuedUpStmtList();
        assertFalse(statementIds.contains(stmt0Id));
    }

    /**
     * same as above - except that the statement to be finalized is from Thread # 1.
     * and it is eventually finalized in Thread # 2 when it executes a SQL statement.
     * @throws InterruptedException
     */
    @LargeTest
    public void testStatementCloseDiffThread() throws InterruptedException {
        createTableAndClearCache();
        final int N = SQLiteDatabase.MAX_SQL_CACHE_SIZE;
        mDatabase.setMaxSqlCacheSize(N);
        // fill up statement cache in mDatabase in a thread
        Thread t1 = new Thread() {
            @Override public void run() {
                SQLiteStatement stmt;
                for (int i = 0; i < N; i++) {
                    ClassToTestSqlCompilationAndCaching c =
                        ClassToTestSqlCompilationAndCaching.create(getDb(),
                                "insert into test values(" + i + ", ?);");
                    // keep track of 0th entry
                    if (i == 0) {
                        stmt0Id = c.getSqlStatementId();
                    }
                    c.close();
                }
            }
        };
        t1.start();
        // wait for the thread to finish
        t1.join();
        // mDatabase shouldn't have any statements to be released
        assertEquals(0, mDatabase.getQueuedUpStmtList().size());

        // add one more to the cache - and the above 'stmt0Id' should fall out of cache
        // just for the heck of it, do it in a separate thread
        Thread t2 = new Thread() {
            @Override public void run() {
                ClassToTestSqlCompilationAndCaching stmt1 =
                    ClassToTestSqlCompilationAndCaching.create(getDb(),
                            "insert into test values(100, ?);");
                stmt1.bindLong(1, 1);
                stmt1.close();
            }
        };
        t2.start();
        t2.join();

        // close() in the above thread should have queuedUp the stmt0Id for finalization
        ArrayList<Integer> statementIds = getDb().getQueuedUpStmtList();
        assertTrue(statementIds.contains(getStmt0Id()));
        assertEquals(1, statementIds.size());

        // execute something to see if this statement gets finalized
        // again do it in a separate thread
        Thread t3 = new Thread() {
            @Override public void run() {
                getDb().execSQL("delete from test where i = 10;");
            }
        };
        t3.start();
        t3.join();

        // is the statement finalized?
        statementIds = getDb().getQueuedUpStmtList();
        assertFalse(statementIds.contains(getStmt0Id()));
    }

    private volatile int stmt0Id = 0;
    private synchronized int getStmt0Id() {
        return this.stmt0Id;
    }

    /**
     * same as above - except that the queue of statements to be finalized are finalized
     * by database close() operation.
     */
    @LargeTest
    public void testStatementCloseByDbClose() throws InterruptedException {
        createTableAndClearCache();
        // fill up statement cache in mDatabase in a thread
        Thread t1 = new Thread() {
            @Override public void run() {
                int N = SQLiteDatabase.MAX_SQL_CACHE_SIZE;
                getDb().setMaxSqlCacheSize(N);
                SQLiteStatement stmt;
                for (int i = 0; i < N; i ++) {
                    ClassToTestSqlCompilationAndCaching c =
                            ClassToTestSqlCompilationAndCaching.create(getDb(),
                                    "insert into test values(" + i + ", ?);");
                    // keep track of 0th entry
                    if (i == 0) {
                        stmt0Id = c.getSqlStatementId();
                    }
                    c.close();
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
                ClassToTestSqlCompilationAndCaching stmt1 =
                        ClassToTestSqlCompilationAndCaching.create(getDb(),
                                "insert into test values(100, ?);");
                stmt1.bindLong(1, 1);
                stmt1.close();
            }
        };
        t2.start();
        t2.join();

        // close() in the above thread should have queuedUp the statement for finalization
        ArrayList<Integer> statementIds = getDb().getQueuedUpStmtList();
        assertTrue(getStmt0Id() > 0);
        assertTrue(statementIds.contains(stmt0Id));
        assertEquals(1, statementIds.size());

        // close the database. everything from mClosedStatementIds in mDatabase
        // should be finalized and cleared from the list
        // again do it in a separate thread
        Thread t3 = new Thread() {
            @Override public void run() {
                getDb().close();
            }
        };
        t3.start();
        t3.join();

        // check mClosedStatementIds in mDatabase. it should be empty
        statementIds = getDb().getQueuedUpStmtList();
        assertEquals(0, statementIds.size());
    }

    /**
     * This test tests usage execSQL() to begin transaction works in the following way
     *   Thread #1 does
     *       execSQL("begin transaction");
     *       insert()
     *   Thread # 2
     *       query()
     *   Thread#1 ("end transaction")
     * Thread # 2 query will execute - because java layer will not have locked the SQLiteDatabase
     * object and sqlite will consider this query to be part of the transaction.
     *
     * but if thread # 1 uses beginTransaction() instead of execSQL() to start transaction,
     * then Thread # 2's query will have been blocked by java layer
     * until Thread#1 ends transaction.
     *
     * @throws InterruptedException
     */
    @SmallTest
    public void testExecSqlToStartAndEndTransaction() throws InterruptedException {
        runExecSqlToStartAndEndTransaction("END");
        // same as above, instead now do "COMMIT" or "ROLLBACK" instead of "END" transaction
        runExecSqlToStartAndEndTransaction("COMMIT");
        runExecSqlToStartAndEndTransaction("ROLLBACK");
    }
    private void runExecSqlToStartAndEndTransaction(String str) throws InterruptedException {
        createTableAndClearCache();
        // disable WAL just so queries and updates use the same database connection
        mDatabase.disableWriteAheadLogging();
        mDatabase.execSQL("BEGIN transaction");
        // even though mDatabase.beginTransaction() is not called to start transaction,
        // mDatabase connection should now be in transaction as a result of
        // mDatabase.execSQL("BEGIN transaction")
        // but mDatabase.mLock should not be held by any thread
        assertTrue(mDatabase.inTransaction());
        assertFalse(mDatabase.isDbLockedByCurrentThread());
        assertFalse(mDatabase.isDbLockedByOtherThreads());
        assertTrue(mDatabase.amIInTransaction());
        mDatabase.execSQL("INSERT into " + TEST_TABLE + " values(10, 999);");
        assertTrue(mDatabase.inTransaction());
        assertFalse(mDatabase.isDbLockedByCurrentThread());
        assertFalse(mDatabase.isDbLockedByOtherThreads());
        assertTrue(mDatabase.amIInTransaction());
        Thread t = new Thread() {
            @Override public void run() {
                assertTrue(mDatabase.amIInTransaction());
                assertEquals(999, DatabaseUtils.longForQuery(getDb(),
                        "select j from " + TEST_TABLE + " WHERE i = 10", null));
                assertTrue(getDb().inTransaction());
                assertFalse(getDb().isDbLockedByCurrentThread());
                assertFalse(getDb().isDbLockedByOtherThreads());
                assertTrue(mDatabase.amIInTransaction());
            }
        };
        t.start();
        t.join();
        assertTrue(mDatabase.amIInTransaction());
        assertTrue(mDatabase.inTransaction());
        assertFalse(mDatabase.isDbLockedByCurrentThread());
        assertFalse(mDatabase.isDbLockedByOtherThreads());
        mDatabase.execSQL(str);
        assertFalse(mDatabase.amIInTransaction());
        assertFalse(mDatabase.inTransaction());
        assertFalse(mDatabase.isDbLockedByCurrentThread());
        assertFalse(mDatabase.isDbLockedByOtherThreads());
    }

    /**
     * test the following
     * http://b/issue?id=2871037
     *          Cursor cursor = db.query(...);
     *          // with WAL enabled, the above uses a pooled database connection
     *          db.beginTransaction()
     *          try {
     *            db.insert(......);
     *            cursor.requery();
     *            // since the cursor uses pooled database connection, the above requery
     *            // will not return the results that were inserted above since the insert is
     *            // done using main database connection AND the transaction is not committed yet.
     *            // fix is to make the above cursor use the main database connection - and NOT
     *            // the pooled database connection
     *            db.setTransactionSuccessful()
     *          } finally {
     *            db.endTransaction()
     *          }
     *
     * @throws InterruptedException
     */
    @SmallTest
    public void testTransactionAndWalInterplay1() throws InterruptedException {
        createTableAndClearCache();
        mDatabase.execSQL("INSERT into " + TEST_TABLE + " values(10, 999);");
        String sql = "select * from " + TEST_TABLE;
        Cursor c = mDatabase.rawQuery(sql, null);
        // should have 1 row in the table
        assertEquals(1, c.getCount());
        mDatabase.beginTransactionNonExclusive();
        try {
            mDatabase.execSQL("INSERT into " + TEST_TABLE + " values(100, 9909);");
            assertEquals(2, DatabaseUtils.longForQuery(mDatabase,
                    "select count(*) from " + TEST_TABLE, null));
            // requery on the previously opened cursor
            // cursor should now use the main database connection and see 2 rows
            c.requery();
            assertEquals(2, c.getCount());
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }
        c.close();

        // do the same test but now do the requery in a separate thread.
        createTableAndClearCache();
        mDatabase.execSQL("INSERT into " + TEST_TABLE + " values(10, 999);");
        final Cursor c1 = mDatabase.rawQuery("select count(*) from " + TEST_TABLE, null);
        // should have 1 row in the table
        assertEquals(1, c1.getCount());
        mDatabase.beginTransactionNonExclusive();
        try {
            mDatabase.execSQL("INSERT into " + TEST_TABLE + " values(100, 9909);");
            assertEquals(2, DatabaseUtils.longForQuery(mDatabase,
                    "select count(*) from " + TEST_TABLE, null));
            // query in a different thread. that causes the cursor to use a pooled connection
            // and since this thread hasn't committed its changes, the cursor should still see only
            // 1 row
            Thread t = new Thread() {
                @Override public void run() {
                    c1.requery();
                    assertEquals(1, c1.getCount());
                }
            };
            t.start();
            t.join();
            // should be 2 rows now - including the the row inserted above
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }
        c1.close();
    }

    /**
     * This test is same as {@link #testTransactionAndWalInterplay1()} except the following:
     * instead of mDatabase.beginTransactionNonExclusive(), use execSQL("BEGIN transaction")
     * and instead of mDatabase.endTransaction(), use execSQL("END");
     */
    @SmallTest
    public void testTransactionAndWalInterplay2() throws InterruptedException {
        createTableAndClearCache();
        mDatabase.execSQL("INSERT into " + TEST_TABLE + " values(10, 999);");
        String sql = "select * from " + TEST_TABLE;
        Cursor c = mDatabase.rawQuery(sql, null);
        // should have 1 row in the table
        assertEquals(1, c.getCount());
        mDatabase.execSQL("BEGIN transaction");
        try {
            mDatabase.execSQL("INSERT into " + TEST_TABLE + " values(100, 9909);");
            assertEquals(2, DatabaseUtils.longForQuery(mDatabase,
                    "select count(*) from " + TEST_TABLE, null));
            // requery on the previously opened cursor
            // cursor should now use the main database connection and see 2 rows
            c.requery();
            assertEquals(2, c.getCount());
        } finally {
            mDatabase.execSQL("commit;");
        }
        c.close();

        // do the same test but now do the requery in a separate thread.
        createTableAndClearCache();
        mDatabase.execSQL("INSERT into " + TEST_TABLE + " values(10, 999);");
        final Cursor c1 = mDatabase.rawQuery("select count(*) from " + TEST_TABLE, null);
        // should have 1 row in the table
        assertEquals(1, c1.getCount());
        mDatabase.execSQL("BEGIN transaction");
        try {
            mDatabase.execSQL("INSERT into " + TEST_TABLE + " values(100, 9909);");
            assertEquals(2, DatabaseUtils.longForQuery(mDatabase,
                    "select count(*) from " + TEST_TABLE, null));
            // query in a different thread. but since the transaction is started using
            // execSQ() instead of beginTransaction(), cursor's query is considered part of
            // the same transaction - and hence it should see the above inserted row
            Thread t = new Thread() {
                @Override public void run() {
                    c1.requery();
                    assertEquals(1, c1.getCount());
                }
            };
            t.start();
            t.join();
            // should be 2 rows now - including the the row inserted above
        } finally {
            mDatabase.execSQL("commit");
        }
        c1.close();
    }

    /**
     * This test is same as {@link #testTransactionAndWalInterplay2()} except the following:
     * instead of committing the data, do rollback and make sure the data seen by the query
     * within the transaction is now gone.
     */
    @SmallTest
    public void testTransactionAndWalInterplay3() {
        createTableAndClearCache();
        mDatabase.execSQL("INSERT into " + TEST_TABLE + " values(10, 999);");
        String sql = "select * from " + TEST_TABLE;
        Cursor c = mDatabase.rawQuery(sql, null);
        // should have 1 row in the table
        assertEquals(1, c.getCount());
        mDatabase.execSQL("BEGIN transaction");
        try {
            mDatabase.execSQL("INSERT into " + TEST_TABLE + " values(100, 9909);");
            assertEquals(2, DatabaseUtils.longForQuery(mDatabase,
                    "select count(*) from " + TEST_TABLE, null));
            // requery on the previously opened cursor
            // cursor should now use the main database connection and see 2 rows
            c.requery();
            assertEquals(2, c.getCount());
        } finally {
            // rollback the change
            mDatabase.execSQL("rollback;");
        }
        // since the change is rolled back, do the same query again and should now find only 1 row
        c.requery();
        assertEquals(1, c.getCount());
        assertEquals(1, DatabaseUtils.longForQuery(mDatabase,
                "select count(*) from " + TEST_TABLE, null));
        c.close();
    }

    @SmallTest
    public void testAttachDb() {
        String newDb = "/sdcard/mydata.db";
        File f = new File(newDb);
        if (f.exists()) {
            f.delete();
        }
        assertFalse(f.exists());
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(newDb, null);
        db.execSQL("create table test1 (i int);");
        db.execSQL("insert into test1 values(1);");
        db.execSQL("insert into test1 values(11);");
        Cursor c = null;
        try {
            c = db.rawQuery("select * from test1", null);
            int count = c.getCount();
            Log.i(TAG, "count: " + count);
            assertEquals(2, count);
        } finally {
            c.close();
            db.close();
            c = null;
        }

        mDatabase.execSQL("attach database ? as newDb" , new String[]{newDb});
        Cursor c1 = null;
        try {
            c1 = mDatabase.rawQuery("select * from newDb.test1", null);
            assertEquals(2, c1.getCount());
        } catch (Exception e) {
            fail("unexpected exception: " + e.getMessage());
        } finally {
            if (c1 != null) {
                c1.close();
            }
        }
        List<Pair<String, String>> dbs = mDatabase.getAttachedDbs();
        for (Pair<String, String> p: dbs) {
            Log.i(TAG, "attached dbs: " + p.first + " : " + p.second);
        }
        assertEquals(2, dbs.size());
     }

    /**
     * http://b/issue?id=2943028
     * SQLiteOpenHelper maintains a Singleton even if it is in bad state.
     */
    @SmallTest
    public void testCloseAndReopen() {
        mDatabase.close();
        TestOpenHelper helper = new TestOpenHelper(getContext(), DB_NAME, null,
                CURRENT_DATABASE_VERSION, new DefaultDatabaseErrorHandler());
        mDatabase = helper.getWritableDatabase();
        createTableAndClearCache();
        mDatabase.execSQL("INSERT into " + TEST_TABLE + " values(10, 999);");
        Cursor c = mDatabase.query(TEST_TABLE, new String[]{"i", "j"}, null, null, null, null, null);
        assertEquals(1, c.getCount());
        c.close();
        mDatabase.close();
        assertFalse(mDatabase.isOpen());
        mDatabase = helper.getReadableDatabase();
        assertTrue(mDatabase.isOpen());
        c = mDatabase.query(TEST_TABLE, new String[]{"i", "j"}, null, null, null, null, null);
        assertEquals(1, c.getCount());
        c.close();
    }
    private class TestOpenHelper extends SQLiteOpenHelper {
        public TestOpenHelper(Context context, String name, CursorFactory factory, int version,
                DatabaseErrorHandler errorHandler) {
            super(context, name, factory, version, errorHandler);
        }
        @Override public void onCreate(SQLiteDatabase db) {}
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}
    }
}
