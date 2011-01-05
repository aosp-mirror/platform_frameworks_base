/*
 * Copyright (C) 2006 The Android Open Source Project
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

import com.google.android.collect.Maps;

import android.app.ActivityThread;
import android.app.AppGlobals;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.SQLException;
import android.database.sqlite.SQLiteDebug.DbStats;
import android.os.Debug;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Config;
import android.util.EventLog;
import android.util.Log;
import android.util.Pair;

import dalvik.system.BlockGuard;

import java.io.File;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Exposes methods to manage a SQLite database.
 * <p>SQLiteDatabase has methods to create, delete, execute SQL commands, and
 * perform other common database management tasks.
 * <p>See the Notepad sample application in the SDK for an example of creating
 * and managing a database.
 * <p> Database names must be unique within an application, not across all
 * applications.
 *
 * <h3>Localized Collation - ORDER BY</h3>
 * <p>In addition to SQLite's default <code>BINARY</code> collator, Android supplies
 * two more, <code>LOCALIZED</code>, which changes with the system's current locale
 * if you wire it up correctly (XXX a link needed!), and <code>UNICODE</code>, which
 * is the Unicode Collation Algorithm and not tailored to the current locale.
 */
public class SQLiteDatabase extends SQLiteClosable {
    private static final String TAG = "Database";
    private static final int EVENT_DB_OPERATION = 52000;
    private static final int EVENT_DB_CORRUPT = 75004;

    /**
     * Algorithms used in ON CONFLICT clause
     * http://www.sqlite.org/lang_conflict.html
     */
    /**
     *  When a constraint violation occurs, an immediate ROLLBACK occurs,
     * thus ending the current transaction, and the command aborts with a
     * return code of SQLITE_CONSTRAINT. If no transaction is active
     * (other than the implied transaction that is created on every command)
     *  then this algorithm works the same as ABORT.
     */
    public static final int CONFLICT_ROLLBACK = 1;

    /**
     * When a constraint violation occurs,no ROLLBACK is executed
     * so changes from prior commands within the same transaction
     * are preserved. This is the default behavior.
     */
    public static final int CONFLICT_ABORT = 2;

    /**
     * When a constraint violation occurs, the command aborts with a return
     * code SQLITE_CONSTRAINT. But any changes to the database that
     * the command made prior to encountering the constraint violation
     * are preserved and are not backed out.
     */
    public static final int CONFLICT_FAIL = 3;

    /**
     * When a constraint violation occurs, the one row that contains
     * the constraint violation is not inserted or changed.
     * But the command continues executing normally. Other rows before and
     * after the row that contained the constraint violation continue to be
     * inserted or updated normally. No error is returned.
     */
    public static final int CONFLICT_IGNORE = 4;

    /**
     * When a UNIQUE constraint violation occurs, the pre-existing rows that
     * are causing the constraint violation are removed prior to inserting
     * or updating the current row. Thus the insert or update always occurs.
     * The command continues executing normally. No error is returned.
     * If a NOT NULL constraint violation occurs, the NULL value is replaced
     * by the default value for that column. If the column has no default
     * value, then the ABORT algorithm is used. If a CHECK constraint
     * violation occurs then the IGNORE algorithm is used. When this conflict
     * resolution strategy deletes rows in order to satisfy a constraint,
     * it does not invoke delete triggers on those rows.
     *  This behavior might change in a future release.
     */
    public static final int CONFLICT_REPLACE = 5;

    /**
     * use the following when no conflict action is specified.
     */
    public static final int CONFLICT_NONE = 0;
    private static final String[] CONFLICT_VALUES = new String[]
            {"", " OR ROLLBACK ", " OR ABORT ", " OR FAIL ", " OR IGNORE ", " OR REPLACE "};

    /**
     * Maximum Length Of A LIKE Or GLOB Pattern
     * The pattern matching algorithm used in the default LIKE and GLOB implementation
     * of SQLite can exhibit O(N^2) performance (where N is the number of characters in
     * the pattern) for certain pathological cases. To avoid denial-of-service attacks
     * the length of the LIKE or GLOB pattern is limited to SQLITE_MAX_LIKE_PATTERN_LENGTH bytes.
     * The default value of this limit is 50000. A modern workstation can evaluate
     * even a pathological LIKE or GLOB pattern of 50000 bytes relatively quickly.
     * The denial of service problem only comes into play when the pattern length gets
     * into millions of bytes. Nevertheless, since most useful LIKE or GLOB patterns
     * are at most a few dozen bytes in length, paranoid application developers may
     * want to reduce this parameter to something in the range of a few hundred
     * if they know that external users are able to generate arbitrary patterns.
     */
    public static final int SQLITE_MAX_LIKE_PATTERN_LENGTH = 50000;

    /**
     * Flag for {@link #openDatabase} to open the database for reading and writing.
     * If the disk is full, this may fail even before you actually write anything.
     *
     * {@more} Note that the value of this flag is 0, so it is the default.
     */
    public static final int OPEN_READWRITE = 0x00000000;          // update native code if changing

    /**
     * Flag for {@link #openDatabase} to open the database for reading only.
     * This is the only reliable way to open a database if the disk may be full.
     */
    public static final int OPEN_READONLY = 0x00000001;           // update native code if changing

    private static final int OPEN_READ_MASK = 0x00000001;         // update native code if changing

    /**
     * Flag for {@link #openDatabase} to open the database without support for localized collators.
     *
     * {@more} This causes the collator <code>LOCALIZED</code> not to be created.
     * You must be consistent when using this flag to use the setting the database was
     * created with.  If this is set, {@link #setLocale} will do nothing.
     */
    public static final int NO_LOCALIZED_COLLATORS = 0x00000010;  // update native code if changing

    /**
     * Flag for {@link #openDatabase} to create the database file if it does not already exist.
     */
    public static final int CREATE_IF_NECESSARY = 0x10000000;     // update native code if changing

    /**
     * Indicates whether the most-recently started transaction has been marked as successful.
     */
    private boolean mInnerTransactionIsSuccessful;

    /**
     * Valid during the life of a transaction, and indicates whether the entire transaction (the
     * outer one and all of the inner ones) so far has been successful.
     */
    private boolean mTransactionIsSuccessful;

    /**
     * Valid during the life of a transaction.
     */
    private SQLiteTransactionListener mTransactionListener;

    /** Synchronize on this when accessing the database */
    private final ReentrantLock mLock = new ReentrantLock(true);

    private long mLockAcquiredWallTime = 0L;
    private long mLockAcquiredThreadTime = 0L;

    // limit the frequency of complaints about each database to one within 20 sec
    // unless run command adb shell setprop log.tag.Database VERBOSE
    private static final int LOCK_WARNING_WINDOW_IN_MS = 20000;
    /** If the lock is held this long then a warning will be printed when it is released. */
    private static final int LOCK_ACQUIRED_WARNING_TIME_IN_MS = 300;
    private static final int LOCK_ACQUIRED_WARNING_THREAD_TIME_IN_MS = 100;
    private static final int LOCK_ACQUIRED_WARNING_TIME_IN_MS_ALWAYS_PRINT = 2000;

    private static final int SLEEP_AFTER_YIELD_QUANTUM = 1000;

    // The pattern we remove from database filenames before
    // potentially logging them.
    private static final Pattern EMAIL_IN_DB_PATTERN = Pattern.compile("[\\w\\.\\-]+@[\\w\\.\\-]+");

    private long mLastLockMessageTime = 0L;

    // Things related to query logging/sampling for debugging
    // slow/frequent queries during development.  Always log queries
    // which take (by default) 500ms+; shorter queries are sampled
    // accordingly.  Commit statements, which are typically slow, are
    // logged together with the most recently executed SQL statement,
    // for disambiguation.  The 500ms value is configurable via a
    // SystemProperty, but developers actively debugging database I/O
    // should probably use the regular log tunable,
    // LOG_SLOW_QUERIES_PROPERTY, defined below.
    private static int sQueryLogTimeInMillis = 0;  // lazily initialized
    private static final int QUERY_LOG_SQL_LENGTH = 64;
    private static final String COMMIT_SQL = "COMMIT;";
    private final Random mRandom = new Random();
    private String mLastSqlStatement = null;

    // String prefix for slow database query EventLog records that show
    // lock acquistions of the database.
    /* package */ static final String GET_LOCK_LOG_PREFIX = "GETLOCK:";

    /** Used by native code, do not rename */
    /* package */ int mNativeHandle = 0;

    /** Used to make temp table names unique */
    /* package */ int mTempTableSequence = 0;

    /** The path for the database file */
    private String mPath;

    /** The anonymized path for the database file for logging purposes */
    private String mPathForLogs = null;  // lazily populated

    /** The flags passed to open/create */
    private int mFlags;

    /** The optional factory to use when creating new Cursors */
    private CursorFactory mFactory;

    private WeakHashMap<SQLiteClosable, Object> mPrograms;

    /**
     * for each instance of this class, a cache is maintained to store
     * the compiled query statement ids returned by sqlite database.
     *     key = sql statement with "?" for bind args
     *     value = {@link SQLiteCompiledSql}
     * If an application opens the database and keeps it open during its entire life, then
     * there will not be an overhead of compilation of sql statements by sqlite.
     *
     * why is this cache NOT static? because sqlite attaches compiledsql statements to the
     * struct created when {@link SQLiteDatabase#openDatabase(String, CursorFactory, int)} is
     * invoked.
     *
     * this cache has an upper limit of mMaxSqlCacheSize (settable by calling the method
     * (@link setMaxCacheSize(int)}). its default is 0 - i.e., no caching by default because
     * most of the apps don't use "?" syntax in their sql, caching is not useful for them.
     */
    /* package */ Map<String, SQLiteCompiledSql> mCompiledQueries = Maps.newHashMap();
    /**
     * @hide
     */
    public static final int MAX_SQL_CACHE_SIZE = 250;
    private int mMaxSqlCacheSize = MAX_SQL_CACHE_SIZE; // max cache size per Database instance
    private int mCacheFullWarnings;
    private static final int MAX_WARNINGS_ON_CACHESIZE_CONDITION = 1;

