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
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.DatabaseUtils;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

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
        SQLiteCompatibilityWalFlags.init(null);

        // Ensure that legacy compatibility wal isn't turned on by the old flag.
        SQLiteCompatibilityWalFlags.init("compatibility_wal_supported=true,wal_syncmode=OFF");
        assertFalse(SQLiteCompatibilityWalFlags.isLegacyCompatibilityWalEnabled());
        try {
            SQLiteCompatibilityWalFlags.getWALSyncMode();
            fail();
        } catch (IllegalStateException expected) {
        }
        assertEquals(-1, SQLiteCompatibilityWalFlags.getTruncateSize());


        SQLiteCompatibilityWalFlags.init("wal_syncmode=VALUE");
        assertFalse(SQLiteCompatibilityWalFlags.isLegacyCompatibilityWalEnabled());
        assertEquals(-1, SQLiteCompatibilityWalFlags.getTruncateSize());
        try {
            SQLiteCompatibilityWalFlags.getWALSyncMode();
            fail();
        } catch (IllegalStateException expected) {
        }

        SQLiteCompatibilityWalFlags.init("legacy_compatibility_wal_enabled=true");
        assertTrue(SQLiteCompatibilityWalFlags.isLegacyCompatibilityWalEnabled());
        assertEquals(SQLiteGlobal.getWALSyncMode(),
                SQLiteCompatibilityWalFlags.getWALSyncMode());

        SQLiteCompatibilityWalFlags.init(
                "legacy_compatibility_wal_enabled=true,wal_syncmode=VALUE");
        assertTrue(SQLiteCompatibilityWalFlags.isLegacyCompatibilityWalEnabled());
        assertEquals("VALUE", SQLiteCompatibilityWalFlags.getWALSyncMode());

        SQLiteCompatibilityWalFlags.init("truncate_size=1024");
        assertEquals(1024, SQLiteCompatibilityWalFlags.getTruncateSize());

        SQLiteCompatibilityWalFlags.reset();
        SQLiteCompatibilityWalFlags.init("Invalid value");
        assertFalse(SQLiteCompatibilityWalFlags.isLegacyCompatibilityWalEnabled());
    }

    @Test
    public void testApplyFlags() {
        Context ctx = InstrumentationRegistry.getContext();

        SQLiteCompatibilityWalFlags.init(
                "legacy_compatibility_wal_enabled=true,wal_syncmode=NORMAL");
        mDatabase = SQLiteDatabase
                .openOrCreateDatabase(ctx.getDatabasePath("SQLiteCompatibilityWalFlagsTest"), null);
        String journalMode = DatabaseUtils.stringForQuery(mDatabase, "PRAGMA journal_mode", null);
        assertEquals("WAL", journalMode.toUpperCase());
        String syncMode = DatabaseUtils.stringForQuery(mDatabase, "PRAGMA synchronous", null);
        assertEquals("Normal mode (1) is expected", "1", syncMode);
    }

    @Test
    public void testApplyFlags_thenDisableWriteAheadLogging() {
        Context ctx = InstrumentationRegistry.getContext();

        SQLiteCompatibilityWalFlags.init(
                "legacy_compatibility_wal_enabled=true,wal_syncmode=FULL");
        mDatabase = SQLiteDatabase
                .openOrCreateDatabase(ctx.getDatabasePath("SQLiteCompatibilityWalFlagsTest"), null);

        mDatabase.disableWriteAheadLogging();
        String journalMode = DatabaseUtils.stringForQuery(mDatabase, "PRAGMA journal_mode", null);
        assertEquals(SQLiteGlobal.getDefaultJournalMode(), journalMode.toUpperCase());
        String syncMode = DatabaseUtils.stringForQuery(mDatabase, "PRAGMA synchronous", null);
        // TODO: This is the old behaviour and seems incorrect. The specified wal_syncmode was only
        // intended to be used if the database is in WAL mode, and we should revert to the global
        // default sync mode if WAL is disabled.
        assertEquals("Normal mode (2) is expected", "2", syncMode);
    }
}
