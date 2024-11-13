/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.database.DatabaseUtils.STATEMENT_CREATE;
import static android.database.DatabaseUtils.STATEMENT_DDL;
import static android.database.DatabaseUtils.STATEMENT_OTHER;
import static android.database.DatabaseUtils.STATEMENT_SELECT;
import static android.database.DatabaseUtils.STATEMENT_UPDATE;
import static android.database.DatabaseUtils.STATEMENT_WITH;
import static android.database.DatabaseUtils.bindSelection;
import static android.database.DatabaseUtils.getSqlStatementType;
import static android.database.DatabaseUtils.getSqlStatementTypeExtended;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DatabaseUtilsTest {
    private static final Object[] ARGS = { "baz", 4, null };

    @Test
    public void testBindSelection_none() throws Exception {
        assertEquals(null,
                bindSelection(null, ARGS));
        assertEquals("",
                bindSelection("", ARGS));
        assertEquals("foo=bar",
                bindSelection("foo=bar", ARGS));
    }

    @Test
    public void testBindSelection_normal() throws Exception {
        assertEquals("foo='baz'",
                bindSelection("foo=?", ARGS));
        assertEquals("foo='baz' AND bar=4",
                bindSelection("foo=? AND bar=?", ARGS));
        assertEquals("foo='baz' AND bar=4 AND meow=NULL",
                bindSelection("foo=? AND bar=? AND meow=?", ARGS));
    }

    @Test
    public void testBindSelection_whitespace() throws Exception {
        assertEquals("BETWEEN 5 AND 10",
                bindSelection("BETWEEN? AND ?", 5, 10));
        assertEquals("IN 'foo'",
                bindSelection("IN?", "foo"));
    }

    @Test
    public void testBindSelection_indexed() throws Exception {
        assertEquals("foo=10 AND bar=11 AND meow=1",
                bindSelection("foo=?10 AND bar=? AND meow=?1",
                        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12));
    }

    @Test
    public void testStatementType() {
        final int sel = STATEMENT_SELECT;
        assertEquals(sel, getSqlStatementType("SELECT"));
        assertEquals(sel, getSqlStatementType("  SELECT"));
        assertEquals(sel, getSqlStatementType(" \n\r\f\t SELECT"));
        assertEquals(sel, getSqlStatementType(" \n\r\f\t SEL"));

        final int upd = STATEMENT_UPDATE;
        assertEquals(upd, getSqlStatementType("UPDATE"));
        assertEquals(upd, getSqlStatementType("  UPDATE"));
        assertEquals(upd, getSqlStatementType(" \n UPDATE"));

        final int ddl = STATEMENT_DDL;
        assertEquals(ddl, getSqlStatementType("ALTER TABLE t1 ADD COLUMN j int"));
        assertEquals(ddl, getSqlStatementType("CREATE TABLE t1 (i int)"));

        // Verify that the answers are case-insensitive
        assertEquals(sel, getSqlStatementType("select"));
        assertEquals(sel, getSqlStatementType("sElect"));
        assertEquals(sel, getSqlStatementType("sELECT"));
        assertEquals(sel, getSqlStatementType("seLECT"));

        // Short statements, leading comments, and WITH are decoded to "other" in the public API.
        final int othr = STATEMENT_OTHER;
        assertEquals(othr, getSqlStatementType("SE"));
        assertEquals(othr, getSqlStatementType("SE LECT"));
        assertEquals(othr, getSqlStatementType("-- cmt\n SE"));
        assertEquals(othr, getSqlStatementType("WITH"));
        assertEquals(othr, getSqlStatementType("-"));
        assertEquals(othr, getSqlStatementType("--"));
        assertEquals(othr, getSqlStatementType("*/* foo */ SEL"));

        // Verify that leading line-comments are skipped.
        assertEquals(sel, getSqlStatementType("-- cmt\n SELECT"));
        assertEquals(sel, getSqlStatementType("-- line 1\n-- line 2\n SELECT"));
        assertEquals(sel, getSqlStatementType("-- line 1\nSELECT"));
        // Verify that embedded comments do not confuse the scanner.
        assertEquals(sel, getSqlStatementType("-- line 1\nSELECT\n-- line 3\n"));

        // Verify that leading block-comments are skipped.
        assertEquals(sel, getSqlStatementType("/* foo */SELECT"));
        assertEquals(sel, getSqlStatementType("/* line 1\n line 2\n*/\nSELECT"));
        assertEquals(sel, getSqlStatementType("/* UPDATE\nline 2*/\nSELECT"));
        // Verify that embedded comment characters do not confuse the scanner.
        assertEquals(sel, getSqlStatementType("/* Foo /* /* // ** */SELECT"));

        // Mix it up with comment types
        assertEquals(sel, getSqlStatementType("/* foo */ -- bar\n SELECT"));

        // Test the extended statement types.  Note that the STATEMENT_COMMENT type is not possible,
        // since leading comments are skipped.

        final int with = STATEMENT_WITH;
        assertEquals(with, getSqlStatementTypeExtended("WITH"));

        final int cre = STATEMENT_CREATE;
        assertEquals(cre, getSqlStatementTypeExtended("CREATE TABLE t1 (i int)"));
    }
}
