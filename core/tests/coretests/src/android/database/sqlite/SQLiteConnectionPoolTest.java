/*
 * Copyright (C) 2017 The Android Open Source Project
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
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.HandlerThread;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * Tests for {@link SQLiteConnectionPool}
 *
 * <p>Run with:  bit FrameworksCoreTests:android.database.sqlite.SQLiteConnectionPoolTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class SQLiteConnectionPoolTest {
    private static final String TAG = "SQLiteConnectionPoolTest";

    private Context mContext;
    private File mTestDatabase;
    private SQLiteDatabaseConfiguration mTestConf;


    @Before
    public void setup() {
        mContext = InstrumentationRegistry.getContext();
        SQLiteDatabase db = SQLiteDatabase
                .openOrCreateDatabase(mContext.getDatabasePath("pool_test"), null);
        mTestDatabase = new File(db.getPath());
        Log.i(TAG, "setup: created " + mTestDatabase);
        db.close();
        mTestConf = new SQLiteDatabaseConfiguration(mTestDatabase.getPath(), 0);
    }

    @After
    public void teardown() {
        if (mTestDatabase != null) {
            Log.i(TAG, "teardown: deleting " + mTestDatabase);
            SQLiteDatabase.deleteDatabase(mTestDatabase);
        }
    }

    @Test
    public void testCloseIdleConnections() throws InterruptedException {
        HandlerThread thread = new HandlerThread("test-close-idle-connections-thread");
        Log.i(TAG, "Starting " + thread.getName());
        thread.start();
        SQLiteConnectionPool pool = SQLiteConnectionPool.open(mTestConf);
        pool.setupIdleConnectionHandler(thread.getLooper(), 100);
        SQLiteConnection c1 = pool.acquireConnection("pragma user_version", 0, null);
        assertEquals("First connection should be returned", 0, c1.getConnectionId());
        pool.releaseConnection(c1);
        SQLiteConnection c2 = pool.acquireConnection("pragma user_version", 0, null);
        assertTrue("Returned connection should be the same", c1 == c2);
        pool.releaseConnection(c2);
        Thread.sleep(200);
        SQLiteConnection c3 = pool.acquireConnection("pragma user_version", 0, null);
        assertTrue("New connection should be returned", c1 != c3);
        assertEquals("New connection should be returned", 1, c3.getConnectionId());
        pool.releaseConnection(c3);
        pool.close();
        thread.quit();
    }
}
