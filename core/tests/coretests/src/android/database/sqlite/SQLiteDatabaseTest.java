/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.os.SystemClock;
import android.test.AndroidTestCase;
import android.util.Log;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class SQLiteDatabaseTest {

    private static final String TAG = "SQLiteDatabaseTest";

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();

    private SQLiteDatabase mDatabase;
    private File mDatabaseFile;
    private static final String DATABASE_FILE_NAME = "database_test.db";

    @Before
    public void setUp() throws Exception {
        assertNotNull(mContext);
        mContext.deleteDatabase(DATABASE_FILE_NAME);
        mDatabaseFile = mContext.getDatabasePath(DATABASE_FILE_NAME);
        mDatabaseFile.getParentFile().mkdirs(); // directory may not exist
        mDatabase = SQLiteDatabase.openOrCreateDatabase(mDatabaseFile, null);
        assertNotNull(mDatabase);
    }

    @After
    public void tearDown() throws Exception {
        closeAndDeleteDatabase();
    }

    private void closeAndDeleteDatabase() {
        mDatabase.close();
        SQLiteDatabase.deleteDatabase(mDatabaseFile);
    }

    @Test
    public void testStatementDDLEvictsCache() {
        // The following will be cached (key is SQL string)
        String selectQuery = "SELECT * FROM t1";

        mDatabase.beginTransaction();
        mDatabase.execSQL("CREATE TABLE `t1` (`c1` INTEGER NOT NULL PRIMARY KEY, data TEXT)");
        try (Cursor c = mDatabase.rawQuery(selectQuery, null)) {
            assertEquals(2, c.getColumnCount());
        }
        // Alter the schema in such a way that if the cached query is used it would produce wrong
        // results due to the change in column amounts.
        mDatabase.execSQL("ALTER TABLE `t1` RENAME TO `t1_old`");
        mDatabase.execSQL("CREATE TABLE `t1` (`c1` INTEGER NOT NULL PRIMARY KEY)");
        // Execute cached query (that should have been evicted), validating it sees the new schema.
        try (Cursor c = mDatabase.rawQuery(selectQuery, null)) {
            assertEquals(1, c.getColumnCount());
        }
        mDatabase.setTransactionSuccessful();
        mDatabase.endTransaction();
    }

    @Test
    public void testStressDDLEvicts() {
        mDatabase.enableWriteAheadLogging();
        mDatabase.execSQL("CREATE TABLE `t1` (`c1` INTEGER NOT NULL PRIMARY KEY, data TEXT)");
        final int iterations = 1000;
        ExecutorService exec = Executors.newFixedThreadPool(2);
        exec.execute(() -> {
                    boolean pingPong = true;
                    for (int i = 0; i < iterations; i++) {
                        mDatabase.beginTransaction();
                        if (pingPong) {
                            mDatabase.execSQL("ALTER TABLE `t1` RENAME TO `t1_old`");
                            mDatabase.execSQL("CREATE TABLE `t1` (`c1` INTEGER NOT NULL "
                                + "PRIMARY KEY)");
                            pingPong = false;
                        } else {
                            mDatabase.execSQL("DROP TABLE `t1`");
                            mDatabase.execSQL("ALTER TABLE `t1_old` RENAME TO `t1`");
                            pingPong = true;
                        }
                        mDatabase.setTransactionSuccessful();
                        mDatabase.endTransaction();
                    }
                });
        exec.execute(() -> {
                    for (int i = 0; i < iterations; i++) {
                        try (Cursor c = mDatabase.rawQuery("SELECT * FROM t1", null)) {
                            c.getCount();
                        }
                    }
                });
        try {
            exec.shutdown();
            assertTrue(exec.awaitTermination(1, TimeUnit.MINUTES));
        } catch (InterruptedException e) {
            fail("Timed out");
        }
    }
}
