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
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.io.File;

public class SQLiteCursorTest extends AndroidTestCase {
    private SQLiteDatabase mDatabase;
    private File mDatabaseFile;
    private static final String TABLE_NAME = "testCursor";
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
        // create a test table
        mDatabase.execSQL("CREATE TABLE " + TABLE_NAME + " (i int, j int);");
    }

    @Override
    protected void tearDown() throws Exception {
        mDatabase.close();
        mDatabaseFile.delete();
        super.tearDown();
    }

    @SmallTest
    public void testQueryObjReassignment() {
        mDatabase.enableWriteAheadLogging();
        // have a few connections in the database connection pool
        DatabaseConnectionPool pool = mDatabase.mConnectionPool;
        pool.setMaxPoolSize(5);
        SQLiteCursor cursor =
                (SQLiteCursor) mDatabase.rawQuery("select * from " + TABLE_NAME, null);
        assertNotNull(cursor);
        // it should use a pooled database connection
        SQLiteDatabase db = cursor.getDatabase();
        assertTrue(db.mConnectionNum > 0);
        assertFalse(mDatabase.equals(db));
        assertEquals(mDatabase, db.mParentConnObj);
        assertTrue(pool.getConnectionList().contains(db));
        assertTrue(db.isOpen());
        // do a requery. cursor should continue to use the above pooled connection
        cursor.requery();
        SQLiteDatabase dbAgain = cursor.getDatabase();
        assertEquals(db, dbAgain);
        // disable WAL so that the pooled connection held by the above cursor is closed
        mDatabase.disableWriteAheadLogging();
        assertFalse(db.isOpen());
        assertNull(mDatabase.mConnectionPool);
        // requery - which should make the cursor use mDatabase connection since the pooled
        // connection is no longer available
        cursor.requery();
        SQLiteDatabase db1 = cursor.getDatabase();
        assertTrue(db1.mConnectionNum == 0);
        assertEquals(mDatabase, db1);
        assertNull(mDatabase.mConnectionPool);
        assertTrue(db1.isOpen());
        assertFalse(mDatabase.equals(db));
        // enable WAL and requery - this time a pooled connection should be used
        mDatabase.enableWriteAheadLogging();
        cursor.requery();
        db = cursor.getDatabase();
        assertTrue(db.mConnectionNum > 0);
        assertFalse(mDatabase.equals(db));
        assertEquals(mDatabase, db.mParentConnObj);
        assertTrue(mDatabase.mConnectionPool.getConnectionList().contains(db));
        assertTrue(db.isOpen());
    }
}
