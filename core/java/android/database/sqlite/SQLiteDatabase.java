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

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.DatabaseUtils;
import android.database.DefaultDatabaseErrorHandler;
import android.database.SQLException;
import android.database.sqlite.SQLiteDebug.DbStats;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Looper;
import android.os.OperationCanceledException;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Log;
import android.util.Pair;
import android.util.Printer;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import dalvik.annotation.optimization.NeverCompile;
import dalvik.system.CloseGuard;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

/**
 * Exposes methods to manage a SQLite database.
 *
 * <p>
 * SQLiteDatabase has methods to create, delete, execute SQL commands, and
 * perform other common database management tasks.
 * </p><p>
 * See the Notepad sample application in the SDK for an example of creating
 * and managing a database.
 * </p><p>
 * Database names must be unique within an application, not across all applications.
 * </p>
 *
 * <h3>Localized Collation - ORDER BY</h3>
 * <p>
 * In addition to SQLite's default <code>BINARY</code> collator, Android supplies
 * two more, <code>LOCALIZED</code>, which changes with the system's current locale,
 * and <code>UNICODE</code>, which is the Unicode Collation Algorithm and not tailored
 * to the current locale.
 * </p>
 */
public final class SQLiteDatabase extends SQLiteClosable {
    private static final String TAG = "SQLiteDatabase";

    private static final int EVENT_DB_CORRUPT = 75004;

    // By default idle connections are not closed
    private static final boolean DEBUG_CLOSE_IDLE_CONNECTIONS = SystemProperties
            .getBoolean("persist.debug.sqlite.close_idle_connections", false);

    // Stores reference to all databases opened in the current process.
    // (The referent Object is not used at this time.)
    // INVARIANT: Guarded by sActiveDatabases.
    @GuardedBy("sActiveDatabases")
    private static WeakHashMap<SQLiteDatabase, Object> sActiveDatabases = new WeakHashMap<>();

    // Tracks which database files are currently open.  If a database file is opened more than
    // once at any given moment, the associated databases are marked as "concurrent".
    @GuardedBy("sActiveDatabases")
    private static final OpenTracker sOpenTracker = new OpenTracker();

    // Thread-local for database sessions that belong to this database.
    // Each thread has its own database session.
    // INVARIANT: Immutable.
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private final ThreadLocal<SQLiteSession> mThreadSession = ThreadLocal
            .withInitial(this::createSession);

    // The optional factory to use when creating new Cursors.  May be null.
    // INVARIANT: Immutable.
    private final CursorFactory mCursorFactory;

    // Error handler to be used when SQLite returns corruption errors.
    // INVARIANT: Immutable.
    private final DatabaseErrorHandler mErrorHandler;

    // Shared database state lock.
    // This lock guards all of the shared state of the database, such as its
    // configuration, whether it is open or closed, and so on.  This lock should
    // be held for as little time as possible.
    //
    // The lock MUST NOT be held while attempting to acquire database connections or
    // while executing SQL statements on behalf of the client as it can lead to deadlock.
    //
    // It is ok to hold the lock while reconfiguring the connection pool or dumping
    // statistics because those operations are non-reentrant and do not try to acquire
    // connections that might be held by other threads.
    //
    // Basic rule: grab the lock, access or modify global state, release the lock, then
    // do the required SQL work.
    private final Object mLock = new Object();

    // Warns if the database is finalized without being closed properly.
    // INVARIANT: Guarded by mLock.
    private final CloseGuard mCloseGuardLocked = CloseGuard.get();

    // The database configuration.
    // INVARIANT: Guarded by mLock.
    @UnsupportedAppUsage
    private final SQLiteDatabaseConfiguration mConfigurationLocked;

    // The connection pool for the database, null when closed.
    // The pool itself is thread-safe, but the reference to it can only be acquired
    // when the lock is held.
    // INVARIANT: Guarded by mLock.
    @UnsupportedAppUsage
    private SQLiteConnectionPool mConnectionPoolLocked;

    // True if the database has attached databases.
    // INVARIANT: Guarded by mLock.
    private boolean mHasAttachedDbsLocked;

    /**
     * When a constraint violation occurs, an immediate ROLLBACK occurs,
     * thus ending the current transaction, and the command aborts with a
     * return code of SQLITE_CONSTRAINT. If no transaction is active
     * (other than the implied transaction that is created on every command)
     * then this algorithm works the same as ABORT.
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
     * This behavior might change in a future release.
     */
    public static final int CONFLICT_REPLACE = 5;

    /**
     * Use the following when no conflict action is specified.
     */
    public static final int CONFLICT_NONE = 0;

    /** {@hide} */
    @UnsupportedAppUsage
    public static final String[] CONFLICT_VALUES = new String[]
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
     * are at most a few dozen bytes in length, cautious application developers may
     * want to reduce this parameter to something in the range of a few hundred
     * if they know that external users are able to generate arbitrary patterns.
     */
    public static final int SQLITE_MAX_LIKE_PATTERN_LENGTH = 50000;

    /**
     * Open flag: Flag for {@link #openDatabase} to open the database for reading and writing.
     * If the disk is full, this may fail even before you actually write anything.
     *
     * {@more} Note that the value of this flag is 0, so it is the default.
     */
    public static final int OPEN_READWRITE = 0x00000000;          // update native code if changing

    /**
     * Open flag: Flag for {@link #openDatabase} to open the database for reading only.
     * This is the only reliable way to open a database if the disk may be full.
     */
    public static final int OPEN_READONLY = 0x00000001;           // update native code if changing

    private static final int OPEN_READ_MASK = 0x00000001;         // update native code if changing

    /**
     * Open flag: Flag for {@link #openDatabase} to open the database without support for
     * localized collators.
     *
     * {@more} This causes the collator <code>LOCALIZED</code> not to be created.
     * You must be consistent when using this flag to use the setting the database was
     * created with.  If this is set, {@link #setLocale} will do nothing.
     */
    public static final int NO_LOCALIZED_COLLATORS = 0x00000010;  // update native code if changing

    /**
     * Open flag: Flag for {@link #openDatabase} to create the database file if it does not
     * already exist.
     */
    public static final int CREATE_IF_NECESSARY = 0x10000000;     // update native code if changing

    /**
     * Open flag: Flag for {@link #openDatabase} to open the database file with
     * write-ahead logging enabled by default.  Using this flag is more efficient
     * than calling {@link #enableWriteAheadLogging}.
     *
     * Write-ahead logging cannot be used with read-only databases so the value of
     * this flag is ignored if the database is opened read-only.
     *
     * @see #enableWriteAheadLogging
     */
    public static final int ENABLE_WRITE_AHEAD_LOGGING = 0x20000000;


    // Note: The below value was only used on Android Pie.
    // public static final int DISABLE_COMPATIBILITY_WAL = 0x40000000;

    /**
     * Open flag: Flag for {@link #openDatabase} to enable the legacy Compatibility WAL when opening
     * database.
     *
     * @hide
     */
    public static final int ENABLE_LEGACY_COMPATIBILITY_WAL = 0x80000000;

    /**
     * Absolute max value that can be set by {@link #setMaxSqlCacheSize(int)}.
     *
     * Each prepared-statement is between 1K - 6K, depending on the complexity of the
     * SQL statement & schema.  A large SQL cache may use a significant amount of memory.
     */
    public static final int MAX_SQL_CACHE_SIZE = 100;

