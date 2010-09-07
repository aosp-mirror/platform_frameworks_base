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
import android.database.sqlite.SQLiteDatabaseTest.ClassToTestSqlCompilationAndCaching;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.io.File;

public class SQLiteUnfinalizedExceptionTest extends AndroidTestCase {
    private SQLiteDatabase mDatabase;
    private File mDatabaseFile;
    private static final String TABLE_NAME = "testCursor";
    @Override
    protected void setUp() throws Exception {
        super.setUp();

        File dbDir = getContext().getDir(this.getClass().getName(), Context.MODE_PRIVATE);
        mDatabaseFile = new File(dbDir, "UnfinalizedExceptionTest.db");
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

    @SmallTest
    public void testUnfinalizedExceptionNotExcpected() {
        mDatabase.execSQL("CREATE TABLE " + TABLE_NAME + " (i int, j int);");
        // the above statement should be in SQLiteDatabase.mPrograms
        // and should automatically be finalized when database is closed
        mDatabase.lock();
        try {
            mDatabase.closeDatabase();
        } finally {
            mDatabase.unlock();
        }
    }

    @SmallTest
    public void testUnfinalizedException() {
        mDatabase.execSQL("CREATE TABLE " + TABLE_NAME + " (i int, j int);");
        mDatabase.lock();
        mDatabase.closePendingStatements(); // clears the above from finalizer queue in mdatabase
        mDatabase.unlock();
        ClassToTestSqlCompilationAndCaching.create(mDatabase, "select * from "  + TABLE_NAME);
        // since the above is NOT closed, closing database should fail
        mDatabase.lock();
        try {
            mDatabase.closeDatabase();
            fail("exception expected");
        } catch (SQLiteUnfinalizedObjectsException e) {
            // expected
        } finally {
            mDatabase.unlock();
        }
    }
}
