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

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.sqlite.SQLiteCursor;
import android.database.sqlite.SQLiteDatabase;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.LargeTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class CursorWindowPerfTest {
    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private static Context getContext() {
        return InstrumentationRegistry.getTargetContext();
    }

    private static final String DB_NAME = CursorWindowPerfTest.class.toString();

    private static SQLiteDatabase sDatabase;

    @BeforeClass
    public static void setup() {
        getContext().deleteDatabase(DB_NAME);
        sDatabase = getContext().openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);

        for (TableHelper helper : TableHelper.TABLE_HELPERS) {
            sDatabase.execSQL(helper.createSql());
            final String insert = helper.insertSql();

            // this test only needs 1 row
            sDatabase.execSQL(insert, helper.createItem(0));
        }

    }

    @AfterClass
    public static void teardown() {
        getContext().deleteDatabase(DB_NAME);
    }

    @Test
    public void loadInt() {
        loadRowFromCursorWindow(TableHelper.INT_1, false);
    }

    @Test
    public void loadInt_doubleRef() {
        loadRowFromCursorWindow(TableHelper.INT_1, true);
    }

    @Test
    public void load10Ints() {
        loadRowFromCursorWindow(TableHelper.INT_10, false);
    }

    @Test
    public void loadUser() {
        loadRowFromCursorWindow(TableHelper.USER, false);
    }

    private void loadRowFromCursorWindow(TableHelper helper, boolean doubleRef) {
        try (Cursor cursor = sDatabase.rawQuery(helper.readSql(), new String[0])) {
            TableHelper.CursorReader reader = helper.createReader(cursor);

            SQLiteCursor sqLiteCursor = (SQLiteCursor) cursor;

            sqLiteCursor.getCount(); // load one window
            CursorWindow window = sqLiteCursor.getWindow();
            assertTrue("must have enough rows", window.getNumRows() >= 1);
            int start = window.getStartPosition();

            BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

            if (!doubleRef) {
                // normal access
                while (state.keepRunning()) {
                    cursor.moveToPosition(start);
                    reader.read();
                }
            } else {
                // add an extra window acquire/release to measure overhead
                while (state.keepRunning()) {
                    cursor.moveToPosition(start);
                    try {
                        window.acquireReference();
                        reader.read();
                    } finally {
                        window.releaseReference();
                    }
                }
            }
        }
    }
}
