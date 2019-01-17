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

package android.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabaseConfiguration;
import android.database.sqlite.SQLiteDebug;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link SQLiteOpenHelper}
 *
 * <p>Run with:  bit FrameworksCoreTests:android.database.SQLiteOpenHelperTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class SQLiteOpenHelperTest {
    private static final String TAG = "SQLiteOpenHelperTest";

    private TestHelper mTestHelper;
    private Context mContext;
    private List<SQLiteOpenHelper> mHelpersToClose;

    private static class TestHelper extends SQLiteOpenHelper {
        TestHelper(Context context) { // In-memory
            super(context, null, null, 1);
        }

        TestHelper(Context context, String name) {
            super(context, name, null, 1);
        }

        TestHelper(Context context, String name, int version, SQLiteDatabase.OpenParams params) {
            super(context, name, version, params);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        }
    }

    @Before
    public void setup() {
        mContext = InstrumentationRegistry.getContext();
        mTestHelper = new TestHelper(mContext, "openhelper_test");
        mHelpersToClose = new ArrayList<>();
        mHelpersToClose.add(mTestHelper);
    }

    @After
    public void teardown() {
        for (SQLiteOpenHelper helper : mHelpersToClose) {
            try {
                helper.close();
                if (mTestHelper.getDatabaseName() != null) {
                    SQLiteDatabase.deleteDatabase(
                            mContext.getDatabasePath(mTestHelper.getDatabaseName()));
                }
            } catch (RuntimeException ex) {
                Log.w(TAG, "Error occured when closing db helper " + helper, ex);
            }
        }
    }

    @Test
    public void testLookasideDefault() throws Exception {
        assertNotNull(mTestHelper.getWritableDatabase());
        verifyLookasideStats(false);
    }

    @Test
    public void testLookasideDisabled() throws Exception {
        mTestHelper.setLookasideConfig(0, 0);
        assertNotNull(mTestHelper.getWritableDatabase());
        verifyLookasideStats(true);
    }

    @Test
    public void testInMemoryLookasideDisabled() throws Exception {
        TestHelper memHelper = new TestHelper(mContext);
        mHelpersToClose.add(memHelper);
        memHelper.setLookasideConfig(0, 0);
        assertNotNull(memHelper.getWritableDatabase());
        verifyLookasideStats(SQLiteDatabaseConfiguration.MEMORY_DB_PATH, true);
    }

    @Test
    public void testInMemoryLookasideDefault() throws Exception {
        TestHelper memHelper = new TestHelper(mContext);
        mHelpersToClose.add(memHelper);
        assertNotNull(memHelper.getWritableDatabase());
        verifyLookasideStats(SQLiteDatabaseConfiguration.MEMORY_DB_PATH, false);
    }

    @Test
    public void testSetLookasideConfigValidation() {
        try {
            mTestHelper.setLookasideConfig(-1, 0);
            fail("Negative slot size should be rejected");
        } catch (IllegalArgumentException expected) {
        }
        try {
            mTestHelper.setLookasideConfig(0, -10);
            fail("Negative slot count should be rejected");
        } catch (IllegalArgumentException expected) {
        }
        try {
            mTestHelper.setLookasideConfig(1, 0);
            fail("Illegal config should be rejected");
        } catch (IllegalArgumentException expected) {
        }
        try {
            mTestHelper.setLookasideConfig(0, 1);
            fail("Illegal config should be rejected");
        } catch (IllegalArgumentException expected) {
        }
    }

    private void verifyLookasideStats(boolean expectDisabled) {
        verifyLookasideStats(mTestHelper.getDatabaseName(), expectDisabled);
    }

    private static void verifyLookasideStats(String dbName, boolean expectDisabled) {
        boolean dbStatFound = false;
        SQLiteDebug.PagerStats info = SQLiteDebug.getDatabaseInfo();
        for (SQLiteDebug.DbStats dbStat : info.dbStats) {
            if (dbStat.dbName.endsWith(dbName)) {
                dbStatFound = true;
                Log.i(TAG, "Lookaside for " + dbStat.dbName + " " + dbStat.lookaside);
                if (expectDisabled) {
                    assertTrue("lookaside slots count should be zero", dbStat.lookaside == 0);
                } else {
                    assertTrue("lookaside slots count should be greater than zero",
                            dbStat.lookaside > 0);
                }
            }
        }
        assertTrue("No dbstat found for " + dbName, dbStatFound);
    }

    @Test
    public void testOpenParamsConstructor() {
        SQLiteDatabase.OpenParams params = new SQLiteDatabase.OpenParams.Builder()
                .setJournalMode("DELETE")
                .setSynchronousMode("OFF")
                .build();

        TestHelper helper = new TestHelper(mContext, "openhelper_test_constructor", 1, params);
        mHelpersToClose.add(helper);

        String journalMode = DatabaseUtils
                .stringForQuery(helper.getReadableDatabase(), "PRAGMA journal_mode", null);

        assertEquals("DELETE", journalMode.toUpperCase());
        String syncMode = DatabaseUtils
                .stringForQuery(helper.getReadableDatabase(), "PRAGMA synchronous", null);

        assertEquals("0", syncMode);
    }

}
