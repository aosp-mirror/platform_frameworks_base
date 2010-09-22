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
import android.database.sqlite.SQLiteDatabaseTest.ClassToTestSqlCompilationAndCaching;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

public class DatabaseConnectionPoolTest extends AndroidTestCase {
    private static final String TAG = "DatabaseConnectionPoolTest";

    private static final int MAX_CONN = 5;
    private static final String TEST_SQL = "select * from test where i = ? AND j = 1";
    private static final String[] TEST_SQLS = new String[] {
        TEST_SQL, TEST_SQL + 1, TEST_SQL + 2, TEST_SQL + 3, TEST_SQL + 4
    };

    private SQLiteDatabase mDatabase;
    private File mDatabaseFile;
    private DatabaseConnectionPool mTestPool;

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
        mDatabase.execSQL("create table test (i int, j int);");
        mTestPool = new DatabaseConnectionPool(mDatabase);
        assertNotNull(mTestPool);
    }

    @Override
    protected void tearDown() throws Exception {
        mTestPool.close();
        mDatabase.close();
        mDatabaseFile.delete();
        super.tearDown();
    }

    @SmallTest
    public void testGetAndRelease() {
        mTestPool.setMaxPoolSize(MAX_CONN);
        // connections should be lazily created.
        assertEquals(0, mTestPool.getSize());
        // MAX pool size should be set to MAX_CONN
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());
        // get a connection
        SQLiteDatabase db = mTestPool.get(TEST_SQL);
        // pool size should be one - since only one should be allocated for the above get()
        assertEquals(1, mTestPool.getSize());
        assertEquals(mDatabase, db.mParentConnObj);
        // no free connections should be available
        assertEquals(0, mTestPool.getFreePoolSize());
        assertFalse(mTestPool.isDatabaseObjFree(db));
        // release the connection
        mTestPool.release(db);
        assertEquals(1, mTestPool.getFreePoolSize());
        assertEquals(1, mTestPool.getSize());
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());
        assertTrue(mTestPool.isDatabaseObjFree(db));
        // release the same object again and expect IllegalStateException
        try {
            mTestPool.release(db);
            fail("illegalStateException expected");
        } catch (IllegalStateException e ) {
            // expected.
        }
    }

    /**
     * get all connections from the pool and ask for one more.
     * should get one of the connections already got so far. 
     */
    @SmallTest
    public void testGetAllConnAndOneMore() {
        mTestPool.setMaxPoolSize(MAX_CONN);
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());
        ArrayList<SQLiteDatabase> dbObjs = new ArrayList<SQLiteDatabase>();
        for (int i = 0; i < MAX_CONN; i++) {
            SQLiteDatabase db = mTestPool.get(TEST_SQL);
            assertFalse(dbObjs.contains(db));
            dbObjs.add(db);
            assertEquals(mDatabase, db.mParentConnObj);
        }
        assertEquals(0, mTestPool.getFreePoolSize());
        assertEquals(MAX_CONN, mTestPool.getSize());
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());
        // pool is maxed out and no free connections. ask for one more connection
        SQLiteDatabase db1 = mTestPool.get(TEST_SQL);
        // make sure db1 is one of the existing ones
        assertTrue(dbObjs.contains(db1));
        // pool size should remain at MAX_CONN
        assertEquals(0, mTestPool.getFreePoolSize());
        assertEquals(MAX_CONN, mTestPool.getSize());
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());
        // release db1 but since it is allocated 2 times, it should still remain 'busy'
        mTestPool.release(db1);
        assertFalse(mTestPool.isDatabaseObjFree(db1));
        assertEquals(0, mTestPool.getFreePoolSize());
        assertEquals(MAX_CONN, mTestPool.getSize());
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());
        // release all connections
        for (int i = 0; i < MAX_CONN; i++) {
            mTestPool.release(dbObjs.get(i));
        }
        // all objects in the pool should be freed now
        assertEquals(MAX_CONN, mTestPool.getFreePoolSize());
        assertEquals(MAX_CONN, mTestPool.getSize());
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());
    }
    
    /**
     * same as above except that each connection has different SQL statement associated with it. 
     */
    @SmallTest
    public void testConnRetrievalForPreviouslySeenSql() {
        mTestPool.setMaxPoolSize(MAX_CONN);
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());

        HashMap<String, SQLiteDatabase> dbObjs = new HashMap<String, SQLiteDatabase>();
        for (int i = 0; i < MAX_CONN; i++) {
            SQLiteDatabase db = mTestPool.get(TEST_SQLS[i]);
            executeSqlOnDatabaseConn(db, TEST_SQLS[i]);
            assertFalse(dbObjs.values().contains(db));
            dbObjs.put(TEST_SQLS[i], db);
        }
        assertEquals(0, mTestPool.getFreePoolSize());
        assertEquals(MAX_CONN, mTestPool.getSize());
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());
        // pool is maxed out and no free connections. ask for one more connection
        // use a previously seen SQL statement
        String testSql = TEST_SQLS[MAX_CONN - 1];
        SQLiteDatabase db1 = mTestPool.get(testSql);
        assertEquals(0, mTestPool.getFreePoolSize());
        assertEquals(MAX_CONN, mTestPool.getSize());
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());
        // make sure db1 is one of the existing ones
        assertTrue(dbObjs.values().contains(db1));
        assertEquals(db1, dbObjs.get(testSql));
        // do the same again
        SQLiteDatabase db2 = mTestPool.get(testSql);
        // make sure db1 is one of the existing ones
        assertEquals(db2, dbObjs.get(testSql));

        // pool size should remain at MAX_CONN
        assertEquals(0, mTestPool.getFreePoolSize());
        assertEquals(MAX_CONN, mTestPool.getSize());
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());

        // release db1 but since the same connection is allocated 3 times,
        // it should still remain 'busy'
        mTestPool.release(db1);
        assertFalse(mTestPool.isDatabaseObjFree(dbObjs.get(testSql)));
        assertEquals(0, mTestPool.getFreePoolSize());
        assertEquals(MAX_CONN, mTestPool.getSize());
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());

        // release db2 but since the same connection is allocated 2 times,
        // it should still remain 'busy'
        mTestPool.release(db2);
        assertFalse(mTestPool.isDatabaseObjFree(dbObjs.get(testSql)));
        assertEquals(0, mTestPool.getFreePoolSize());
        assertEquals(MAX_CONN, mTestPool.getSize());
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());

        // release all connections
        for (int i = 0; i < MAX_CONN; i++) {
            mTestPool.release(dbObjs.get(TEST_SQLS[i]));
        }
        // all objects in the pool should be freed now
        assertEquals(MAX_CONN, mTestPool.getFreePoolSize());
        assertEquals(MAX_CONN, mTestPool.getSize());
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());
    }

    private void executeSqlOnDatabaseConn(SQLiteDatabase db, String sql) {
        // get the given sql be compiled on the given database connection.
        // this will help DatabaseConenctionPool figure out if a given SQL statement
        // is already cached by a database connection.
        ClassToTestSqlCompilationAndCaching c =
                ClassToTestSqlCompilationAndCaching.create(db, sql);
        c.close();
    }

    /**
     * get a connection for a SQL statement 'blah'. (connection_s)
     * make sure the pool has at least one free connection even after this get().
     * and get a connection for the same SQL again.
     *    this connection should be different from connection_s.
     *    even though there is a connection with the given SQL pre-compiled, since is it not free
     *    AND since the pool has free connections available, should get a new connection.
     */
    @SmallTest
    public void testGetConnForTheSameSql() {
        mTestPool.setMaxPoolSize(MAX_CONN);

        SQLiteDatabase db = mTestPool.get(TEST_SQL);
        executeSqlOnDatabaseConn(db, TEST_SQL);
        assertEquals(0, mTestPool.getFreePoolSize());
        assertEquals(1, mTestPool.getSize());
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());

        assertFalse(mTestPool.isDatabaseObjFree(db));

        SQLiteDatabase db1 = mTestPool.get(TEST_SQL);
        assertEquals(0, mTestPool.getFreePoolSize());
        assertEquals(2, mTestPool.getSize());
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());

        assertFalse(mTestPool.isDatabaseObjFree(db1));
        assertFalse(db1.equals(db));

        mTestPool.release(db);
        assertEquals(1, mTestPool.getFreePoolSize());
        assertEquals(2, mTestPool.getSize());
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());

        mTestPool.release(db1);
        assertEquals(2, mTestPool.getFreePoolSize());
        assertEquals(2, mTestPool.getSize());
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());
    }

    /**
     * get the same connection N times and release it N times.
     * this tests DatabaseConnectionPool.PoolObj.mNumHolders
     */
    @SmallTest
    public void testGetSameConnNtimesAndReleaseItNtimes() {
        mTestPool.setMaxPoolSize(MAX_CONN);
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());

        HashMap<String, SQLiteDatabase> dbObjs = new HashMap<String, SQLiteDatabase>();
        for (int i = 0; i < MAX_CONN; i++) {
            SQLiteDatabase db = mTestPool.get(TEST_SQLS[i]);
            executeSqlOnDatabaseConn(db, TEST_SQLS[i]);
            assertFalse(dbObjs.values().contains(db));
            dbObjs.put(TEST_SQLS[i], db);
        }
        assertEquals(0, mTestPool.getFreePoolSize());
        assertEquals(MAX_CONN, mTestPool.getSize());
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());
        // every connection in the pool should have numHolders = 1
        for (int i = 0; i < MAX_CONN; i ++) {
            assertEquals(1, mTestPool.getPool().get(i).getNumHolders());
        }
        // pool is maxed out and no free connections. ask for one more connection
        // use a previously seen SQL statement
        String testSql = TEST_SQLS[MAX_CONN - 1];
        SQLiteDatabase db1 = mTestPool.get(testSql);
        assertEquals(0, mTestPool.getFreePoolSize());
        assertEquals(MAX_CONN, mTestPool.getSize());
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());
        // make sure db1 is one of the existing ones
        assertTrue(dbObjs.values().contains(db1));
        assertEquals(db1, dbObjs.get(testSql));
        assertFalse(mTestPool.isDatabaseObjFree(db1));
        DatabaseConnectionPool.PoolObj poolObj = mTestPool.getPool().get(db1.mConnectionNum - 1);
        int numHolders = poolObj.getNumHolders();
        assertEquals(2, numHolders);
        assertEquals(0, mTestPool.getFreePoolSize());
        assertEquals(MAX_CONN, mTestPool.getSize());
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());
        // get the same connection N times more
        int N = 100;
        for (int i = 0; i < N; i++) {
            SQLiteDatabase db2 = mTestPool.get(testSql);
            assertEquals(db1, db2);
            assertFalse(mTestPool.isDatabaseObjFree(db2));
            // numHolders for this object should be now up by 1
            int prev = numHolders;
            numHolders = poolObj.getNumHolders();
            assertEquals(prev + 1, numHolders);
        }
        // release it N times
        for (int i = 0; i < N; i++) {
            mTestPool.release(db1);
            int prev = numHolders;
            numHolders = poolObj.getNumHolders();
            assertEquals(prev - 1, numHolders);
            assertFalse(mTestPool.isDatabaseObjFree(db1));
        }
        // the connection should still have 2 more holders
        assertFalse(mTestPool.isDatabaseObjFree(db1));
        assertEquals(2, poolObj.getNumHolders());
        assertEquals(0, mTestPool.getFreePoolSize());
        assertEquals(MAX_CONN, mTestPool.getSize());
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());
        // release 2 more times
        mTestPool.release(db1);
        mTestPool.release(db1);
        assertEquals(0, poolObj.getNumHolders());
        assertEquals(1, mTestPool.getFreePoolSize());
        assertEquals(MAX_CONN, mTestPool.getSize());
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());
        assertTrue(mTestPool.isDatabaseObjFree(db1));
    }

    @SmallTest
    public void testStressTest() {
        mTestPool.setMaxPoolSize(MAX_CONN);
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());

        HashMap<SQLiteDatabase, Integer> dbMap = new HashMap<SQLiteDatabase, Integer>();
        for (int i = 0; i < MAX_CONN; i++) {
            SQLiteDatabase db = mTestPool.get(TEST_SQLS[i]);
            assertFalse(dbMap.containsKey(db));
            dbMap.put(db, 1);
            executeSqlOnDatabaseConn(db, TEST_SQLS[i]);
        }
        assertEquals(0, mTestPool.getFreePoolSize());
        assertEquals(MAX_CONN, mTestPool.getSize());
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());
        // ask for lot more connections but since the pool is maxed out, we should start receiving
        // connections that we already got so far
        for (int i = MAX_CONN; i < 1000; i++) {
            SQLiteDatabase db = mTestPool.get(TEST_SQL + i);
            assertTrue(dbMap.containsKey(db));
            int k = dbMap.get(db);
            dbMap.put(db, ++k);
        }
        assertEquals(0, mTestPool.getFreePoolSize());
        assertEquals(MAX_CONN, mTestPool.getSize());
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());
        // print the distribution of the database connection handles received, should be uniform.
        for (SQLiteDatabase d : dbMap.keySet()) {
            Log.i(TAG, "connection # " + d.mConnectionNum + ", numHolders: " + dbMap.get(d));
        }
        // print the pool info
        Log.i(TAG, mTestPool.toString());
        // release all
        for (SQLiteDatabase d : dbMap.keySet()) {
            int num = dbMap.get(d);
            for (int i = 0; i < num; i++) {
                mTestPool.release(d);
            }
        }
        assertEquals(MAX_CONN, mTestPool.getFreePoolSize());
        assertEquals(MAX_CONN, mTestPool.getSize());
        assertEquals(MAX_CONN, mTestPool.getMaxPoolSize());
    }
}
