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
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.SmallTest;

import java.io.File;
import java.util.Random;
import java.util.concurrent.locks.ReentrantLock;

public class SQLiteStatementTest extends AndroidTestCase {
    private SQLiteDatabase mDatabase;
    private File mDatabaseFile;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        File dbDir = getContext().getDir(this.getClass().getName(), Context.MODE_PRIVATE);
        mDatabaseFile = new File(dbDir, "database_test.db");
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

    /**
     * Start 2 threads to repeatedly execute the above SQL statement.
     * Even though 2 threads are executing the same SQL, they each should get their own copy of
     * prepared SQL statement id and there SHOULD NOT be an error from sqlite or android.
     * @throws InterruptedException thrown if the test threads started by this test are interrupted
     */
    @LargeTest
    public void testUseOfSameSqlStatementBy2Threads() throws InterruptedException {
        mDatabase.execSQL("CREATE TABLE test_pstmt (i INTEGER PRIMARY KEY, j text);");
        final String stmt = "SELECT * FROM test_pstmt WHERE i = ?";
        class RunStmtThread extends Thread {
            @Override public void run() {
                // do it enough times to make sure there are no corner cases going untested
                for (int i = 0; i < 1000; i++) {
                    SQLiteStatement s1 = mDatabase.compileStatement(stmt);
                    s1.bindLong(1, i);
                    s1.execute();
                    s1.close();
                 }
            }
        }
        RunStmtThread t1 = new RunStmtThread();
        t1.start();
        RunStmtThread t2 = new RunStmtThread();
        t2.start();
         while (t1.isAlive() || t2.isAlive()) {
             Thread.sleep(10);
         }
     }

    /**
     * A simple test: start 2 threads to repeatedly execute the same {@link SQLiteStatement}.
     * The 2 threads take turns to use the {@link SQLiteStatement}; i.e., it is NOT in use
     * by both the threads at the same time.
     *
     * @throws InterruptedException thrown if the test threads started by this test are interrupted
     */
    @LargeTest
    public void testUseOfSameSqliteStatementBy2Threads() throws InterruptedException {
        mDatabase.execSQL("CREATE TABLE test_pstmt (i INTEGER PRIMARY KEY, j text);");
        final String stmt = "SELECT * FROM test_pstmt WHERE i = ?";
        final SQLiteStatement s1 = mDatabase.compileStatement(stmt);
        class RunStmtThread extends Thread {
            @Override public void run() {
                // do it enough times to make sure there are no corner cases going untested
                for (int i = 0; i < 1000; i++) {
                    lock();
                    try {
                        s1.bindLong(1, i);
                        s1.execute();
                    } finally {
                        unlock();
                    }
                    Thread.yield();
                }
            }
        }
        RunStmtThread t1 = new RunStmtThread();
        t1.start();
        RunStmtThread t2 = new RunStmtThread();
        t2.start();
        while (t1.isAlive() || t2.isAlive()) {
            Thread.sleep(10);
        }
    }
    /** Synchronize on this when accessing the SqliteStatemet in the above */
    private final ReentrantLock mLock = new ReentrantLock(true);
    private void lock() {
        mLock.lock();
    }
    private void unlock() {
        mLock.unlock();
    }

    /**
     * Tests the following: a {@link SQLiteStatement} object should not refer to a
     * pre-compiled SQL statement id except in during the period of binding the arguments
     * and executing the SQL statement.
     */
    @LargeTest
    public void testReferenceToPrecompiledStatementId() {
        mDatabase.execSQL("create table t (i int, j text);");
        verifyReferenceToPrecompiledStatementId(false);
        verifyReferenceToPrecompiledStatementId(true);

        // a small stress test to make sure there are no side effects of
        // the acquire & release of pre-compiled statement id by SQLiteStatement object.
        for (int i = 0; i < 100; i++) {
            verifyReferenceToPrecompiledStatementId(false);
            verifyReferenceToPrecompiledStatementId(true);
        }
    }

    @SuppressWarnings("deprecation")
    private void verifyReferenceToPrecompiledStatementId(boolean wal) {
        if (wal) {
            mDatabase.enableWriteAheadLogging();
        } else {
            mDatabase.disableWriteAheadLogging();
        }
        // test with INSERT statement - doesn't use connection pool, if WAL is set
        SQLiteStatement stmt = mDatabase.compileStatement("insert into t values(?,?);");
        assertEquals(mDatabase.mNativeHandle, stmt.nHandle);
        assertEquals(mDatabase, stmt.mDatabase);
        // sql statement should not be compiled yet
        assertEquals(0, stmt.nStatement);
        assertEquals(0, stmt.getSqlStatementId());
        int colValue = new Random().nextInt();
        stmt.bindLong(1, colValue);
        // verify that the sql statement is still not compiled
        assertEquals(0, stmt.getSqlStatementId());
        // should still be using the mDatabase connection - verify
        assertEquals(mDatabase.mNativeHandle, stmt.nHandle);
        assertEquals(mDatabase, stmt.mDatabase);
        stmt.bindString(2, "blah" + colValue);
        // verify that the sql statement is still not compiled
        assertEquals(0, stmt.getSqlStatementId());
        assertEquals(mDatabase.mNativeHandle, stmt.nHandle);
        assertEquals(mDatabase, stmt.mDatabase);
        stmt.executeInsert();
        // now that the statement is executed, pre-compiled statement should be released
        assertEquals(0, stmt.nStatement);
        assertEquals(0, stmt.getSqlStatementId());
        assertEquals(mDatabase.mNativeHandle, stmt.nHandle);
        assertEquals(mDatabase, stmt.mDatabase);
        stmt.close();
        // pre-compiled SQL statement should still remain released from this object
        assertEquals(0, stmt.nStatement);
        assertEquals(0, stmt.getSqlStatementId());
        // but the database handle should still be the same
        assertEquals(mDatabase, stmt.mDatabase);

        // test with a SELECT statement - uses connection pool if WAL is set
        stmt = mDatabase.compileStatement("select i from t where j=?;");
        // sql statement should not be compiled yet
        assertEquals(0, stmt.nStatement);
        assertEquals(0, stmt.getSqlStatementId());
        assertEquals(mDatabase.mNativeHandle, stmt.nHandle);
        assertEquals(mDatabase, stmt.mDatabase);
        stmt.bindString(1, "blah" + colValue);
        // verify that the sql statement is still not compiled
        assertEquals(0, stmt.nStatement);
        assertEquals(0, stmt.getSqlStatementId());
        assertEquals(mDatabase.mNativeHandle, stmt.nHandle);
        assertEquals(mDatabase, stmt.mDatabase);
        // execute the statement
        Long l = stmt.simpleQueryForLong();
        assertEquals(colValue, l.intValue());
        // now that the statement is executed, pre-compiled statement should be released
        assertEquals(0, stmt.nStatement);
        assertEquals(0, stmt.getSqlStatementId());
        assertEquals(mDatabase.mNativeHandle, stmt.nHandle);
        assertEquals(mDatabase, stmt.mDatabase);
        stmt.close();
        // pre-compiled SQL statement should still remain released from this object
        assertEquals(0, stmt.nStatement);
        assertEquals(0, stmt.getSqlStatementId());
        // but the database handle should still remain attached to the statement
        assertEquals(mDatabase.mNativeHandle, stmt.nHandle);
        assertEquals(mDatabase, stmt.mDatabase);
    }
}
