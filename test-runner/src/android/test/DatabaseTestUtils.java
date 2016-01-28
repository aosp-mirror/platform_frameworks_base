/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.test;

import com.google.android.collect.Sets;

import android.database.sqlite.SQLiteDatabase;
import android.database.Cursor;

import java.util.Set;

/**
 * A collection of utilities for writing unit tests for database code.
 * @hide pending API council approval
 */
public class DatabaseTestUtils {

    /**
     * Compares the schema of two databases and asserts that they are equal.
     * @param expectedDb the db that is known to have the correct schema
     * @param db the db whose schema should be checked
     */
    public static void assertSchemaEquals(SQLiteDatabase expectedDb, SQLiteDatabase db) {
        Set<String> expectedSchema = getSchemaSet(expectedDb);
        Set<String> schema = getSchemaSet(db);
        MoreAsserts.assertEquals(expectedSchema, schema);
    }

    private static Set<String> getSchemaSet(SQLiteDatabase db) {
        Set<String> schemaSet = Sets.newHashSet();

        Cursor entityCursor = db.rawQuery("SELECT sql FROM sqlite_master", null);
        try {
            while (entityCursor.moveToNext()) {
                String sql = entityCursor.getString(0);
                schemaSet.add(sql);
            }
        } finally {
            entityCursor.close();
        }
        return schemaSet;
    }
}
