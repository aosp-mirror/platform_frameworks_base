/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.os.StatFs;

/**
 * Provides access to SQLite functions that affect all database connection,
 * such as memory management.
 *
 * @hide
 */
public final class SQLiteGlobal {
    private static final String TAG = "SQLiteGlobal";

    private static final Object sLock = new Object();
    private static boolean sInitialized;
    private static int sSoftHeapLimit;
    private static int sDefaultPageSize;

    private static native void nativeConfig(boolean verboseLog, int softHeapLimit);
    private static native int nativeReleaseMemory(int bytesToFree);

    private SQLiteGlobal() {
    }

    /**
     * Initializes global SQLite settings the first time it is called.
     * Should be called before opening the first (or any) database.
     * Does nothing on repeated subsequent calls.
     */
    public static void initializeOnce() {
        synchronized (sLock) {
            if (!sInitialized) {
                sInitialized = true;

                // Limit to 8MB for now.  This is 4 times the maximum cursor window
                // size, as has been used by the original code in SQLiteDatabase for
                // a long time.
                // TODO: We really do need to test whether this helps or hurts us.
                sSoftHeapLimit = 8 * 1024 * 1024;

                // Configure SQLite.
                nativeConfig(SQLiteDebug.DEBUG_SQL_LOG, sSoftHeapLimit);
            }
        }
    }

    /**
     * Attempts to release memory by pruning the SQLite page cache and other
     * internal data structures.
     *
     * @return The number of bytes that were freed.
     */
    public static int releaseMemory() {
        synchronized (sLock) {
            if (!sInitialized) {
                return 0;
            }
            return nativeReleaseMemory(sSoftHeapLimit);
        }
    }

    /**
     * Gets the default page size to use when creating a database.
     */
    public static int getDefaultPageSize() {
        synchronized (sLock) {
            if (sDefaultPageSize == 0) {
                sDefaultPageSize = new StatFs("/data").getBlockSize();
            }
            return sDefaultPageSize;
        }
    }
}
