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

import android.app.ActivityThread;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.DatabaseUtils;
import android.database.DefaultDatabaseErrorHandler;
import android.database.SQLException;
import android.database.sqlite.SQLiteDebug.DbStats;
import android.os.Debug;
import android.os.StatFs;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
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

    /** Used by native code, do not rename. make it volatile, so it is thread-safe. */
    /* package */ volatile int mNativeHandle = 0;

    /** Used to make temp table names unique */
    /* package */ int mTempTableSequence = 0;

    /**
     * The size, in bytes, of a block on "/data". This corresponds to the Unix
     * statfs.f_bsize field. note that this field is lazily initialized.
     */
    private static int sBlockSize = 0;

    /** The path for the database file */
    private final String mPath;

    /** The anonymized path for the database file for logging purposes */
    private String mPathForLogs = null;  // lazily populated

    /** The flags passed to open/create */
    private final int mFlags;

    /** The optional factory to use when creating new Cursors */
    private final CursorFactory mFactory;

    private final WeakHashMap<SQLiteClosable, Object> mPrograms;

    /**
     * for each instance of this class, a LRU cache is maintained to store
     * the compiled query statement ids returned by sqlite database.
     *     key = SQL statement with "?" for bind args
     *     value = {@link SQLiteCompiledSql}
     * If an application opens the database and keeps it open during its entire life, then
     * there will not be an overhead of compilation of SQL statements by sqlite.
     *
     * why is this cache NOT static? because sqlite attaches compiledsql statements to the
     * struct created when {@link SQLiteDatabase#openDatabase(String, CursorFactory, int)} is
     * invoked.
     *
     * this cache has an upper limit of mMaxSqlCacheSize (settable by calling the method
     * (@link setMaxSqlCacheSize(int)}).
     */
    // default statement-cache size per database connection ( = instance of this class)
    private int mMaxSqlCacheSize = 25;
    /* package */ final Map<String, SQLiteCompiledSql> mCompiledQueries =
        new LinkedHashMap<String, SQLiteCompiledSql>(mMaxSqlCacheSize + 1, 0.75f, true) {
            @Override
            public boolean removeEldestEntry(Map.Entry<String, SQLiteCompiledSql> eldest) {
                // eldest = least-recently used entry
                // if it needs to be removed to accommodate a new entry,
                //     close {@link SQLiteCompiledSql} represented by this entry, if not in use
                //     and then let it be removed from the Map.
                // when this is called, the caller must be trying to add a just-compiled stmt
                // to cache; i.e., caller should already have acquired database lock AND
                // the lock on mCompiledQueries. do as assert of these two 2 facts.
                verifyLockOwner();
                if (this.size() <= mMaxSqlCacheSize) {
                    // cache is not full. nothing needs to be removed
                    return false;
                }
                // cache is full. eldest will be removed.
                SQLiteCompiledSql entry = eldest.getValue();
                if (!entry.isInUse()) {
                    // this {@link SQLiteCompiledSql} is not in use. release it.
                    entry.releaseSqlStatement();
                }
                // return true, so that this entry is removed automatically by the caller.
                return true;
            }
        };
    /**
     * absolute max value that can be set by {@link #setMaxSqlCacheSize(int)}
     * size of each prepared-statement is between 1K - 6K, depending on the complexity of the
     * SQL statement & schema.
     */
    public static final int MAX_SQL_CACHE_SIZE = 100;
    private int mCacheFullWarnings;
    private static final int MAX_WARNINGS_ON_CACHESIZE_CONDITION = 1;

    /** maintain stats about number of cache hits and misses */
    private int mNumCacheHits;
    private int mNumCacheMisses;

    /** Used to find out where this object was created in case it never got closed. */
    private final Throwable mStackTrace;

    // System property that enables logging of slow queries. Specify the threshold in ms.
    private static final String LOG_SLOW_QUERIES_PROPERTY = "db.log.slow_query_threshold";
    private final int mSlowQueryThreshold;

    /** stores the list of statement ids that need to be finalized by sqlite */
    private final ArrayList<Integer> mClosedStatementIds = new ArrayList<Integer>();

    /** {@link DatabaseErrorHandler} to be used when SQLite returns any of the following errors
     *    Corruption
     * */
    private final DatabaseErrorHandler mErrorHandler;

    /** The Database connection pool {@link DatabaseConnectionPool}.
     * Visibility is package-private for testing purposes. otherwise, private visibility is enough.
     */
    /* package */ volatile DatabaseConnectionPool mConnectionPool = null;

    /** Each database connection handle in the pool is assigned a number 1..N, where N is the
     * size of the connection pool.
     * The main connection handle to which the pool is attached is assigned a value of 0.
     */
    /* package */ final short mConnectionNum;

    private static final String MEMORY_DB_PATH = ":memory:";

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
            // close the database which will close all pending statements to be finalized also
            close();
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
        EventLog.writeEvent(EVENT_DB_CORRUPT, mPath);
        mErrorHandler.onCorruption(this);
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
     * Begins a transaction in EXCLUSIVE mode.
     * <p>
     * Transactions can be nested.
     * When the outer transaction is ended all of
     * the work done in that transaction and all of the nested transactions will be committed or
     * rolled back. The changes will be rolled back if any transaction is ended without being
     * marked as clean (by calling setTransactionSuccessful). Otherwise they will be committed.
     * </p>
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
        beginTransaction(null /* transactionStatusCallback */, true);
    }

    /**
     * Begins a transaction in IMMEDIATE mode. Transactions can be nested. When
     * the outer transaction is ended all of the work done in that transaction
     * and all of the nested transactions will be committed or rolled back. The
     * changes will be rolled back if any transaction is ended without being
     * marked as clean (by calling setTransactionSuccessful). Otherwise they
     * will be committed.
     * <p>
     * Here is the standard idiom for transactions:
     *
     * <pre>
     *   db.beginTransactionNonExclusive();
     *   try {
     *     ...
     *     db.setTransactionSuccessful();
     *   } finally {
     *     db.endTransaction();
     *   }
     * </pre>
     */
    public void beginTransactionNonExclusive() {
        beginTransaction(null /* transactionStatusCallback */, false);
    }

    /**
     * Begins a transaction in EXCLUSIVE mode.
     * <p>
     * Transactions can be nested.
     * When the outer transaction is ended all of
     * the work done in that transaction and all of the nested transactions will be committed or
     * rolled back. The changes will be rolled back if any transaction is ended without being
     * marked as clean (by calling setTransactionSuccessful). Otherwise they will be committed.
     * </p>
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
     *
     * @param transactionListener listener that should be notified when the transaction begins,
     * commits, or is rolled back, either explicitly or by a call to
     * {@link #yieldIfContendedSafely}.
     */
    public void beginTransactionWithListener(SQLiteTransactionListener transactionListener) {
        beginTransaction(transactionListener, true);
    }

    /**
     * Begins a transaction in IMMEDIATE mode. Transactions can be nested. When
     * the outer transaction is ended all of the work done in that transaction
     * and all of the nested transactions will be committed or rolled back. The
     * changes will be rolled back if any transaction is ended without being
     * marked as clean (by calling setTransactionSuccessful). Otherwise they
     * will be committed.
     * <p>
     * Here is the standard idiom for transactions:
     *
     * <pre>
     *   db.beginTransactionWithListenerNonExclusive(listener);
     *   try {
     *     ...
     *     db.setTransactionSuccessful();
     *   } finally {
     *     db.endTransaction();
     *   }
     * </pre>
     *
     * @param transactionListener listener that should be notified when the
     *            transaction begins, commits, or is rolled back, either
     *            explicitly or by a call to {@link #yieldIfContendedSafely}.
     */
    public void beginTransactionWithListenerNonExclusive(
            SQLiteTransactionListener transactionListener) {
        beginTransaction(transactionListener, false);
    }

    private void beginTransaction(SQLiteTransactionListener transactionListener,
            boolean exclusive) {
        verifyDbIsOpen();
        lockForced();
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
            if (exclusive) {
                execSQL("BEGIN EXCLUSIVE;");
            } else {
                execSQL("BEGIN IMMEDIATE;");
            }
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
        verifyLockOwner();
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
                // if write-ahead logging is used, we have to take care of checkpoint.
                // TODO: should applications be given the flexibility of choosing when to
                // trigger checkpoint?
                // for now, do checkpoint after every COMMIT because that is the fastest
                // way to guarantee that readers will see latest data.
                // but this is the slowest way to run sqlite with in write-ahead logging mode.
                if (this.mConnectionPool != null) {
                    execSQL("PRAGMA wal_checkpoint;");
                    if (SQLiteDebug.DEBUG_SQL_STATEMENTS) {
                        Log.i(TAG, "PRAGMA wal_Checkpoint done");
                    }
                }
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
        verifyDbIsOpen();
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
        return openDatabase(path, factory, flags, new DefaultDatabaseErrorHandler());
    }

    /**
     * Open the database according to the flags {@link #OPEN_READWRITE}
     * {@link #OPEN_READONLY} {@link #CREATE_IF_NECESSARY} and/or {@link #NO_LOCALIZED_COLLATORS}.
     *
     * <p>Sets the locale of the database to the  the system's current locale.
     * Call {@link #setLocale} if you would like something else.</p>
     *
     * <p>Accepts input param: a concrete instance of {@link DatabaseErrorHandler} to be
     * used to handle corruption when sqlite reports database corruption.</p>
     *
     * @param path to database file to open and/or create
     * @param factory an optional factory class that is called to instantiate a
     *            cursor when query is called, or null for default
     * @param flags to control database access mode
     * @param errorHandler the {@link DatabaseErrorHandler} obj to be used to handle corruption
     * when sqlite reports database corruption
     * @return the newly opened database
     * @throws SQLiteException if the database cannot be opened
     */
    public static SQLiteDatabase openDatabase(String path, CursorFactory factory, int flags,
            DatabaseErrorHandler errorHandler) {
        SQLiteDatabase sqliteDatabase = openDatabase(path, factory, flags, errorHandler,
                (short) 0 /* the main connection handle */);

        // set sqlite pagesize to mBlockSize
        if (sBlockSize == 0) {
            // TODO: "/data" should be a static final String constant somewhere. it is hardcoded
            // in several places right now.
            sBlockSize = new StatFs("/data").getBlockSize();
        }
        sqliteDatabase.setPageSize(sBlockSize);
        sqliteDatabase.setJournalMode(path, "TRUNCATE");

        // add this database to the list of databases opened in this process
        ActiveDatabases.addActiveDatabase(sqliteDatabase);
        return sqliteDatabase;
    }

    private static SQLiteDatabase openDatabase(String path, CursorFactory factory, int flags,
            DatabaseErrorHandler errorHandler, short connectionNum) {
        SQLiteDatabase db = new SQLiteDatabase(path, factory, flags, errorHandler, connectionNum);
        try {
            // Open the database.
            db.dbopen(path, flags);
            db.setLocale(Locale.getDefault());
            if (SQLiteDebug.DEBUG_SQL_STATEMENTS) {
                db.enableSqlTracing(path, connectionNum);
            }
            if (SQLiteDebug.DEBUG_SQL_TIME) {
                db.enableSqlProfiling(path, connectionNum);
            }
            return db;
        } catch (SQLiteDatabaseCorruptException e) {
            db.mErrorHandler.onCorruption(db);
            return SQLiteDatabase.openDatabase(path, factory, flags, errorHandler);
        } catch (SQLiteException e) {
            Log.e(TAG, "Failed to open the database. closing it.", e);
            db.close();
            throw e;
        }
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
     * Equivalent to openDatabase(path, factory, CREATE_IF_NECESSARY, errorHandler).
     */
    public static SQLiteDatabase openOrCreateDatabase(String path, CursorFactory factory,
            DatabaseErrorHandler errorHandler) {
        return openDatabase(path, factory, CREATE_IF_NECESSARY, errorHandler);
    }

    private void setJournalMode(final String dbPath, final String mode) {
        // journal mode can be set only for non-memory databases
        if (!dbPath.equalsIgnoreCase(MEMORY_DB_PATH)) {
            String s = DatabaseUtils.stringForQuery(this, "PRAGMA journal_mode=" + mode, null);
            if (!s.equalsIgnoreCase(mode)) {
                Log.e(TAG, "setting journal_mode to " + mode + " failed for db: " + dbPath +
                        " (on pragma set journal_mode, sqlite returned:" + s);
            }
        }
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
        return openDatabase(MEMORY_DB_PATH, factory, CREATE_IF_NECESSARY);
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
            // finalize ALL statements queued up so far
            closePendingStatements();
            // close this database instance - regardless of its reference count value
            dbclose();
            if (mConnectionPool != null) {
                mConnectionPool.close();
            }
        } finally {
            unlock();
        }
    }

    private void closeClosable() {
        /* deallocate all compiled SQL statement objects from mCompiledQueries cache.
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
        return ((Long) DatabaseUtils.longForQuery(this, "PRAGMA user_version;", null)).intValue();
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
        long pageCount = DatabaseUtils.longForQuery(this, "PRAGMA max_page_count;", null);
        return pageCount * getPageSize();
    }

    /**
     * Sets the maximum size the database will grow to. The maximum size cannot
     * be set below the current size.
     *
     * @param numBytes the maximum database size, in bytes
     * @return the new maximum database size
     */
    public long setMaximumSize(long numBytes) {
        long pageSize = getPageSize();
        long numPages = numBytes / pageSize;
        // If numBytes isn't a multiple of pageSize, bump up a page
        if ((numBytes % pageSize) != 0) {
            numPages++;
        }
        long newPageCount = DatabaseUtils.longForQuery(this, "PRAGMA max_page_count = " + numPages,
                null);
        return newPageCount * pageSize;
    }

    /**
     * Returns the current database page size, in bytes.
     *
     * @return the database page size, in bytes
     */
    public long getPageSize() {
        return DatabaseUtils.longForQuery(this, "PRAGMA page_size;", null);
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
                    + " FROM " + table + " WHERE _id=?)", new String[] {String.valueOf(rowId)});
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
        verifyDbIsOpen();
        String prefixSql = sql.trim().substring(0, 6);
        SQLiteDatabase db = this;
        // get a pooled database connection handle to use, if this is a query
        if (prefixSql.equalsIgnoreCase("SELECT")) {
            db = getDbConnection(sql);
        }
        db.lock();
        try {
            return new SQLiteStatement(db, sql);
        } finally {
            releaseDbConnection(db);
            db.unlock();
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
        verifyDbIsOpen();
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
        verifyDbIsOpen();
        BlockGuard.getThreadPolicy().onReadFromDisk();
        long timeStart = 0;

        if (Config.LOGV || mSlowQueryThreshold != -1) {
            timeStart = System.currentTimeMillis();
        }

        SQLiteDatabase db = getDbConnection(sql);
        SQLiteCursorDriver driver = new SQLiteDirectCursorDriver(db, sql, editTable);

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
            releaseDbConnection(db);
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
     * @param nullColumnHack SQL doesn't allow inserting a completely empty row,
     *            so if initialValues is empty this column will explicitly be
     *            assigned a NULL value
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
     * @param nullColumnHack SQL doesn't allow inserting a completely empty row,
     *            so if initialValues is empty this column will explicitly be
     *            assigned a NULL value
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
     * @param nullColumnHack SQL doesn't allow inserting a completely empty row,
     *            so if initialValues is empty this row will explicitly be
     *            assigned a NULL value
     * @param initialValues this map contains the initial column values for
     *   the row. The key
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
     * @param nullColumnHack SQL doesn't allow inserting a completely empty row,
     *            so if initialValues is empty this row will explicitly be
     *            assigned a NULL value
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
     * @param nullColumnHack SQL doesn't allow inserting a completely empty row,
     *            so if initialValues is empty this column will explicitly be
     *            assigned a NULL value
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
        verifyDbIsOpen();
        BlockGuard.getThreadPolicy().onWriteToDisk();

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
        verifyDbIsOpen();
        BlockGuard.getThreadPolicy().onWriteToDisk();
        lock();
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

        verifyDbIsOpen();
        lock();
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
     * Execute a single SQL statement that is NOT a SELECT
     * or any other SQL statement that returns data.
     * <p>
     * Use of this method is discouraged as it doesn't perform well when issuing the same SQL
     * statement repeatedly (see {@link #compileStatement(String)} to prepare statements for
     * repeated use), and it has no means to return any data (such as the number of affected rows).
     * Instead, you're encouraged to use {@link #insert(String, String, ContentValues)},
     * {@link #update(String, ContentValues, String, String[])}, et al, when possible.
     * </p>
     *
     * @param sql the SQL statement to be executed. Multiple statements separated by semicolons are
     * not supported.
     * @throws SQLException If the SQL string is invalid for some reason
     */
    public void execSQL(String sql) throws SQLException {
        sql = sql.trim();
        String prefix = sql.substring(0, 6);
        if (prefix.equalsIgnoreCase("ATTACH")) {
            disableWriteAheadLogging();
        }
        verifyDbIsOpen();
        BlockGuard.getThreadPolicy().onWriteToDisk();
        long timeStart = SystemClock.uptimeMillis();
        lock();
        logTimeStat(mLastSqlStatement, timeStart, GET_LOCK_LOG_PREFIX);
        try {
            closePendingStatements();
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
     * Execute a single SQL statement that is NOT a SELECT/INSERT/UPDATE/DELETE.
     * <p>
     * For INSERT statements, use any of the following instead.
     * <ul>
     *   <li>{@link #insert(String, String, ContentValues)}</li>
     *   <li>{@link #insertOrThrow(String, String, ContentValues)}</li>
     *   <li>{@link #insertWithOnConflict(String, String, ContentValues, int)}</li>
     * </ul>
     * <p>
     * For UPDATE statements, use any of the following instead.
     * <ul>
     *   <li>{@link #update(String, ContentValues, String, String[])}</li>
     *   <li>{@link #updateWithOnConflict(String, ContentValues, String, String[], int)}</li>
     * </ul>
     * <p>
     * For DELETE statements, use any of the following instead.
     * <ul>
     *   <li>{@link #delete(String, String, String[])}</li>
     * </ul>
     * <p>
     * For example, the following are good candidates for using this method:
     * <ul>
     *   <li>ALTER TABLE</li>
     *   <li>CREATE or DROP table / trigger / view / index / virtual table</li>
     *   <li>REINDEX</li>
     *   <li>RELEASE</li>
     *   <li>SAVEPOINT</li>
     *   <li>PRAGMA that returns no data</li>
     * </ul>
     * </p>
     *
     * @param sql the SQL statement to be executed. Multiple statements separated by semicolons are
     * not supported.
     * @param bindArgs only byte[], String, Long and Double are supported in bindArgs.
     * @throws SQLException If the SQL string is invalid for some reason
     */
    public void execSQL(String sql, Object[] bindArgs) throws SQLException {
        BlockGuard.getThreadPolicy().onWriteToDisk();
        if (bindArgs == null) {
            throw new IllegalArgumentException("Empty bindArgs");
        }
        verifyDbIsOpen();
        long timeStart = SystemClock.uptimeMillis();
        lock();
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
     * Private constructor.
     *
     * @param path The full path to the database
     * @param factory The factory to use when creating cursors, may be NULL.
     * @param flags 0 or {@link #NO_LOCALIZED_COLLATORS}.  If the database file already
     *              exists, mFlags will be updated appropriately.
     * @param errorHandler The {@link DatabaseErrorHandler} to be used when sqlite reports database
     * corruption. may be NULL.
     * @param connectionNum 0 for main database connection handle. 1..N for pooled database
     * connection handles.
     */
    private SQLiteDatabase(String path, CursorFactory factory, int flags,
            DatabaseErrorHandler errorHandler, short connectionNum) {
        if (path == null) {
            throw new IllegalArgumentException("path should not be null");
        }
        mFlags = flags;
        mPath = path;
        mSlowQueryThreshold = SystemProperties.getInt(LOG_SLOW_QUERIES_PROPERTY, -1);
        mStackTrace = new DatabaseObjectNotClosedException().fillInStackTrace();
        mFactory = factory;
        mPrograms = new WeakHashMap<SQLiteClosable,Object>();
        // Set the DatabaseErrorHandler to be used when SQLite reports corruption.
        // If the caller sets errorHandler = null, then use default errorhandler.
        mErrorHandler = (errorHandler == null) ? new DefaultDatabaseErrorHandler() : errorHandler;
        mConnectionNum = connectionNum;
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

        String blockingPackage = ActivityThread.currentPackageName();
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

    /* package */ void verifyDbIsOpen() {
        if (!isOpen()) {
            throw new IllegalStateException("database " + getPath() + " already closed");
        }
    }

    /* package */ void verifyLockOwner() {
        verifyDbIsOpen();
        if (mLockingEnabled && !isDbLockedByCurrentThread()) {
            throw new IllegalStateException("Don't have database lock!");
        }
    }

    /*
     * ============================================================================
     *
     *       The following methods deal with compiled-sql cache
     * ============================================================================
     */
    /**
     * Adds the given SQL and its compiled-statement-id-returned-by-sqlite to the
     * cache of compiledQueries attached to 'this'.
     * <p>
     * If there is already a {@link SQLiteCompiledSql} in compiledQueries for the given SQL,
     * the new {@link SQLiteCompiledSql} object is NOT inserted into the cache (i.e.,the current
     * mapping is NOT replaced with the new mapping).
     */
    /* package */ void addToCompiledQueries(String sql, SQLiteCompiledSql compiledStatement) {
        SQLiteCompiledSql compiledSql = null;
        synchronized(mCompiledQueries) {
            // don't insert the new mapping if a mapping already exists
            compiledSql = mCompiledQueries.get(sql);
            if (compiledSql != null) {
                return;
            }

            if (mCompiledQueries.size() == mMaxSqlCacheSize) {
                /*
                 * cache size of {@link #mMaxSqlCacheSize} is not enough for this app.
                 * log a warning.
                 * chances are it is NOT using ? for bindargs - or cachesize is too small.
                 */
                if (++mCacheFullWarnings == MAX_WARNINGS_ON_CACHESIZE_CONDITION) {
                    Log.w(TAG, "Reached MAX size for compiled-sql statement cache for database " +
                            getPath() + ". Consider increasing cachesize.");
                }
            } 
            /* add the given SQLiteCompiledSql compiledStatement to cache.
             * no need to worry about the cache size - because {@link #mCompiledQueries}
             * self-limits its size to {@link #mMaxSqlCacheSize}.
             */
            mCompiledQueries.put(sql, compiledStatement);
            if (SQLiteDebug.DEBUG_SQL_CACHE) {
                Log.v(TAG, "|adding_sql_to_cache|" + getPath() + "|" +
                        mCompiledQueries.size() + "|" + sql);
            }
        }
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
     * From the compiledQueries cache, returns the compiled-statement-id for the given SQL.
     * Returns null, if not found in the cache.
     */
    /* package */ SQLiteCompiledSql getCompiledStatementForSql(String sql) {
        SQLiteCompiledSql compiledStatement = null;
        boolean cacheHit;
        synchronized(mCompiledQueries) {
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
                    "|" + cacheHit + "|" + sql);
        }
        return compiledStatement;
    }

    /**
     * Sets the maximum size of the prepared-statement cache for this database.
     * (size of the cache = number of compiled-sql-statements stored in the cache).
     *<p>
     * Maximum cache size can ONLY be increased from its current size (default = 10).
     * If this method is called with smaller size than the current maximum value,
     * then IllegalStateException is thrown.
     *<p>
     * This method is thread-safe.
     *
     * @param cacheSize the size of the cache. can be (0 to {@link #MAX_SQL_CACHE_SIZE})
     * @throws IllegalStateException if input cacheSize > {@link #MAX_SQL_CACHE_SIZE} or
     * > the value set with previous setMaxSqlCacheSize() call.
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

    /* package */ boolean isSqlInStatementCache(String sql) {
        synchronized (mCompiledQueries) {
            return mCompiledQueries.containsKey(sql);
        }
    }

    /* package */ void finalizeStatementLater(int id) {
        if (!isOpen()) {
            // database already closed. this statement will already have been finalized.
            return;
        }
        synchronized(mClosedStatementIds) {
            if (mClosedStatementIds.contains(id)) {
                // this statement id is already queued up for finalization.
                return;
            }
            mClosedStatementIds.add(id);
        }
    }

    /**
     * public visibility only for testing. otherwise, package visibility is sufficient
     * @hide
     */
    public void closePendingStatements() {
        if (!isOpen()) {
            // since this database is already closed, no need to finalize anything.
            mClosedStatementIds.clear();
            return;
        }
        verifyLockOwner();
        /* to minimize synchronization on mClosedStatementIds, make a copy of the list */
        ArrayList<Integer> list = new ArrayList<Integer>(mClosedStatementIds.size());
        synchronized(mClosedStatementIds) {
            list.addAll(mClosedStatementIds);
            mClosedStatementIds.clear();
        }
        // finalize all the statements from the copied list
        int size = list.size();
        for (int i = 0; i < size; i++) {
            native_finalize(list.get(i));
        }
    }

    /**
     * for testing only
     * @hide
     */
    public ArrayList<Integer> getQueuedUpStmtList() {
        return mClosedStatementIds;
    }

    /**
     * This method enables parallel execution of queries from multiple threads on the same database.
     * It does this by opening multiple handles to the database and using a different
     * database handle for each query.
     * <p>
     * If a transaction is in progress on one connection handle and say, a table is updated in the
     * transaction, then query on the same table on another connection handle will block for the
     * transaction to complete. But this method enables such queries to execute by having them
     * return old version of the data from the table. Most often it is the data that existed in the
     * table prior to the above transaction updates on that table.
     * <p>
     * Maximum number of simultaneous handles used to execute queries in parallel is
     * dependent upon the device memory and possibly other properties.
     * <p>
     * After calling this method, execution of queries in parallel is enabled as long as this
     * database handle is open. To disable execution of queries in parallel, database should
     * be closed and reopened.
     * <p>
     * If a query is part of a transaction, then it is executed on the same database handle the
     * transaction was begun.
     * <p>
     * If the database has any attached databases, then execution of queries in paralel is NOT
     * possible. In such cases, a message is printed to logcat and false is returned.
     * <p>
     * This feature is not available for :memory: databases. In such cases,
     * a message is printed to logcat and false is returned.
     * <p>
     * A typical way to use this method is the following:
     * <pre>
     *     SQLiteDatabase db = SQLiteDatabase.openDatabase("db_filename", cursorFactory,
     *             CREATE_IF_NECESSARY, myDatabaseErrorHandler);
     *     db.enableWriteAheadLogging();
     * </pre>
     * <p>
     * Writers should use {@link #beginTransactionNonExclusive()} or
     * {@link #beginTransactionWithListenerNonExclusive(SQLiteTransactionListener)}
     * to start a trsnsaction.
     * Non-exclusive mode allows database file to be in readable by threads executing queries.
     * </p>
     *
     * @return true if write-ahead-logging is set. false otherwise
     */
    public synchronized boolean enableWriteAheadLogging() {
        if (mPath.equalsIgnoreCase(MEMORY_DB_PATH)) {
            Log.i(TAG, "can't enable WAL for memory databases.");
            return false;
        }

        // make sure this database has NO attached databases because sqlite's write-ahead-logging
        // doesn't work for databases with attached databases
        if (getAttachedDbs().size() > 1) {
            Log.i(TAG, "this database: " + mPath + " has attached databases. can't  enable WAL.");
            return false;
        }
        if (mConnectionPool == null) {
            mConnectionPool = new DatabaseConnectionPool(this);
            setJournalMode(mPath, "WAL");
        }
        return true;
    }

    private synchronized void disableWriteAheadLogging() {
        if (mConnectionPool == null) {
            return;
        }
        mConnectionPool.close();
        mConnectionPool = null;
    }

    /**
     * Sets the database connection handle pool size to the given value.
     * Database connection handle pool is enabled when the app calls
     * {@link #enableWriteAheadLogging()}.
     * <p>
     * The default connection handle pool is set by the system by taking into account various
     * aspects of the device, such as memory, number of cores etc. It is recommended that
     * applications use the default pool size set by the system.
     *
     * @param size the value the connection handle pool size should be set to.
     */
    public synchronized void setConnectionPoolSize(int size) {
        if (mConnectionPool == null) {
            throw new IllegalStateException("connection pool not enabled");
        }
        int i = mConnectionPool.getMaxPoolSize();
        if (size < i) {
            throw new IllegalArgumentException(
                    "cannot set max pool size to a value less than the current max value(=" +
                    i + ")");
        }
        mConnectionPool.setMaxPoolSize(size);
    }

    /* package */ SQLiteDatabase createPoolConnection(short connectionNum) {
        return openDatabase(mPath, mFactory, mFlags, mErrorHandler, connectionNum);
    }

    private boolean isPooledConnection() {
        return this.mConnectionNum > 0;
    }

    private SQLiteDatabase getDbConnection(String sql) {
        verifyDbIsOpen();

        // use the current connection handle if
        // 1. this is a pooled connection handle
        // 2. OR, if this thread is in a transaction
        // 3. OR, if there is NO connection handle pool setup
        SQLiteDatabase db = null;
        if (isPooledConnection() ||
                (inTransaction() && mLock.isHeldByCurrentThread()) ||
                (this.mConnectionPool == null)) {
            db = this;
        } else {
            // get a connection handle from the pool
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                assert mConnectionPool != null;
            }
            db = mConnectionPool.get(sql);
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "getDbConnection threadid = " + Thread.currentThread().getId() +
                    ", request on # " + mConnectionNum +
                    ", assigned # " + db.mConnectionNum + ", " + getPath());
        }
        return db;
    }

    private void releaseDbConnection(SQLiteDatabase db) {
        // ignore this release call if
        // 1. the database is closed
        // 2. OR, if db is NOT a pooled connection handle
        // 3. OR, if the database being released is same as 'this' (this condition means
        //     that we should always be releasing a pooled connection handle by calling this method
        //     from the 'main' connection handle
        if (!isOpen() || !db.isPooledConnection() || (db == this)) {
            return;
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            assert isPooledConnection();
            assert mConnectionPool != null;
            Log.d(TAG, "releaseDbConnection threadid = " + Thread.currentThread().getId() +
                    ", releasing # " + db.mConnectionNum + ", " + getPath());
        }
        mConnectionPool.release(db);
    }

    static class ActiveDatabases {
        private static final ActiveDatabases activeDatabases = new ActiveDatabases();
        private HashSet<WeakReference<SQLiteDatabase>> mActiveDatabases =
                new HashSet<WeakReference<SQLiteDatabase>>();
        private ActiveDatabases() {} // disable instantiation of this class
        static ActiveDatabases getInstance() {
            return activeDatabases;
        }
        private static void addActiveDatabase(SQLiteDatabase sqliteDatabase) {
            activeDatabases.mActiveDatabases.add(new WeakReference<SQLiteDatabase>(sqliteDatabase));
        }
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

            try {
                // get SQLITE_DBSTATUS_LOOKASIDE_USED for the db
                int lookasideUsed = db.native_getDbLookaside();

                // get the lastnode of the dbname
                String path = db.getPath();
                int indx = path.lastIndexOf("/");
                String lastnode = path.substring((indx != -1) ? ++indx : 0);

                // get list of attached dbs and for each db, get its size and pagesize
                ArrayList<Pair<String, String>> attachedDbs = db.getAttachedDbs();
                if (attachedDbs == null) {
                    continue;
                }
                for (int i = 0; i < attachedDbs.size(); i++) {
                    Pair<String, String> p = attachedDbs.get(i);
                    long pageCount = DatabaseUtils.longForQuery(db, "PRAGMA " + p.first
                            + ".page_count;", null);

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
                                lookasideUsed, db.mNumCacheHits, db.mNumCacheMisses,
                                db.mCompiledQueries.size()));
                    }
                }
                // if there are pooled connections, return the cache stats for them also.
                if (db.mConnectionPool != null) {
                    for (SQLiteDatabase pDb : db.mConnectionPool.getConnectionList()) {
                        dbStatsList.add(new DbStats("(pooled # " + pDb.mConnectionNum + ") "
                                + lastnode, 0, 0, 0, pDb.mNumCacheHits, pDb.mNumCacheMisses,
                                pDb.mCompiledQueries.size()));
                    }
                }
            } catch (SQLiteException e) {
                // ignore. we don't care about exceptions when we are taking adb
                // bugreport!
            }
        }
        return dbStatsList;
    }

    /**
     * Returns list of full pathnames of all attached databases including the main database
     * by executing 'pragma database_list' on the database.
     *
     * @return ArrayList of pairs of (database name, database file path) or null if the database
     * is not open.
     */
    public ArrayList<Pair<String, String>> getAttachedDbs() {
        if (!isOpen()) {
            return null;
        }
        ArrayList<Pair<String, String>> attachedDbs = new ArrayList<Pair<String, String>>();
        Cursor c = null;
        try {
            c = rawQuery("pragma database_list;", null);
            while (c.moveToNext()) {
                // sqlite returns a row for each database in the returned list of databases.
                //   in each row,
                //       1st column is the database name such as main, or the database
                //                              name specified on the "ATTACH" command
                //       2nd column is the database file path.
                attachedDbs.add(new Pair<String, String>(c.getString(1), c.getString(2)));
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return attachedDbs;
    }

    /**
     * Runs 'pragma integrity_check' on the given database (and all the attached databases)
     * and returns true if the given database (and all its attached databases) pass integrity_check,
     * false otherwise.
     *<p>
     * If the result is false, then this method logs the errors reported by the integrity_check
     * command execution.
     *<p>
     * Note that 'pragma integrity_check' on a database can take a long time.
     *
     * @return true if the given database (and all its attached databases) pass integrity_check,
     * false otherwise.
     */
    public boolean isDatabaseIntegrityOk() {
        verifyDbIsOpen();
        ArrayList<Pair<String, String>> attachedDbs = getAttachedDbs();
        if (attachedDbs == null) {
            throw new IllegalStateException("databaselist for: " + getPath() + " couldn't " +
                    "be retrieved. probably because the database is closed");
        }
        boolean isDatabaseCorrupt = false;
        for (int i = 0; i < attachedDbs.size(); i++) {
            Pair<String, String> p = attachedDbs.get(i);
            SQLiteStatement prog = null;
            try {
                prog = compileStatement("PRAGMA " + p.first + ".integrity_check(1);");
                String rslt = prog.simpleQueryForString();
                if (!rslt.equalsIgnoreCase("ok")) {
                    // integrity_checker failed on main or attached databases
                    isDatabaseCorrupt = true;
                    Log.e(TAG, "PRAGMA integrity_check on " + p.second + " returned: " + rslt);
                }
            } finally {
                if (prog != null) prog.close();
            }
        }
        return isDatabaseCorrupt;
    }

    /**
     * Native call to open the database.
     *
     * @param path The full path to the database
     */
    private native void dbopen(String path, int flags);

    /**
     * Native call to setup tracing of all SQL statements
     *
     * @param path the full path to the database
     * @param connectionNum connection number: 0 - N, where the main database
     *            connection handle is numbered 0 and the connection handles in the connection
     *            pool are numbered 1..N.
     */
    private native void enableSqlTracing(String path, short connectionNum);

    /**
     * Native call to setup profiling of all SQL statements.
     * currently, sqlite's profiling = printing of execution-time
     * (wall-clock time) of each of the SQL statements, as they
     * are executed.
     *
     * @param path the full path to the database
     * @param connectionNum connection number: 0 - N, where the main database
     *            connection handle is numbered 0 and the connection handles in the connection
     *            pool are numbered 1..N.
     */
    private native void enableSqlProfiling(String path, short connectionNum);

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

    /**
     * finalizes the given statement id.
     *
     * @param statementId statement to be finzlied by sqlite
     */
    private final native void native_finalize(int statementId);
}
