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

package android.database.sqlite;

import android.util.Log;

/**
 * Provides debugging info about all SQLite databases running in the current process.
 *
 * {@hide}
 */
public final class SQLiteDebug {
    /**
     * Controls the printing of SQL statements as they are executed.
     */
    public static final boolean DEBUG_SQL_STATEMENTS =
            Log.isLoggable("SQLiteStatements", Log.VERBOSE);

    /**
     * Controls the printing of compiled-sql-statement cache stats.
     */
    public static final boolean DEBUG_SQL_CACHE =
            Log.isLoggable("SQLiteCompiledSql", Log.VERBOSE);

    /**
     * Controls the capturing and printing of complete sql statement including the bind args and
     * the database name.
     */
    public static final boolean DEBUG_CAPTURE_SQL =
            Log.isLoggable("SQLiteCaptureSql", Log.VERBOSE);

    /**
     * Controls the stack trace reporting of active cursors being
     * finalized.
     */
    public static final boolean DEBUG_ACTIVE_CURSOR_FINALIZATION =
            Log.isLoggable("SQLiteCursorClosing", Log.VERBOSE);

    /**
     * Controls the tracking of time spent holding the database lock.
     */
    public static final boolean DEBUG_LOCK_TIME_TRACKING =
            Log.isLoggable("SQLiteLockTime", Log.VERBOSE);

    /**
     * Controls the printing of stack traces when tracking the time spent holding the database lock.
     */
    public static final boolean DEBUG_LOCK_TIME_TRACKING_STACK_TRACE =
            Log.isLoggable("SQLiteLockStackTrace", Log.VERBOSE);

    /**
     * Contains statistics about the active pagers in the current process.
     *
     * @see #getPagerStats(PagerStats)
     */
    public static class PagerStats {
        /** The total number of bytes in all pagers in the current process */
        public long totalBytes;
        /** The number of bytes in referenced pages in all pagers in the current process */
        public long referencedBytes;
        /** The number of bytes in all database files opened in the current process */
        public long databaseBytes;
        /** The number of pagers opened in the current process */
        public int numPagers;
    }

    /**
     * Gathers statistics about all pagers in the current process.
     */
    public static native void getPagerStats(PagerStats stats);

    /**
     * Returns the size of the SQLite heap.
     * @return The size of the SQLite heap in bytes.
     */
    public static native long getHeapSize();

    /**
     * Returns the amount of allocated memory in the SQLite heap.
     * @return The allocated size in bytes.
     */
    public static native long getHeapAllocatedSize();

    /**
     * Returns the amount of free memory in the SQLite heap.
     * @return The freed size in bytes.
     */
    public static native long getHeapFreeSize();

    /**
     * Determines the number of dirty belonging to the SQLite
     * heap segments of this process.  pages[0] returns the number of
     * shared pages, pages[1] returns the number of private pages
     */
    public static native void getHeapDirtyPages(int[] pages);

    private static int sNumActiveCursorsFinalized = 0;

    /**
     * Returns the number of active cursors that have been finalized. This depends on the GC having
     * run but is still useful for tests.
     */
    public static int getNumActiveCursorsFinalized() {
        return sNumActiveCursorsFinalized;
    }

    static synchronized void notifyActiveCursorFinalized() {
        sNumActiveCursorsFinalized++;
    }

    /**
     * returns a  message containing the given database name (path) and the string built by
     * replacing "?" characters in the given sql string with the corresponding
     * positional values from the given param bindArgs.
     *
     * @param path the database name
     * @param sql sql string with possibly "?" for bindargs
     * @param bindArgs args for "?"s in the above string
     * @return the String to be logged
     */
    /* package */ static String captureSql(String path, String sql, Object[] bindArgs) {
        // how many bindargs in sql
        sql = sql.trim();
        String args[] = sql.split("\\?");
        // how many "?"s in the given sql string?
        int varArgsInSql = (sql.endsWith("?")) ? args.length : args.length - 1;

        // how many bind args do we have in the given input param bindArgs
        int bindArgsLen = (bindArgs == null) ? 0 : bindArgs.length;
        if (varArgsInSql < bindArgsLen) {
            return "too many bindArgs provided. " +
                    "# of bindArgs = " + bindArgsLen + ", # of varargs = " + varArgsInSql +
                    "; sql = " + sql;
        }

        // if there are no bindArgs, we are done. log the sql as is.
        if (bindArgsLen == 0 && varArgsInSql == 0) {
            return logSql(path, sql);
        }

        StringBuilder buf = new StringBuilder();

        // take the supplied bindArgs and plug them into sql
        for (int i = 0; i < bindArgsLen; i++) {
            buf.append(args[i]);
            buf.append(bindArgs[i]);
        }

        // does given sql have more varArgs than the supplied bindArgs
        // if so, assign nulls to the extra varArgs in sql
        for (int i = bindArgsLen; i < varArgsInSql; i ++) {
            buf.append(args[i]);
            buf.append("null");
        }

        // if there are any characters left in the given sql string AFTER the last "?"
        // log them also. for example, if the given sql = "select * from test where a=? and b=1
        // then the following code appends " and b=1" string to buf.
        if (varArgsInSql < args.length) {
            buf.append(args[varArgsInSql]);
        }
        return logSql(path, buf.toString());
    }

    private static String logSql(String path, String sql) {
        return "captured_sql|" + path + "|" + sql + ";";
    }
}