    /**
     * @hide
     */
    @StringDef(prefix = {"JOURNAL_MODE_"},
            value =
                    {
                            JOURNAL_MODE_WAL,
                            JOURNAL_MODE_PERSIST,
                            JOURNAL_MODE_TRUNCATE,
                            JOURNAL_MODE_MEMORY,
                            JOURNAL_MODE_DELETE,
                            JOURNAL_MODE_OFF,
                    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface JournalMode {}

    /**
     * The {@code WAL} journaling mode uses a write-ahead log instead of a rollback journal to
     * implement transactions. The WAL journaling mode is persistent; after being set it stays
     * in effect across multiple database connections and after closing and reopening the database.
     *
     * Performance Considerations:
     * This mode is recommended when the goal is to improve write performance or parallel read/write
     * performance. However, it is important to note that WAL introduces checkpoints which commit
     * all transactions that have not been synced to the database thus to maximize read performance
     * and lower checkpointing cost a small journal size is recommended. However, other modes such
     * as {@code DELETE} will not perform checkpoints, so it is a trade off that needs to be
     * considered as part of the decision of which journal mode to use.
     *
     * <p> See <a href=https://www.sqlite.org/pragma.html#pragma_journal_mode>here</a> for more
     * details.</p>
     */
    public static final String JOURNAL_MODE_WAL = "WAL";

    /**
     * The {@code PERSIST} journaling mode prevents the rollback journal from being deleted at the
     * end of each transaction. Instead, the header of the journal is overwritten with zeros.
     * This will prevent other database connections from rolling the journal back.
     *
     * This mode is useful as an optimization on platforms where deleting or truncating a file is
     * much more expensive than overwriting the first block of a file with zeros.
     *
     * <p> See <a href=https://www.sqlite.org/pragma.html#pragma_journal_mode>here</a> for more
     * details.</p>
     */
    public static final String JOURNAL_MODE_PERSIST = "PERSIST";

    /**
     * The {@code TRUNCATE} journaling mode commits transactions by truncating the rollback journal
     * to zero-length instead of deleting it. On many systems, truncating a file is much faster than
     * deleting the file since the containing directory does not need to be changed.
     *
     * <p> See <a href=https://www.sqlite.org/pragma.html#pragma_journal_mode>here</a> for more
     * details.</p>
     */
    public static final String JOURNAL_MODE_TRUNCATE = "TRUNCATE";

    /**
     * The {@code MEMORY} journaling mode stores the rollback journal in volatile RAM.
     * This saves disk I/O but at the expense of database safety and integrity. If the application
     * using SQLite crashes in the middle of a transaction when the MEMORY journaling mode is set,
     * then the database file will very likely go corrupt.
     *
     * <p> See <a href=https://www.sqlite.org/pragma.html#pragma_journal_mode>here</a> for more
     * details.</p>
     */
    public static final String JOURNAL_MODE_MEMORY = "MEMORY";

    /**
     * The {@code DELETE} journaling mode is the normal behavior. In the DELETE mode, the rollback
     * journal is deleted at the conclusion of each transaction.
     *
     * <p> See <a href=https://www.sqlite.org/pragma.html#pragma_journal_mode>here</a> for more
     * details.</p>
     */
    public static final String JOURNAL_MODE_DELETE = "DELETE";

    /**
     * The {@code OFF} journaling mode disables the rollback journal completely. No rollback journal
     * is ever created and hence there is never a rollback journal to delete. The OFF journaling
     * mode disables the atomic commit and rollback capabilities of SQLite. The ROLLBACK command
     * behaves in an undefined way thus applications must avoid using the ROLLBACK command.
     * If the application crashes in the middle of a transaction, then the database file will very
     * likely go corrupt.
     *
     * <p> See <a href=https://www.sqlite.org/pragma.html#pragma_journal_mode>here</a> for more
     * details.</p>
     */
    public static final String JOURNAL_MODE_OFF = "OFF";

    /**
     * @hide
     */
    @StringDef(prefix = {"SYNC_MODE_"},
            value =
                    {
                            SYNC_MODE_EXTRA,
                            SYNC_MODE_FULL,
                            SYNC_MODE_NORMAL,
                            SYNC_MODE_OFF,
                    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SyncMode {}

    /**
     * The {@code EXTRA} sync mode is like {@code FULL} sync mode with the addition that the
     * directory containing a rollback journal is synced after that journal is unlinked to commit a
     * transaction in {@code DELETE} journal mode.
     *
     * {@code EXTRA} provides additional durability if the commit is followed closely by a
     * power loss.
     *
     * <p> See <a href=https://www.sqlite.org/pragma.html#pragma_synchronous>here</a> for more
     * details.</p>
     */
    @SuppressLint("IntentName") public static final String SYNC_MODE_EXTRA = "EXTRA";

    /**
     * In {@code FULL} sync mode the SQLite database engine will use the xSync method of the VFS
     * to ensure that all content is safely written to the disk surface prior to continuing.
     * This ensures that an operating system crash or power failure will not corrupt the database.
     * {@code FULL} is very safe, but it is also slower.
     *
     * {@code FULL} is the most commonly used synchronous setting when not in WAL mode.
     *
     * <p> See <a href=https://www.sqlite.org/pragma.html#pragma_synchronous>here</a> for more
     * details.</p>
     */
    public static final String SYNC_MODE_FULL = "FULL";

    /**
     * The {@code NORMAL} sync mode, the SQLite database engine will still sync at the most critical
     * moments, but less often than in {@code FULL} mode. There is a very small chance that a
     * power failure at the wrong time could corrupt the database in {@code DELETE} journal mode on
     * an older filesystem.
     *
     * {@code WAL} journal mode is safe from corruption with {@code NORMAL} sync mode, and probably
     * {@code DELETE} sync mode is safe too on modern filesystems. WAL mode is always consistent
     * with {@code NORMAL} sync mode, but WAL mode does lose durability. A transaction committed in
     * WAL mode with {@code NORMAL} might roll back following a power loss or system crash.
     * Transactions are durable across application crashes regardless of the synchronous setting
     * or journal mode.
     *
     * The {@code NORMAL} sync mode is a good choice for most applications running in WAL mode.
     *
     * <p>Caveat: Even though this sync mode is safe Be careful when using {@code NORMAL} sync mode
     * when dealing with data dependencies between multiple databases, unless those databases use
     * the same durability or are somehow synced, there could be corruption.</p>
     *
     * <p> See <a href=https://www.sqlite.org/pragma.html#pragma_synchronous>here</a> for more
     * details.</p>
     */
    public static final String SYNC_MODE_NORMAL = "NORMAL";

    /**
     * In {@code OFF} sync mode SQLite continues without syncing as soon as it has handed data off
     * to the operating system. If the application running SQLite crashes, the data will be safe,
     * but the database might become corrupted if the operating system crashes or the computer loses
     * power before that data has been written to the disk surface. On the other hand, commits can
     * be orders of magnitude faster with synchronous {@code OFF}.
     *
     * <p> See <a href=https://www.sqlite.org/pragma.html#pragma_synchronous>here</a> for more
     * details.</p>
     */
    public static final String SYNC_MODE_OFF = "OFF";

    private SQLiteDatabase(@Nullable final String path, @Nullable final int openFlags,
            @Nullable CursorFactory cursorFactory, @Nullable DatabaseErrorHandler errorHandler,
            int lookasideSlotSize, int lookasideSlotCount, long idleConnectionTimeoutMs,
            @Nullable String journalMode, @Nullable String syncMode) {
        mCursorFactory = cursorFactory;
        mErrorHandler = errorHandler != null ? errorHandler : new DefaultDatabaseErrorHandler();
        mConfigurationLocked = new SQLiteDatabaseConfiguration(path, openFlags);
        mConfigurationLocked.lookasideSlotSize = lookasideSlotSize;
        mConfigurationLocked.lookasideSlotCount = lookasideSlotCount;

        // Disable lookaside allocator on low-RAM devices
        if (ActivityManager.isLowRamDeviceStatic()) {
            mConfigurationLocked.lookasideSlotCount = 0;
            mConfigurationLocked.lookasideSlotSize = 0;
        }
        long effectiveTimeoutMs = Long.MAX_VALUE;
        // Never close idle connections for in-memory databases
        if (!mConfigurationLocked.isInMemoryDb()) {
            // First, check app-specific value. Otherwise use defaults
            // -1 in idleConnectionTimeoutMs indicates unset value
            if (idleConnectionTimeoutMs >= 0) {
                effectiveTimeoutMs = idleConnectionTimeoutMs;
            } else if (DEBUG_CLOSE_IDLE_CONNECTIONS) {
                effectiveTimeoutMs = SQLiteGlobal.getIdleConnectionTimeout();
            }
        }
        mConfigurationLocked.idleConnectionTimeoutMs = effectiveTimeoutMs;
        if (SQLiteCompatibilityWalFlags.isLegacyCompatibilityWalEnabled()) {
            mConfigurationLocked.openFlags |= ENABLE_LEGACY_COMPATIBILITY_WAL;
        }
        mConfigurationLocked.journalMode = journalMode;
        mConfigurationLocked.syncMode = syncMode;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            dispose(true);
        } finally {
            super.finalize();
        }
    }

    @Override
    protected void onAllReferencesReleased() {
        dispose(false);
    }

    private void dispose(boolean finalized) {
        final SQLiteConnectionPool pool;
        final String path;
        synchronized (mLock) {
            if (mCloseGuardLocked != null) {
                if (finalized) {
                    mCloseGuardLocked.warnIfOpen();
                }
                mCloseGuardLocked.close();
            }

            pool = mConnectionPoolLocked;
            mConnectionPoolLocked = null;
            path = isInMemoryDatabase() ? null : getPath();
        }

        if (!finalized) {
            synchronized (sActiveDatabases) {
                sOpenTracker.close(path);
                sActiveDatabases.remove(this);
            }

            if (pool != null) {
                pool.close();
            }
        }
    }

    /**
     * Attempts to release memory that SQLite holds but does not require to
     * operate properly. Typically this memory will come from the page cache.
     *
     * @return the number of bytes actually released
     */
    public static int releaseMemory() {
        return SQLiteGlobal.releaseMemory();
    }

    /**
     * Control whether or not the SQLiteDatabase is made thread-safe by using locks
     * around critical sections. This is pretty expensive, so if you know that your
     * DB will only be used by a single thread then you should set this to false.
     * The default is true.
     * @param lockingEnabled set to true to enable locks, false otherwise
     *
     * @deprecated This method now does nothing.  Do not use.
     */
    @Deprecated
    public void setLockingEnabled(boolean lockingEnabled) {
    }

    /**
     * Gets a label to use when describing the database in log messages.
     * @return The label.
     */
    String getLabel() {
        synchronized (mLock) {
            return mConfigurationLocked.label;
        }
    }

    /**
     * Sends a corruption message to the database error handler.
     */
    void onCorruption() {
        EventLog.writeEvent(EVENT_DB_CORRUPT, getLabel());
        mErrorHandler.onCorruption(this);
    }

    /**
     * Gets the {@link SQLiteSession} that belongs to this thread for this database.
     * Once a thread has obtained a session, it will continue to obtain the same
     * session even after the database has been closed (although the session will not
     * be usable).  However, a thread that does not already have a session cannot
     * obtain one after the database has been closed.
     *
     * The idea is that threads that have active connections to the database may still
     * have work to complete even after the call to {@link #close}.  Active database
     * connections are not actually disposed until they are released by the threads
     * that own them.
     *
     * @return The session, never null.
     *
     * @throws IllegalStateException if the thread does not yet have a session and
     * the database is not open.
     */
    @UnsupportedAppUsage
    SQLiteSession getThreadSession() {
        return mThreadSession.get(); // initialValue() throws if database closed
    }

    SQLiteSession createSession() {
        final SQLiteConnectionPool pool;
        synchronized (mLock) {
            throwIfNotOpenLocked();
            pool = mConnectionPoolLocked;
        }
        return new SQLiteSession(pool);
    }

    /**
     * Gets default connection flags that are appropriate for this thread, taking into
     * account whether the thread is acting on behalf of the UI.
     *
     * @param readOnly True if the connection should be read-only.
     * @return The connection flags.
     */
    int getThreadDefaultConnectionFlags(boolean readOnly) {
        int flags = readOnly ? SQLiteConnectionPool.CONNECTION_FLAG_READ_ONLY :
                SQLiteConnectionPool.CONNECTION_FLAG_PRIMARY_CONNECTION_AFFINITY;
        if (isMainThread()) {
            flags |= SQLiteConnectionPool.CONNECTION_FLAG_INTERACTIVE;
        }
        return flags;
    }

    private static boolean isMainThread() {
        // FIXME: There should be a better way to do this.
        // Would also be nice to have something that would work across Binder calls.
        Looper looper = Looper.myLooper();
        return looper != null && looper == Looper.getMainLooper();
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
     * Begins a transaction in DEFERRED mode, with the android-specific constraint that the
     * transaction is read-only. The database may not be modified inside a read-only transaction.
     * <p>
     * Read-only transactions may run concurrently with other read-only transactions, and if they
     * database is in WAL mode, they may also run concurrently with IMMEDIATE or EXCLUSIVE
     * transactions.
     * <p>
     * Transactions can be nested.  However, the behavior of the transaction is not altered by
     * nested transactions.  A nested transaction may be any of the three transaction types but if
     * the outermost type is read-only then nested transactions remain read-only, regardless of how
     * they are started.
     * <p>
     * Here is the standard idiom for read-only transactions:
     *
     * <pre>
     *   db.beginTransactionReadOnly();
     *   try {
     *     ...
     *   } finally {
     *     db.endTransaction();
     *   }
     * </pre>
     */
    @FlaggedApi(Flags.FLAG_SQLITE_APIS_35)
    public void beginTransactionReadOnly() {
        beginTransactionWithListenerReadOnly(null);
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
    public void beginTransactionWithListener(
            @Nullable SQLiteTransactionListener transactionListener) {
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
            @Nullable SQLiteTransactionListener transactionListener) {
        beginTransaction(transactionListener, false);
    }

    /**
     * Begins a transaction in read-only mode with a {@link SQLiteTransactionListener} listener.
     * The database may not be updated inside a read-only transaction.
     * <p>
     * Transactions can be nested.  However, the behavior of the transaction is not altered by
     * nested transactions.  A nested transaction may be any of the three transaction types but if
     * the outermost type is read-only then nested transactions remain read-only, regardless of how
     * they are started.
     * <p>
     * Here is the standard idiom for read-only transactions:
     *
     * <pre>
     *   db.beginTransactionWightListenerReadOnly(listener);
     *   try {
     *     ...
     *   } finally {
     *     db.endTransaction();
     *   }
     * </pre>
     */
    @FlaggedApi(Flags.FLAG_SQLITE_APIS_35)
    public void beginTransactionWithListenerReadOnly(
            @Nullable SQLiteTransactionListener transactionListener) {
        beginTransaction(transactionListener, SQLiteSession.TRANSACTION_MODE_DEFERRED);
    }

    @UnsupportedAppUsage
    private void beginTransaction(SQLiteTransactionListener transactionListener,
            boolean exclusive) {
        beginTransaction(transactionListener,
                exclusive ? SQLiteSession.TRANSACTION_MODE_EXCLUSIVE :
                SQLiteSession.TRANSACTION_MODE_IMMEDIATE);
    }

    /**
     * Begin a transaction with the specified mode.  Valid modes are
     * {@link SQLiteSession.TRANSACTION_MODE_DEFERRED},
     * {@link SQLiteSession.TRANSACTION_MODE_IMMEDIATE}, and
     * {@link SQLiteSession.TRANSACTION_MODE_EXCLUSIVE}.
     */
    private void beginTransaction(@Nullable SQLiteTransactionListener listener, int mode) {
        acquireReference();
        try {
            // DEFERRED transactions are read-only to allows concurrent read-only transactions.
            // Others are read/write.
            boolean readOnly = (mode == SQLiteSession.TRANSACTION_MODE_DEFERRED);
            getThreadSession().beginTransaction(mode, listener,
                    getThreadDefaultConnectionFlags(readOnly), null);
        } finally {
            releaseReference();
        }
    }

    /**
     * End a transaction. See beginTransaction for notes about how to use this and when transactions
     * are committed and rolled back.
     */
    public void endTransaction() {
        acquireReference();
        try {
            getThreadSession().endTransaction(null);
        } finally {
            releaseReference();
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
        acquireReference();
        try {
            getThreadSession().setTransactionSuccessful();
        } finally {
            releaseReference();
        }
    }

    /**
     * Returns true if the current thread has a transaction pending.
     *
     * @return True if the current thread is in a transaction.
     */
    public boolean inTransaction() {
        acquireReference();
        try {
            return getThreadSession().hasTransaction();
        } finally {
            releaseReference();
        }
    }

    /**
     * Returns true if the current thread is holding an active connection to the database.
     * <p>
     * The name of this method comes from a time when having an active connection
     * to the database meant that the thread was holding an actual lock on the
     * database.  Nowadays, there is no longer a true "database lock" although threads
     * may block if they cannot acquire a database connection to perform a
     * particular operation.
     * </p>
     *
     * @return True if the current thread is holding an active connection to the database.
     */
    public boolean isDbLockedByCurrentThread() {
        acquireReference();
        try {
            return getThreadSession().hasConnection();
        } finally {
            releaseReference();
        }
    }

    /**
     * Always returns false.
     * <p>
     * There is no longer the concept of a database lock, so this method always returns false.
     * </p>
     *
     * @return False.
     * @deprecated Always returns false.  Do not use this method.
     */
    @Deprecated
    public boolean isDbLockedByOtherThreads() {
        return false;
    }

    /**
     * Temporarily end the transaction to let other threads run. The transaction is assumed to be
     * successful so far. Do not call setTransactionSuccessful before calling this. When this
     * returns a new transaction will have been created but not marked as successful.
     * @return true if the transaction was yielded
     * @deprecated if the db is locked more than once (because of nested transactions) then the lock
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

    private boolean yieldIfContendedHelper(boolean throwIfUnsafe, long sleepAfterYieldDelay) {
        acquireReference();
        try {
            return getThreadSession().yieldTransaction(sleepAfterYieldDelay, throwIfUnsafe, null);
        } finally {
            releaseReference();
        }
    }

    /**
     * Deprecated.
     * @deprecated This method no longer serves any useful purpose and has been deprecated.
     */
    @Deprecated
    public Map<String, String> getSyncedTables() {
        return new HashMap<String, String>(0);
    }

    /**
     * Open the database according to the flags {@link #OPEN_READWRITE}
     * {@link #OPEN_READONLY} {@link #CREATE_IF_NECESSARY} and/or {@link #NO_LOCALIZED_COLLATORS}.
     *
     * <p>Sets the locale of the database to the system's current locale.
     * Call {@link #setLocale} if you would like something else.</p>
     *
     * @param path to database file to open and/or create
     * @param factory an optional factory class that is called to instantiate a
     *            cursor when query is called, or null for default
     * @param flags to control database access mode
     * @return the newly opened database
     * @throws SQLiteException if the database cannot be opened
     */
    public static SQLiteDatabase openDatabase(@NonNull String path, @Nullable CursorFactory factory,
            @DatabaseOpenFlags int flags) {
        return openDatabase(path, factory, flags, null);
    }

    /**
     * Open the database according to the specified {@link OpenParams parameters}
     *
     * @param path path to database file to open and/or create.
     * <p><strong>Important:</strong> The file should be constructed either from an absolute path or
     * by using {@link android.content.Context#getDatabasePath(String)}.
     * @param openParams configuration parameters that are used for opening {@link SQLiteDatabase}
     * @return the newly opened database
     * @throws SQLiteException if the database cannot be opened
     */
    public static SQLiteDatabase openDatabase(@NonNull File path,
            @NonNull OpenParams openParams) {
        return openDatabase(path.getPath(), openParams);
    }

    @UnsupportedAppUsage
    private static SQLiteDatabase openDatabase(@NonNull String path,
            @NonNull OpenParams openParams) {
        Preconditions.checkArgument(openParams != null, "OpenParams cannot be null");
        SQLiteDatabase db = new SQLiteDatabase(path, openParams.mOpenFlags,
                openParams.mCursorFactory, openParams.mErrorHandler,
                openParams.mLookasideSlotSize, openParams.mLookasideSlotCount,
                openParams.mIdleConnectionTimeout, openParams.mJournalMode, openParams.mSyncMode);
        db.open();
        return db;
    }

    /**
     * Open the database according to the flags {@link #OPEN_READWRITE}
     * {@link #OPEN_READONLY} {@link #CREATE_IF_NECESSARY} and/or {@link #NO_LOCALIZED_COLLATORS}.
     *
     * <p>Sets the locale of the database to the system's current locale.
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
    public static SQLiteDatabase openDatabase(@NonNull String path, @Nullable CursorFactory factory,
            @DatabaseOpenFlags int flags, @Nullable DatabaseErrorHandler errorHandler) {
        SQLiteDatabase db = new SQLiteDatabase(path, flags, factory, errorHandler, -1, -1, -1, null,
                null);
        db.open();
        return db;
    }

    /**
     * Equivalent to openDatabase(file.getPath(), factory, CREATE_IF_NECESSARY).
     */
    public static SQLiteDatabase openOrCreateDatabase(@NonNull File file,
            @Nullable CursorFactory factory) {
        return openOrCreateDatabase(file.getPath(), factory);
    }

    /**
     * Equivalent to openDatabase(path, factory, CREATE_IF_NECESSARY).
     */
    public static SQLiteDatabase openOrCreateDatabase(@NonNull String path,
            @Nullable CursorFactory factory) {
        return openDatabase(path, factory, CREATE_IF_NECESSARY, null);
    }

    /**
     * Equivalent to openDatabase(path, factory, CREATE_IF_NECESSARY, errorHandler).
     */
    public static SQLiteDatabase openOrCreateDatabase(@NonNull String path,
            @Nullable CursorFactory factory, @Nullable DatabaseErrorHandler errorHandler) {
        return openDatabase(path, factory, CREATE_IF_NECESSARY, errorHandler);
    }

    /**
     * Deletes a database including its journal file and other auxiliary files
     * that may have been created by the database engine.
     *
     * @param file The database file path.
     * @return True if the database was successfully deleted.
     */
    public static boolean deleteDatabase(@NonNull File file) {
        return deleteDatabase(file, /*removeCheckFile=*/ true);
    }


    /** @hide */
    public static boolean deleteDatabase(@NonNull File file, boolean removeCheckFile) {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }

        boolean deleted = false;
        deleted |= file.delete();
        deleted |= new File(file.getPath() + "-journal").delete();
        deleted |= new File(file.getPath() + "-shm").delete();
        deleted |= new File(file.getPath() + "-wal").delete();

        // This file is not a standard SQLite file, so don't update the deleted flag.
        new File(file.getPath() + SQLiteGlobal.WIPE_CHECK_FILE_SUFFIX).delete();

        File dir = file.getParentFile();
        if (dir != null) {
            final String prefix = file.getName() + "-mj";
            File[] files = dir.listFiles(new FileFilter() {
                @Override
                public boolean accept(File candidate) {
                    return candidate.getName().startsWith(prefix);
                }
            });
            if (files != null) {
                for (File masterJournal : files) {
                    deleted |= masterJournal.delete();
                }
            }
        }
        return deleted;
    }

    /**
     * Reopens the database in read-write mode.
     * If the database is already read-write, does nothing.
     *
     * @throws SQLiteException if the database could not be reopened as requested, in which
     * case it remains open in read only mode.
     * @throws IllegalStateException if the database is not open.
     *
     * @see #isReadOnly()
     * @hide
     */
    @UnsupportedAppUsage
    public void reopenReadWrite() {
        synchronized (mLock) {
            throwIfNotOpenLocked();

            if (!isReadOnlyLocked()) {
                return; // nothing to do
            }

            // Reopen the database in read-write mode.
            final int oldOpenFlags = mConfigurationLocked.openFlags;
            mConfigurationLocked.openFlags = (mConfigurationLocked.openFlags & ~OPEN_READ_MASK)
                    | OPEN_READWRITE;
            try {
                mConnectionPoolLocked.reconfigure(mConfigurationLocked);
            } catch (RuntimeException ex) {
                mConfigurationLocked.openFlags = oldOpenFlags;
                throw ex;
            }
        }
    }

    /**
     * Track the number of times a database file has been opened.  There is a primary connection
     * associated with every open database, and these can contend with each other, leading to
     * unexpected SQLiteDatabaseLockedException exceptions.  The tracking here is only advisory:
     * multiply-opened databases are logged but no other action is taken.
     *
     * This class is not thread-safe.
     */
    private static class OpenTracker {
        // The list of currently-open databases.  This maps the database file to the number of
        // currently-active opens.
        private final ArrayMap<String, Integer> mOpens = new ArrayMap<>();

        // The maximum number of concurrently open database paths that will be stored.  Once this
        // many paths have been recorded, further paths are logged but not saved.
        private static final int MAX_RECORDED_PATHS = 20;

        // The list of databases that were ever concurrently opened.
        private final ArraySet<String> mConcurrent = new ArraySet<>();

        /** Return the canonical path.  On error, just return the input path. */
        private static String normalize(String path) {
            try {
                return new File(path).toPath().toRealPath().toString();
            } catch (Exception e) {
                // If there is an IO or security exception, just continue, using the input path.
                return path;
            }
        }

        /** Return true if the path is currently open in another SQLiteDatabase instance. */
        void open(@Nullable String path) {
            if (path == null) return;
            path = normalize(path);

            Integer count = mOpens.get(path);
            if (count == null || count == 0) {
                mOpens.put(path, 1);
                return;
            } else {
                mOpens.put(path, count + 1);
                if (mConcurrent.size() < MAX_RECORDED_PATHS) {
                    mConcurrent.add(path);
                }
                Log.w(TAG, "multiple primary connections on " + path);
                return;
            }
        }

        void close(@Nullable String path) {
            if (path == null) return;
            path = normalize(path);
            Integer count = mOpens.get(path);
            if (count == null || count <= 0) {
                Log.e(TAG, "open database counting failure on " + path);
            } else if (count == 1) {
                // Implicitly set the count to zero, and make mOpens smaller.
                mOpens.remove(path);
            } else {
                mOpens.put(path, count - 1);
            }
        }

        ArraySet<String> getConcurrentDatabasePaths() {
            return new ArraySet<>(mConcurrent);
        }
    }

    private void open() {
        try {
            try {
                openInner();
            } catch (RuntimeException ex) {
                if (SQLiteDatabaseCorruptException.isCorruptException(ex)) {
                    Log.e(TAG, "Database corruption detected in open()", ex);
                    onCorruption();
                    openInner();
                } else {
                    throw ex;
                }
            }
        } catch (SQLiteException ex) {
            Log.e(TAG, "Failed to open database '" + getLabel() + "'.", ex);
            close();
            throw ex;
        }
    }

    private void openInner() {
        final String path;
        synchronized (mLock) {
            assert mConnectionPoolLocked == null;
            mConnectionPoolLocked = SQLiteConnectionPool.open(mConfigurationLocked);
            mCloseGuardLocked.open("close");
            path = isInMemoryDatabase() ? null : getPath();
        }

        synchronized (sActiveDatabases) {
            sActiveDatabases.put(this, null);
            sOpenTracker.open(path);
        }
    }

    /**
     * Create a memory backed SQLite database.  Its contents will be destroyed
     * when the database is closed.
     *
     * <p>Sets the locale of the database to the system's current locale.
     * Call {@link #setLocale} if you would like something else.</p>
     *
     * @param factory an optional factory class that is called to instantiate a
     *            cursor when query is called
     * @return a SQLiteDatabase instance
     * @throws SQLiteException if the database cannot be created
     */
    @NonNull
    public static SQLiteDatabase create(@Nullable CursorFactory factory) {
        // This is a magic string with special meaning for SQLite.
        return openDatabase(SQLiteDatabaseConfiguration.MEMORY_DB_PATH,
                factory, CREATE_IF_NECESSARY);
    }

    /**
     * Create a memory backed SQLite database.  Its contents will be destroyed
     * when the database is closed.
     *
     * <p>Sets the locale of the database to the system's current locale.
     * Call {@link #setLocale} if you would like something else.</p>
     * @param openParams configuration parameters that are used for opening SQLiteDatabase
     * @return a SQLiteDatabase instance
     * @throws SQLException if the database cannot be created
     */
    @NonNull
    public static SQLiteDatabase createInMemory(@NonNull OpenParams openParams) {
        return openDatabase(SQLiteDatabaseConfiguration.MEMORY_DB_PATH,
                openParams.toBuilder().addOpenFlags(CREATE_IF_NECESSARY).build());
    }

    /**
     * Register a custom scalar function that can be called from SQL
     * expressions.
     * <p>
     * For example, registering a custom scalar function named {@code REVERSE}
     * could be used in a query like
     * {@code SELECT REVERSE(name) FROM employees}.
     * <p>
     * When attempting to register multiple functions with the same function
     * name, SQLite will replace any previously defined functions with the
     * latest definition, regardless of what function type they are. SQLite does
     * not support unregistering functions.
     *
     * @param functionName Case-insensitive name to register this function
     *            under, limited to 255 UTF-8 bytes in length.
     * @param scalarFunction Functional interface that will be invoked when the
     *            function name is used by a SQL statement. The argument values
     *            from the SQL statement are passed to the functional interface,
     *            and the return values from the functional interface are
     *            returned back into the SQL statement.
     * @throws SQLiteException if the custom function could not be registered.
     * @see #setCustomAggregateFunction(String, BinaryOperator)
     */
    public void setCustomScalarFunction(@NonNull String functionName,
            @NonNull UnaryOperator<String> scalarFunction) throws SQLiteException {
        Objects.requireNonNull(functionName);
        Objects.requireNonNull(scalarFunction);

        synchronized (mLock) {
            throwIfNotOpenLocked();

            mConfigurationLocked.customScalarFunctions.put(functionName, scalarFunction);
            try {
                mConnectionPoolLocked.reconfigure(mConfigurationLocked);
            } catch (RuntimeException ex) {
                mConfigurationLocked.customScalarFunctions.remove(functionName);
                throw ex;
            }
        }
    }

    /**
     * Register a custom aggregate function that can be called from SQL
     * expressions.
     * <p>
     * For example, registering a custom aggregation function named
     * {@code LONGEST} could be used in a query like
     * {@code SELECT LONGEST(name) FROM employees}.
     * <p>
     * The implementation of this method follows the reduction flow outlined in
     * {@link java.util.stream.Stream#reduce(BinaryOperator)}, and the custom
     * aggregation function is expected to be an associative accumulation
     * function, as defined by that class.
     * <p>
     * When attempting to register multiple functions with the same function
     * name, SQLite will replace any previously defined functions with the
     * latest definition, regardless of what function type they are. SQLite does
     * not support unregistering functions.
     *
     * @param functionName Case-insensitive name to register this function
     *            under, limited to 255 UTF-8 bytes in length.
     * @param aggregateFunction Functional interface that will be invoked when
     *            the function name is used by a SQL statement. The argument
     *            values from the SQL statement are passed to the functional
     *            interface, and the return values from the functional interface
     *            are returned back into the SQL statement.
     * @throws SQLiteException if the custom function could not be registered.
     * @see #setCustomScalarFunction(String, UnaryOperator)
     */
    public void setCustomAggregateFunction(@NonNull String functionName,
            @NonNull BinaryOperator<String> aggregateFunction) throws SQLiteException {
        Objects.requireNonNull(functionName);
        Objects.requireNonNull(aggregateFunction);

        synchronized (mLock) {
            throwIfNotOpenLocked();

            mConfigurationLocked.customAggregateFunctions.put(functionName, aggregateFunction);
            try {
                mConnectionPoolLocked.reconfigure(mConfigurationLocked);
            } catch (RuntimeException ex) {
                mConfigurationLocked.customAggregateFunctions.remove(functionName);
                throw ex;
            }
        }
    }

    /**
     * Execute the given SQL statement on all connections to this database.
     * <p>
     * This statement will be immediately executed on all existing connections,
     * and will be automatically executed on all future connections.
     * <p>
     * Some example usages are changes like {@code PRAGMA trusted_schema=OFF} or
     * functions like {@code SELECT icu_load_collation()}. If you execute these
     * statements using {@link #execSQL} then they will only apply to a single
     * database connection; using this method will ensure that they are
     * uniformly applied to all current and future connections.
     *
     * @param sql The SQL statement to be executed. Multiple statements
     *            separated by semicolons are not supported.
     * @param bindArgs The arguments that should be bound to the SQL statement.
     */
    public void execPerConnectionSQL(@NonNull String sql, @Nullable Object[] bindArgs)
            throws SQLException {
        Objects.requireNonNull(sql);

        // Copy arguments to ensure that the caller doesn't accidentally change
        // the values used by future connections
        bindArgs = DatabaseUtils.deepCopyOf(bindArgs);

        synchronized (mLock) {
            throwIfNotOpenLocked();

            final int index = mConfigurationLocked.perConnectionSql.size();
            mConfigurationLocked.perConnectionSql.add(Pair.create(sql, bindArgs));
            try {
                mConnectionPoolLocked.reconfigure(mConfigurationLocked);
            } catch (RuntimeException ex) {
                mConfigurationLocked.perConnectionSql.remove(index);
                throw ex;
            }
        }
    }

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
     * @deprecated This method no longer serves any useful purpose and has been deprecated.
     */
    @Deprecated
    public void markTableSyncable(String table, String deletedTable) {
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
     * @deprecated This method no longer serves any useful purpose and has been deprecated.
     */
    @Deprecated
    public void markTableSyncable(String table, String foreignKey, String updateTable) {
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
     *<p>
     * No two threads should be using the same {@link SQLiteStatement} at the same time.
     *
     * @param sql The raw SQL statement, may contain ? for unknown values to be
     *            bound later.
     * @return A pre-compiled {@link SQLiteStatement} object. Note that
     * {@link SQLiteStatement}s are not synchronized, see the documentation for more details.
     */
    public SQLiteStatement compileStatement(String sql) throws SQLException {
        acquireReference();
        try {
            return new SQLiteStatement(this, sql, null);
        } finally {
            releaseReference();
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
     *         replaced by the values from selectionArgs, in the order that they
     *         appear in the selection. The values will be bound as Strings.
     *         If selection is null or does not contain ?s then selectionArgs
     *         may be null.
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
    @NonNull
    public Cursor query(boolean distinct, @NonNull String table,
            @Nullable String[] columns, @Nullable String selection,
            @Nullable String[] selectionArgs, @Nullable String groupBy, @Nullable String having,
            @Nullable String orderBy, @Nullable String limit) {
        return queryWithFactory(null, distinct, table, columns, selection, selectionArgs,
                groupBy, having, orderBy, limit, null);
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
     *         replaced by the values from selectionArgs, in the order that they
     *         appear in the selection. The values will be bound as Strings.
     *         If selection is null or does not contain ?s then selectionArgs
     *         may be null.
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
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     * If the operation is canceled, then {@link OperationCanceledException} will be thrown
     * when the query is executed.
     * @return A {@link Cursor} object, which is positioned before the first entry. Note that
     * {@link Cursor}s are not synchronized, see the documentation for more details.
     * @see Cursor
     */
    @NonNull
    public Cursor query(boolean distinct, @NonNull String table,
            @Nullable String[] columns, @Nullable String selection,
            @Nullable String[] selectionArgs, @Nullable String groupBy, @Nullable String having,
            @Nullable String orderBy, @Nullable String limit,
            @Nullable CancellationSignal cancellationSignal) {
        return queryWithFactory(null, distinct, table, columns, selection, selectionArgs,
                groupBy, having, orderBy, limit, cancellationSignal);
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
     *         replaced by the values from selectionArgs, in the order that they
     *         appear in the selection. The values will be bound as Strings.
     *         If selection is null or does not contain ?s then selectionArgs
     *         may be null.
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
    @SuppressLint("SamShouldBeLast")
    @NonNull
    public Cursor queryWithFactory(@Nullable CursorFactory cursorFactory,
            boolean distinct, @NonNull String table, @Nullable String[] columns,
            @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String groupBy,
            @Nullable String having, @Nullable String orderBy, @Nullable String limit) {
        return queryWithFactory(cursorFactory, distinct, table, columns, selection,
                selectionArgs, groupBy, having, orderBy, limit, null);
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
     *         replaced by the values from selectionArgs, in the order that they
     *         appear in the selection. The values will be bound as Strings.
     *         If selection is null or does not contain ?s then selectionArgs
     *         may be null.
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
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     * If the operation is canceled, then {@link OperationCanceledException} will be thrown
     * when the query is executed.
     * @return A {@link Cursor} object, which is positioned before the first entry. Note that
     * {@link Cursor}s are not synchronized, see the documentation for more details.
     * @see Cursor
     */
    @SuppressLint("SamShouldBeLast")
    @NonNull
    public Cursor queryWithFactory(@Nullable CursorFactory cursorFactory,
            boolean distinct, @NonNull String table, @Nullable String[] columns,
            @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String groupBy,
            @Nullable String having, @Nullable String orderBy, @Nullable String limit,
            @Nullable CancellationSignal cancellationSignal) {
        acquireReference();
        try {
            String sql = SQLiteQueryBuilder.buildQueryString(
                    distinct, table, columns, selection, groupBy, having, orderBy, limit);

            return rawQueryWithFactory(cursorFactory, sql, selectionArgs,
                    findEditTable(table), cancellationSignal);
        } finally {
            releaseReference();
        }
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
     *         replaced by the values from selectionArgs, in the order that they
     *         appear in the selection. The values will be bound as Strings.
     *         If selection is null or does not contain ?s then selectionArgs
     *         may be null.
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
    @NonNull
    public Cursor query(@NonNull String table, @Nullable String[] columns,
            @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String groupBy,
            @Nullable String having, @Nullable String orderBy) {

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
     *         replaced by the values from selectionArgs, in the order that they
     *         appear in the selection. The values will be bound as Strings.
     *         If selection is null or does not contain ?s then selectionArgs
     *         may be null.
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
    @NonNull
    public Cursor query(@NonNull String table, @Nullable String[] columns,
            @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String groupBy,
            @Nullable String having, @Nullable String orderBy, @Nullable String limit) {

        return query(false, table, columns, selection, selectionArgs, groupBy,
                having, orderBy, limit);
    }

    /**
     * Runs the provided SQL and returns a {@link Cursor} over the result set.
     *
     * @param sql the SQL query. The SQL string must not be ; terminated
     * @param selectionArgs You may include ?s in where clause in the query,
     *     which will be replaced by the values from selectionArgs. The
     *     values will be bound as Strings. If selection is null or does not contain ?s then
     *     selectionArgs may be null.
     * @return A {@link Cursor} object, which is positioned before the first entry. Note that
     * {@link Cursor}s are not synchronized, see the documentation for more details.
     */
    @NonNull
    public Cursor rawQuery(@NonNull String sql, @Nullable String[] selectionArgs) {
        return rawQueryWithFactory(null, sql, selectionArgs, null, null);
    }

    /**
     * Runs the provided SQL and returns a {@link Cursor} over the result set.
     *
     * @param sql the SQL query. The SQL string must not be ; terminated
     * @param selectionArgs You may include ?s in where clause in the query,
     *     which will be replaced by the values from selectionArgs. The
     *     values will be bound as Strings. If selection is null or does not contain ?s then
     *     selectionArgs may be null.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     * If the operation is canceled, then {@link OperationCanceledException} will be thrown
     * when the query is executed.
     * @return A {@link Cursor} object, which is positioned before the first entry. Note that
     * {@link Cursor}s are not synchronized, see the documentation for more details.
     */
    @NonNull
    public Cursor rawQuery(@NonNull String sql, @Nullable String[] selectionArgs,
            @Nullable CancellationSignal cancellationSignal) {
        return rawQueryWithFactory(null, sql, selectionArgs, null, cancellationSignal);
    }

    /**
     * Runs the provided SQL and returns a cursor over the result set.
     *
     * @param cursorFactory the cursor factory to use, or null for the default factory
     * @param sql the SQL query. The SQL string must not be ; terminated
     * @param selectionArgs You may include ?s in where clause in the query,
     *     which will be replaced by the values from selectionArgs. The
     *     values will be bound as Strings. If selection is null or does not contain ?s then
     *     selectionArgs may be null.
     * @param editTable the name of the first table, which is editable
     * @return A {@link Cursor} object, which is positioned before the first entry. Note that
     * {@link Cursor}s are not synchronized, see the documentation for more details.
     */
    @NonNull
    public Cursor rawQueryWithFactory(
            @Nullable CursorFactory cursorFactory, @NonNull String sql,
            @Nullable String[] selectionArgs, @NonNull String editTable) {
        return rawQueryWithFactory(cursorFactory, sql, selectionArgs, editTable, null);
    }

    /**
     * Runs the provided SQL and returns a cursor over the result set.
     *
     * @param cursorFactory the cursor factory to use, or null for the default factory
     * @param sql the SQL query. The SQL string must not be ; terminated
     * @param selectionArgs You may include ?s in where clause in the query,
     *     which will be replaced by the values from selectionArgs. The
     *     values will be bound as Strings. If selection is null or does not contain ?s then
     *     selectionArgs may be null.
     * @param editTable the name of the first table, which is editable
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     * If the operation is canceled, then {@link OperationCanceledException} will be thrown
     * when the query is executed.
     * @return A {@link Cursor} object, which is positioned before the first entry. Note that
     * {@link Cursor}s are not synchronized, see the documentation for more details.
     */
    @NonNull
    public Cursor rawQueryWithFactory(
            @Nullable CursorFactory cursorFactory, @NonNull String sql,
            @Nullable String[] selectionArgs, @NonNull String editTable,
            @Nullable CancellationSignal cancellationSignal) {
        acquireReference();
        try {
            SQLiteCursorDriver driver = new SQLiteDirectCursorDriver(this, sql, editTable,
                    cancellationSignal);
            return driver.query(cursorFactory != null ? cursorFactory : mCursorFactory,
                    selectionArgs);
        } finally {
            releaseReference();
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
     * @return the row ID of the newly inserted row, or -1 if an error occurred
     */
    public long insert(@NonNull String table, @Nullable String nullColumnHack,
            @Nullable ContentValues values) {
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
    public long insertOrThrow(@NonNull String table, @Nullable String nullColumnHack,
            @Nullable ContentValues values) throws SQLException {
        return insertWithOnConflict(table, nullColumnHack, values, CONFLICT_NONE);
    }

    /**
     * Convenience method for replacing a row in the database.
     * Inserts a new row if a row does not already exist.
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
     *   the row. The keys should be the column names and the values the column values.
     * @return the row ID of the newly inserted row, or -1 if an error occurred
     */
    public long replace(@NonNull String table, @Nullable String nullColumnHack,
            @Nullable ContentValues initialValues) {
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
     * Inserts a new row if a row does not already exist.
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
     *   the row. The keys should be the column names and the values the column values.
     * @throws SQLException
     * @return the row ID of the newly inserted row, or -1 if an error occurred
     */
    public long replaceOrThrow(@NonNull String table, @Nullable String nullColumnHack,
            @Nullable ContentValues initialValues) throws SQLException {
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
     * @return the row ID of the newly inserted row OR <code>-1</code> if either the
     *            input parameter <code>conflictAlgorithm</code> = {@link #CONFLICT_IGNORE}
     *            or an error occurred.
     */
    public long insertWithOnConflict(@NonNull String table, @Nullable String nullColumnHack,
            @Nullable ContentValues initialValues, int conflictAlgorithm) {
        acquireReference();
        try {
            StringBuilder sql = new StringBuilder();
            sql.append("INSERT");
            sql.append(CONFLICT_VALUES[conflictAlgorithm]);
            sql.append(" INTO ");
            sql.append(table);
            sql.append('(');

            Object[] bindArgs = null;
            int size = (initialValues != null && !initialValues.isEmpty())
                    ? initialValues.size() : 0;
            if (size > 0) {
                bindArgs = new Object[size];
                int i = 0;
                for (String colName : initialValues.keySet()) {
                    sql.append((i > 0) ? "," : "");
                    sql.append(colName);
                    bindArgs[i++] = initialValues.get(colName);
                }
                sql.append(')');
                sql.append(" VALUES (");
                for (i = 0; i < size; i++) {
                    sql.append((i > 0) ? ",?" : "?");
                }
            } else {
                sql.append(nullColumnHack).append(") VALUES (NULL");
            }
            sql.append(')');

            SQLiteStatement statement = new SQLiteStatement(this, sql.toString(), bindArgs);
            try {
                return statement.executeInsert();
            } finally {
                statement.close();
            }
        } finally {
            releaseReference();
        }
    }

    /**
     * Convenience method for deleting rows in the database.
     *
     * @param table the table to delete from
     * @param whereClause the optional WHERE clause to apply when deleting.
     *            Passing null will delete all rows.
     * @param whereArgs You may include ?s in the where clause, which
     *            will be replaced by the values from whereArgs. The values
     *            will be bound as Strings. If whereClause is null or does not
     *            contain ?s then whereArgs may be null.
     * @return the number of rows affected if a whereClause is passed in, 0
     *         otherwise. To remove all rows and get a count pass "1" as the
     *         whereClause.
     */
    public int delete(@NonNull String table, @Nullable String whereClause,
            @Nullable String[] whereArgs) {
        acquireReference();
        try {
            SQLiteStatement statement =  new SQLiteStatement(this, "DELETE FROM " + table +
                    (!TextUtils.isEmpty(whereClause) ? " WHERE " + whereClause : ""), whereArgs);
            try {
                return statement.executeUpdateDelete();
            } finally {
                statement.close();
            }
        } finally {
            releaseReference();
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
     * @param whereArgs You may include ?s in the where clause, which
     *            will be replaced by the values from whereArgs. The values
     *            will be bound as Strings. If whereClause is null or does not
     *            contain ?s then whereArgs may be null.
     * @return the number of rows affected
     */
    public int update(@NonNull String table, @Nullable ContentValues values,
            @Nullable String whereClause, @Nullable String[] whereArgs) {
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
     * @param whereArgs You may include ?s in the where clause, which
     *            will be replaced by the values from whereArgs. The values
     *            will be bound as Strings. If whereClause is null or does not
     *            contain ?s then whereArgs may be null.
     * @param conflictAlgorithm for update conflict resolver
     * @return the number of rows affected
     */
    public int updateWithOnConflict(@NonNull String table, @Nullable ContentValues values,
            @Nullable String whereClause, @Nullable String[] whereArgs, int conflictAlgorithm) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Empty values");
        }

        acquireReference();
        try {
            StringBuilder sql = new StringBuilder(120);
            sql.append("UPDATE ");
            sql.append(CONFLICT_VALUES[conflictAlgorithm]);
            sql.append(table);
            sql.append(" SET ");

            // move all bind args to one array
            int setValuesSize = values.size();
            int bindArgsSize = (whereArgs == null) ? setValuesSize : (setValuesSize + whereArgs.length);
            Object[] bindArgs = new Object[bindArgsSize];
            int i = 0;
            for (String colName : values.keySet()) {
                sql.append((i > 0) ? "," : "");
                sql.append(colName);
                bindArgs[i++] = values.get(colName);
                sql.append("=?");
            }
            if (whereArgs != null) {
                for (i = setValuesSize; i < bindArgsSize; i++) {
                    bindArgs[i] = whereArgs[i - setValuesSize];
                }
            }
            if (!TextUtils.isEmpty(whereClause)) {
                sql.append(" WHERE ");
                sql.append(whereClause);
            }

            SQLiteStatement statement = new SQLiteStatement(this, sql.toString(), bindArgs);
            try {
                return statement.executeUpdateDelete();
            } finally {
                statement.close();
            }
        } finally {
            releaseReference();
        }
    }

    /**
     * Execute a single SQL statement that is NOT a SELECT
     * or any other SQL statement that returns data.
     * <p>
     * It has no means to return any data (such as the number of affected rows).
     * Instead, you're encouraged to use {@link #insert(String, String, ContentValues)},
     * {@link #update(String, ContentValues, String, String[])}, et al, when possible.
     * </p>
     * <p>
     * When using {@link #enableWriteAheadLogging()}, journal_mode is
     * automatically managed by this class. So, do not set journal_mode
     * using "PRAGMA journal_mode'<value>" statement if your app is using
     * {@link #enableWriteAheadLogging()}
     * </p>
     * <p>
     * Note that {@code PRAGMA} values which apply on a per-connection basis
     * should <em>not</em> be configured using this method; you should instead
     * use {@link #execPerConnectionSQL} to ensure that they are uniformly
     * applied to all current and future connections.
     * </p>
     *
     * @param sql the SQL statement to be executed. Multiple statements separated by semicolons are
     * not supported.
     * @throws SQLException if the SQL string is invalid
     */
    public void execSQL(String sql) throws SQLException {
        executeSql(sql, null);
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
     * <p>
     * When using {@link #enableWriteAheadLogging()}, journal_mode is
     * automatically managed by this class. So, do not set journal_mode
     * using "PRAGMA journal_mode'<value>" statement if your app is using
     * {@link #enableWriteAheadLogging()}
     * </p>
     * <p>
     * Note that {@code PRAGMA} values which apply on a per-connection basis
     * should <em>not</em> be configured using this method; you should instead
     * use {@link #execPerConnectionSQL} to ensure that they are uniformly
     * applied to all current and future connections.
     * </p>
     *
     * @param sql the SQL statement to be executed. Multiple statements separated by semicolons are
     * not supported.
     * @param bindArgs only byte[], String, Long and Double are supported in bindArgs.
     * @throws SQLException if the SQL string is invalid
     */
    public void execSQL(@NonNull String sql, @NonNull Object[] bindArgs)
            throws SQLException {
        if (bindArgs == null) {
            throw new IllegalArgumentException("Empty bindArgs");
        }
        executeSql(sql, bindArgs);
    }

    /** {@hide} */
    public int executeSql(@NonNull String sql, @NonNull Object[] bindArgs)
            throws SQLException {
        acquireReference();
        try {
            final int statementType = DatabaseUtils.getSqlStatementType(sql);
            if (statementType == DatabaseUtils.STATEMENT_ATTACH) {
                boolean disableWal = false;
                synchronized (mLock) {
                    if (!mHasAttachedDbsLocked) {
                        mHasAttachedDbsLocked = true;
                        disableWal = true;
                        mConnectionPoolLocked.disableIdleConnectionHandler();
                    }
                }
                if (disableWal) {
                    disableWriteAheadLogging();
                }
            }

            try (SQLiteStatement statement = new SQLiteStatement(this, sql, bindArgs)) {
                return statement.executeUpdateDelete();
            } finally {
                // If schema was updated, close non-primary connections and clear prepared
                // statement caches of active connections, otherwise they might have outdated
                // schema information.
                if (statementType == DatabaseUtils.STATEMENT_DDL) {
                    mConnectionPoolLocked.closeAvailableNonPrimaryConnectionsAndLogExceptions();
                    mConnectionPoolLocked.clearAcquiredConnectionsPreparedStatementCache();
                }
            }
        } finally {
            releaseReference();
        }
    }

    /**
     * Return a {@link SQLiteRawStatement} connected to the database.  A transaction must be in
     * progress or an exception will be thrown.  The resulting object will be closed automatically
     * when the current transaction closes.
     *
     * @param sql The SQL string to be compiled into a prepared statement.
     * @return A {@link SQLiteRawStatement} holding the compiled SQL.
     * @throws IllegalStateException if a transaction is not in progress.
     * @throws SQLiteException if the SQL cannot be compiled.
     */
    @FlaggedApi(Flags.FLAG_SQLITE_APIS_35)
    @NonNull
    public SQLiteRawStatement createRawStatement(@NonNull String sql) {
        Objects.requireNonNull(sql);
        return new SQLiteRawStatement(this, sql);
    }

    /**
     * Return the "rowid" of the last row to be inserted on the current connection. This method must
     * only be called when inside a transaction. {@link IllegalStateException} is thrown if the
     * method is called outside a transaction. If the function is called before any inserts in the
     * current transaction, the value returned will be from a previous transaction, which may be
     * from a different thread. If no inserts have occurred on the current connection, the function
     * returns 0. See the SQLite documentation for the specific details.
     *
     * @see <a href="https://sqlite.org/c3ref/last_insert_rowid.html">sqlite3_last_insert_rowid</a>
     *
     * @return The ROWID of the last row to be inserted under this connection.
     * @throws IllegalStateException if there is no current transaction.
     */
    @FlaggedApi(Flags.FLAG_SQLITE_APIS_35)
    public long getLastInsertRowId() {
        return getThreadSession().getLastInsertRowId();
    }

    /**
     * Return the number of database rows that were inserted, updated, or deleted by the most recent
     * SQL statement within the current transaction.
     *
     * @see <a href="https://sqlite.org/c3ref/changes.html">sqlite3_changes64</a>
     *
     * @return The number of rows changed by the most recent sql statement
     * @throws IllegalStateException if there is no current transaction.
     */
    @FlaggedApi(Flags.FLAG_SQLITE_APIS_35)
    public long getLastChangedRowCount() {
        return getThreadSession().getLastChangedRowCount();
    }

    /**
     * Return the total number of database rows that have been inserted, updated, or deleted on
     * the current connection since it was created.  Due to Android's internal management of
     * SQLite connections, the value may, or may not, include changes made in earlier
     * transactions. Best practice is to compare values returned within a single transaction.
     *
     * <code><pre>
     *    database.beginTransaction();
     *    try {
     *        long initialValue = database.getTotalChangedRowCount();
     *        // Execute SQL statements
     *        long changedRows = database.getTotalChangedRowCount() - initialValue;
     *        // changedRows counts the total number of rows updated in the transaction.
     *    } finally {
     *        database.endTransaction();
     *    }
     * </pre></code>
     *
     * @see <a href="https://sqlite.org/c3ref/changes.html">sqlite3_total_changes64</a>
     *
     * @return The number of rows changed on the current connection.
     * @throws IllegalStateException if there is no current transaction.
     */
    @FlaggedApi(Flags.FLAG_SQLITE_APIS_35)
    public long getTotalChangedRowCount() {
        return getThreadSession().getTotalChangedRowCount();
    }

    /**
     * Verifies that a SQL SELECT statement is valid by compiling it.
     * If the SQL statement is not valid, this method will throw a {@link SQLiteException}.
     *
     * @param sql SQL to be validated
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     * If the operation is canceled, then {@link OperationCanceledException} will be thrown
     * when the query is executed.
     * @throws SQLiteException if {@code sql} is invalid
     */
    public void validateSql(@NonNull String sql, @Nullable CancellationSignal cancellationSignal) {
        getThreadSession().prepare(sql,
                getThreadDefaultConnectionFlags(/* readOnly =*/ true), cancellationSignal, null);
    }

    /**
     * Returns true if the database is opened as read only.
     *
     * @return True if database is opened as read only.
     */
    public boolean isReadOnly() {
        synchronized (mLock) {
            return isReadOnlyLocked();
        }
    }

    private boolean isReadOnlyLocked() {
        return (mConfigurationLocked.openFlags & OPEN_READ_MASK) == OPEN_READONLY;
    }

    /**
     * Returns true if the database is in-memory db.
     *
     * @return True if the database is in-memory.
     * @hide
     */
    public boolean isInMemoryDatabase() {
        synchronized (mLock) {
            return mConfigurationLocked.isInMemoryDb();
        }
    }

    /**
     * Returns true if the database is currently open.
     *
     * @return True if the database is currently open (has not been closed).
     */
    public boolean isOpen() {
        synchronized (mLock) {
            return mConnectionPoolLocked != null;
        }
    }

    /**
     * Return list of databases that have been concurrently opened.
     * @hide
     */
    @VisibleForTesting
    public static ArraySet<String> getConcurrentDatabasePaths() {
        synchronized (sActiveDatabases) {
            return sOpenTracker.getConcurrentDatabasePaths();
        }
    }

    /**
     * Returns true if the new version code is greater than the current database version.
     *
     * @param newVersion The new version code.
     * @return True if the new version code is greater than the current database version.
     */
    public boolean needUpgrade(int newVersion) {
        return newVersion > getVersion();
    }

    /**
     * Gets the path to the database file.
     *
     * @return The path to the database file.
     */
    public final String getPath() {
        synchronized (mLock) {
            return mConfigurationLocked.path;
        }
    }

    /**
     * Sets the locale for this database.  Does nothing if this database has
     * the {@link #NO_LOCALIZED_COLLATORS} flag set or was opened read only.
     *
     * @param locale The new locale.
     *
     * @throws SQLException if the locale could not be set.  The most common reason
     * for this is that there is no collator available for the locale you requested.
     * In this case the database remains unchanged.
     */
    public void setLocale(Locale locale) {
        if (locale == null) {
            throw new IllegalArgumentException("locale must not be null.");
        }

        synchronized (mLock) {
            throwIfNotOpenLocked();

            final Locale oldLocale = mConfigurationLocked.locale;
            mConfigurationLocked.locale = locale;
            try {
                mConnectionPoolLocked.reconfigure(mConfigurationLocked);
            } catch (RuntimeException ex) {
                mConfigurationLocked.locale = oldLocale;
                throw ex;
            }
        }
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
     * @throws IllegalStateException if input cacheSize > {@link #MAX_SQL_CACHE_SIZE}.
     */
    public void setMaxSqlCacheSize(int cacheSize) {
        if (cacheSize > MAX_SQL_CACHE_SIZE || cacheSize < 0) {
            throw new IllegalStateException(
                    "expected value between 0 and " + MAX_SQL_CACHE_SIZE);
        }

        synchronized (mLock) {
            throwIfNotOpenLocked();

            final int oldMaxSqlCacheSize = mConfigurationLocked.maxSqlCacheSize;
            mConfigurationLocked.maxSqlCacheSize = cacheSize;
            try {
                mConnectionPoolLocked.reconfigure(mConfigurationLocked);
            } catch (RuntimeException ex) {
                mConfigurationLocked.maxSqlCacheSize = oldMaxSqlCacheSize;
                throw ex;
            }
        }
    }

    /** @hide */
    @NeverCompile
    public double getStatementCacheMissRate() {
        synchronized (mLock) {
            throwIfNotOpenLocked();

            return mConnectionPoolLocked.getStatementCacheMissRate();
        }
    }

    /**
     * Sets whether foreign key constraints are enabled for the database.
     * <p>
     * By default, foreign key constraints are not enforced by the database.
     * This method allows an application to enable foreign key constraints.
     * It must be called each time the database is opened to ensure that foreign
     * key constraints are enabled for the session.
     * </p><p>
     * A good time to call this method is right after calling {@link #openOrCreateDatabase}
     * or in the {@link SQLiteOpenHelper#onConfigure} callback.
     * </p><p>
     * When foreign key constraints are disabled, the database does not check whether
     * changes to the database will violate foreign key constraints.  Likewise, when
     * foreign key constraints are disabled, the database will not execute cascade
     * delete or update triggers.  As a result, it is possible for the database
     * state to become inconsistent.  To perform a database integrity check,
     * call {@link #isDatabaseIntegrityOk}.
     * </p><p>
     * This method must not be called while a transaction is in progress.
     * </p><p>
     * See also <a href="http://sqlite.org/foreignkeys.html">SQLite Foreign Key Constraints</a>
     * for more details about foreign key constraint support.
     * </p>
     *
     * @param enable True to enable foreign key constraints, false to disable them.
     *
     * @throws IllegalStateException if the are transactions is in progress
     * when this method is called.
     */
    public void setForeignKeyConstraintsEnabled(boolean enable) {
        synchronized (mLock) {
            throwIfNotOpenLocked();

            if (mConfigurationLocked.foreignKeyConstraintsEnabled == enable) {
                return;
            }

            mConfigurationLocked.foreignKeyConstraintsEnabled = enable;
            try {
                mConnectionPoolLocked.reconfigure(mConfigurationLocked);
            } catch (RuntimeException ex) {
                mConfigurationLocked.foreignKeyConstraintsEnabled = !enable;
                throw ex;
            }
        }
    }

    /**
     * This method enables parallel execution of queries from multiple threads on the
     * same database.  It does this by opening multiple connections to the database
     * and using a different database connection for each query.  The database
     * journal mode is also changed to enable writes to proceed concurrently with reads.
     * <p>
     * When write-ahead logging is not enabled (the default), it is not possible for
     * reads and writes to occur on the database at the same time.  Before modifying the
     * database, the writer implicitly acquires an exclusive lock on the database which
     * prevents readers from accessing the database until the write is completed.
     * </p><p>
     * In contrast, when write-ahead logging is enabled (by calling this method), write
     * operations occur in a separate log file which allows reads to proceed concurrently.
     * While a write is in progress, readers on other threads will perceive the state
     * of the database as it was before the write began.  When the write completes, readers
     * on other threads will then perceive the new state of the database.
     * </p><p>
     * It is a good idea to enable write-ahead logging whenever a database will be
     * concurrently accessed and modified by multiple threads at the same time.
     * However, write-ahead logging uses significantly more memory than ordinary
     * journaling because there are multiple connections to the same database.
     * So if a database will only be used by a single thread, or if optimizing
     * concurrency is not very important, then write-ahead logging should be disabled.
     * </p><p>
     * After calling this method, execution of queries in parallel is enabled as long as
     * the database remains open.  To disable execution of queries in parallel, either
     * call {@link #disableWriteAheadLogging} or close the database and reopen it.
     * </p><p>
     * The maximum number of connections used to execute queries in parallel is
     * dependent upon the device memory and possibly other properties.
     * </p><p>
     * If a query is part of a transaction, then it is executed on the same database handle the
     * transaction was begun.
     * </p><p>
     * Writers should use {@link #beginTransactionNonExclusive()} or
     * {@link #beginTransactionWithListenerNonExclusive(SQLiteTransactionListener)}
     * to start a transaction.  Non-exclusive mode allows database file to be in readable
     * by other threads executing queries.
     * </p><p>
     * If the database has any attached databases, then execution of queries in parallel is NOT
     * possible.  Likewise, write-ahead logging is not supported for read-only databases
     * or memory databases.  In such cases, {@link #enableWriteAheadLogging} returns false.
     * </p><p>
     * The best way to enable write-ahead logging is to pass the
     * {@link #ENABLE_WRITE_AHEAD_LOGGING} flag to {@link #openDatabase}.  This is
     * more efficient than calling {@link #enableWriteAheadLogging}.
     * <code><pre>
     *     SQLiteDatabase db = SQLiteDatabase.openDatabase("db_filename", cursorFactory,
     *             SQLiteDatabase.CREATE_IF_NECESSARY | SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
     *             myDatabaseErrorHandler);
     * </pre></code>
     * </p><p>
     * Another way to enable write-ahead logging is to call {@link #enableWriteAheadLogging}
     * after opening the database.
     * <code><pre>
     *     SQLiteDatabase db = SQLiteDatabase.openDatabase("db_filename", cursorFactory,
     *             SQLiteDatabase.CREATE_IF_NECESSARY, myDatabaseErrorHandler);
     *     db.enableWriteAheadLogging();
     * </pre></code>
     * </p><p>
     * See also <a href="http://sqlite.org/wal.html">SQLite Write-Ahead Logging</a> for
     * more details about how write-ahead logging works.
     * </p>
     *
     * @return True if write-ahead logging is enabled.
     *
     * @throws IllegalStateException if there are transactions in progress at the
     * time this method is called.  WAL mode can only be changed when there are no
     * transactions in progress.
     *
     * @see #ENABLE_WRITE_AHEAD_LOGGING
     * @see #disableWriteAheadLogging
     */
    public boolean enableWriteAheadLogging() {
        synchronized (mLock) {
            throwIfNotOpenLocked();

            if (mConfigurationLocked.resolveJournalMode().equalsIgnoreCase(
                        SQLiteDatabase.JOURNAL_MODE_WAL)) {
                return true;
            }

            if (isReadOnlyLocked()) {
                // WAL doesn't make sense for readonly-databases.
                // TODO: True, but connection pooling does still make sense...
                return false;
            }

            if (mConfigurationLocked.isInMemoryDb()) {
                Log.i(TAG, "can't enable WAL for memory databases.");
                return false;
            }

            // make sure this database has NO attached databases because sqlite's write-ahead-logging
            // doesn't work for databases with attached databases
            if (mHasAttachedDbsLocked) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "this database: " + mConfigurationLocked.label
                            + " has attached databases. can't  enable WAL.");
                }
                return false;
            }

            mConfigurationLocked.openFlags |= ENABLE_WRITE_AHEAD_LOGGING;
            try {
                mConnectionPoolLocked.reconfigure(mConfigurationLocked);
            } catch (RuntimeException ex) {
                mConfigurationLocked.openFlags &= ~ENABLE_WRITE_AHEAD_LOGGING;
                throw ex;
            }
        }
        return true;
    }

    /**
     * This method disables the features enabled by {@link #enableWriteAheadLogging()}.
     *
     * @throws IllegalStateException if there are transactions in progress at the
     * time this method is called.  WAL mode can only be changed when there are no
     * transactions in progress.
     *
     * @see #enableWriteAheadLogging
     */
    public void disableWriteAheadLogging() {
        synchronized (mLock) {
            throwIfNotOpenLocked();

            final int oldFlags = mConfigurationLocked.openFlags;
            // WAL was never enabled for this database, so there's nothing left to do.
            if (!mConfigurationLocked.resolveJournalMode().equalsIgnoreCase(
                        SQLiteDatabase.JOURNAL_MODE_WAL)) {
                return;
            }

            // If an app explicitly disables WAL, it takes priority over any directive
            // to use the legacy "compatibility WAL" mode.
            mConfigurationLocked.openFlags &= ~ENABLE_WRITE_AHEAD_LOGGING;
            mConfigurationLocked.openFlags &= ~ENABLE_LEGACY_COMPATIBILITY_WAL;

            try {
                mConnectionPoolLocked.reconfigure(mConfigurationLocked);
            } catch (RuntimeException ex) {
                mConfigurationLocked.openFlags = oldFlags;
                throw ex;
            }
        }
    }

    /**
     * Returns true if write-ahead logging has been enabled for this database.
     *
     * @return True if write-ahead logging has been enabled for this database.
     *
     * @see #enableWriteAheadLogging
     * @see #ENABLE_WRITE_AHEAD_LOGGING
     */
    public boolean isWriteAheadLoggingEnabled() {
        synchronized (mLock) {
            throwIfNotOpenLocked();

            return mConfigurationLocked.resolveJournalMode().equalsIgnoreCase(
                    SQLiteDatabase.JOURNAL_MODE_WAL);
        }
    }

    /**
     * Collect statistics about all open databases in the current process.
     * Used by bug report.
     */
    static ArrayList<DbStats> getDbStats() {
        ArrayList<DbStats> dbStatsList = new ArrayList<DbStats>();
        for (SQLiteDatabase db : getActiveDatabases()) {
            db.collectDbStats(dbStatsList);
        }
        return dbStatsList;
    }

    @UnsupportedAppUsage
    private void collectDbStats(ArrayList<DbStats> dbStatsList) {
        synchronized (mLock) {
            if (mConnectionPoolLocked != null) {
                mConnectionPoolLocked.collectDbStats(dbStatsList);
            }
        }
    }

    @UnsupportedAppUsage
    private static ArrayList<SQLiteDatabase> getActiveDatabases() {
        ArrayList<SQLiteDatabase> databases = new ArrayList<SQLiteDatabase>();
        synchronized (sActiveDatabases) {
            databases.addAll(sActiveDatabases.keySet());
        }
        return databases;
    }

    private static ArrayList<SQLiteConnectionPool> getActiveDatabasePools() {
        ArrayList<SQLiteConnectionPool> connectionPools = new ArrayList<SQLiteConnectionPool>();
        synchronized (sActiveDatabases) {
            for (SQLiteDatabase db : sActiveDatabases.keySet()) {
                synchronized (db.mLock) {
                    if (db.mConnectionPoolLocked != null) {
                        connectionPools.add(db.mConnectionPoolLocked);
                    }
                }
            }
        }
        return connectionPools;
    }

    /** @hide */
    @NeverCompile
    public int getTotalPreparedStatements() {
        throwIfNotOpenLocked();

        return mConnectionPoolLocked.mTotalPrepareStatements;
    }

    /** @hide */
    @NeverCompile
    public int getTotalStatementCacheMisses() {
        throwIfNotOpenLocked();

        return mConnectionPoolLocked.mTotalPrepareStatementCacheMiss;
    }

    /**
     * Dump detailed information about all open databases in the current process.
     * Used by bug report.
     */
    static void dumpAll(Printer printer, boolean verbose, boolean isSystem) {
        // Use this ArraySet to collect file paths.
        final ArraySet<String> directories = new ArraySet<>();

        // Accounting across all databases
        long totalStatementsTimeInMs = 0;
        long totalStatementsCount = 0;

        ArrayList<SQLiteConnectionPool> activeConnectionPools = getActiveDatabasePools();

        activeConnectionPools.sort(
                (a, b) -> Long.compare(b.getTotalStatementsCount(), a.getTotalStatementsCount()));
        for (SQLiteConnectionPool dbPool : activeConnectionPools) {
            dbPool.dump(printer, verbose, directories);
            totalStatementsTimeInMs += dbPool.getTotalStatementsTime();
            totalStatementsCount += dbPool.getTotalStatementsCount();
        }

        if (totalStatementsCount > 0) {
            // Only print when there is information available

            // Sorted statements per database
            printer.println("Statements Executed per Database");
            for (SQLiteConnectionPool dbPool : activeConnectionPools) {
                printer.println(
                        "  " + dbPool.getPath() + " :    " + dbPool.getTotalStatementsCount());
            }
            printer.println("");
            printer.println(
                    "Total Statements Executed for all Active Databases: " + totalStatementsCount);

            // Sorted execution time per database
            activeConnectionPools.sort(
                    (a, b) -> Long.compare(b.getTotalStatementsTime(), a.getTotalStatementsTime()));
            printer.println("");
            printer.println("");
            printer.println("Statement Time per Database (ms)");
            for (SQLiteConnectionPool dbPool : activeConnectionPools) {
                printer.println(
                        "  " + dbPool.getPath() + " :    " + dbPool.getTotalStatementsTime());
            }
            printer.println("Total Statements Time for all Active Databases (ms): "
                    + totalStatementsTimeInMs);
        }

        // Dump DB files in the directories.
        if (directories.size() > 0) {
            final String[] dirs = directories.toArray(new String[directories.size()]);
            Arrays.sort(dirs);
            for (String dir : dirs) {
                dumpDatabaseDirectory(printer, new File(dir), isSystem);
            }
        }

        // Dump concurrently-opened database files, if any
        final ArraySet<String> concurrent;
        synchronized (sActiveDatabases) {
            concurrent = sOpenTracker.getConcurrentDatabasePaths();
        }
        if (concurrent.size() > 0) {
            printer.println("");
            printer.println("Concurrently opened database files");
            for (String f : concurrent) {
                printer.println("  " + f);
            }
        }
    }

    private static void dumpDatabaseDirectory(Printer pw, File dir, boolean isSystem) {
        pw.println("");
        pw.println("Database files in " + dir.getAbsolutePath() + ":");
        final File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            pw.println("  [none]");
            return;
        }
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));

        for (File f : files) {
            if (isSystem) {
                // If called within the system server, the directory contains other files too, so
                // filter by file extensions.
                // (If it's an app, just print all files because they may not use *.db
                // extension.)
                final String name = f.getName();
                if (!(name.endsWith(".db") || name.endsWith(".db-wal")
                        || name.endsWith(".db-journal")
                        || name.endsWith(SQLiteGlobal.WIPE_CHECK_FILE_SUFFIX))) {
                    continue;
                }
            }
            pw.println(String.format("  %-40s %7db %s", f.getName(), f.length(),
                    SQLiteDatabase.getFileTimestamps(f.getAbsolutePath())));
        }
    }

    /**
     * Returns list of full pathnames of all attached databases including the main database
     * by executing 'pragma database_list' on the database.
     *
     * @return ArrayList of pairs of (database name, database file path) or null if the database
     * is not open.
     */
    public List<Pair<String, String>> getAttachedDbs() {
        ArrayList<Pair<String, String>> attachedDbs = new ArrayList<Pair<String, String>>();
        synchronized (mLock) {
            if (mConnectionPoolLocked == null) {
                return null; // not open
            }

            if (!mHasAttachedDbsLocked) {
                // No attached databases.
                // There is a small window where attached databases exist but this flag is not
                // set yet.  This can occur when this thread is in a race condition with another
                // thread that is executing the SQL statement: "attach database <blah> as <foo>"
                // If this thread is NOT ok with such a race condition (and thus possibly not
                // receivethe entire list of attached databases), then the caller should ensure
                // that no thread is executing any SQL statements while a thread is calling this
                // method.  Typically, this method is called when 'adb bugreport' is done or the
                // caller wants to collect stats on the database and all its attached databases.
                attachedDbs.add(new Pair<String, String>("main", mConfigurationLocked.path));
                return attachedDbs;
            }

            acquireReference();
        }

        try {
            // has attached databases. query sqlite to get the list of attached databases.
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
        } finally {
            releaseReference();
        }
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
        acquireReference();
        try {
            List<Pair<String, String>> attachedDbs = null;
            try {
                attachedDbs = getAttachedDbs();
                if (attachedDbs == null) {
                    throw new IllegalStateException("databaselist for: " + getPath() + " couldn't " +
                            "be retrieved. probably because the database is closed");
                }
            } catch (SQLiteException e) {
                // can't get attachedDb list. do integrity check on the main database
                attachedDbs = new ArrayList<Pair<String, String>>();
                attachedDbs.add(new Pair<String, String>("main", getPath()));
            }

            for (int i = 0; i < attachedDbs.size(); i++) {
                Pair<String, String> p = attachedDbs.get(i);
                SQLiteStatement prog = null;
                try {
                    prog = compileStatement("PRAGMA " + p.first + ".integrity_check(1);");
                    String rslt = prog.simpleQueryForString();
                    if (!rslt.equalsIgnoreCase("ok")) {
                        // integrity_checker failed on main or attached databases
                        Log.e(TAG, "PRAGMA integrity_check on " + p.second + " returned: " + rslt);
                        return false;
                    }
                } finally {
                    if (prog != null) prog.close();
                }
            }
        } finally {
            releaseReference();
        }
        return true;
    }

    @Override
    public String toString() {
        return "SQLiteDatabase: " + getPath();
    }

    private void throwIfNotOpenLocked() {
        if (mConnectionPoolLocked == null) {
            throw new IllegalStateException("The database '" + mConfigurationLocked.label
                    + "' is not open.");
        }
    }

    /**
     * Used to allow returning sub-classes of {@link Cursor} when calling query.
     */
    public interface CursorFactory {
        /**
         * See {@link SQLiteCursor#SQLiteCursor(SQLiteCursorDriver, String, SQLiteQuery)}.
         */
        public Cursor newCursor(SQLiteDatabase db,
                SQLiteCursorDriver masterQuery, String editTable,
                SQLiteQuery query);
    }

    /**
     * A callback interface for a custom sqlite3 function.
     * This can be used to create a function that can be called from
     * sqlite3 database triggers.
     * @hide
     */
    public interface CustomFunction {
        public void callback(String[] args);
    }

    /**
     * Wrapper for configuration parameters that are used for opening {@link SQLiteDatabase}
     */
    public static final class OpenParams {
        private final int mOpenFlags;
        private final CursorFactory mCursorFactory;
        private final DatabaseErrorHandler mErrorHandler;
        private final int mLookasideSlotSize;
        private final int mLookasideSlotCount;
        private final long mIdleConnectionTimeout;
        private final String mJournalMode;
        private final String mSyncMode;

        private OpenParams(int openFlags, CursorFactory cursorFactory,
                DatabaseErrorHandler errorHandler, int lookasideSlotSize, int lookasideSlotCount,
                long idleConnectionTimeout, String journalMode, String syncMode) {
            mOpenFlags = openFlags;
            mCursorFactory = cursorFactory;
            mErrorHandler = errorHandler;
            mLookasideSlotSize = lookasideSlotSize;
            mLookasideSlotCount = lookasideSlotCount;
            mIdleConnectionTimeout = idleConnectionTimeout;
            mJournalMode = journalMode;
            mSyncMode = syncMode;
        }

        /**
         * Returns size in bytes of each lookaside slot or -1 if not set.
         *
         * @see Builder#setLookasideConfig(int, int)
         */
        @IntRange(from = -1)
        public int getLookasideSlotSize() {
            return mLookasideSlotSize;
        }

        /**
         * Returns total number of lookaside memory slots per database connection or -1 if not
         * set.
         *
         * @see Builder#setLookasideConfig(int, int)
         */
        @IntRange(from = -1)
        public int getLookasideSlotCount() {
            return mLookasideSlotCount;
        }

        /**
         * Returns flags to control database access mode. Default value is 0.
         *
         * @see Builder#setOpenFlags(int)
         */
        @DatabaseOpenFlags
        public int getOpenFlags() {
            return mOpenFlags;
        }

        /**
         * Returns an optional factory class that is called to instantiate a cursor when query
         * is called
         *
         * @see Builder#setCursorFactory(CursorFactory)
         */
        @Nullable
        public CursorFactory getCursorFactory() {
            return mCursorFactory;
        }

        /**
         * Returns handler for database corruption errors
         *
         * @see Builder#setErrorHandler(DatabaseErrorHandler)
         */
        @Nullable
        public DatabaseErrorHandler getErrorHandler() {
            return mErrorHandler;
        }

        /**
         * Returns maximum number of milliseconds that SQLite connection is allowed to be idle
         * before it is closed and removed from the pool.
         * <p>If the value isn't set, the timeout defaults to the system wide timeout
         *
         * @return timeout in milliseconds or -1 if the value wasn't set.
         */
        public long getIdleConnectionTimeout() {
            return mIdleConnectionTimeout;
        }

        /**
         * Returns <a href="https://sqlite.org/pragma.html#pragma_journal_mode">journal mode</a>.
         * set via {@link Builder#setJournalMode(String)}.
         */
        @Nullable
        public String getJournalMode() {
            return mJournalMode;
        }

        /**
         * Returns <a href="https://sqlite.org/pragma.html#pragma_synchronous">synchronous mode</a>.
         * If not set, a system wide default will be used.
         * @see Builder#setSynchronousMode(String)
         */
        @Nullable
        public String getSynchronousMode() {
            return mSyncMode;
        }

        /**
         * Creates a new instance of builder {@link Builder#Builder(OpenParams) initialized} with
         * {@code this} parameters.
         * @hide
         */
        @NonNull
        public Builder toBuilder() {
            return new Builder(this);
        }

        /**
         * Builder for {@link OpenParams}.
         */
        public static final class Builder {
            private int mLookasideSlotSize = -1;
            private int mLookasideSlotCount = -1;
            private long mIdleConnectionTimeout = -1;
            private int mOpenFlags;
            private CursorFactory mCursorFactory;
            private DatabaseErrorHandler mErrorHandler;
            private String mJournalMode;
            private String mSyncMode;

            public Builder() {
            }

            public Builder(OpenParams params) {
                mLookasideSlotSize = params.mLookasideSlotSize;
                mLookasideSlotCount = params.mLookasideSlotCount;
                mOpenFlags = params.mOpenFlags;
                mCursorFactory = params.mCursorFactory;
                mErrorHandler = params.mErrorHandler;
                mJournalMode = params.mJournalMode;
                mSyncMode = params.mSyncMode;
            }

            /**
             * Configures
             * <a href="https://sqlite.org/malloc.html#lookaside">lookaside memory allocator</a>
             *
             * <p>SQLite default settings will be used, if this method isn't called.
             * Use {@code setLookasideConfig(0,0)} to disable lookaside
             *
             * <p><strong>Note:</strong> Provided slotSize/slotCount configuration is just a
             * recommendation. The system may choose different values depending on a device, e.g.
             * lookaside allocations can be disabled on low-RAM devices
             *
             * @param slotSize The size in bytes of each lookaside slot.
             * @param slotCount The total number of lookaside memory slots per database connection.
             */
            public Builder setLookasideConfig(@IntRange(from = 0) final int slotSize,
                    @IntRange(from = 0) final int slotCount) {
                Preconditions.checkArgument(slotSize >= 0,
                        "lookasideSlotCount cannot be negative");
                Preconditions.checkArgument(slotCount >= 0,
                        "lookasideSlotSize cannot be negative");
                Preconditions.checkArgument(
                        (slotSize > 0 && slotCount > 0) || (slotCount == 0 && slotSize == 0),
                        "Invalid configuration: %d, %d", slotSize, slotCount);

                mLookasideSlotSize = slotSize;
                mLookasideSlotCount = slotCount;
                return this;
            }

            /**
             * Returns true if {@link #ENABLE_WRITE_AHEAD_LOGGING} flag is set
             * @hide
             */
            public boolean isWriteAheadLoggingEnabled() {
                return (mOpenFlags & ENABLE_WRITE_AHEAD_LOGGING) != 0;
            }

            /**
             * Sets flags to control database access mode
             * @param openFlags The new flags to set
             * @see #OPEN_READWRITE
             * @see #OPEN_READONLY
             * @see #CREATE_IF_NECESSARY
             * @see #NO_LOCALIZED_COLLATORS
             * @see #ENABLE_WRITE_AHEAD_LOGGING
             * @return same builder instance for chaining multiple calls into a single statement
             */
            @NonNull
            public Builder setOpenFlags(@DatabaseOpenFlags int openFlags) {
                mOpenFlags = openFlags;
                return this;
            }

            /**
             * Adds flags to control database access mode
             *
             * @param openFlags The new flags to add
             * @return same builder instance for chaining multiple calls into a single statement
             */
            @NonNull
            public Builder addOpenFlags(@DatabaseOpenFlags int openFlags) {
                mOpenFlags |= openFlags;
                return this;
            }

            /**
             * Removes database access mode flags
             *
             * @param openFlags Flags to remove
             * @return same builder instance for chaining multiple calls into a single statement
             */
            @NonNull
            public Builder removeOpenFlags(@DatabaseOpenFlags int openFlags) {
                mOpenFlags &= ~openFlags;
                return this;
            }

            /**
             * Sets {@link #ENABLE_WRITE_AHEAD_LOGGING} flag if {@code enabled} is {@code true},
             * unsets otherwise
             * @hide
             */
            public void setWriteAheadLoggingEnabled(boolean enabled) {
                if (enabled) {
                    addOpenFlags(ENABLE_WRITE_AHEAD_LOGGING);
                } else {
                    removeOpenFlags(ENABLE_WRITE_AHEAD_LOGGING);
                }
            }

            /**
             * Set an optional factory class that is called to instantiate a cursor when query
             * is called.
             *
             * @param cursorFactory instance
             * @return same builder instance for chaining multiple calls into a single statement
             */
            @NonNull
            public Builder setCursorFactory(@Nullable CursorFactory cursorFactory) {
                mCursorFactory = cursorFactory;
                return this;
            }


            /**
             * Sets {@link DatabaseErrorHandler} object to handle db corruption errors
             */
            @NonNull
            public Builder setErrorHandler(@Nullable DatabaseErrorHandler errorHandler) {
                mErrorHandler = errorHandler;
                return this;
            }

            /**
             * Sets the maximum number of milliseconds that SQLite connection is allowed to be idle
             * before it is closed and removed from the pool.
             *
             * <p><b>DO NOT USE</b> this method.
             * This feature has negative side effects that are very hard to foresee.
             * <p>A connection timeout allows the system to internally close a connection to
             * a SQLite database after a given timeout, which is good for reducing app's memory
             * consumption.
             * <b>However</b> the side effect is it <b>will reset all of SQLite's per-connection
             * states</b>, which are typically modified with a {@code PRAGMA} statement, and
             * these states <b>will not be restored</b> when a connection is re-established
             * internally, and the system does not provide a callback for an app to reconfigure a
             * connection.
             * This feature may only be used if an app relies on none of such per-connection states.
             *
             * @param idleConnectionTimeoutMs timeout in milliseconds. Use {@link Long#MAX_VALUE}
             * to allow unlimited idle connections.
             *
             * @see SQLiteOpenHelper#setIdleConnectionTimeout(long)
             *
             * @deprecated DO NOT USE this method. See the javadoc for the details.
             */
            @NonNull
            @Deprecated
            public Builder setIdleConnectionTimeout(
                    @IntRange(from = 0) long idleConnectionTimeoutMs) {
                Preconditions.checkArgument(idleConnectionTimeoutMs >= 0,
                        "idle connection timeout cannot be negative");
                mIdleConnectionTimeout = idleConnectionTimeoutMs;
                return this;
            }

            /**
             * Sets <a href="https://sqlite.org/pragma.html#pragma_journal_mode">journal mode</a>
             * to use.
             *
             * <p>Note: If journal mode is not set, the platform will use a manufactured-specified
             * default which can vary across devices.
             */
            @NonNull
            public Builder setJournalMode(@JournalMode @NonNull String journalMode) {
                Objects.requireNonNull(journalMode);
                mJournalMode = journalMode;
                return this;
            }

            /**
             * Sets <a href="https://sqlite.org/pragma.html#pragma_synchronous">synchronous mode</a>
             *
             * <p>Note: If sync mode is not set, the platform will use a manufactured-specified
             * default which can vary across devices.
             */
            @NonNull
            public Builder setSynchronousMode(@SyncMode @NonNull String syncMode) {
                Objects.requireNonNull(syncMode);
                mSyncMode = syncMode;
                return this;
            }

            /**
             * Creates an instance of {@link OpenParams} with the options that were previously set
             * on this builder
             */
            @NonNull
            public OpenParams build() {
                return new OpenParams(mOpenFlags, mCursorFactory, mErrorHandler, mLookasideSlotSize,
                        mLookasideSlotCount, mIdleConnectionTimeout, mJournalMode, mSyncMode);
            }
        }
    }

    /** @hide */
    @IntDef(flag = true, prefix = {"OPEN_", "CREATE_", "NO_", "ENABLE_"}, value = {
            OPEN_READWRITE,
            OPEN_READONLY,
            CREATE_IF_NECESSARY,
            NO_LOCALIZED_COLLATORS,
            ENABLE_WRITE_AHEAD_LOGGING
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DatabaseOpenFlags {}

    /** @hide */
    public static void wipeDetected(String filename, String reason) {
        wtfAsSystemServer(TAG, "DB wipe detected:"
                + " package=" + ActivityThread.currentPackageName()
                + " reason=" + reason
                + " file=" + filename
                + " " + getFileTimestamps(filename)
                + " checkfile " + getFileTimestamps(filename + SQLiteGlobal.WIPE_CHECK_FILE_SUFFIX),
                new Throwable("STACKTRACE"));
    }

    /** @hide */
    public static String getFileTimestamps(String path) {
        try {
            BasicFileAttributes attr = Files.readAttributes(
                    FileSystems.getDefault().getPath(path), BasicFileAttributes.class);
            return "ctime=" + attr.creationTime()
                    + " mtime=" + attr.lastModifiedTime()
                    + " atime=" + attr.lastAccessTime();
        } catch (IOException e) {
            return "[unable to obtain timestamp]";
        }
    }

    /** @hide */
    static void wtfAsSystemServer(String tag, String message, Throwable stacktrace) {
        Log.e(tag, message, stacktrace);
        ContentResolver.onDbCorruption(tag, message, stacktrace);
    }
}
