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

import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Process;
import android.os.SystemProperties;
import android.util.Log;
import android.util.Printer;

import java.util.ArrayList;

/**
 * Provides debugging info about all SQLite databases running in the current process.
 *
 * {@hide}
 */
@TestApi
public final class SQLiteDebug {
    private static native void nativeGetPagerStats(PagerStats stats);

    /**
     * Inner class to avoid getting the value frozen in zygote.
     *
     * {@hide}
     */
    public static final class NoPreloadHolder {
        /**
         * Controls the printing of informational SQL log messages.
         *
         * Enable using "adb shell setprop log.tag.SQLiteLog VERBOSE".
         */
        public static final boolean DEBUG_SQL_LOG =
                Log.isLoggable("SQLiteLog", Log.VERBOSE);

        /**
         * Controls the printing of SQL statements as they are executed.
         *
         * Enable using "adb shell setprop log.tag.SQLiteStatements VERBOSE".
         */
        public static final boolean DEBUG_SQL_STATEMENTS =
                Log.isLoggable("SQLiteStatements", Log.VERBOSE);

        /**
         * Controls the printing of wall-clock time taken to execute SQL statements
         * as they are executed.
         *
         * Enable using "adb shell setprop log.tag.SQLiteTime VERBOSE".
         */
        public static final boolean DEBUG_SQL_TIME =
                Log.isLoggable("SQLiteTime", Log.VERBOSE);


        /**
         * True to enable database performance testing instrumentation.
         */
        public static final boolean DEBUG_LOG_SLOW_QUERIES =
                Log.isLoggable("SQLiteSlowQueries", Log.VERBOSE);

        private static final String SLOW_QUERY_THRESHOLD_PROP = "db.log.slow_query_threshold";

        private static final String SLOW_QUERY_THRESHOLD_UID_PROP =
                SLOW_QUERY_THRESHOLD_PROP + "." + Process.myUid();

        /**
         * Whether to add detailed information to slow query log.
         */
        public static final boolean DEBUG_LOG_DETAILED = Build.IS_DEBUGGABLE
                && SystemProperties.getBoolean("db.log.detailed", false);
    }

    private SQLiteDebug() {
    }

    /**
     * Determines whether a query should be logged.
     *
     * Reads the "db.log.slow_query_threshold" system property, which can be changed
     * by the user at any time.  If the value is zero, then all queries will
     * be considered slow.  If the value does not exist or is negative, then no queries will
     * be considered slow.
     *
     * To enable it for a specific UID, "db.log.slow_query_threshold.UID" could also be used.
     *
     * This value can be changed dynamically while the system is running.
     * For example, "adb shell setprop db.log.slow_query_threshold 200" will
     * log all queries that take 200ms or longer to run.
     * @hide
     */
    public static boolean shouldLogSlowQuery(long elapsedTimeMillis) {
        final int slowQueryMillis = Math.min(
                SystemProperties.getInt(NoPreloadHolder.SLOW_QUERY_THRESHOLD_PROP,
                        Integer.MAX_VALUE),
                SystemProperties.getInt(NoPreloadHolder.SLOW_QUERY_THRESHOLD_UID_PROP,
                        Integer.MAX_VALUE));
        return elapsedTimeMillis >= slowQueryMillis;
    }

    /**
     * Contains statistics about the active pagers in the current process.
     *
     * @see #nativeGetPagerStats(PagerStats)
     */
    public static class PagerStats {

        @UnsupportedAppUsage
        public PagerStats() {
        }

        /** the current amount of memory checked out by sqlite using sqlite3_malloc().
         * documented at http://www.sqlite.org/c3ref/c_status_malloc_size.html
         */
        @UnsupportedAppUsage
        public int memoryUsed;

        /** the number of bytes of page cache allocation which could not be sattisfied by the
         * SQLITE_CONFIG_PAGECACHE buffer and where forced to overflow to sqlite3_malloc().
         * The returned value includes allocations that overflowed because they where too large
         * (they were larger than the "sz" parameter to SQLITE_CONFIG_PAGECACHE) and allocations
         * that overflowed because no space was left in the page cache.
         * documented at http://www.sqlite.org/c3ref/c_status_malloc_size.html
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public int pageCacheOverflow;

        /** records the largest memory allocation request handed to sqlite3.
         * documented at http://www.sqlite.org/c3ref/c_status_malloc_size.html
         */
        @UnsupportedAppUsage
        public int largestMemAlloc;

        /** a list of {@link DbStats} - one for each main database opened by the applications
         * running on the android device
         */
        @UnsupportedAppUsage
        public ArrayList<DbStats> dbStats;
    }

    /**
     * contains statistics about a database
     */
    public static class DbStats {
        /** name of the database */
        @UnsupportedAppUsage
        public String dbName;

        /** the page size for the database */
        @UnsupportedAppUsage
        public long pageSize;

        /** the database size */
        @UnsupportedAppUsage
        public long dbSize;

        /**
         * Number of lookaside slots: http://www.sqlite.org/c3ref/c_dbstatus_lookaside_used.html */
        @UnsupportedAppUsage
        public int lookaside;

        /** statement cache stats: hits/misses/cachesize */
        public String cache;

        public DbStats(String dbName, long pageCount, long pageSize, int lookaside,
            int hits, int misses, int cachesize) {
            this.dbName = dbName;
            this.pageSize = pageSize / 1024;
            dbSize = (pageCount * pageSize) / 1024;
            this.lookaside = lookaside;
            this.cache = hits + "/" + misses + "/" + cachesize;
        }
    }

    /**
     * return all pager and database stats for the current process.
     * @return {@link PagerStats}
     */
    @UnsupportedAppUsage
    public static PagerStats getDatabaseInfo() {
        PagerStats stats = new PagerStats();
        nativeGetPagerStats(stats);
        stats.dbStats = SQLiteDatabase.getDbStats();
        return stats;
    }

    /**
     * Dumps detailed information about all databases used by the process.
     * @param printer The printer for dumping database state.
     * @param args Command-line arguments supplied to dumpsys dbinfo
     */
    public static void dump(Printer printer, String[] args) {
        dump(printer, args, false);
    }

    /** @hide */
    public static void dump(Printer printer, String[] args, boolean isSystem) {
        boolean verbose = false;
        for (String arg : args) {
            if (arg.equals("-v")) {
                verbose = true;
            }
        }

        SQLiteDatabase.dumpAll(printer, verbose, isSystem);
    }
}
