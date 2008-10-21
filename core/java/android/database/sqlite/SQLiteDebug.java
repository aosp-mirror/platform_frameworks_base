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

import android.util.Config;

/**
 * Provides debugging info about all SQLite databases running in the current process.
 * 
 * {@hide}
 */
public final class SQLiteDebug {
    /**
     * Controls the printing of SQL statements as they are executed.
     */
    public static final boolean DEBUG_SQL_STATEMENTS = Config.LOGV;

    /**
     * Controls the stack trace reporting of active cursors being
     * finalized.
     */
    public static final boolean DEBUG_ACTIVE_CURSOR_FINALIZATION = Config.LOGV;

    /**
     * Controls the tracking of time spent holding the database lock. 
     */
    public static final boolean DEBUG_LOCK_TIME_TRACKING = false;

    /**
     * Controls the printing of stack traces when tracking the time spent holding the database lock. 
     */
    public static final boolean DEBUG_LOCK_TIME_TRACKING_STACK_TRACE = false;

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
}
