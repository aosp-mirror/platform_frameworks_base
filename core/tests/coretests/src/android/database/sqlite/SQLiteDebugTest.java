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

package android.database.sqlite;

import junit.framework.TestCase;

/**
 * Tests for the SQLiteDebug
 */
public class SQLiteDebugTest extends TestCase {
    private static final String TEST_DB = "test.db";

    public void testCaptureSql() {
        String rslt = SQLiteDebug.captureSql(TEST_DB, "select * from t1 where a=? and b=1",
                new Object[] {"blah"});
        String expectedVal = "select * from t1 where a='blah' and b=1";
        assertTrue(rslt.equals("captured_sql|" + TEST_DB + "|" + expectedVal));

        rslt = SQLiteDebug.captureSql(TEST_DB, "select * from t1 where a=?",
                new Object[] {"blah"});
        expectedVal = "select * from t1 where a='blah'";
        assertTrue(rslt.equals("captured_sql|" + TEST_DB + "|" + expectedVal));

        rslt = SQLiteDebug.captureSql(TEST_DB, "select * from t1 where a=1",
                new Object[] {"blah"});
        assertTrue(rslt.startsWith("too many bindArgs provided."));

        rslt = SQLiteDebug.captureSql(TEST_DB, "update t1 set a=? where b=?",
                new Object[] {"blah", "foo"});
        expectedVal = "update t1 set a='blah' where b='foo'";
        assertTrue(rslt.equals("captured_sql|" + TEST_DB + "|" + expectedVal));
    }
}
