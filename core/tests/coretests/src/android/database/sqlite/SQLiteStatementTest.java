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
import android.test.FlakyTest;
import android.test.suitebuilder.annotation.LargeTest;

import java.io.File;

public class SQLiteStatementTest extends AndroidTestCase {

    private SQLiteDatabase mDatabase;
    private File mDatabaseFile;
    Boolean exceptionRecvd = false;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        exceptionRecvd = false;
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

    @LargeTest
    public void testUseOfSameSqlStatementBy2Threads() throws Exception {
        mDatabase.execSQL("CREATE TABLE test_pstmt (i INTEGER PRIMARY KEY, j text);");

        // thread 1 creates a prepared statement
        final String stmt = "SELECT * FROM test_pstmt WHERE i = ?";

        // start 2 threads to do repeatedly execute "stmt"
        // since these 2 threads are executing the same sql, they each should get
        // their own copy and
        // there SHOULD NOT be an error from sqlite: "prepared statement is busy"
        class RunStmtThread extends Thread {
            private static final int N = 1000;
            @Override public void run() {
                int i = 0;
                try {
                    // execute many times
                    for (i = 0; i < N; i++) {
                        SQLiteStatement s1 = mDatabase.compileStatement(stmt);
                        s1.bindLong(1, i);
                        s1.execute();
                        s1.close();
                    }
                } catch (SQLiteException e) {
                    fail("SQLiteException: " + e.getMessage());
                    return;
                } catch (Exception e) {
                    e.printStackTrace();
                    fail("random unexpected exception: " + e.getMessage());
                    return;
                }
            }
        }
        RunStmtThread t1 = new RunStmtThread();
        t1.start();
        RunStmtThread t2 = new RunStmtThread();
        t2.start();
        while (t1.isAlive() || t2.isAlive()) {
            Thread.sleep(1000);
        }
    }

    @FlakyTest
    public void testUseOfSamePreparedStatementBy2Threads() throws Exception {
        mDatabase.execSQL("CREATE TABLE test_pstmt (i INTEGER PRIMARY KEY, j text);");

        // thread 1 creates a prepared statement
        final String stmt = "SELECT * FROM test_pstmt WHERE i = ?";
        final SQLiteStatement s1 = mDatabase.compileStatement(stmt);

        // start 2 threads to do repeatedly execute "stmt"
        // since these 2 threads are executing the same prepared statement,
        // should see an error from sqlite: "prepared statement is busy"
        class RunStmtThread extends Thread {
            private static final int N = 1000;
            @Override public void run() {
                int i = 0;
                try {
                    // execute many times
                    for (i = 0; i < N; i++) {
                        s1.bindLong(1, i);
                        s1.execute();
                    }
                } catch (SQLiteException e) {
                    // expect it
                    assertTrue(e.getMessage().contains("library routine called out of sequence:"));
                    exceptionRecvd = true;
                    return;
                } catch (Exception e) {
                    e.printStackTrace();
                    fail("random unexpected exception: " + e.getMessage());
                    return;
                }
            }
        }
        RunStmtThread t1 = new RunStmtThread();
        t1.start();
        RunStmtThread t2 = new RunStmtThread();
        t2.start();
        while (t1.isAlive() || t2.isAlive()) {
            Thread.sleep(1000);
        }
        assertTrue(exceptionRecvd);
    }

    public void testGetSqlStatementId() {
        mDatabase.execSQL("CREATE TABLE test (_id INTEGER PRIMARY KEY, text1 TEXT, text2 TEXT, " +
                "num1 INTEGER, num2 INTEGER, image BLOB);");
        final String statement = "DELETE FROM test WHERE _id=?;";
        SQLiteStatement statementOne = mDatabase.compileStatement(statement);
        SQLiteStatement statementTwo = mDatabase.compileStatement(statement);
        // since the same compiled statement is being accessed at the same time by 2 different
        // objects, they each get their own statement id
        assertTrue(statementOne.getSqlStatementId() != statementTwo.getSqlStatementId());
        statementOne.close();
        statementTwo.close();

        statementOne = mDatabase.compileStatement(statement);
        int n = statementOne.getSqlStatementId();
        statementOne.close();
        statementTwo = mDatabase.compileStatement(statement);
        assertEquals(n, statementTwo.getSqlStatementId());
        statementTwo.close();

        // now try to compile 2 different statements and they should have different uniquerIds.
        SQLiteStatement statement1 = mDatabase.compileStatement("DELETE FROM test WHERE _id=1;");
        SQLiteStatement statement2 = mDatabase.compileStatement("DELETE FROM test WHERE _id=2;");
        assertTrue(statement1.getSqlStatementId() != statement2.getSqlStatementId());
        statement1.close();
        statement2.close();
    }

    public void testOnAllReferencesReleased() {
        mDatabase.execSQL("CREATE TABLE test (_id INTEGER PRIMARY KEY, text1 TEXT, text2 TEXT, " +
                "num1 INTEGER, num2 INTEGER, image BLOB);");
        final String statement = "DELETE FROM test WHERE _id=?;";
        SQLiteStatement statementOne = mDatabase.compileStatement(statement);
        assertTrue(statementOne.getSqlStatementId() > 0);
        int nStatement = statementOne.getSqlStatementId();
        statementOne.releaseReference();
        assertEquals(0, statementOne.getSqlStatementId());
        statementOne.close();
    }

    public void testOnAllReferencesReleasedFromContainer() {
        mDatabase.execSQL("CREATE TABLE test (_id INTEGER PRIMARY KEY, text1 TEXT, text2 TEXT, " +
                "num1 INTEGER, num2 INTEGER, image BLOB);");
        final String statement = "DELETE FROM test WHERE _id=?;";
        SQLiteStatement statementOne = mDatabase.compileStatement(statement);
        assertTrue(statementOne.getSqlStatementId() > 0);
        int nStatement = statementOne.getSqlStatementId();
        statementOne.releaseReferenceFromContainer();
        assertEquals(0, statementOne.getSqlStatementId());
        statementOne.close();
    }
}
