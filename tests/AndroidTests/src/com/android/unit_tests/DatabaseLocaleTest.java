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

package com.android.unit_tests;

import android.database.sqlite.SQLiteDatabase;
import android.database.Cursor;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;
import android.test.MoreAsserts;

import java.util.ArrayList;
import java.util.Locale;

import junit.framework.TestCase;

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
        mDatabase.execSQL("CREATE TABLE test (data TEXT COLLATE LOCALIZED);");
        for (String s : STRINGS) {
            mDatabase.execSQL("INSERT INTO test VALUES('" + s + "');");
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
        String[] results = query("SELECT data FROM test");
        MoreAsserts.assertEquals(STRINGS, results);
    }

    @MediumTest
    public void testLocaleenUS() throws Exception {
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
}
