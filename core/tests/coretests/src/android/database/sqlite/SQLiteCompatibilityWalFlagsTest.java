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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.DatabaseUtils;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * Tests for {@link SQLiteCompatibilityWalFlags}
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class SQLiteCompatibilityWalFlagsTest {

    private SQLiteDatabase mDatabase;

    @After
    public void tearDown() {
        SQLiteCompatibilityWalFlags.reset();
        if (mDatabase != null) {
            mDatabase.close();
            SQLiteDatabase.deleteDatabase(new File(mDatabase.getPath()));
        }
    }

    @Test
    public void testParseConfig() {
        SQLiteCompatibilityWalFlags.init("");
        assertFalse(SQLiteCompatibilityWalFlags.areFlagsSet());

        SQLiteCompatibilityWalFlags.init(null);
        assertFalse(SQLiteCompatibilityWalFlags.areFlagsSet());

        SQLiteCompatibilityWalFlags.init("compatibility_wal_supported=false,wal_syncmode=OFF");
        assertTrue(SQLiteCompatibilityWalFlags.areFlagsSet());
        assertFalse(SQLiteCompatibilityWalFlags.isCompatibilityWalSupported());
        assertEquals("OFF", SQLiteCompatibilityWalFlags.getWALSyncMode());
        assertEquals(-1, SQLiteCompatibilityWalFlags.getTruncateSize());

        SQLiteCompatibilityWalFlags.init("wal_syncmode=VALUE");
        assertTrue(SQLiteCompatibilityWalFlags.areFlagsSet());
        assertEquals(SQLiteGlobal.isCompatibilityWalSupported(),
                SQLiteCompatibilityWalFlags.isCompatibilityWalSupported());
        assertEquals("VALUE", SQLiteCompatibilityWalFlags.getWALSyncMode());
        assertEquals(-1, SQLiteCompatibilityWalFlags.getTruncateSize());

        SQLiteCompatibilityWalFlags.init("compatibility_wal_supported=true");
        assertTrue(SQLiteCompatibilityWalFlags.areFlagsSet());
        assertEquals(SQLiteGlobal.getWALSyncMode(),
                SQLiteCompatibilityWalFlags.getWALSyncMode());
        assertTrue(SQLiteCompatibilityWalFlags.isCompatibilityWalSupported());
        assertEquals(-1, SQLiteCompatibilityWalFlags.getTruncateSize());

        SQLiteCompatibilityWalFlags.init("truncate_size=1024");
        assertEquals(1024, SQLiteCompatibilityWalFlags.getTruncateSize());

        SQLiteCompatibilityWalFlags.reset();
        SQLiteCompatibilityWalFlags.init("Invalid value");
        assertFalse(SQLiteCompatibilityWalFlags.areFlagsSet());
    }

    @Test
    public void testApplyFlags() {
        Context ctx = InstrumentationRegistry.getContext();

        SQLiteCompatibilityWalFlags.init("compatibility_wal_supported=true,wal_syncmode=NORMAL");
        mDatabase = SQLiteDatabase
                .openOrCreateDatabase(ctx.getDatabasePath("SQLiteCompatibilityWalFlagsTest"), null);
        String journalMode = DatabaseUtils.stringForQuery(mDatabase, "PRAGMA journal_mode", null);
        assertEquals("WAL", journalMode.toUpperCase());
        String syncMode = DatabaseUtils.stringForQuery(mDatabase, "PRAGMA synchronous", null);
        assertEquals("Normal mode (1) is expected", "1", syncMode);
    }


}