    /** maintain stats about number of cache hits and misses */
    private int mNumCacheHits;
    private int mNumCacheMisses;

    /** the following 2 members maintain the time when a database is opened and closed */
    private String mTimeOpened = null;
    private String mTimeClosed = null;

    /** Used to find out where this object was created in case it never got closed. */
    private Throwable mStackTrace = null;

    // System property that enables logging of slow queries. Specify the threshold in ms.
    private static final String LOG_SLOW_QUERIES_PROPERTY = "db.log.slow_query_threshold";
    private final int mSlowQueryThreshold;

    /**
     * @param closable
     */
    void addSQLiteClosable(SQLiteClosable closable) {
        lock();
        try {
            mPrograms.put(closable, null);
        } finally {
            unlock();
        }
    }

    void removeSQLiteClosable(SQLiteClosable closable) {
        lock();
        try {
            mPrograms.remove(closable);
        } finally {
            unlock();
        }
    }

    @Override
    protected void onAllReferencesReleased() {
        if (isOpen()) {
            if (SQLiteDebug.DEBUG_SQL_CACHE) {
                mTimeClosed = getTime();
            }
            dbclose();
        }
    }

    /**
     * Attempts to release memory that SQLite holds but does not require to
     * operate properly. Typically this memory will come from the page cache.
     *
     * @return the number of bytes actually released
     */
    static public native int releaseMemory();

    /**
     * Control whether or not the SQLiteDatabase is made thread-safe by using locks
     * around critical sections. This is pretty expensive, so if you know that your
     * DB will only be used by a single thread then you should set this to false.
     * The default is true.
     * @param lockingEnabled set to true to enable locks, false otherwise
     */
    public void setLockingEnabled(boolean lockingEnabled) {
        mLockingEnabled = lockingEnabled;
    }

    /**
     * If set then the SQLiteDatabase is made thread-safe by using locks
     * around critical sections
     */
    private boolean mLockingEnabled = true;

    /* package */ void onCorruption() {
        Log.e(TAG, "Removing corrupt database: " + mPath);
        EventLog.writeEvent(EVENT_DB_CORRUPT, mPath);
        try {
            // Close the database (if we can), which will cause subsequent operations to fail.
            close();
        } finally {
            // Delete the corrupt file.  Don't re-create it now -- that would just confuse people
            // -- but the next time someone tries to open it, they can set it up from scratch.
            if (!mPath.equalsIgnoreCase(":memory")) {
                // delete is only for non-memory database files
                new File(mPath).delete();
            }
        }
    }

    /**
     * Locks the database for exclusive access. The database lock must be held when
     * touch the native sqlite3* object since it is single threaded and uses
     * a polling lock contention algorithm. The lock is recursive, and may be acquired
     * multiple times by the same thread. This is a no-op if mLockingEnabled is false.
     *
     * @see #unlock()
     */
    /* package */ void lock() {
        if (!mLockingEnabled) return;
        mLock.lock();
        if (SQLiteDebug.DEBUG_LOCK_TIME_TRACKING) {
            if (mLock.getHoldCount() == 1) {
                // Use elapsed real-time since the CPU may sleep when waiting for IO
                mLockAcquiredWallTime = SystemClock.elapsedRealtime();
                mLockAcquiredThreadTime = Debug.threadCpuTimeNanos();
            }
        }
    }

    /**
     * Locks the database for exclusive access. The database lock must be held when
     * touch the native sqlite3* object since it is single threaded and uses
     * a polling lock contention algorithm. The lock is recursive, and may be acquired
     * multiple times by the same thread.
     *
     * @see #unlockForced()
     */
    private void lockForced() {
        mLock.lock();
        if (SQLiteDebug.DEBUG_LOCK_TIME_TRACKING) {
            if (mLock.getHoldCount() == 1) {
                // Use elapsed real-time since the CPU may sleep when waiting for IO
                mLockAcquiredWallTime = SystemClock.elapsedRealtime();
                mLockAcquiredThreadTime = Debug.threadCpuTimeNanos();
            }
        }
    }

    /**
     * Releases the database lock. This is a no-op if mLockingEnabled is false.
     *
     * @see #unlock()
     */
    /* package */ void unlock() {
        if (!mLockingEnabled) return;
        if (SQLiteDebug.DEBUG_LOCK_TIME_TRACKING) {
            if (mLock.getHoldCount() == 1) {
                checkLockHoldTime();
            }
        }
        mLock.unlock();
    }

    /**
     * Releases the database lock.
     *
     * @see #unlockForced()
     */
    private void unlockForced() {
        if (SQLiteDebug.DEBUG_LOCK_TIME_TRACKING) {
            if (mLock.getHoldCount() == 1) {
                checkLockHoldTime();
            }
        }
        mLock.unlock();
    }

    private void checkLockHoldTime() {
        // Use elapsed real-time since the CPU may sleep when waiting for IO
        long elapsedTime = SystemClock.elapsedRealtime();
        long lockedTime = elapsedTime - mLockAcquiredWallTime;
        if (lockedTime < LOCK_ACQUIRED_WARNING_TIME_IN_MS_ALWAYS_PRINT &&
                !Log.isLoggable(TAG, Log.VERBOSE) &&
                (elapsedTime - mLastLockMessageTime) < LOCK_WARNING_WINDOW_IN_MS) {
            return;
        }
        if (lockedTime > LOCK_ACQUIRED_WARNING_TIME_IN_MS) {
            int threadTime = (int)
                    ((Debug.threadCpuTimeNanos() - mLockAcquiredThreadTime) / 1000000);
            if (threadTime > LOCK_ACQUIRED_WARNING_THREAD_TIME_IN_MS ||
                    lockedTime > LOCK_ACQUIRED_WARNING_TIME_IN_MS_ALWAYS_PRINT) {
                mLastLockMessageTime = elapsedTime;
                String msg = "lock held on " + mPath + " for " + lockedTime + "ms. Thread time was "
                        + threadTime + "ms";
                if (SQLiteDebug.DEBUG_LOCK_TIME_TRACKING_STACK_TRACE) {
                    Log.d(TAG, msg, new Exception());
                } else {
                    Log.d(TAG, msg);
                }
            }
        }
    }

    /**
     * Begins a transaction. Transactions can be nested. When the outer transaction is ended all of
     * the work done in that transaction and all of the nested transactions will be committed or
     * rolled back. The changes will be rolled back if any transaction is ended without being
     * marked as clean (by calling setTransactionSuccessful). Otherwise they will be committed.
     *
     * <p>Here is the standard idiom for transactions:
     *
     * <pre>
     *   db.beginTransaction();
     *   try {
     *     ...
     *     db.setTransactionSuccessful();
     *   } finally {
     *     db.endTransaction();
     *   }
     * </pre>
     */
    public void beginTransaction() {
        beginTransactionWithListener(null /* transactionStatusCallback */);
    }

    /**
     * Begins a transaction. Transactions can be nested. When the outer transaction is ended all of
     * the work done in that transaction and all of the nested transactions will be committed or
     * rolled back. The changes will be rolled back if any transaction is ended without being
     * marked as clean (by calling setTransactionSuccessful). Otherwise they will be committed.
     *
     * <p>Here is the standard idiom for transactions:
     *
     * <pre>
     *   db.beginTransactionWithListener(listener);
     *   try {
     *     ...
     *     db.setTransactionSuccessful();
     *   } finally {
     *     db.endTransaction();
     *   }
     * </pre>
     * @param transactionListener listener that should be notified when the transaction begins,
     * commits, or is rolled back, either explicitly or by a call to
     * {@link #yieldIfContendedSafely}.
     */
    public void beginTransactionWithListener(SQLiteTransactionListener transactionListener) {
        lockForced();
        if (!isOpen()) {
            throw new IllegalStateException("database not open");
        }
        boolean ok = false;
        try {
            // If this thread already had the lock then get out
            if (mLock.getHoldCount() > 1) {
                if (mInnerTransactionIsSuccessful) {
                    String msg = "Cannot call beginTransaction between "
                            + "calling setTransactionSuccessful and endTransaction";
                    IllegalStateException e = new IllegalStateException(msg);
                    Log.e(TAG, "beginTransaction() failed", e);
                    throw e;
                }
                ok = true;
                return;
            }

            // This thread didn't already have the lock, so begin a database
            // transaction now.
            execSQL("BEGIN EXCLUSIVE;");
            mTransactionListener = transactionListener;
            mTransactionIsSuccessful = true;
            mInnerTransactionIsSuccessful = false;
            if (transactionListener != null) {
                try {
                    transactionListener.onBegin();
                } catch (RuntimeException e) {
                    execSQL("ROLLBACK;");
                    throw e;
                }
            }
            ok = true;
        } finally {
            if (!ok) {
                // beginTransaction is called before the try block so we must release the lock in
                // the case of failure.
                unlockForced();
            }
        }
    }

    /**
     * End a transaction. See beginTransaction for notes about how to use this and when transactions
     * are committed and rolled back.
     */
    public void endTransaction() {
        if (!isOpen()) {
            throw new IllegalStateException("database not open");
        }
        if (!mLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("no transaction pending");
        }
        try {
            if (mInnerTransactionIsSuccessful) {
                mInnerTransactionIsSuccessful = false;
            } else {
                mTransactionIsSuccessful = false;
            }
            if (mLock.getHoldCount() != 1) {
                return;
            }
            RuntimeException savedException = null;
            if (mTransactionListener != null) {
                try {
                    if (mTransactionIsSuccessful) {
                        mTransactionListener.onCommit();
                    } else {
                        mTransactionListener.onRollback();
                    }
                } catch (RuntimeException e) {
                    savedException = e;
                    mTransactionIsSuccessful = false;
                }
            }
            if (mTransactionIsSuccessful) {
                execSQL(COMMIT_SQL);
            } else {
                try {
                    execSQL("ROLLBACK;");
                    if (savedException != null) {
                        throw savedException;
                    }
                } catch (SQLException e) {
                    if (Config.LOGD) {
                        Log.d(TAG, "exception during rollback, maybe the DB previously "
                                + "performed an auto-rollback");
                    }
                }
            }
        } finally {
            mTransactionListener = null;
            unlockForced();
            if (Config.LOGV) {
                Log.v(TAG, "unlocked " + Thread.currentThread()
                        + ", holdCount is " + mLock.getHoldCount());
            }
        }
    }

