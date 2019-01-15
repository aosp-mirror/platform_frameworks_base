/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.database.sqlite.SQLiteDatabase;
import android.test.MoreAsserts;
import android.util.Log;

import androidx.test.filters.MediumTest;
import androidx.test.filters.SmallTest;

import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.Locale;

public class DatabaseLocaleTest extends TestCase {

    private SQLiteDatabase mDatabase;

    private static final String[] STRINGS = {
        "c\u00f4t\u00e9",
        "cote",
        "c\u00f4te",
        "cot\u00e9",
        "boy",
        "dog",
        "COTE",
    };

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mDatabase = SQLiteDatabase.create(null);
        mDatabase.execSQL(
                "CREATE TABLE test (id INTEGER PRIMARY KEY, data TEXT COLLATE LOCALIZED);");
    }

    private void insertStrings() {
        for (String s : STRINGS) {
            mDatabase.execSQL("INSERT INTO test (data) VALUES('" + s + "');");
        }
    }

    @Override
    protected void tearDown() throws Exception {
        mDatabase.close();
        super.tearDown();
    }

    private String[] query(String sql) {
        Log.i("LocaleTest", "Querying: " + sql);
        Cursor c = mDatabase.rawQuery(sql, null);
        assertNotNull(c);
        ArrayList<String> items = new ArrayList<String>();
        while (c.moveToNext()) {
            items.add(c.getString(0));
            Log.i("LocaleTest", "...." + c.getString(0));
        }
        String[] result = items.toArray(new String[items.size()]);
        assertEquals(STRINGS.length, result.length);
        c.close();
        return result;
    }

    @MediumTest
    public void testLocaleInsertOrder() throws Exception {
        insertStrings();
        String[] results = query("SELECT data FROM test");
        MoreAsserts.assertEquals(STRINGS, results);
    }

    @MediumTest
    public void testLocaleenUS() throws Exception {
        insertStrings();
        Log.i("LocaleTest", "about to call setLocale en_US");
        mDatabase.setLocale(new Locale("en", "US"));
        String[] results;
        results = query("SELECT data FROM test ORDER BY data COLLATE LOCALIZED ASC");

        // The database code currently uses PRIMARY collation strength,
        // meaning that all versions of a character compare equal (regardless
        // of case or accents), leaving the "cote" flavors in database order.
        MoreAsserts.assertEquals(results, new String[] {
                STRINGS[4],  // "boy"
                STRINGS[0],  // sundry forms of "cote"
                STRINGS[1],
                STRINGS[2],
                STRINGS[3],
                STRINGS[6],  // "COTE"
                STRINGS[5],  // "dog"
        });
    }

    @SmallTest
    public void testHoge() throws Exception {
        Cursor cursor = null;
        try {
            String expectedString = new String(new int[] {0xFE000}, 0, 1);
            mDatabase.execSQL("INSERT INTO test(id, data) VALUES(1, '" + expectedString + "')");
            cursor = mDatabase.rawQuery("SELECT data FROM test WHERE id = 1", null);
            
            assertNotNull(cursor);
            assertTrue(cursor.moveToFirst());
            String actualString = cursor.getString(0);
            assertEquals(expectedString.length(), actualString.length());
            for (int i = 0; i < expectedString.length(); i++) {
                assertEquals((int)expectedString.charAt(i), (int)actualString.charAt(i));
            }
            assertEquals(expectedString, actualString);
        } finally {
            if (cursor != null) cursor.close();
        }
    }
}
