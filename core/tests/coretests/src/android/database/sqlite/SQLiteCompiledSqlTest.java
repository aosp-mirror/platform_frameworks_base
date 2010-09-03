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
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabaseTest.ClassToTestSqlCompilationAndCaching;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.io.File;

public class SQLiteCompiledSqlTest extends AndroidTestCase {
    private SQLiteDatabase mDatabase;
    private File mDatabaseFile;
    private static final String TABLE_NAME = "testCache";
    private SQLiteCache mCache;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        File dbDir = getContext().getDir(this.getClass().getName(), Context.MODE_PRIVATE);
        mDatabaseFile = new File(dbDir, "sqlitecursor_test.db");
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
        mDatabase.close();
        mDatabaseFile.delete();
        super.tearDown();
    }

    /**
     * releaseIfNotInUse() should release only if it is not in use
     */
    @SmallTest
    public void testReleaseIfNotInUse() {
        ClassToTestSqlCompilationAndCaching c;
        // do some CRUD sql
        int crudSqlNum = 20 * 4;
        mDatabase.setMaxSqlCacheSize(crudSqlNum);
        for (int i = 0; i < crudSqlNum / 4; i++) {
            c = ClassToTestSqlCompilationAndCaching.create(mDatabase,
                    "insert into " + TABLE_NAME + " values(" + i + ",?);");
            c.close();
            c = ClassToTestSqlCompilationAndCaching.create(mDatabase,
                    "update " + TABLE_NAME + " set i = " + i);
            c.close();
            c = ClassToTestSqlCompilationAndCaching.create(mDatabase,
                    "select * from " + TABLE_NAME + " where i = " + i);
            c.close();
            c = ClassToTestSqlCompilationAndCaching.create(mDatabase,
                    "delete from " + TABLE_NAME + " where i = " + i);
            c.close();
        }
        assertEquals(crudSqlNum, mCache.getCachesize());
        String sql = "insert into " + TABLE_NAME + " values(1,?);";
        SQLiteCompiledSql compiledSql = mCache.getCompiledStatementForSql(sql);
        assertNotNull(compiledSql);
        assertTrue(compiledSql.isInUse());
        // the object is in use. try to release it
        compiledSql.releaseIfNotInUse();
        // compiledSql should not be released yet
        int stmtId = compiledSql.nStatement;
        assertTrue(stmtId > 0);
        // free the object and call releaseIfNotInUse() again - and it should work this time
        compiledSql.free();
        assertFalse(compiledSql.isInUse());
        compiledSql.releaseIfNotInUse();
        assertEquals(0, compiledSql.nStatement);
        assertTrue(mDatabase.getQueuedUpStmtList().contains(stmtId));
    }
}