    /**
     * Marks the current transaction as successful. Do not do any more database work between
     * calling this and calling endTransaction. Do as little non-database work as possible in that
     * situation too. If any errors are encountered between this and endTransaction the transaction
     * will still be committed.
     *
     * @throws IllegalStateException if the current thread is not in a transaction or the
     * transaction is already marked as successful.
     */
    public void setTransactionSuccessful() {
        if (!isOpen()) {
            throw new IllegalStateException("database not open");
        }
        if (!mLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("no transaction pending");
        }
        if (mInnerTransactionIsSuccessful) {
            throw new IllegalStateException(
                    "setTransactionSuccessful may only be called once per call to beginTransaction");
        }
        mInnerTransactionIsSuccessful = true;
    }

    /**
     * return true if there is a transaction pending
     */
    public boolean inTransaction() {
        return mLock.getHoldCount() > 0;
    }

    /**
     * Checks if the database lock is held by this thread.
     *
     * @return true, if this thread is holding the database lock.
     */
    public boolean isDbLockedByCurrentThread() {
        return mLock.isHeldByCurrentThread();
    }

    /**
     * Checks if the database is locked by another thread. This is
     * just an estimate, since this status can change at any time,
     * including after the call is made but before the result has
     * been acted upon.
     *
     * @return true, if the database is locked by another thread
     */
    public boolean isDbLockedByOtherThreads() {
        return !mLock.isHeldByCurrentThread() && mLock.isLocked();
    }

    /**
     * Temporarily end the transaction to let other threads run. The transaction is assumed to be
     * successful so far. Do not call setTransactionSuccessful before calling this. When this
     * returns a new transaction will have been created but not marked as successful.
     * @return true if the transaction was yielded
     * @deprecated if the db is locked more than once (becuase of nested transactions) then the lock
     *   will not be yielded. Use yieldIfContendedSafely instead.
     */
    @Deprecated
    public boolean yieldIfContended() {
        return yieldIfContendedHelper(false /* do not check yielding */,
                -1 /* sleepAfterYieldDelay */);
    }

    /**
     * Temporarily end the transaction to let other threads run. The transaction is assumed to be
     * successful so far. Do not call setTransactionSuccessful before calling this. When this
     * returns a new transaction will have been created but not marked as successful. This assumes
     * that there are no nested transactions (beginTransaction has only been called once) and will
     * throw an exception if that is not the case.
     * @return true if the transaction was yielded
     */
    public boolean yieldIfContendedSafely() {
        return yieldIfContendedHelper(true /* check yielding */, -1 /* sleepAfterYieldDelay*/);
    }

    /**
     * Temporarily end the transaction to let other threads run. The transaction is assumed to be
     * successful so far. Do not call setTransactionSuccessful before calling this. When this
     * returns a new transaction will have been created but not marked as successful. This assumes
     * that there are no nested transactions (beginTransaction has only been called once) and will
     * throw an exception if that is not the case.
     * @param sleepAfterYieldDelay if > 0, sleep this long before starting a new transaction if
     *   the lock was actually yielded. This will allow other background threads to make some
     *   more progress than they would if we started the transaction immediately.
     * @return true if the transaction was yielded
     */
    public boolean yieldIfContendedSafely(long sleepAfterYieldDelay) {
        return yieldIfContendedHelper(true /* check yielding */, sleepAfterYieldDelay);
    }

    private boolean yieldIfContendedHelper(boolean checkFullyYielded, long sleepAfterYieldDelay) {
        if (mLock.getQueueLength() == 0) {
            // Reset the lock acquire time since we know that the thread was willing to yield
            // the lock at this time.
            mLockAcquiredWallTime = SystemClock.elapsedRealtime();
            mLockAcquiredThreadTime = Debug.threadCpuTimeNanos();
            return false;
        }
        setTransactionSuccessful();
        SQLiteTransactionListener transactionListener = mTransactionListener;
        endTransaction();
        if (checkFullyYielded) {
            if (this.isDbLockedByCurrentThread()) {
                throw new IllegalStateException(
                        "Db locked more than once. yielfIfContended cannot yield");
            }
        }
        if (sleepAfterYieldDelay > 0) {
            // Sleep for up to sleepAfterYieldDelay milliseconds, waking up periodically to
            // check if anyone is using the database.  If the database is not contended,
            // retake the lock and return.
            long remainingDelay = sleepAfterYieldDelay;
            while (remainingDelay > 0) {
                try {
                    Thread.sleep(remainingDelay < SLEEP_AFTER_YIELD_QUANTUM ?
                            remainingDelay : SLEEP_AFTER_YIELD_QUANTUM);
                } catch (InterruptedException e) {
                    Thread.interrupted();
                }
                remainingDelay -= SLEEP_AFTER_YIELD_QUANTUM;
                if (mLock.getQueueLength() == 0) {
                    break;
                }
            }
        }
        beginTransactionWithListener(transactionListener);
        return true;
    }

    /** Maps table names to info about what to which _sync_time column to set
     * to NULL on an update. This is used to support syncing. */
    private final Map<String, SyncUpdateInfo> mSyncUpdateInfo =
            new HashMap<String, SyncUpdateInfo>();

    public Map<String, String> getSyncedTables() {
        synchronized(mSyncUpdateInfo) {
            HashMap<String, String> tables = new HashMap<String, String>();
            for (String table : mSyncUpdateInfo.keySet()) {
                SyncUpdateInfo info = mSyncUpdateInfo.get(table);
                if (info.deletedTable != null) {
                    tables.put(table, info.deletedTable);
                }
            }
            return tables;
        }
    }

    /**
     * Internal class used to keep track what needs to be marked as changed
     * when an update occurs. This is used for syncing, so the sync engine
     * knows what data has been updated locally.
     */
    static private class SyncUpdateInfo {
        /**
         * Creates the SyncUpdateInfo class.
         *
         * @param masterTable The table to set _sync_time to NULL in
         * @param deletedTable The deleted table that corresponds to the
         *          master table
         * @param foreignKey The key that refers to the primary key in table
         */
        SyncUpdateInfo(String masterTable, String deletedTable,
                String foreignKey) {
            this.masterTable = masterTable;
            this.deletedTable = deletedTable;
            this.foreignKey = foreignKey;
        }

        /** The table containing the _sync_time column */
        String masterTable;

        /** The deleted table that corresponds to the master table */
        String deletedTable;

        /** The key in the local table the row in table. It may be _id, if table
         * is the local table. */
        String foreignKey;
    }

    /**
     * Used to allow returning sub-classes of {@link Cursor} when calling query.
     */
    public interface CursorFactory {
        /**
         * See
         * {@link SQLiteCursor#SQLiteCursor(SQLiteDatabase, SQLiteCursorDriver,
         * String, SQLiteQuery)}.
         */
        public Cursor newCursor(SQLiteDatabase db,
                SQLiteCursorDriver masterQuery, String editTable,
                SQLiteQuery query);
    }

    /**
     * Open the database according to the flags {@link #OPEN_READWRITE}
     * {@link #OPEN_READONLY} {@link #CREATE_IF_NECESSARY} and/or {@link #NO_LOCALIZED_COLLATORS}.
     *
     * <p>Sets the locale of the database to the  the system's current locale.
     * Call {@link #setLocale} if you would like something else.</p>
     *
     * @param path to database file to open and/or create
     * @param factory an optional factory class that is called to instantiate a
     *            cursor when query is called, or null for default
     * @param flags to control database access mode
     * @return the newly opened database
     * @throws SQLiteException if the database cannot be opened
     */
    public static SQLiteDatabase openDatabase(String path, CursorFactory factory, int flags) {
        SQLiteDatabase sqliteDatabase = null;
        try {
            // Open the database.
            sqliteDatabase = new SQLiteDatabase(path, factory, flags);
            if (SQLiteDebug.DEBUG_SQL_STATEMENTS) {
                sqliteDatabase.enableSqlTracing(path);
            }
            if (SQLiteDebug.DEBUG_SQL_TIME) {
                sqliteDatabase.enableSqlProfiling(path);
            }
        } catch (SQLiteDatabaseCorruptException e) {
            // Try to recover from this, if we can.
            // TODO: should we do this for other open failures?
            Log.e(TAG, "Deleting and re-creating corrupt database " + path, e);
            EventLog.writeEvent(EVENT_DB_CORRUPT, path);
            if (!path.equalsIgnoreCase(":memory")) {
                // delete is only for non-memory database files
                new File(path).delete();
            }
            sqliteDatabase = new SQLiteDatabase(path, factory, flags);
        }
        ActiveDatabases.getInstance().mActiveDatabases.add(
                new WeakReference<SQLiteDatabase>(sqliteDatabase));
        return sqliteDatabase;
    }

    /**
     * Equivalent to openDatabase(file.getPath(), factory, CREATE_IF_NECESSARY).
     */
    public static SQLiteDatabase openOrCreateDatabase(File file, CursorFactory factory) {
        return openOrCreateDatabase(file.getPath(), factory);
    }

    /**
     * Equivalent to openDatabase(path, factory, CREATE_IF_NECESSARY).
     */
    public static SQLiteDatabase openOrCreateDatabase(String path, CursorFactory factory) {
        return openDatabase(path, factory, CREATE_IF_NECESSARY);
    }

    /**
     * Create a memory backed SQLite database.  Its contents will be destroyed
     * when the database is closed.
     *
     * <p>Sets the locale of the database to the  the system's current locale.
     * Call {@link #setLocale} if you would like something else.</p>
     *
     * @param factory an optional factory class that is called to instantiate a
     *            cursor when query is called
     * @return a SQLiteDatabase object, or null if the database can't be created
     */
    public static SQLiteDatabase create(CursorFactory factory) {
        // This is a magic string with special meaning for SQLite.
        return openDatabase(":memory:", factory, CREATE_IF_NECESSARY);
    }

