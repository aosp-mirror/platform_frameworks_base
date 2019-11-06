/*
 * Copyright (C) 2019 The Android Open Source Project
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
import static org.junit.Assert.fail;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class SQLiteTokenizerTest {
    private List<String> getTokens(String sql) {
        return SQLiteTokenizer.tokenize(sql, SQLiteTokenizer.OPTION_NONE);
    }

    private void checkTokens(String sql, String spaceSeparatedExpectedTokens) {
        final List<String> expected = spaceSeparatedExpectedTokens == null
                ? new ArrayList<>()
                : Arrays.asList(spaceSeparatedExpectedTokens.split(" +"));

        assertEquals(expected, getTokens(sql));
    }

    private void assertInvalidSql(String sql, String message) {
        try {
            getTokens(sql);
            fail("Didn't throw InvalidSqlException");
        } catch (IllegalArgumentException e) {
            assertTrue("Expected " + e.getMessage() + " to contain " + message,
                    e.getMessage().contains(message));
        }
    }

    @Test
    public void testWhitespaces() {
        checkTokens("  select  \t\r\n a\n\n  ", "select a");
        checkTokens("a b", "a b");
    }

    @Test
    public void testComment() {
        checkTokens("--\n", null);
        checkTokens("a--\n", "a");
        checkTokens("a--abcdef\n", "a");
        checkTokens("a--abcdef\nx", "a x");
        checkTokens("a--\nx", "a x");
        assertInvalidSql("a--abcdef", "Unterminated comment");
        assertInvalidSql("a--abcdef\ndef--", "Unterminated comment");

        checkTokens("/**/", null);
        assertInvalidSql("/*", "Unterminated comment");
        assertInvalidSql("/*/", "Unterminated comment");
        assertInvalidSql("/*\n* /*a", "Unterminated comment");
        checkTokens("a/**/", "a");
        checkTokens("/**/b", "b");
        checkTokens("a/**/b", "a b");
        checkTokens("a/* -- \n* /* **/b", "a b");
    }

    @Test
    public void testStrings() {
        assertInvalidSql("'", "Unterminated quote");
        assertInvalidSql("a'", "Unterminated quote");
        assertInvalidSql("a'''", "Unterminated quote");
        assertInvalidSql("a''' ", "Unterminated quote");
        checkTokens("''", null);
        checkTokens("''''", null);
        checkTokens("a''''b", "a b");
        checkTokens("a' '' 'b", "a b");
        checkTokens("'abc'", null);
        checkTokens("'abc\ndef'", null);
        checkTokens("a'abc\ndef'", "a");
        checkTokens("'abc\ndef'b", "b");
        checkTokens("a'abc\ndef'b", "a b");
        checkTokens("a'''abc\nd''ef'''b", "a b");
    }

    @Test
    public void testDoubleQuotes() {
        assertInvalidSql("\"", "Unterminated quote");
        assertInvalidSql("a\"", "Unterminated quote");
        assertInvalidSql("a\"\"\"", "Unterminated quote");
        assertInvalidSql("a\"\"\" ", "Unterminated quote");
        checkTokens("\"\"", "");
        checkTokens("\"\"\"\"", "\"");
        checkTokens("a\"\"\"\"b", "a \" b");
        checkTokens("a\"\t\"\"\t\"b", "a  \t\"\t  b");
        checkTokens("\"abc\"", "abc");
        checkTokens("\"abc\ndef\"", "abc\ndef");
        checkTokens("a\"abc\ndef\"", "a abc\ndef");
        checkTokens("\"abc\ndef\"b", "abc\ndef b");
        checkTokens("a\"abc\ndef\"b", "a abc\ndef b");
        checkTokens("a\"\"\"abc\nd\"\"ef\"\"\"b", "a \"abc\nd\"ef\" b");
    }

    @Test
    public void testBackQuotes() {
        assertInvalidSql("`", "Unterminated quote");
        assertInvalidSql("a`", "Unterminated quote");
        assertInvalidSql("a```", "Unterminated quote");
        assertInvalidSql("a``` ", "Unterminated quote");
        checkTokens("``", "");
        checkTokens("````", "`");
        checkTokens("a````b", "a ` b");
        checkTokens("a`\t``\t`b", "a  \t`\t  b");
        checkTokens("`abc`", "abc");
        checkTokens("`abc\ndef`", "abc\ndef");
        checkTokens("a`abc\ndef`", "a abc\ndef");
        checkTokens("`abc\ndef`b", "abc\ndef b");
        checkTokens("a`abc\ndef`b", "a abc\ndef b");
        checkTokens("a```abc\nd``ef```b", "a `abc\nd`ef` b");
    }

    @Test
    public void testBrackets() {
        assertInvalidSql("[", "Unterminated quote");
        assertInvalidSql("a[", "Unterminated quote");
        assertInvalidSql("a[ ", "Unterminated quote");
        assertInvalidSql("a[[ ", "Unterminated quote");
        checkTokens("[]", "");
        checkTokens("[[]", "[");
        checkTokens("a[[]b", "a [ b");
        checkTokens("a[\t[\t]b", "a  \t[\t  b");
        checkTokens("[abc]", "abc");
        checkTokens("[abc\ndef]", "abc\ndef");
        checkTokens("a[abc\ndef]", "a abc\ndef");
        checkTokens("[abc\ndef]b", "abc\ndef b");
        checkTokens("a[abc\ndef]b", "a abc\ndef b");
        checkTokens("a[[abc\nd[ef[]b", "a [abc\nd[ef[ b");
    }

    @Test
    public void testSemicolons() {
        assertInvalidSql(";", "Semicolon is not allowed");
        assertInvalidSql("  ;", "Semicolon is not allowed");
        assertInvalidSql(";  ", "Semicolon is not allowed");
        assertInvalidSql("-;-", "Semicolon is not allowed");
        checkTokens("--;\n", null);
        checkTokens("/*;*/", null);
        checkTokens("';'", null);
        checkTokens("[;]", ";");
        checkTokens("`;`", ";");
    }

    @Test
    public void testTokens() {
        checkTokens("a,abc,a00b,_1,_123,abcdef", "a abc a00b _1 _123 abcdef");
        checkTokens("a--\nabc/**/a00b''_1'''ABC'''`_123`abc[d]\"e\"f",
                "a abc a00b _1 _123 abc d e f");
    }
}
