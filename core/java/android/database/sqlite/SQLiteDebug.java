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

import java.util.ArrayList;

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
     * Controls the printing of wall-clock time taken to execute SQL statements
     * as they are executed.
     */
    public static final boolean DEBUG_SQL_TIME =
            Log.isLoggable("SQLiteTime", Log.VERBOSE);

    /**
     * Controls the printing of compiled-sql-statement cache stats.
     */
    public static final boolean DEBUG_SQL_CACHE =
            Log.isLoggable("SQLiteCompiledSql", Log.VERBOSE);

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
        /** The total number of bytes in all pagers in the current process
         * @deprecated not used any longer
         */
        @Deprecated
        public long totalBytes;
        /** The number of bytes in referenced pages in all pagers in the current process
         * @deprecated not used any longer
         * */
        @Deprecated
        public long referencedBytes;
        /** The number of bytes in all database files opened in the current process
         * @deprecated not used any longer
         */
        @Deprecated
        public long databaseBytes;
        /** The number of pagers opened in the current process
         * @deprecated not used any longer
         */
        @Deprecated
        public int numPagers;

        /** the current amount of memory checked out by sqlite using sqlite3_malloc().
         * documented at http://www.sqlite.org/c3ref/c_status_malloc_size.html
         */
        public int memoryUsed;

        /** the number of bytes of page cache allocation which could not be sattisfied by the
         * SQLITE_CONFIG_PAGECACHE buffer and where forced to overflow to sqlite3_malloc().
         * The returned value includes allocations that overflowed because they where too large
         * (they were larger than the "sz" parameter to SQLITE_CONFIG_PAGECACHE) and allocations
         * that overflowed because no space was left in the page cache.
         * documented at http://www.sqlite.org/c3ref/c_status_malloc_size.html
         */
        public int pageCacheOverflo;

        /** records the largest memory allocation request handed to sqlite3.
         * documented at http://www.sqlite.org/c3ref/c_status_malloc_size.html
         */
        public int largestMemAlloc;

        /** a list of {@link DbStats} - one for each main database opened by the applications
         * running on the android device
         */
        public ArrayList<DbStats> dbStats;
    }

    /**
     * contains statistics about a database
     */
    public static class DbStats {
        /** name of the database */
        public String dbName;

        /** the page size for the database */
        public long pageSize;

        /** the database size */
        public long dbSize;

        /** documented here http://www.sqlite.org/c3ref/c_dbstatus_lookaside_used.html */
        public int lookaside;

        public DbStats(String dbName, long pageCount, long pageSize, int lookaside) {
            this.dbName = dbName;
            this.pageSize = pageSize;
            dbSize = (pageCount * pageSize) / 1024;
            this.lookaside = lookaside;
        }
    }

    /**
     * return all pager and database stats for the current process.
     * @return {@link PagerStats}
     */
    public static PagerStats getDatabaseInfo() {
        PagerStats stats = new PagerStats();
        getPagerStats(stats);
        stats.dbStats = SQLiteDatabase.getDbStats();
        return stats;
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
}
