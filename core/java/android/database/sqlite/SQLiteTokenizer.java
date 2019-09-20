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

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * SQL Tokenizer specialized to extract tokens from SQL (snippets).
 * <p>
 * Based on sqlite3GetToken() in tokenzie.c in SQLite.
 * <p>
 * Source for v3.8.6 (which android uses): http://www.sqlite.org/src/artifact/ae45399d6252b4d7
 * (Latest source as of now: http://www.sqlite.org/src/artifact/78c8085bc7af1922)
 * <p>
 * Also draft spec: http://www.sqlite.org/draft/tokenreq.html
 *
 * @hide
 */
public class SQLiteTokenizer {
    private static boolean isAlpha(char ch) {
        return ('a' <= ch && ch <= 'z') || ('A' <= ch && ch <= 'Z') || (ch == '_');
    }

    private static boolean isNum(char ch) {
        return ('0' <= ch && ch <= '9');
    }

    private static boolean isAlNum(char ch) {
        return isAlpha(ch) || isNum(ch);
    }

    private static boolean isAnyOf(char ch, String set) {
        return set.indexOf(ch) >= 0;
    }

    private static IllegalArgumentException genException(String message, String sql) {
        throw new IllegalArgumentException(message + " in '" + sql + "'");
    }

    private static char peek(String s, int index) {
        return index < s.length() ? s.charAt(index) : '\0';
    }

    public static final int OPTION_NONE = 0;

    /**
     * Require that SQL contains only tokens; any comments or values will result
     * in an exception.
     */
    public static final int OPTION_TOKEN_ONLY = 1 << 0;

    /**
     * Tokenize the given SQL, returning the list of each encountered token.
     *
     * @throws IllegalArgumentException if invalid SQL is encountered.
     */
    public static List<String> tokenize(@Nullable String sql, int options) {
        final ArrayList<String> res = new ArrayList<>();
        tokenize(sql, options, res::add);
        return res;
    }

    /**
     * Tokenize the given SQL, sending each encountered token to the given
     * {@link Consumer}.
     *
     * @throws IllegalArgumentException if invalid SQL is encountered.
     */
    public static void tokenize(@Nullable String sql, int options, Consumer<String> checker) {
        if (sql == null) {
            return;
        }
        int pos = 0;
        final int len = sql.length();
        while (pos < len) {
            final char ch = peek(sql, pos);

            // Regular token.
            if (isAlpha(ch)) {
                final int start = pos;
                pos++;
                while (isAlNum(peek(sql, pos))) {
                    pos++;
                }
                final int end = pos;

                final String token = sql.substring(start, end);
                checker.accept(token);

                continue;
            }

            // Handle quoted tokens
            if (isAnyOf(ch, "'\"`")) {
                final int quoteStart = pos;
                pos++;

                for (;;) {
                    pos = sql.indexOf(ch, pos);
                    if (pos < 0) {
                        throw genException("Unterminated quote", sql);
                    }
                    if (peek(sql, pos + 1) != ch) {
                        break;
                    }
                    // Quoted quote char -- e.g. "abc""def" is a single string.
                    pos += 2;
                }
                final int quoteEnd = pos;
                pos++;

                if (ch != '\'') {
                    // Extract the token
                    final String tokenUnquoted = sql.substring(quoteStart + 1, quoteEnd);

                    final String token;

                    // Unquote if needed. i.e. "aa""bb" -> aa"bb
                    if (tokenUnquoted.indexOf(ch) >= 0) {
                        token = tokenUnquoted.replaceAll(
                                String.valueOf(ch) + ch, String.valueOf(ch));
                    } else {
                        token = tokenUnquoted;
                    }
                    checker.accept(token);
                } else {
                    if ((options &= OPTION_TOKEN_ONLY) != 0) {
                        throw genException("Non-token detected", sql);
                    }
                }
                continue;
            }
            // Handle tokens enclosed in [...]
            if (ch == '[') {
                final int quoteStart = pos;
                pos++;

                pos = sql.indexOf(']', pos);
                if (pos < 0) {
                    throw genException("Unterminated quote", sql);
                }
                final int quoteEnd = pos;
                pos++;

                final String token = sql.substring(quoteStart + 1, quoteEnd);

                checker.accept(token);
                continue;
            }
            if ((options &= OPTION_TOKEN_ONLY) != 0) {
                throw genException("Non-token detected", sql);
            }

            // Detect comments.
            if (ch == '-' && peek(sql, pos + 1) == '-') {
                pos += 2;
                pos = sql.indexOf('\n', pos);
                if (pos < 0) {
                    // We disallow strings ending in an inline comment.
                    throw genException("Unterminated comment", sql);
                }
                pos++;

                continue;
            }
            if (ch == '/' && peek(sql, pos + 1) == '*') {
                pos += 2;
                pos = sql.indexOf("*/", pos);
                if (pos < 0) {
                    throw genException("Unterminated comment", sql);
                }
                pos += 2;

                continue;
            }

            // Semicolon is never allowed.
            if (ch == ';') {
                throw genException("Semicolon is not allowed", sql);
            }

            // For this purpose, we can simply ignore other characters.
            // (Note it doesn't handle the X'' literal properly and reports this X as a token,
            // but that should be fine...)
            pos++;
        }
    }

    /**
     * Test if given token is a
     * <a href="https://www.sqlite.org/lang_keywords.html">SQLite reserved
     * keyword</a>.
     */
    public static boolean isKeyword(@NonNull String token) {
        switch (token.toUpperCase(Locale.US)) {
            case "ABORT": case "ACTION": case "ADD": case "AFTER":
            case "ALL": case "ALTER": case "ANALYZE": case "AND":
            case "AS": case "ASC": case "ATTACH": case "AUTOINCREMENT":
            case "BEFORE": case "BEGIN": case "BETWEEN": case "BINARY":
            case "BY": case "CASCADE": case "CASE": case "CAST":
            case "CHECK": case "COLLATE": case "COLUMN": case "COMMIT":
            case "CONFLICT": case "CONSTRAINT": case "CREATE": case "CROSS":
            case "CURRENT": case "CURRENT_DATE": case "CURRENT_TIME": case "CURRENT_TIMESTAMP":
            case "DATABASE": case "DEFAULT": case "DEFERRABLE": case "DEFERRED":
            case "DELETE": case "DESC": case "DETACH": case "DISTINCT":
            case "DO": case "DROP": case "EACH": case "ELSE":
            case "END": case "ESCAPE": case "EXCEPT": case "EXCLUDE":
            case "EXCLUSIVE": case "EXISTS": case "EXPLAIN": case "FAIL":
            case "FILTER": case "FOLLOWING": case "FOR": case "FOREIGN":
            case "FROM": case "FULL": case "GLOB": case "GROUP":
            case "GROUPS": case "HAVING": case "IF": case "IGNORE":
            case "IMMEDIATE": case "IN": case "INDEX": case "INDEXED":
            case "INITIALLY": case "INNER": case "INSERT": case "INSTEAD":
            case "INTERSECT": case "INTO": case "IS": case "ISNULL":
            case "JOIN": case "KEY": case "LEFT": case "LIKE":
            case "LIMIT": case "MATCH": case "NATURAL": case "NO":
            case "NOCASE": case "NOT": case "NOTHING": case "NOTNULL":
            case "NULL": case "OF": case "OFFSET": case "ON":
            case "OR": case "ORDER": case "OTHERS": case "OUTER":
            case "OVER": case "PARTITION": case "PLAN": case "PRAGMA":
            case "PRECEDING": case "PRIMARY": case "QUERY": case "RAISE":
            case "RANGE": case "RECURSIVE": case "REFERENCES": case "REGEXP":
            case "REINDEX": case "RELEASE": case "RENAME": case "REPLACE":
            case "RESTRICT": case "RIGHT": case "ROLLBACK": case "ROW":
            case "ROWS": case "RTRIM": case "SAVEPOINT": case "SELECT":
            case "SET": case "TABLE": case "TEMP": case "TEMPORARY":
            case "THEN": case "TIES": case "TO": case "TRANSACTION":
            case "TRIGGER": case "UNBOUNDED": case "UNION": case "UNIQUE":
            case "UPDATE": case "USING": case "VACUUM": case "VALUES":
            case "VIEW": case "VIRTUAL": case "WHEN": case "WHERE":
            case "WINDOW": case "WITH": case "WITHOUT":
                return true;
            default:
                return false;
        }
    }

    /**
     * Test if given token is a
     * <a href="https://www.sqlite.org/lang_corefunc.html">SQLite reserved
     * function</a>.
     */
    public static boolean isFunction(@NonNull String token) {
        switch (token.toLowerCase(Locale.US)) {
            case "abs": case "avg": case "char": case "coalesce":
            case "count": case "glob": case "group_concat": case "hex":
            case "ifnull": case "instr": case "length": case "like":
            case "likelihood": case "likely": case "lower": case "ltrim":
            case "max": case "min": case "nullif": case "random":
            case "randomblob": case "replace": case "round": case "rtrim":
            case "substr": case "sum": case "total": case "trim":
            case "typeof": case "unicode": case "unlikely": case "upper":
            case "zeroblob":
                return true;
            default:
                return false;
        }
    }

    /**
     * Test if given token is a
     * <a href="https://www.sqlite.org/datatype3.html">SQLite reserved type</a>.
     */
    public static boolean isType(@NonNull String token) {
        switch (token.toUpperCase(Locale.US)) {
            case "INT": case "INTEGER": case "TINYINT": case "SMALLINT":
            case "MEDIUMINT": case "BIGINT": case "INT2": case "INT8":
            case "CHARACTER": case "VARCHAR": case "NCHAR": case "NVARCHAR":
            case "TEXT": case "CLOB": case "BLOB": case "REAL":
            case "DOUBLE": case "FLOAT": case "NUMERIC": case "DECIMAL":
            case "BOOLEAN": case "DATE": case "DATETIME":
                return true;
            default:
                return false;
        }
    }
}
