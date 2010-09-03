/*
 * Copyright (C) 2010 The Android Open Source Project
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
import android.database.sqlite.SQLiteDatabaseTest.ClassToTestSqlCompilationAndCaching;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.io.File;
import java.util.ArrayList;

public class SQLiteCacheTest extends AndroidTestCase {
    private SQLiteDatabase mDatabase;
    private File mDatabaseFile;
    private static final String TABLE_NAME = "testCache";
    private SQLiteCache mCache;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        File dbDir = getContext().getDir(this.getClass().getName(), Context.MODE_PRIVATE);
        mDatabaseFile = new File(dbDir, "test.db");
        if (mDatabaseFile.exists()) {
            mDatabaseFile.delete();
        }
        mDatabase = SQLiteDatabase.openOrCreateDatabase(mDatabaseFile.getPath(), null);
        assertNotNull(mDatabase);
        mCache = mDatabase.mCache;
        assertNotNull(mCache);

        // create a test table
        mDatabase.execSQL("CREATE TABLE " + TABLE_NAME + " (i int, j int);");
    }

    @Override
    protected void tearDown() throws Exception {
        mDatabase.lock();
        // flush the above statement from cache and close all the pending statements to be released
        mCache.dealloc();
        mDatabase.closePendingStatements();
        mDatabase.unlock();
        assertEquals(0, mDatabase.getQueuedUpStmtList().size());
        mDatabase.close();
        mDatabaseFile.delete();
        super.tearDown();
    }

    /**
     * do N+1 queries - and when the 0th entry is removed from LRU cache due to the
     * insertion of (N+1)th entry, make sure 0th entry is closed
     */
    @SmallTest
    public void testLruCaching() {
        mDatabase.disableWriteAheadLogging();
        // set cache size
        int N = 25;
        mDatabase.setMaxSqlCacheSize(N);

        ArrayList<Integer> stmtObjs = new ArrayList<Integer>();
        ArrayList<String> sqlStrings = new ArrayList<String>();
        int stmt0 = 0;
        for (int i = 0; i < N+1; i++) {
            String s = "insert into " + TABLE_NAME + " values(" + i + ",?);";
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
        assertEquals(N, mCache.getCachesize());
        // is 0'th entry out of the cache? it should be in the list of statementIds
        // corresponding to the pre-compiled sql statements to be finalized.
        assertTrue(mDatabase.getQueuedUpStmtList().contains(stmt0));
        for (int i = 1; i < N+1; i++) {
            SQLiteCompiledSql compSql =
                    mDatabase.mCache.getCompiledStatementForSql(sqlStrings.get(i));
            assertNotNull(compSql);
            assertTrue(stmtObjs.contains(compSql.nStatement));
        }
        assertEquals(N, mCache.getCachesize());

    }

    /**
     * Cache should only have Select / Insert / Update / Delete / Replace.
     */
    @SmallTest
    public void testCachingOfCRUDstatementsOnly() {
        ClassToTestSqlCompilationAndCaching c;
        // do some CRUD sql
        int crudSqlNum = 7 * 4;
        mDatabase.setMaxSqlCacheSize(crudSqlNum);
        for (int i = 0; i < crudSqlNum / 4; i++) {
            c= ClassToTestSqlCompilationAndCaching.create(mDatabase,
                    "insert into " + TABLE_NAME + " values(" + i + ",?);");
            c.close();
            c = ClassToTestSqlCompilationAndCaching.create(mDatabase,
                    "update " + TABLE_NAME + " set i = " + i);
            c.close();
            c = ClassToTestSqlCompilationAndCaching.create(mDatabase,
                    "select * from " + TABLE_NAME + " where i = " + i);
            c.close();
            c= ClassToTestSqlCompilationAndCaching.create(mDatabase,
                    "delete from " + TABLE_NAME + " where i = " + i);
            c.close();
        }
        // do some non-CRUD sql
        c = ClassToTestSqlCompilationAndCaching.create(mDatabase,
                "create table j (i int);");
        c.close();
        c = ClassToTestSqlCompilationAndCaching.create(mDatabase,
                "pragma database_list;");
        c.close();
        c = ClassToTestSqlCompilationAndCaching.create(mDatabase,
                "begin transaction;");
        c.close();
        c = ClassToTestSqlCompilationAndCaching.create(mDatabase,
                "commit;");
        c.close();
        c = ClassToTestSqlCompilationAndCaching.create(mDatabase,
                "attach database \"blah\" as blah_db;");
        c.close();
        // cache size should be crudSqlNum
        assertEquals(crudSqlNum, mCache.getCachesize());
        // everything in the cache should be a CRUD sql statement
        for (String s : mCache.getKeys()) {
            int type = DatabaseUtils.getSqlStatementType(s);
            assertTrue(type == DatabaseUtils.STATEMENT_SELECT ||
                    type == DatabaseUtils.STATEMENT_UPDATE);
        }
    }

    /**
     * calling SQLiteCache.getCompiledStatementForSql() should reserve the cached-entry
     * for the caller, if the entry exists
     */
    @SmallTest
    public void testGetShouldReserveEntry() {
        String sql = "insert into " + TABLE_NAME + " values(1,?);";
        ClassToTestSqlCompilationAndCaching c =
                ClassToTestSqlCompilationAndCaching.create(mDatabase, sql);
        c.close();
        SQLiteCompiledSql compiledSql = mCache.getCompiledStatementForSql(sql);
        assertNotNull(compiledSql);
        assertTrue(compiledSql.isInUse());
        // get entry for the same sql again. should get null and a warning in log
        assertNull(mCache.getCompiledStatementForSql(sql));
        compiledSql.free();
        assertFalse(compiledSql.isInUse());
    }
}