    /**
     * Close the database.
     */
    public void close() {
        if (!isOpen()) {
            return; // already closed
        }
        lock();
        try {
            closeClosable();
            // close this database instance - regardless of its reference count value
            onAllReferencesReleased();
        } finally {
            unlock();
        }
    }

    private void closeClosable() {
        /* deallocate all compiled sql statement objects from mCompiledQueries cache.
         * this should be done before de-referencing all {@link SQLiteClosable} objects
         * from this database object because calling
         * {@link SQLiteClosable#onAllReferencesReleasedFromContainer()} could cause the database
         * to be closed. sqlite doesn't let a database close if there are
         * any unfinalized statements - such as the compiled-sql objects in mCompiledQueries.
         */
        deallocCachedSqlStatements();

        Iterator<Map.Entry<SQLiteClosable, Object>> iter = mPrograms.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<SQLiteClosable, Object> entry = iter.next();
            SQLiteClosable program = entry.getKey();
            if (program != null) {
                program.onAllReferencesReleasedFromContainer();
            }
        }
    }

    /**
     * Native call to close the database.
     */
    private native void dbclose();

    /**
     * Gets the database version.
     *
     * @return the database version
     */
    public int getVersion() {
        SQLiteStatement prog = null;
        lock();
        if (!isOpen()) {
            throw new IllegalStateException("database not open");
        }
        try {
            prog = new SQLiteStatement(this, "PRAGMA user_version;");
            long version = prog.simpleQueryForLong();
            return (int) version;
        } finally {
            if (prog != null) prog.close();
            unlock();
        }
    }

    /**
     * Sets the database version.
     *
     * @param version the new database version
     */
    public void setVersion(int version) {
        execSQL("PRAGMA user_version = " + version);
    }

    /**
     * Returns the maximum size the database may grow to.
     *
     * @return the new maximum database size
     */
    public long getMaximumSize() {
        SQLiteStatement prog = null;
        lock();
        if (!isOpen()) {
            throw new IllegalStateException("database not open");
        }
        try {
            prog = new SQLiteStatement(this,
                    "PRAGMA max_page_count;");
            long pageCount = prog.simpleQueryForLong();
            return pageCount * getPageSize();
        } finally {
            if (prog != null) prog.close();
            unlock();
        }
    }

    /**
     * Sets the maximum size the database will grow to. The maximum size cannot
     * be set below the current size.
     *
     * @param numBytes the maximum database size, in bytes
     * @return the new maximum database size
     */
    public long setMaximumSize(long numBytes) {
        SQLiteStatement prog = null;
        lock();
        if (!isOpen()) {
            throw new IllegalStateException("database not open");
        }
        try {
            long pageSize = getPageSize();
            long numPages = numBytes / pageSize;
            // If numBytes isn't a multiple of pageSize, bump up a page
            if ((numBytes % pageSize) != 0) {
                numPages++;
            }
            prog = new SQLiteStatement(this,
                    "PRAGMA max_page_count = " + numPages);
            long newPageCount = prog.simpleQueryForLong();
            return newPageCount * pageSize;
        } finally {
            if (prog != null) prog.close();
            unlock();
        }
    }

    /**
     * Returns the current database page size, in bytes.
     *
     * @return the database page size, in bytes
     */
    public long getPageSize() {
        SQLiteStatement prog = null;
        lock();
        if (!isOpen()) {
            throw new IllegalStateException("database not open");
        }
        try {
            prog = new SQLiteStatement(this,
                    "PRAGMA page_size;");
            long size = prog.simpleQueryForLong();
            return size;
        } finally {
            if (prog != null) prog.close();
            unlock();
        }
    }

    /**
     * Sets the database page size. The page size must be a power of two. This
     * method does not work if any data has been written to the database file,
     * and must be called right after the database has been created.
     *
     * @param numBytes the database page size, in bytes
     */
    public void setPageSize(long numBytes) {
        execSQL("PRAGMA page_size = " + numBytes);
    }

    /**
     * Mark this table as syncable. When an update occurs in this table the
     * _sync_dirty field will be set to ensure proper syncing operation.
     *
     * @param table the table to mark as syncable
     * @param deletedTable The deleted table that corresponds to the
     *          syncable table
     */
    public void markTableSyncable(String table, String deletedTable) {
        markTableSyncable(table, "_id", table, deletedTable);
    }

    /**
     * Mark this table as syncable, with the _sync_dirty residing in another
     * table. When an update occurs in this table the _sync_dirty field of the
     * row in updateTable with the _id in foreignKey will be set to
     * ensure proper syncing operation.
     *
     * @param table an update on this table will trigger a sync time removal
     * @param foreignKey this is the column in table whose value is an _id in
     *          updateTable
     * @param updateTable this is the table that will have its _sync_dirty
     */
    public void markTableSyncable(String table, String foreignKey,
            String updateTable) {
        markTableSyncable(table, foreignKey, updateTable, null);
    }

    /**
     * Mark this table as syncable, with the _sync_dirty residing in another
     * table. When an update occurs in this table the _sync_dirty field of the
     * row in updateTable with the _id in foreignKey will be set to
     * ensure proper syncing operation.
     *
     * @param table an update on this table will trigger a sync time removal
     * @param foreignKey this is the column in table whose value is an _id in
     *          updateTable
     * @param updateTable this is the table that will have its _sync_dirty
     * @param deletedTable The deleted table that corresponds to the
     *          updateTable
     */
    private void markTableSyncable(String table, String foreignKey,
            String updateTable, String deletedTable) {
        lock();
        try {
            native_execSQL("SELECT _sync_dirty FROM " + updateTable
                    + " LIMIT 0");
            native_execSQL("SELECT " + foreignKey + " FROM " + table
                    + " LIMIT 0");
        } finally {
            unlock();
        }

        SyncUpdateInfo info = new SyncUpdateInfo(updateTable, deletedTable,
                foreignKey);
        synchronized (mSyncUpdateInfo) {
            mSyncUpdateInfo.put(table, info);
        }
    }

    /**
     * Call for each row that is updated in a cursor.
     *
     * @param table the table the row is in
     * @param rowId the row ID of the updated row
     */
    /* package */ void rowUpdated(String table, long rowId) {
        SyncUpdateInfo info;
        synchronized (mSyncUpdateInfo) {
            info = mSyncUpdateInfo.get(table);
        }
        if (info != null) {
            execSQL("UPDATE " + info.masterTable
                    + " SET _sync_dirty=1 WHERE _id=(SELECT " + info.foreignKey
                    + " FROM " + table + " WHERE _id=" + rowId + ")");
        }
    }

    /**
     * Finds the name of the first table, which is editable.
     *
     * @param tables a list of tables
     * @return the first table listed
     */
    public static String findEditTable(String tables) {
        if (!TextUtils.isEmpty(tables)) {
            // find the first word terminated by either a space or a comma
            int spacepos = tables.indexOf(' ');
            int commapos = tables.indexOf(',');

            if (spacepos > 0 && (spacepos < commapos || commapos < 0)) {
                return tables.substring(0, spacepos);
            } else if (commapos > 0 && (commapos < spacepos || spacepos < 0) ) {
                return tables.substring(0, commapos);
            }
            return tables;
        } else {
            throw new IllegalStateException("Invalid tables");
        }
    }

    /**
     * Compiles an SQL statement into a reusable pre-compiled statement object.
     * The parameters are identical to {@link #execSQL(String)}. You may put ?s in the
     * statement and fill in those values with {@link SQLiteProgram#bindString}
     * and {@link SQLiteProgram#bindLong} each time you want to run the
     * statement. Statements may not return result sets larger than 1x1.
     *
     * @param sql The raw SQL statement, may contain ? for unknown values to be
     *            bound later.
     * @return A pre-compiled {@link SQLiteStatement} object. Note that
     * {@link SQLiteStatement}s are not synchronized, see the documentation for more details.
     */
    public SQLiteStatement compileStatement(String sql) throws SQLException {
        lock();
        if (!isOpen()) {
            throw new IllegalStateException("database not open");
        }
        try {
            return new SQLiteStatement(this, sql);
        } finally {
            unlock();
        }
    }

    /**
     * Query the given URL, returning a {@link Cursor} over the result set.
     *
     * @param distinct true if you want each row to be unique, false otherwise.
     * @param table The table name to compile the query against.
     * @param columns A list of which columns to return. Passing null will
     *            return all columns, which is discouraged to prevent reading
     *            data from storage that isn't going to be used.
     * @param selection A filter declaring which rows to return, formatted as an
     *            SQL WHERE clause (excluding the WHERE itself). Passing null
     *            will return all rows for the given table.
     * @param selectionArgs You may include ?s in selection, which will be
     *         replaced by the values from selectionArgs, in order that they
     *         appear in the selection. The values will be bound as Strings.
     * @param groupBy A filter declaring how to group rows, formatted as an SQL
     *            GROUP BY clause (excluding the GROUP BY itself). Passing null
     *            will cause the rows to not be grouped.
     * @param having A filter declare which row groups to include in the cursor,
     *            if row grouping is being used, formatted as an SQL HAVING
     *            clause (excluding the HAVING itself). Passing null will cause
     *            all row groups to be included, and is required when row
     *            grouping is not being used.
     * @param orderBy How to order the rows, formatted as an SQL ORDER BY clause
     *            (excluding the ORDER BY itself). Passing null will use the
     *            default sort order, which may be unordered.
     * @param limit Limits the number of rows returned by the query,
     *            formatted as LIMIT clause. Passing null denotes no LIMIT clause.
     * @return A {@link Cursor} object, which is positioned before the first entry. Note that
     * {@link Cursor}s are not synchronized, see the documentation for more details.
     * @see Cursor
     */
    public Cursor query(boolean distinct, String table, String[] columns,
            String selection, String[] selectionArgs, String groupBy,
            String having, String orderBy, String limit) {
        return queryWithFactory(null, distinct, table, columns, selection, selectionArgs,
                groupBy, having, orderBy, limit);
    }

    /**
     * Query the given URL, returning a {@link Cursor} over the result set.
     *
     * @param cursorFactory the cursor factory to use, or null for the default factory
     * @param distinct true if you want each row to be unique, false otherwise.
     * @param table The table name to compile the query against.
     * @param columns A list of which columns to return. Passing null will
     *            return all columns, which is discouraged to prevent reading
     *            data from storage that isn't going to be used.
     * @param selection A filter declaring which rows to return, formatted as an
     *            SQL WHERE clause (excluding the WHERE itself). Passing null
     *            will return all rows for the given table.
     * @param selectionArgs You may include ?s in selection, which will be
     *         replaced by the values from selectionArgs, in order that they
     *         appear in the selection. The values will be bound as Strings.
     * @param groupBy A filter declaring how to group rows, formatted as an SQL
     *            GROUP BY clause (excluding the GROUP BY itself). Passing null
     *            will cause the rows to not be grouped.
     * @param having A filter declare which row groups to include in the cursor,
     *            if row grouping is being used, formatted as an SQL HAVING
     *            clause (excluding the HAVING itself). Passing null will cause
     *            all row groups to be included, and is required when row
     *            grouping is not being used.
     * @param orderBy How to order the rows, formatted as an SQL ORDER BY clause
     *            (excluding the ORDER BY itself). Passing null will use the
     *            default sort order, which may be unordered.
     * @param limit Limits the number of rows returned by the query,
     *            formatted as LIMIT clause. Passing null denotes no LIMIT clause.
     * @return A {@link Cursor} object, which is positioned before the first entry. Note that
     * {@link Cursor}s are not synchronized, see the documentation for more details.
     * @see Cursor
     */
    public Cursor queryWithFactory(CursorFactory cursorFactory,
            boolean distinct, String table, String[] columns,
            String selection, String[] selectionArgs, String groupBy,
            String having, String orderBy, String limit) {
        if (!isOpen()) {
            throw new IllegalStateException("database not open");
        }
        String sql = SQLiteQueryBuilder.buildQueryString(
                distinct, table, columns, selection, groupBy, having, orderBy, limit);

        return rawQueryWithFactory(
                cursorFactory, sql, selectionArgs, findEditTable(table));
    }

    /**
     * Query the given table, returning a {@link Cursor} over the result set.
     *
     * @param table The table name to compile the query against.
     * @param columns A list of which columns to return. Passing null will
     *            return all columns, which is discouraged to prevent reading
     *            data from storage that isn't going to be used.
     * @param selection A filter declaring which rows to return, formatted as an
     *            SQL WHERE clause (excluding the WHERE itself). Passing null
     *            will return all rows for the given table.
     * @param selectionArgs You may include ?s in selection, which will be
     *         replaced by the values from selectionArgs, in order that they
     *         appear in the selection. The values will be bound as Strings.
     * @param groupBy A filter declaring how to group rows, formatted as an SQL
     *            GROUP BY clause (excluding the GROUP BY itself). Passing null
     *            will cause the rows to not be grouped.
     * @param having A filter declare which row groups to include in the cursor,
     *            if row grouping is being used, formatted as an SQL HAVING
     *            clause (excluding the HAVING itself). Passing null will cause
     *            all row groups to be included, and is required when row
     *            grouping is not being used.
     * @param orderBy How to order the rows, formatted as an SQL ORDER BY clause
     *            (excluding the ORDER BY itself). Passing null will use the
     *            default sort order, which may be unordered.
     * @return A {@link Cursor} object, which is positioned before the first entry. Note that
     * {@link Cursor}s are not synchronized, see the documentation for more details.
     * @see Cursor
     */
    public Cursor query(String table, String[] columns, String selection,
            String[] selectionArgs, String groupBy, String having,
            String orderBy) {

        return query(false, table, columns, selection, selectionArgs, groupBy,
                having, orderBy, null /* limit */);
    }

    /**
     * Query the given table, returning a {@link Cursor} over the result set.
     *
     * @param table The table name to compile the query against.
     * @param columns A list of which columns to return. Passing null will
     *            return all columns, which is discouraged to prevent reading
     *            data from storage that isn't going to be used.
     * @param selection A filter declaring which rows to return, formatted as an
     *            SQL WHERE clause (excluding the WHERE itself). Passing null
     *            will return all rows for the given table.
     * @param selectionArgs You may include ?s in selection, which will be
     *         replaced by the values from selectionArgs, in order that they
     *         appear in the selection. The values will be bound as Strings.
     * @param groupBy A filter declaring how to group rows, formatted as an SQL
     *            GROUP BY clause (excluding the GROUP BY itself). Passing null
     *            will cause the rows to not be grouped.
     * @param having A filter declare which row groups to include in the cursor,
     *            if row grouping is being used, formatted as an SQL HAVING
     *            clause (excluding the HAVING itself). Passing null will cause
     *            all row groups to be included, and is required when row
     *            grouping is not being used.
     * @param orderBy How to order the rows, formatted as an SQL ORDER BY clause
     *            (excluding the ORDER BY itself). Passing null will use the
     *            default sort order, which may be unordered.
     * @param limit Limits the number of rows returned by the query,
     *            formatted as LIMIT clause. Passing null denotes no LIMIT clause.
     * @return A {@link Cursor} object, which is positioned before the first entry. Note that
     * {@link Cursor}s are not synchronized, see the documentation for more details.
     * @see Cursor
     */
    public Cursor query(String table, String[] columns, String selection,
            String[] selectionArgs, String groupBy, String having,
            String orderBy, String limit) {

        return query(false, table, columns, selection, selectionArgs, groupBy,
                having, orderBy, limit);
    }

    /**
     * Runs the provided SQL and returns a {@link Cursor} over the result set.
     *
     * @param sql the SQL query. The SQL string must not be ; terminated
     * @param selectionArgs You may include ?s in where clause in the query,
     *     which will be replaced by the values from selectionArgs. The
     *     values will be bound as Strings.
     * @return A {@link Cursor} object, which is positioned before the first entry. Note that
     * {@link Cursor}s are not synchronized, see the documentation for more details.
     */
    public Cursor rawQuery(String sql, String[] selectionArgs) {
        return rawQueryWithFactory(null, sql, selectionArgs, null);
    }

    /**
     * Runs the provided SQL and returns a cursor over the result set.
     *
     * @param cursorFactory the cursor factory to use, or null for the default factory
     * @param sql the SQL query. The SQL string must not be ; terminated
     * @param selectionArgs You may include ?s in where clause in the query,
     *     which will be replaced by the values from selectionArgs. The
     *     values will be bound as Strings.
     * @param editTable the name of the first table, which is editable
     * @return A {@link Cursor} object, which is positioned before the first entry. Note that
     * {@link Cursor}s are not synchronized, see the documentation for more details.
     */
    public Cursor rawQueryWithFactory(
            CursorFactory cursorFactory, String sql, String[] selectionArgs,
            String editTable) {
        if (!isOpen()) {
            throw new IllegalStateException("database not open");
        }
        BlockGuard.getThreadPolicy().onReadFromDisk();
        long timeStart = 0;

        if (Config.LOGV || mSlowQueryThreshold != -1) {
            timeStart = System.currentTimeMillis();
        }

        SQLiteCursorDriver driver = new SQLiteDirectCursorDriver(this, sql, editTable);

        Cursor cursor = null;
        try {
            cursor = driver.query(
                    cursorFactory != null ? cursorFactory : mFactory,
                    selectionArgs);
        } finally {
            if (Config.LOGV || mSlowQueryThreshold != -1) {

                // Force query execution
                int count = -1;
                if (cursor != null) {
                    count = cursor.getCount();
                }

                long duration = System.currentTimeMillis() - timeStart;

                if (Config.LOGV || duration >= mSlowQueryThreshold) {
                    Log.v(SQLiteCursor.TAG,
                          "query (" + duration + " ms): " + driver.toString() + ", args are "
                                  + (selectionArgs != null
                                  ? TextUtils.join(",", selectionArgs)
                                  : "<null>")  + ", count is " + count);
                }
            }
        }
        return cursor;
    }

    /**
     * Runs the provided SQL and returns a cursor over the result set.
     * The cursor will read an initial set of rows and the return to the caller.
     * It will continue to read in batches and send data changed notifications
     * when the later batches are ready.
     * @param sql the SQL query. The SQL string must not be ; terminated
     * @param selectionArgs You may include ?s in where clause in the query,
     *     which will be replaced by the values from selectionArgs. The
     *     values will be bound as Strings.
     * @param initialRead set the initial count of items to read from the cursor
     * @param maxRead set the count of items to read on each iteration after the first
     * @return A {@link Cursor} object, which is positioned before the first entry. Note that
     * {@link Cursor}s are not synchronized, see the documentation for more details.
     *
     * This work is incomplete and not fully tested or reviewed, so currently
     * hidden.
     * @hide
     */
    public Cursor rawQuery(String sql, String[] selectionArgs,
            int initialRead, int maxRead) {
        SQLiteCursor c = (SQLiteCursor)rawQueryWithFactory(
                null, sql, selectionArgs, null);
        c.setLoadStyle(initialRead, maxRead);
        return c;
    }

    /**
     * Convenience method for inserting a row into the database.
     *
     * @param table the table to insert the row into
     * @param nullColumnHack optional; may be <code>null</code>.
     *            SQL doesn't allow inserting a completely empty row without
     *            naming at least one column name.  If your provided <code>values</code> is
     *            empty, no column names are known and an empty row can't be inserted.
     *            If not set to null, the <code>nullColumnHack</code> parameter
     *            provides the name of nullable column name to explicitly insert a NULL into
     *            in the case where your <code>values</code> is empty.
     * @param values this map contains the initial column values for the
     *            row. The keys should be the column names and the values the
     *            column values
     * @return the row ID of the newly inserted row, or -1 if an error occurred
     */
    public long insert(String table, String nullColumnHack, ContentValues values) {
        try {
            return insertWithOnConflict(table, nullColumnHack, values, CONFLICT_NONE);
        } catch (SQLException e) {
            Log.e(TAG, "Error inserting " + values, e);
            return -1;
        }
    }

    /**
     * Convenience method for inserting a row into the database.
     *
     * @param table the table to insert the row into
     * @param nullColumnHack optional; may be <code>null</code>.
     *            SQL doesn't allow inserting a completely empty row without
     *            naming at least one column name.  If your provided <code>values</code> is
     *            empty, no column names are known and an empty row can't be inserted.
     *            If not set to null, the <code>nullColumnHack</code> parameter
     *            provides the name of nullable column name to explicitly insert a NULL into
     *            in the case where your <code>values</code> is empty.
     * @param values this map contains the initial column values for the
     *            row. The keys should be the column names and the values the
     *            column values
     * @throws SQLException
     * @return the row ID of the newly inserted row, or -1 if an error occurred
     */
    public long insertOrThrow(String table, String nullColumnHack, ContentValues values)
            throws SQLException {
        return insertWithOnConflict(table, nullColumnHack, values, CONFLICT_NONE);
    }

    /**
     * Convenience method for replacing a row in the database.
     *
     * @param table the table in which to replace the row
     * @param nullColumnHack optional; may be <code>null</code>.
     *            SQL doesn't allow inserting a completely empty row without
     *            naming at least one column name.  If your provided <code>initialValues</code> is
     *            empty, no column names are known and an empty row can't be inserted.
     *            If not set to null, the <code>nullColumnHack</code> parameter
     *            provides the name of nullable column name to explicitly insert a NULL into
     *            in the case where your <code>initialValues</code> is empty.
     * @param initialValues this map contains the initial column values for
     *   the row.
     * @return the row ID of the newly inserted row, or -1 if an error occurred
     */
    public long replace(String table, String nullColumnHack, ContentValues initialValues) {
        try {
            return insertWithOnConflict(table, nullColumnHack, initialValues,
                    CONFLICT_REPLACE);
        } catch (SQLException e) {
            Log.e(TAG, "Error inserting " + initialValues, e);
            return -1;
        }
    }

    /**
     * Convenience method for replacing a row in the database.
     *
     * @param table the table in which to replace the row
     * @param nullColumnHack optional; may be <code>null</code>.
     *            SQL doesn't allow inserting a completely empty row without
     *            naming at least one column name.  If your provided <code>initialValues</code> is
     *            empty, no column names are known and an empty row can't be inserted.
     *            If not set to null, the <code>nullColumnHack</code> parameter
     *            provides the name of nullable column name to explicitly insert a NULL into
     *            in the case where your <code>initialValues</code> is empty.
     * @param initialValues this map contains the initial column values for
     *   the row. The key
     * @throws SQLException
     * @return the row ID of the newly inserted row, or -1 if an error occurred
     */
    public long replaceOrThrow(String table, String nullColumnHack,
            ContentValues initialValues) throws SQLException {
        return insertWithOnConflict(table, nullColumnHack, initialValues,
                CONFLICT_REPLACE);
    }

    /**
     * General method for inserting a row into the database.
     *
     * @param table the table to insert the row into
     * @param nullColumnHack optional; may be <code>null</code>.
     *            SQL doesn't allow inserting a completely empty row without
     *            naming at least one column name.  If your provided <code>initialValues</code> is
     *            empty, no column names are known and an empty row can't be inserted.
     *            If not set to null, the <code>nullColumnHack</code> parameter
     *            provides the name of nullable column name to explicitly insert a NULL into
     *            in the case where your <code>initialValues</code> is empty.
     * @param initialValues this map contains the initial column values for the
     *            row. The keys should be the column names and the values the
     *            column values
     * @param conflictAlgorithm for insert conflict resolver
     * @return the row ID of the newly inserted row
     * OR the primary key of the existing row if the input param 'conflictAlgorithm' =
     * {@link #CONFLICT_IGNORE}
     * OR -1 if any error
     */
    public long insertWithOnConflict(String table, String nullColumnHack,
            ContentValues initialValues, int conflictAlgorithm) {
        BlockGuard.getThreadPolicy().onWriteToDisk();
        if (!isOpen()) {
            throw new IllegalStateException("database not open");
        }

        // Measurements show most sql lengths <= 152
        StringBuilder sql = new StringBuilder(152);
        sql.append("INSERT");
        sql.append(CONFLICT_VALUES[conflictAlgorithm]);
        sql.append(" INTO ");
        sql.append(table);
        // Measurements show most values lengths < 40
        StringBuilder values = new StringBuilder(40);

        Set<Map.Entry<String, Object>> entrySet = null;
        if (initialValues != null && initialValues.size() > 0) {
            entrySet = initialValues.valueSet();
            Iterator<Map.Entry<String, Object>> entriesIter = entrySet.iterator();
            sql.append('(');

            boolean needSeparator = false;
            while (entriesIter.hasNext()) {
                if (needSeparator) {
                    sql.append(", ");
                    values.append(", ");
                }
                needSeparator = true;
                Map.Entry<String, Object> entry = entriesIter.next();
                sql.append(entry.getKey());
                values.append('?');
            }

            sql.append(')');
        } else {
            sql.append("(" + nullColumnHack + ") ");
            values.append("NULL");
        }

        sql.append(" VALUES(");
        sql.append(values);
        sql.append(");");

        lock();
        SQLiteStatement statement = null;
        try {
            statement = compileStatement(sql.toString());

            // Bind the values
            if (entrySet != null) {
                int size = entrySet.size();
                Iterator<Map.Entry<String, Object>> entriesIter = entrySet.iterator();
                for (int i = 0; i < size; i++) {
                    Map.Entry<String, Object> entry = entriesIter.next();
                    DatabaseUtils.bindObjectToProgram(statement, i + 1, entry.getValue());
                }
            }

            // Run the program and then cleanup
            statement.execute();

            long insertedRowId = lastInsertRow();
            if (insertedRowId == -1) {
                Log.e(TAG, "Error inserting " + initialValues + " using " + sql);
            } else {
                if (Config.LOGD && Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Inserting row " + insertedRowId + " from "
                            + initialValues + " using " + sql);
                }
            }
            return insertedRowId;
        } catch (SQLiteDatabaseCorruptException e) {
            onCorruption();
            throw e;
        } finally {
            if (statement != null) {
                statement.close();
            }
            unlock();
        }
    }

    /**
     * Convenience method for deleting rows in the database.
     *
     * @param table the table to delete from
     * @param whereClause the optional WHERE clause to apply when deleting.
     *            Passing null will delete all rows.
     * @return the number of rows affected if a whereClause is passed in, 0
     *         otherwise. To remove all rows and get a count pass "1" as the
     *         whereClause.
     */
    public int delete(String table, String whereClause, String[] whereArgs) {
        BlockGuard.getThreadPolicy().onWriteToDisk();
        lock();
        if (!isOpen()) {
            throw new IllegalStateException("database not open");
        }
        SQLiteStatement statement = null;
        try {
            statement = compileStatement("DELETE FROM " + table
                    + (!TextUtils.isEmpty(whereClause)
                    ? " WHERE " + whereClause : ""));
            if (whereArgs != null) {
                int numArgs = whereArgs.length;
                for (int i = 0; i < numArgs; i++) {
                    DatabaseUtils.bindObjectToProgram(statement, i + 1, whereArgs[i]);
                }
            }
            statement.execute();
            return lastChangeCount();
        } catch (SQLiteDatabaseCorruptException e) {
            onCorruption();
            throw e;
        } finally {
            if (statement != null) {
                statement.close();
            }
            unlock();
        }
    }

    /**
     * Convenience method for updating rows in the database.
     *
     * @param table the table to update in
     * @param values a map from column names to new column values. null is a
     *            valid value that will be translated to NULL.
     * @param whereClause the optional WHERE clause to apply when updating.
     *            Passing null will update all rows.
     * @return the number of rows affected
     */
    public int update(String table, ContentValues values, String whereClause, String[] whereArgs) {
        return updateWithOnConflict(table, values, whereClause, whereArgs, CONFLICT_NONE);
    }

    /**
     * Convenience method for updating rows in the database.
     *
     * @param table the table to update in
     * @param values a map from column names to new column values. null is a
     *            valid value that will be translated to NULL.
     * @param whereClause the optional WHERE clause to apply when updating.
     *            Passing null will update all rows.
     * @param conflictAlgorithm for update conflict resolver
     * @return the number of rows affected
     */
    public int updateWithOnConflict(String table, ContentValues values,
            String whereClause, String[] whereArgs, int conflictAlgorithm) {
        BlockGuard.getThreadPolicy().onWriteToDisk();
        if (values == null || values.size() == 0) {
            throw new IllegalArgumentException("Empty values");
        }

        StringBuilder sql = new StringBuilder(120);
        sql.append("UPDATE ");
        sql.append(CONFLICT_VALUES[conflictAlgorithm]);
        sql.append(table);
        sql.append(" SET ");

        Set<Map.Entry<String, Object>> entrySet = values.valueSet();
        Iterator<Map.Entry<String, Object>> entriesIter = entrySet.iterator();

        while (entriesIter.hasNext()) {
            Map.Entry<String, Object> entry = entriesIter.next();
            sql.append(entry.getKey());
            sql.append("=?");
            if (entriesIter.hasNext()) {
                sql.append(", ");
            }
        }

        if (!TextUtils.isEmpty(whereClause)) {
            sql.append(" WHERE ");
            sql.append(whereClause);
        }

        lock();
        if (!isOpen()) {
            throw new IllegalStateException("database not open");
        }
        SQLiteStatement statement = null;
        try {
            statement = compileStatement(sql.toString());

            // Bind the values
            int size = entrySet.size();
            entriesIter = entrySet.iterator();
            int bindArg = 1;
            for (int i = 0; i < size; i++) {
                Map.Entry<String, Object> entry = entriesIter.next();
                DatabaseUtils.bindObjectToProgram(statement, bindArg, entry.getValue());
                bindArg++;
            }

            if (whereArgs != null) {
                size = whereArgs.length;
                for (int i = 0; i < size; i++) {
                    statement.bindString(bindArg, whereArgs[i]);
                    bindArg++;
                }
            }

            // Run the program and then cleanup
            statement.execute();
            int numChangedRows = lastChangeCount();
            if (Config.LOGD && Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Updated " + numChangedRows + " using " + values + " and " + sql);
            }
            return numChangedRows;
        } catch (SQLiteDatabaseCorruptException e) {
            onCorruption();
            throw e;
        } catch (SQLException e) {
            Log.e(TAG, "Error updating " + values + " using " + sql);
            throw e;
        } finally {
            if (statement != null) {
                statement.close();
            }
            unlock();
        }
    }

    /**
     * Execute a single SQL statement that is not a query. For example, CREATE
     * TABLE, DELETE, INSERT, etc. Multiple statements separated by semicolons are not
     * supported.  Takes a write lock.
     *
     * @throws SQLException if the SQL string is invalid
     */
    public void execSQL(String sql) throws SQLException {
        BlockGuard.getThreadPolicy().onWriteToDisk();
        long timeStart = SystemClock.uptimeMillis();
        lock();
        if (!isOpen()) {
            throw new IllegalStateException("database not open");
        }
        logTimeStat(mLastSqlStatement, timeStart, GET_LOCK_LOG_PREFIX);
        try {
            native_execSQL(sql);
        } catch (SQLiteDatabaseCorruptException e) {
            onCorruption();
            throw e;
        } finally {
            unlock();
        }

        // Log commit statements along with the most recently executed
        // SQL statement for disambiguation.  Note that instance
        // equality to COMMIT_SQL is safe here.
        if (sql == COMMIT_SQL) {
            logTimeStat(mLastSqlStatement, timeStart, COMMIT_SQL);
        } else {
            logTimeStat(sql, timeStart, null);
        }
    }

    /**
     * Execute a single SQL statement that is not a query. For example, CREATE
     * TABLE, DELETE, INSERT, etc. Multiple statements separated by semicolons are not
     * supported.  Takes a write lock.
     *
     * @param sql
     * @param bindArgs only byte[], String, Long and Double are supported in bindArgs.
     * @throws SQLException if the SQL string is invalid
     */
    public void execSQL(String sql, Object[] bindArgs) throws SQLException {
        BlockGuard.getThreadPolicy().onWriteToDisk();
        if (bindArgs == null) {
            throw new IllegalArgumentException("Empty bindArgs");
        }
        long timeStart = SystemClock.uptimeMillis();
        lock();
        if (!isOpen()) {
            throw new IllegalStateException("database not open");
        }
        SQLiteStatement statement = null;
        try {
            statement = compileStatement(sql);
            if (bindArgs != null) {
                int numArgs = bindArgs.length;
                for (int i = 0; i < numArgs; i++) {
                    DatabaseUtils.bindObjectToProgram(statement, i + 1, bindArgs[i]);
                }
            }
            statement.execute();
        } catch (SQLiteDatabaseCorruptException e) {
            onCorruption();
            throw e;
        } finally {
            if (statement != null) {
                statement.close();
            }
            unlock();
        }
        logTimeStat(sql, timeStart);
    }

    @Override
    protected void finalize() {
        if (isOpen()) {
            Log.e(TAG, "close() was never explicitly called on database '" +
                    mPath + "' ", mStackTrace);
            closeClosable();
            onAllReferencesReleased();
        }
    }

    /**
     * Private constructor. See {@link #create} and {@link #openDatabase}.
     *
     * @param path The full path to the database
     * @param factory The factory to use when creating cursors, may be NULL.
     * @param flags 0 or {@link #NO_LOCALIZED_COLLATORS}.  If the database file already
     *              exists, mFlags will be updated appropriately.
     */
    private SQLiteDatabase(String path, CursorFactory factory, int flags) {
        if (path == null) {
            throw new IllegalArgumentException("path should not be null");
        }
        mFlags = flags;
        mPath = path;
        mSlowQueryThreshold = SystemProperties.getInt(LOG_SLOW_QUERIES_PROPERTY, -1);
        mStackTrace = new DatabaseObjectNotClosedException().fillInStackTrace();
        mFactory = factory;
        dbopen(mPath, mFlags);
        if (SQLiteDebug.DEBUG_SQL_CACHE) {
            mTimeOpened = getTime();
        }
        mPrograms = new WeakHashMap<SQLiteClosable,Object>();
        try {
            setLocale(Locale.getDefault());
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to setLocale() when constructing, closing the database", e);
            dbclose();
            if (SQLiteDebug.DEBUG_SQL_CACHE) {
                mTimeClosed = getTime();
            }
            throw e;
        }
    }

    private String getTime() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS ").format(System.currentTimeMillis());
    }

    /**
     * return whether the DB is opened as read only.
     * @return true if DB is opened as read only
     */
    public boolean isReadOnly() {
        return (mFlags & OPEN_READ_MASK) == OPEN_READONLY;
    }

    /**
     * @return true if the DB is currently open (has not been closed)
     */
    public boolean isOpen() {
        return mNativeHandle != 0;
    }

    public boolean needUpgrade(int newVersion) {
        return newVersion > getVersion();
    }

    /**
     * Getter for the path to the database file.
     *
     * @return the path to our database file.
     */
    public final String getPath() {
        return mPath;
    }

    /* package */ void logTimeStat(String sql, long beginMillis) {
        logTimeStat(sql, beginMillis, null);
    }

    /* package */ void logTimeStat(String sql, long beginMillis, String prefix) {
        // Keep track of the last statement executed here, as this is
        // the common funnel through which all methods of hitting
        // libsqlite eventually flow.
        mLastSqlStatement = sql;

        // Sample fast queries in proportion to the time taken.
        // Quantize the % first, so the logged sampling probability
        // exactly equals the actual sampling rate for this query.

        int samplePercent;
        long durationMillis = SystemClock.uptimeMillis() - beginMillis;
        if (durationMillis == 0 && prefix == GET_LOCK_LOG_PREFIX) {
            // The common case is locks being uncontended.  Don't log those,
            // even at 1%, which is our default below.
            return;
        }
        if (sQueryLogTimeInMillis == 0) {
            sQueryLogTimeInMillis = SystemProperties.getInt("db.db_operation.threshold_ms", 500);
        }
        if (durationMillis >= sQueryLogTimeInMillis) {
            samplePercent = 100;
        } else {;
            samplePercent = (int) (100 * durationMillis / sQueryLogTimeInMillis) + 1;
            if (mRandom.nextInt(100) >= samplePercent) return;
        }

        // Note: the prefix will be "COMMIT;" or "GETLOCK:" when non-null.  We wait to do
        // it here so we avoid allocating in the common case.
        if (prefix != null) {
            sql = prefix + sql;
        }

        if (sql.length() > QUERY_LOG_SQL_LENGTH) sql = sql.substring(0, QUERY_LOG_SQL_LENGTH);

        // ActivityThread.currentPackageName() only returns non-null if the
        // current thread is an application main thread.  This parameter tells
        // us whether an event loop is blocked, and if so, which app it is.
        //
        // Sadly, there's no fast way to determine app name if this is *not* a
        // main thread, or when we are invoked via Binder (e.g. ContentProvider).
        // Hopefully the full path to the database will be informative enough.

        String blockingPackage = AppGlobals.getInitialPackage();
        if (blockingPackage == null) blockingPackage = "";

        EventLog.writeEvent(
            EVENT_DB_OPERATION,
            getPathForLogs(),
            sql,
            durationMillis,
            blockingPackage,
            samplePercent);
    }

    /**
     * Removes email addresses from database filenames before they're
     * logged to the EventLog where otherwise apps could potentially
     * read them.
     */
    private String getPathForLogs() {
        if (mPathForLogs != null) {
            return mPathForLogs;
        }
        if (mPath == null) {
            return null;
        }
        if (mPath.indexOf('@') == -1) {
            mPathForLogs = mPath;
        } else {
            mPathForLogs = EMAIL_IN_DB_PATTERN.matcher(mPath).replaceAll("XX@YY");
        }
        return mPathForLogs;
    }

    /**
     * Sets the locale for this database.  Does nothing if this database has
     * the NO_LOCALIZED_COLLATORS flag set or was opened read only.
     * @throws SQLException if the locale could not be set.  The most common reason
     * for this is that there is no collator available for the locale you requested.
     * In this case the database remains unchanged.
     */
    public void setLocale(Locale locale) {
        lock();
        try {
            native_setLocale(locale.toString(), mFlags);
        } finally {
            unlock();
        }
    }

    /*
     * ============================================================================
     *
     *       The following methods deal with compiled-sql cache
     * ============================================================================
     */
    /**
     * adds the given sql and its compiled-statement-id-returned-by-sqlite to the
     * cache of compiledQueries attached to 'this'.
     *
     * if there is already a {@link SQLiteCompiledSql} in compiledQueries for the given sql,
     * the new {@link SQLiteCompiledSql} object is NOT inserted into the cache (i.e.,the current
     * mapping is NOT replaced with the new mapping).
     */
    /* package */ void addToCompiledQueries(String sql, SQLiteCompiledSql compiledStatement) {
        if (mMaxSqlCacheSize == 0) {
            // for this database, there is no cache of compiled sql.
            if (SQLiteDebug.DEBUG_SQL_CACHE) {
                Log.v(TAG, "|NOT adding_sql_to_cache|" + getPath() + "|" + sql);
            }
            return;
        }

        SQLiteCompiledSql compiledSql = null;
        synchronized(mCompiledQueries) {
            // don't insert the new mapping if a mapping already exists
            compiledSql = mCompiledQueries.get(sql);
            if (compiledSql != null) {
                return;
            }
            // add this <sql, compiledStatement> to the cache
            if (mCompiledQueries.size() == mMaxSqlCacheSize) {
                /*
                 * cache size of {@link #mMaxSqlCacheSize} is not enough for this app.
                 * log a warning MAX_WARNINGS_ON_CACHESIZE_CONDITION times
                 * chances are it is NOT using ? for bindargs - so caching is useless.
                 * TODO: either let the callers set max cchesize for their app, or intelligently
                 * figure out what should be cached for a given app.
                 */
                if (++mCacheFullWarnings == MAX_WARNINGS_ON_CACHESIZE_CONDITION) {
                    Log.w(TAG, "Reached MAX size for compiled-sql statement cache for database " +
                            getPath() + "; i.e., NO space for this sql statement in cache: " +
                            sql + ". Please change your sql statements to use '?' for " +
                            "bindargs, instead of using actual values");
                }
                // don't add this entry to cache
            } else {
                // cache is NOT full. add this to cache.
                mCompiledQueries.put(sql, compiledStatement);
                if (SQLiteDebug.DEBUG_SQL_CACHE) {
                    Log.v(TAG, "|adding_sql_to_cache|" + getPath() + "|" +
                            mCompiledQueries.size() + "|" + sql);
                }
            }
        }
        return;
    }


    private void deallocCachedSqlStatements() {
        synchronized (mCompiledQueries) {
            for (SQLiteCompiledSql compiledSql : mCompiledQueries.values()) {
                compiledSql.releaseSqlStatement();
            }
            mCompiledQueries.clear();
        }
    }

    /**
     * from the compiledQueries cache, returns the compiled-statement-id for the given sql.
     * returns null, if not found in the cache.
     */
    /* package */ SQLiteCompiledSql getCompiledStatementForSql(String sql) {
        SQLiteCompiledSql compiledStatement = null;
        boolean cacheHit;
        synchronized(mCompiledQueries) {
            if (mMaxSqlCacheSize == 0) {
                // for this database, there is no cache of compiled sql.
                if (SQLiteDebug.DEBUG_SQL_CACHE) {
                    Log.v(TAG, "|cache NOT found|" + getPath());
                }
                return null;
            }
            cacheHit = (compiledStatement = mCompiledQueries.get(sql)) != null;
        }
        if (cacheHit) {
            mNumCacheHits++;
        } else {
            mNumCacheMisses++;
        }

        if (SQLiteDebug.DEBUG_SQL_CACHE) {
            Log.v(TAG, "|cache_stats|" +
                    getPath() + "|" + mCompiledQueries.size() +
                    "|" + mNumCacheHits + "|" + mNumCacheMisses +
                    "|" + cacheHit + "|" + mTimeOpened + "|" + mTimeClosed + "|" + sql);
        }
        return compiledStatement;
    }

    /**
     * returns true if the given sql is cached in compiled-sql cache.
     * @hide
     */
    public boolean isInCompiledSqlCache(String sql) {
        synchronized(mCompiledQueries) {
            return mCompiledQueries.containsKey(sql);
        }
    }

    /**
     * purges the given sql from the compiled-sql cache.
     * @hide
     */
    public void purgeFromCompiledSqlCache(String sql) {
        synchronized(mCompiledQueries) {
            mCompiledQueries.remove(sql);
        }
    }

    /**
     * remove everything from the compiled sql cache
     * @hide
     */
    public void resetCompiledSqlCache() {
        synchronized(mCompiledQueries) {
            mCompiledQueries.clear();
        }
    }

    /**
     * return the current maxCacheSqlCacheSize
     * @hide
     */
    public synchronized int getMaxSqlCacheSize() {
        return mMaxSqlCacheSize;
    }

    /**
     * set the max size of the compiled sql cache for this database after purging the cache.
     * (size of the cache = number of compiled-sql-statements stored in the cache).
     *
     * max cache size can ONLY be increased from its current size (default = 0).
     * if this method is called with smaller size than the current value of mMaxSqlCacheSize,
     * then IllegalStateException is thrown
     *
     * synchronized because we don't want t threads to change cache size at the same time.
     * @param cacheSize the size of the cache. can be (0 to MAX_SQL_CACHE_SIZE)
     * @throws IllegalStateException if input cacheSize > MAX_SQL_CACHE_SIZE or < 0 or
     * < the value set with previous setMaxSqlCacheSize() call.
     *
     * @hide
     */
    public synchronized void setMaxSqlCacheSize(int cacheSize) {
        if (cacheSize > MAX_SQL_CACHE_SIZE || cacheSize < 0) {
            throw new IllegalStateException("expected value between 0 and " + MAX_SQL_CACHE_SIZE);
        } else if (cacheSize < mMaxSqlCacheSize) {
            throw new IllegalStateException("cannot set cacheSize to a value less than the value " +
                    "set with previous setMaxSqlCacheSize() call.");
        }
        mMaxSqlCacheSize = cacheSize;
    }

    static class ActiveDatabases {
        private static final ActiveDatabases activeDatabases = new ActiveDatabases();
        private HashSet<WeakReference<SQLiteDatabase>> mActiveDatabases =
            new HashSet<WeakReference<SQLiteDatabase>>();
        private ActiveDatabases() {} // disable instantiation of this class
        static ActiveDatabases getInstance() {return activeDatabases;}
    }

    /**
     * this method is used to collect data about ALL open databases in the current process.
     * bugreport is a user of this data. 
     */
    /* package */ static ArrayList<DbStats> getDbStats() {
        ArrayList<DbStats> dbStatsList = new ArrayList<DbStats>();
        for (WeakReference<SQLiteDatabase> w : ActiveDatabases.getInstance().mActiveDatabases) {
            SQLiteDatabase db = w.get();
            if (db == null || !db.isOpen()) {
                continue;
            }
            // get SQLITE_DBSTATUS_LOOKASIDE_USED for the db
            int lookasideUsed = db.native_getDbLookaside();

            // get the lastnode of the dbname
            String path = db.getPath();
            int indx = path.lastIndexOf("/");
            String lastnode = path.substring((indx != -1) ? ++indx : 0);

            // get list of attached dbs and for each db, get its size and pagesize
            ArrayList<Pair<String, String>> attachedDbs = getAttachedDbs(db);
            if (attachedDbs == null) {
                continue;
            }
            for (int i = 0; i < attachedDbs.size(); i++) {
                Pair<String, String> p = attachedDbs.get(i);
                long pageCount = getPragmaVal(db, p.first + ".page_count;");

                // first entry in the attached db list is always the main database
                // don't worry about prefixing the dbname with "main"
                String dbName;
                if (i == 0) {
                    dbName = lastnode;
                } else {
                    // lookaside is only relevant for the main db
                    lookasideUsed = 0;
                    dbName = "  (attached) " + p.first;
                    // if the attached db has a path, attach the lastnode from the path to above
                    if (p.second.trim().length() > 0) {
                        int idx = p.second.lastIndexOf("/");
                        dbName += " : " + p.second.substring((idx != -1) ? ++idx : 0);
                    }
                }
                if (pageCount > 0) {
                    dbStatsList.add(new DbStats(dbName, pageCount, db.getPageSize(),
                            lookasideUsed));
                }
            }
        }
        return dbStatsList;
    }

    /**
     * get the specified pragma value from sqlite for the specified database.
     * only handles pragma's that return int/long.
     * NO JAVA locks are held in this method.
     * TODO: use this to do all pragma's in this class
     */
    private static long getPragmaVal(SQLiteDatabase db, String pragma) {
        if (!db.isOpen()) {
            return 0;
        }
        SQLiteStatement prog = null;
        try {
            prog = new SQLiteStatement(db, "PRAGMA " + pragma);
            long val = prog.simpleQueryForLong();
            return val;
        } finally {
            if (prog != null) prog.close();
        }
    }

    /**
     * returns list of full pathnames of all attached databases
     * including the main database
     * TODO: move this to {@link DatabaseUtils}
     */
    private static ArrayList<Pair<String, String>> getAttachedDbs(SQLiteDatabase dbObj) {
        if (!dbObj.isOpen()) {
            return null;
        }
        ArrayList<Pair<String, String>> attachedDbs = new ArrayList<Pair<String, String>>();
        Cursor c = dbObj.rawQuery("pragma database_list;", null);
        while (c.moveToNext()) {
             attachedDbs.add(new Pair<String, String>(c.getString(1), c.getString(2)));
        }
        c.close();
        return attachedDbs;
    }

    /**
     * Native call to open the database.
     *
     * @param path The full path to the database
     */
    private native void dbopen(String path, int flags);

    /**
     * Native call to setup tracing of all sql statements
     *
     * @param path the full path to the database
     */
    private native void enableSqlTracing(String path);

    /**
     * Native call to setup profiling of all sql statements.
     * currently, sqlite's profiling = printing of execution-time
     * (wall-clock time) of each of the sql statements, as they
     * are executed.
     *
     * @param path the full path to the database
     */
    private native void enableSqlProfiling(String path);

    /**
     * Native call to execute a raw SQL statement. {@link #lock} must be held
     * when calling this method.
     *
     * @param sql The raw SQL string
     * @throws SQLException
     */
    /* package */ native void native_execSQL(String sql) throws SQLException;

    /**
     * Native call to set the locale.  {@link #lock} must be held when calling
     * this method.
     * @throws SQLException
     */
    /* package */ native void native_setLocale(String loc, int flags);

    /**
     * Returns the row ID of the last row inserted into the database.
     *
     * @return the row ID of the last row inserted into the database.
     */
    /* package */ native long lastInsertRow();

    /**
     * Returns the number of changes made in the last statement executed.
     *
     * @return the number of changes made in the last statement executed.
     */
    /* package */ native int lastChangeCount();

    /**
     * return the SQLITE_DBSTATUS_LOOKASIDE_USED documented here
     * http://www.sqlite.org/c3ref/c_dbstatus_lookaside_used.html
     * @return int value of SQLITE_DBSTATUS_LOOKASIDE_USED
     */
    private native int native_getDbLookaside();
}
