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

import android.annotation.NonNull;
import com.android.internal.annotations.GuardedBy;

import android.database.Cursor;
import android.database.CursorWindow;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDebug.DbStats;
import android.database.sqlite.SQLiteDebug.NoPreloadHolder;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.Trace;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.util.Pair;
import android.util.Printer;
import dalvik.system.BlockGuard;
import dalvik.system.CloseGuard;
import java.io.File;
import java.io.IOException;
import java.lang.ref.Reference;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

/**
 * Represents a SQLite database connection.
 * Each connection wraps an instance of a native <code>sqlite3</code> object.
 * <p>
 * When database connection pooling is enabled, there can be multiple active
 * connections to the same database.  Otherwise there is typically only one
 * connection per database.
 * </p><p>
 * When the SQLite WAL feature is enabled, multiple readers and one writer
 * can concurrently access the database.  Without WAL, readers and writers
 * are mutually exclusive.
 * </p>
 *
 * <h2>Ownership and concurrency guarantees</h2>
 * <p>
 * Connection objects are not thread-safe.  They are acquired as needed to
 * perform a database operation and are then returned to the pool.  At any
 * given time, a connection is either owned and used by a {@link SQLiteSession}
 * object or the {@link SQLiteConnectionPool}.  Those classes are
 * responsible for serializing operations to guard against concurrent
 * use of a connection.
 * </p><p>
 * The guarantee of having a single owner allows this class to be implemented
 * without locks and greatly simplifies resource management.
 * </p>
 *
 * <h2>Encapsulation guarantees</h2>
 * <p>
 * The connection object object owns *all* of the SQLite related native
 * objects that are associated with the connection.  What's more, there are
 * no other objects in the system that are capable of obtaining handles to
 * those native objects.  Consequently, when the connection is closed, we do
 * not have to worry about what other components might have references to
 * its associated SQLite state -- there are none.
 * </p><p>
 * Encapsulation is what ensures that the connection object's
 * lifecycle does not become a tortured mess of finalizers and reference
 * queues.
 * </p>
 *
 * <h2>Reentrance</h2>
 * <p>
 * This class must tolerate reentrant execution of SQLite operations because
 * triggers may call custom SQLite functions that perform additional queries.
 * </p>
 *
 * @hide
 */
public final class SQLiteConnection implements CancellationSignal.OnCancelListener {
    private static final String TAG = "SQLiteConnection";
    private static final boolean DEBUG = false;

    private static final String[] EMPTY_STRING_ARRAY = new String[0];
    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

    private final CloseGuard mCloseGuard = CloseGuard.get();

    private final SQLiteConnectionPool mPool;
    private final SQLiteDatabaseConfiguration mConfiguration;
    private final int mConnectionId;
    private final boolean mIsPrimaryConnection;
    private final boolean mIsReadOnlyConnection;
    private PreparedStatement mPreparedStatementPool;

    private final PreparedStatementCache mPreparedStatementCache;

    // The recent operations log.
    private final OperationLog mRecentOperations;

    // The native SQLiteConnection pointer.  (FOR INTERNAL USE ONLY)
    private long mConnectionPtr;

    private boolean mOnlyAllowReadOnlyOperations;

    // The number of times attachCancellationSignal has been called.
    // Because SQLite statement execution can be reentrant, we keep track of how many
    // times we have attempted to attach a cancellation signal to the connection so that
    // we can ensure that we detach the signal at the right time.
    private int mCancellationSignalAttachCount;

    private static native long nativeOpen(String path, int openFlags, String label,
            boolean enableTrace, boolean enableProfile, int lookasideSlotSize,
            int lookasideSlotCount);
    private static native void nativeClose(long connectionPtr);
    private static native void nativeRegisterCustomScalarFunction(long connectionPtr,
            String name, UnaryOperator<String> function);
    private static native void nativeRegisterCustomAggregateFunction(long connectionPtr,
            String name, BinaryOperator<String> function);
    private static native void nativeRegisterLocalizedCollators(long connectionPtr, String locale);
    private static native long nativePrepareStatement(long connectionPtr, String sql);
    private static native void nativeFinalizeStatement(long connectionPtr, long statementPtr);
    private static native int nativeGetParameterCount(long connectionPtr, long statementPtr);
    private static native boolean nativeIsReadOnly(long connectionPtr, long statementPtr);
    private static native int nativeGetColumnCount(long connectionPtr, long statementPtr);
    private static native String nativeGetColumnName(long connectionPtr, long statementPtr,
            int index);
    private static native void nativeBindNull(long connectionPtr, long statementPtr,
            int index);
    private static native void nativeBindLong(long connectionPtr, long statementPtr,
            int index, long value);
    private static native void nativeBindDouble(long connectionPtr, long statementPtr,
            int index, double value);
    private static native void nativeBindString(long connectionPtr, long statementPtr,
            int index, String value);
    private static native void nativeBindBlob(long connectionPtr, long statementPtr,
            int index, byte[] value);
    private static native void nativeResetStatementAndClearBindings(
            long connectionPtr, long statementPtr);
    private static native void nativeExecute(long connectionPtr, long statementPtr,
            boolean isPragmaStmt);
    private static native long nativeExecuteForLong(long connectionPtr, long statementPtr);
    private static native String nativeExecuteForString(long connectionPtr, long statementPtr);
    private static native int nativeExecuteForBlobFileDescriptor(
            long connectionPtr, long statementPtr);
    private static native int nativeExecuteForChangedRowCount(long connectionPtr, long statementPtr);
    private static native long nativeExecuteForLastInsertedRowId(
            long connectionPtr, long statementPtr);
    private static native long nativeExecuteForCursorWindow(
            long connectionPtr, long statementPtr, long windowPtr,
            int startPos, int requiredPos, boolean countAllRows);
    private static native int nativeGetDbLookaside(long connectionPtr);
    private static native void nativeCancel(long connectionPtr);
    private static native void nativeResetCancel(long connectionPtr, boolean cancelable);
    private static native int nativeLastInsertRowId(long connectionPtr);
    private static native long nativeChanges(long connectionPtr);
    private static native long nativeTotalChanges(long connectionPtr);

    private SQLiteConnection(SQLiteConnectionPool pool,
            SQLiteDatabaseConfiguration configuration,
            int connectionId, boolean primaryConnection) {
        mPool = pool;
        mRecentOperations = new OperationLog(mPool);
        mConfiguration = new SQLiteDatabaseConfiguration(configuration);
        mConnectionId = connectionId;
        mIsPrimaryConnection = primaryConnection;
        mIsReadOnlyConnection = mConfiguration.isReadOnlyDatabase();
        mPreparedStatementCache = new PreparedStatementCache(
                mConfiguration.maxSqlCacheSize);
        mCloseGuard.open("SQLiteConnection.close");
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mPool != null && mConnectionPtr != 0) {
                mPool.onConnectionLeaked();
            }

            dispose(true);
        } finally {
            super.finalize();
        }
    }

    // Called by SQLiteConnectionPool only.
    static SQLiteConnection open(SQLiteConnectionPool pool,
            SQLiteDatabaseConfiguration configuration,
            int connectionId, boolean primaryConnection) {
        SQLiteConnection connection = new SQLiteConnection(pool, configuration,
                connectionId, primaryConnection);
        try {
            connection.open();
            return connection;
        } catch (SQLiteException ex) {
            connection.dispose(false);
            throw ex;
        }
    }

    // Called by SQLiteConnectionPool only.
    // Closes the database closes and releases all of its associated resources.
    // Do not call methods on the connection after it is closed.  It will probably crash.
    void close() {
        dispose(false);
    }

    private void open() {
        final String file = mConfiguration.path;
        final int cookie = mRecentOperations.beginOperation("open", null, null);
        try {
            mConnectionPtr = nativeOpen(file, mConfiguration.openFlags,
                    mConfiguration.label,
                    NoPreloadHolder.DEBUG_SQL_STATEMENTS, NoPreloadHolder.DEBUG_SQL_TIME,
                    mConfiguration.lookasideSlotSize, mConfiguration.lookasideSlotCount);
        } catch (SQLiteCantOpenDatabaseException e) {
            final StringBuilder message = new StringBuilder("Cannot open database '")
                    .append(file).append('\'')
                    .append(" with flags 0x")
                    .append(Integer.toHexString(mConfiguration.openFlags));

            try {
                // Try to diagnose for common reasons. If something fails in here, that's fine;
                // just swallow the exception.

                final Path path = FileSystems.getDefault().getPath(file);
                final Path dir = path.getParent();
                if (dir == null) {
                    message.append(": Directory not specified in the file path");
                } else if (!Files.isDirectory(dir)) {
                    message.append(": Directory ").append(dir).append(" doesn't exist");
                } else if (!Files.exists(path)) {
                    message.append(": File ").append(path).append(
                            " doesn't exist");
                    if ((mConfiguration.openFlags & SQLiteDatabase.CREATE_IF_NECESSARY) != 0) {
                        message.append(
                                " and CREATE_IF_NECESSARY is set, check directory permissions");
                    }
                } else if (!Files.isReadable(path)) {
                    message.append(": File ").append(path).append(" is not readable");
                } else if (Files.isDirectory(path)) {
                    message.append(": Path ").append(path).append(" is a directory");
                } else {
                    message.append(": Unable to deduct failure reason");
                }
            } catch (Throwable th) {
                message.append(": Unable to deduct failure reason"
                        + " because filesystem couldn't be examined: ").append(th.getMessage());
            }
            throw new SQLiteCantOpenDatabaseException(message.toString(), e);
        } finally {
            mRecentOperations.endOperation(cookie);
        }
        setPageSize();
        setForeignKeyModeFromConfiguration();
        setJournalFromConfiguration();
        setSyncModeFromConfiguration();
        setJournalSizeLimit();
        setAutoCheckpointInterval();
        setLocaleFromConfiguration();
        setCustomFunctionsFromConfiguration();
        executePerConnectionSqlFromConfiguration(0);
    }

    private void dispose(boolean finalized) {
        if (mCloseGuard != null) {
            if (finalized) {
                mCloseGuard.warnIfOpen();
            }
            mCloseGuard.close();
        }

        if (mConnectionPtr != 0) {
            final int cookie = mRecentOperations.beginOperation("close", null, null);
            try {
                mPreparedStatementCache.evictAll();
                nativeClose(mConnectionPtr);
                mConnectionPtr = 0;
            } finally {
                mRecentOperations.endOperation(cookie);
            }
        }
    }

    private void setPageSize() {
        if (!mConfiguration.isInMemoryDb() && !mIsReadOnlyConnection) {
            final long newValue = SQLiteGlobal.getDefaultPageSize();
            long value = executeForLong("PRAGMA page_size", null, null);
            if (value != newValue) {
                execute("PRAGMA page_size=" + newValue, null, null);
            }
        }
    }

    private void setAutoCheckpointInterval() {
        if (!mConfiguration.isInMemoryDb() && !mIsReadOnlyConnection) {
            final long newValue = SQLiteGlobal.getWALAutoCheckpoint();
            long value = executeForLong("PRAGMA wal_autocheckpoint", null, null);
            if (value != newValue) {
                executeForLong("PRAGMA wal_autocheckpoint=" + newValue, null, null);
            }
        }
    }

    private void setJournalSizeLimit() {
        if (!mConfiguration.isInMemoryDb() && !mIsReadOnlyConnection) {
            final long newValue = SQLiteGlobal.getJournalSizeLimit();
            long value = executeForLong("PRAGMA journal_size_limit", null, null);
            if (value != newValue) {
                executeForLong("PRAGMA journal_size_limit=" + newValue, null, null);
            }
        }
    }

    private void setForeignKeyModeFromConfiguration() {
        if (!mIsReadOnlyConnection) {
            final long newValue = mConfiguration.foreignKeyConstraintsEnabled ? 1 : 0;
            long value = executeForLong("PRAGMA foreign_keys", null, null);
            if (value != newValue) {
                execute("PRAGMA foreign_keys=" + newValue, null, null);
            }
        }
    }

    private void setJournalFromConfiguration() {
        if (!mIsReadOnlyConnection) {
            setJournalMode(mConfiguration.resolveJournalMode());
            maybeTruncateWalFile();
        } else {
            // No need to truncate for read only databases.
            mConfiguration.shouldTruncateWalFile = false;
        }
    }

    private void setSyncModeFromConfiguration() {
        if (!mIsReadOnlyConnection) {
            setSyncMode(mConfiguration.resolveSyncMode());
        }
    }

    /**
     * If the WAL file exists and larger than a threshold, truncate it by executing
     * PRAGMA wal_checkpoint.
     */
    private void maybeTruncateWalFile() {
        if (!mConfiguration.shouldTruncateWalFile) {
            return;
        }

        final long threshold = SQLiteGlobal.getWALTruncateSize();
        if (DEBUG) {
            Log.d(TAG, "Truncate threshold=" + threshold);
        }
        if (threshold == 0) {
            return;
        }

        final File walFile = new File(mConfiguration.path + "-wal");
        if (!walFile.isFile()) {
            return;
        }
        final long size = walFile.length();
        if (size < threshold) {
            if (DEBUG) {
                Log.d(TAG, walFile.getAbsolutePath() + " " + size + " bytes: No need to truncate");
            }
            return;
        }

        Log.i(TAG, walFile.getAbsolutePath() + " " + size + " bytes: Bigger than "
                + threshold + "; truncating");
        try {
            executeForString("PRAGMA wal_checkpoint(TRUNCATE)", null, null);
            mConfiguration.shouldTruncateWalFile = false;
        } catch (SQLiteException e) {
            Log.w(TAG, "Failed to truncate the -wal file", e);
        }
    }

    private void setSyncMode(@SQLiteDatabase.SyncMode String newValue) {
        if (TextUtils.isEmpty(newValue)) {
            // No change to the sync mode is intended
            return;
        }
        String value = executeForString("PRAGMA synchronous", null, null);
        if (!canonicalizeSyncMode(value).equalsIgnoreCase(
                canonicalizeSyncMode(newValue))) {
            execute("PRAGMA synchronous=" + newValue, null, null);
        }
    }

    private static @SQLiteDatabase.SyncMode String canonicalizeSyncMode(String value) {
        switch (value) {
            case "0": return SQLiteDatabase.SYNC_MODE_OFF;
            case "1": return SQLiteDatabase.SYNC_MODE_NORMAL;
            case "2": return SQLiteDatabase.SYNC_MODE_FULL;
            case "3": return SQLiteDatabase.SYNC_MODE_EXTRA;
        }
        return value;
    }

    private void setJournalMode(@SQLiteDatabase.JournalMode String newValue) {
        if (TextUtils.isEmpty(newValue)) {
            // No change to the journal mode is intended
            return;
        }
        String value = executeForString("PRAGMA journal_mode", null, null);
        if (!value.equalsIgnoreCase(newValue)) {
            try {
                String result = executeForString("PRAGMA journal_mode=" + newValue, null, null);
                if (result.equalsIgnoreCase(newValue)) {
                    return;
                }
                // PRAGMA journal_mode silently fails and returns the original journal
                // mode in some cases if the journal mode could not be changed.
            } catch (SQLiteDatabaseLockedException ex) {
                // This error (SQLITE_BUSY) occurs if one connection has the database
                // open in WAL mode and another tries to change it to non-WAL.
            }
            // Because we always disable WAL mode when a database is first opened
            // (even if we intend to re-enable it), we can encounter problems if
            // there is another open connection to the database somewhere.
            // This can happen for a variety of reasons such as an application opening
            // the same database in multiple processes at the same time or if there is a
            // crashing content provider service that the ActivityManager has
            // removed from its registry but whose process hasn't quite died yet
            // by the time it is restarted in a new process.
            //
            // If we don't change the journal mode, nothing really bad happens.
            // In the worst case, an application that enables WAL might not actually
            // get it, although it can still use connection pooling.
            Log.w(TAG, "Could not change the database journal mode of '"
                    + mConfiguration.label + "' from '" + value + "' to '" + newValue
                    + "' because the database is locked.  This usually means that "
                    + "there are other open connections to the database which prevents "
                    + "the database from enabling or disabling write-ahead logging mode.  "
                    + "Proceeding without changing the journal mode.");
        }
    }

    private void setLocaleFromConfiguration() {
        if ((mConfiguration.openFlags & SQLiteDatabase.NO_LOCALIZED_COLLATORS) != 0) {
            return;
        }

        // Register the localized collators.
        final String newLocale = mConfiguration.locale.toString();
        nativeRegisterLocalizedCollators(mConnectionPtr, newLocale);

        if (!mConfiguration.isInMemoryDb()) {
            checkDatabaseWiped();
        }

        // If the database is read-only, we cannot modify the android metadata table
        // or existing indexes.
        if (mIsReadOnlyConnection) {
            return;
        }

        try {
            // Ensure the android metadata table exists.
            execute("CREATE TABLE IF NOT EXISTS android_metadata (locale TEXT)", null, null);

            // Check whether the locale was actually changed.
            final String oldLocale = executeForString("SELECT locale FROM android_metadata "
                    + "UNION SELECT NULL ORDER BY locale DESC LIMIT 1", null, null);
            if (oldLocale != null && oldLocale.equals(newLocale)) {
                return;
            }

            // Go ahead and update the indexes using the new locale.
            execute("BEGIN", null, null);
            boolean success = false;
            try {
                execute("DELETE FROM android_metadata", null, null);
                execute("INSERT INTO android_metadata (locale) VALUES(?)",
                        new Object[] { newLocale }, null);
                execute("REINDEX LOCALIZED", null, null);
                success = true;
            } finally {
                execute(success ? "COMMIT" : "ROLLBACK", null, null);
            }
        } catch (SQLiteException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new SQLiteException("Failed to change locale for db '" + mConfiguration.label
                    + "' to '" + newLocale + "'.", ex);
        }
    }

    private void setCustomFunctionsFromConfiguration() {
        for (int i = 0; i < mConfiguration.customScalarFunctions.size(); i++) {
            nativeRegisterCustomScalarFunction(mConnectionPtr,
                    mConfiguration.customScalarFunctions.keyAt(i),
                    mConfiguration.customScalarFunctions.valueAt(i));
        }
        for (int i = 0; i < mConfiguration.customAggregateFunctions.size(); i++) {
            nativeRegisterCustomAggregateFunction(mConnectionPtr,
                    mConfiguration.customAggregateFunctions.keyAt(i),
                    mConfiguration.customAggregateFunctions.valueAt(i));
        }
    }

    private void executePerConnectionSqlFromConfiguration(int startIndex) {
        for (int i = startIndex; i < mConfiguration.perConnectionSql.size(); i++) {
            final Pair<String, Object[]> statement = mConfiguration.perConnectionSql.get(i);
            final int type = DatabaseUtils.getSqlStatementType(statement.first);
            switch (type) {
                case DatabaseUtils.STATEMENT_SELECT:
                    executeForString(statement.first, statement.second, null);
                    break;
                case DatabaseUtils.STATEMENT_PRAGMA:
                    execute(statement.first, statement.second, null);
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Unsupported configuration statement: " + statement);
            }
        }
    }

    private void checkDatabaseWiped() {
        if (!SQLiteGlobal.checkDbWipe()) {
            return;
        }
        try {
            final File checkFile = new File(mConfiguration.path
                    + SQLiteGlobal.WIPE_CHECK_FILE_SUFFIX);

            final boolean hasMetadataTable = executeForLong(
                    "SELECT count(*) FROM sqlite_master"
                            + " WHERE type='table' AND name='android_metadata'", null, null) > 0;
            final boolean hasCheckFile = checkFile.exists();

            if (!mIsReadOnlyConnection && !hasCheckFile) {
                // Create the check file, unless it's a readonly connection,
                // in which case we can't create the metadata table anyway.
                checkFile.createNewFile();
            }

            if (!hasMetadataTable && hasCheckFile) {
                // Bad. The DB is gone unexpectedly.
                SQLiteDatabase.wipeDetected(mConfiguration.path, "unknown");
            }

        } catch (RuntimeException | IOException ex) {
            SQLiteDatabase.wtfAsSystemServer(TAG,
                    "Unexpected exception while checking for wipe", ex);
        }
    }

    // Called by SQLiteConnectionPool only.
    void reconfigure(SQLiteDatabaseConfiguration configuration) {
        mOnlyAllowReadOnlyOperations = false;

        // Remember what changed.
        boolean foreignKeyModeChanged = configuration.foreignKeyConstraintsEnabled
                != mConfiguration.foreignKeyConstraintsEnabled;
        boolean localeChanged = !configuration.locale.equals(mConfiguration.locale);
        boolean customScalarFunctionsChanged = !configuration.customScalarFunctions
                .equals(mConfiguration.customScalarFunctions);
        boolean customAggregateFunctionsChanged = !configuration.customAggregateFunctions
                .equals(mConfiguration.customAggregateFunctions);
        final int oldSize = mConfiguration.perConnectionSql.size();
        final int newSize = configuration.perConnectionSql.size();
        boolean perConnectionSqlChanged = newSize > oldSize;
        boolean journalModeChanged = !configuration.resolveJournalMode().equalsIgnoreCase(
                mConfiguration.resolveJournalMode());
        boolean syncModeChanged =
                !configuration.resolveSyncMode().equalsIgnoreCase(mConfiguration.resolveSyncMode());

        // Update configuration parameters.
        mConfiguration.updateParametersFrom(configuration);

        // Update prepared statement cache size.
        mPreparedStatementCache.resize(configuration.maxSqlCacheSize);

        if (foreignKeyModeChanged) {
            setForeignKeyModeFromConfiguration();
        }

        if (journalModeChanged) {
            setJournalFromConfiguration();
        }

        if (syncModeChanged) {
            setSyncModeFromConfiguration();
        }

        if (localeChanged) {
            setLocaleFromConfiguration();
        }
        if (customScalarFunctionsChanged || customAggregateFunctionsChanged) {
            setCustomFunctionsFromConfiguration();
        }
        if (perConnectionSqlChanged) {
            executePerConnectionSqlFromConfiguration(oldSize);
        }
    }

    // Called by SQLiteConnectionPool only.
    // When set to true, executing write operations will throw SQLiteException.
    // Preparing statements that might write is ok, just don't execute them.
    void setOnlyAllowReadOnlyOperations(boolean readOnly) {
        mOnlyAllowReadOnlyOperations = readOnly;
    }

    // Called by SQLiteConnectionPool only to decide if this connection has the desired statement
    // already prepared.  Returns true if the prepared statement cache contains the specified SQL.
    // The statement may be stale, but that will be a rare occurrence and affects performance only
    // a tiny bit, and only when database schema changes.
    boolean isPreparedStatementInCache(String sql) {
        return mPreparedStatementCache.get(sql) != null;
    }

    /**
     * Gets the unique id of this connection.
     * @return The connection id.
     */
    public int getConnectionId() {
        return mConnectionId;
    }

    /**
     * Returns true if this is the primary database connection.
     * @return True if this is the primary database connection.
     */
    public boolean isPrimaryConnection() {
        return mIsPrimaryConnection;
    }

    /**
     * Prepares a statement for execution but does not bind its parameters or execute it.
     * <p>
     * This method can be used to check for syntax errors during compilation
     * prior to execution of the statement.  If the {@code outStatementInfo} argument
     * is not null, the provided {@link SQLiteStatementInfo} object is populated
     * with information about the statement.
     * </p><p>
     * A prepared statement makes no reference to the arguments that may eventually
     * be bound to it, consequently it it possible to cache certain prepared statements
     * such as SELECT or INSERT/UPDATE statements.  If the statement is cacheable,
     * then it will be stored in the cache for later.
     * </p><p>
     * To take advantage of this behavior as an optimization, the connection pool
     * provides a method to acquire a connection that already has a given SQL statement
     * in its prepared statement cache so that it is ready for execution.
     * </p>
     *
     * @param sql The SQL statement to prepare.
     * @param outStatementInfo The {@link SQLiteStatementInfo} object to populate
     * with information about the statement, or null if none.
     *
     * @throws SQLiteException if an error occurs, such as a syntax error.
     */
    public void prepare(String sql, SQLiteStatementInfo outStatementInfo) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null.");
        }

        final int cookie = mRecentOperations.beginOperation("prepare", sql, null);
        try {
            final PreparedStatement statement = acquirePreparedStatement(sql);
            try {
                if (outStatementInfo != null) {
                    outStatementInfo.numParameters = statement.mNumParameters;
                    outStatementInfo.readOnly = statement.mReadOnly;

                    final int columnCount = nativeGetColumnCount(
                            mConnectionPtr, statement.mStatementPtr);
                    if (columnCount == 0) {
                        outStatementInfo.columnNames = EMPTY_STRING_ARRAY;
                    } else {
                        outStatementInfo.columnNames = new String[columnCount];
                        for (int i = 0; i < columnCount; i++) {
                            outStatementInfo.columnNames[i] = nativeGetColumnName(
                                    mConnectionPtr, statement.mStatementPtr, i);
                        }
                    }
                }
            } finally {
                releasePreparedStatement(statement);
            }
        } catch (RuntimeException ex) {
            mRecentOperations.failOperation(cookie, ex);
            throw ex;
        } finally {
            mRecentOperations.endOperation(cookie);
        }
    }

    /**
     * Executes a statement that does not return a result.
     *
     * @param sql The SQL statement to execute.
     * @param bindArgs The arguments to bind, or null if none.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     *
     * @throws SQLiteException if an error occurs, such as a syntax error
     * or invalid number of bind arguments.
     * @throws OperationCanceledException if the operation was canceled.
     */
    public void execute(String sql, Object[] bindArgs,
            CancellationSignal cancellationSignal) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null.");
        }

        final int cookie = mRecentOperations.beginOperation("execute", sql, bindArgs);
        try {
            final boolean isPragmaStmt =
                DatabaseUtils.getSqlStatementType(sql) == DatabaseUtils.STATEMENT_PRAGMA;
            final PreparedStatement statement = acquirePreparedStatement(sql);
            try {
                throwIfStatementForbidden(statement);
                bindArguments(statement, bindArgs);
                applyBlockGuardPolicy(statement);
                attachCancellationSignal(cancellationSignal);
                try {
                    nativeExecute(mConnectionPtr, statement.mStatementPtr, isPragmaStmt);
                } finally {
                    detachCancellationSignal(cancellationSignal);
                }
            } finally {
                releasePreparedStatement(statement);
            }
        } catch (RuntimeException ex) {
            mRecentOperations.failOperation(cookie, ex);
            throw ex;
        } finally {
            mRecentOperations.endOperation(cookie);
        }
    }

    /**
     * Executes a statement that returns a single <code>long</code> result.
     *
     * @param sql The SQL statement to execute.
     * @param bindArgs The arguments to bind, or null if none.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     * @return The value of the first column in the first row of the result set
     * as a <code>long</code>, or zero if none.
     *
     * @throws SQLiteException if an error occurs, such as a syntax error
     * or invalid number of bind arguments.
     * @throws OperationCanceledException if the operation was canceled.
     */
    public long executeForLong(String sql, Object[] bindArgs,
            CancellationSignal cancellationSignal) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null.");
        }

        final int cookie = mRecentOperations.beginOperation("executeForLong", sql, bindArgs);
        try {
            final PreparedStatement statement = acquirePreparedStatement(sql);
            try {
                throwIfStatementForbidden(statement);
                bindArguments(statement, bindArgs);
                applyBlockGuardPolicy(statement);
                attachCancellationSignal(cancellationSignal);
                try {
                    long ret = nativeExecuteForLong(mConnectionPtr, statement.mStatementPtr);
                    mRecentOperations.setResult(ret);
                    return ret;
                } finally {
                    detachCancellationSignal(cancellationSignal);
                }
            } finally {
                releasePreparedStatement(statement);
            }
        } catch (RuntimeException ex) {
            mRecentOperations.failOperation(cookie, ex);
            throw ex;
        } finally {
            mRecentOperations.endOperation(cookie);
        }
    }

    /**
     * Executes a statement that returns a single {@link String} result.
     *
     * @param sql The SQL statement to execute.
     * @param bindArgs The arguments to bind, or null if none.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     * @return The value of the first column in the first row of the result set
     * as a <code>String</code>, or null if none.
     *
     * @throws SQLiteException if an error occurs, such as a syntax error
     * or invalid number of bind arguments.
     * @throws OperationCanceledException if the operation was canceled.
     */
    public String executeForString(String sql, Object[] bindArgs,
            CancellationSignal cancellationSignal) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null.");
        }

        final int cookie = mRecentOperations.beginOperation("executeForString", sql, bindArgs);
        try {
            final PreparedStatement statement = acquirePreparedStatement(sql);
            try {
                throwIfStatementForbidden(statement);
                bindArguments(statement, bindArgs);
                applyBlockGuardPolicy(statement);
                attachCancellationSignal(cancellationSignal);
                try {
                    String ret = nativeExecuteForString(mConnectionPtr, statement.mStatementPtr);
                    mRecentOperations.setResult(ret);
                    return ret;
                } finally {
                    detachCancellationSignal(cancellationSignal);
                }
            } finally {
                releasePreparedStatement(statement);
            }
        } catch (RuntimeException ex) {
            mRecentOperations.failOperation(cookie, ex);
            throw ex;
        } finally {
            mRecentOperations.endOperation(cookie);
        }
    }

    /**
     * Executes a statement that returns a single BLOB result as a
     * file descriptor to a shared memory region.
     *
     * @param sql The SQL statement to execute.
     * @param bindArgs The arguments to bind, or null if none.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     * @return The file descriptor for a shared memory region that contains
     * the value of the first column in the first row of the result set as a BLOB,
     * or null if none.
     *
     * @throws SQLiteException if an error occurs, such as a syntax error
     * or invalid number of bind arguments.
     * @throws OperationCanceledException if the operation was canceled.
     */
    public ParcelFileDescriptor executeForBlobFileDescriptor(String sql, Object[] bindArgs,
            CancellationSignal cancellationSignal) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null.");
        }

        final int cookie = mRecentOperations.beginOperation("executeForBlobFileDescriptor",
                sql, bindArgs);
        try {
            final PreparedStatement statement = acquirePreparedStatement(sql);
            try {
                throwIfStatementForbidden(statement);
                bindArguments(statement, bindArgs);
                applyBlockGuardPolicy(statement);
                attachCancellationSignal(cancellationSignal);
                try {
                    int fd = nativeExecuteForBlobFileDescriptor(
                            mConnectionPtr, statement.mStatementPtr);
                    return fd >= 0 ? ParcelFileDescriptor.adoptFd(fd) : null;
                } finally {
                    detachCancellationSignal(cancellationSignal);
                }
            } finally {
                releasePreparedStatement(statement);
            }
        } catch (RuntimeException ex) {
            mRecentOperations.failOperation(cookie, ex);
            throw ex;
        } finally {
            mRecentOperations.endOperation(cookie);
        }
    }

    /**
     * Executes a statement that returns a count of the number of rows
     * that were changed.  Use for UPDATE or DELETE SQL statements.
     *
     * @param sql The SQL statement to execute.
     * @param bindArgs The arguments to bind, or null if none.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     * @return The number of rows that were changed.
     *
     * @throws SQLiteException if an error occurs, such as a syntax error
     * or invalid number of bind arguments.
     * @throws OperationCanceledException if the operation was canceled.
     */
    public int executeForChangedRowCount(String sql, Object[] bindArgs,
            CancellationSignal cancellationSignal) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null.");
        }

        int changedRows = 0;
        final int cookie = mRecentOperations.beginOperation("executeForChangedRowCount",
                sql, bindArgs);
        try {
            final PreparedStatement statement = acquirePreparedStatement(sql);
            try {
                throwIfStatementForbidden(statement);
                bindArguments(statement, bindArgs);
                applyBlockGuardPolicy(statement);
                attachCancellationSignal(cancellationSignal);
                try {
                    changedRows = nativeExecuteForChangedRowCount(
                            mConnectionPtr, statement.mStatementPtr);
                    return changedRows;
                } finally {
                    detachCancellationSignal(cancellationSignal);
                }
            } finally {
                releasePreparedStatement(statement);
            }
        } catch (RuntimeException ex) {
            mRecentOperations.failOperation(cookie, ex);
            throw ex;
        } finally {
            if (mRecentOperations.endOperationDeferLog(cookie)) {
                mRecentOperations.logOperation(cookie, "changedRows=" + changedRows);
            }
        }
    }

    /**
     * Executes a statement that returns the row id of the last row inserted
     * by the statement.  Use for INSERT SQL statements.
     *
     * @param sql The SQL statement to execute.
     * @param bindArgs The arguments to bind, or null if none.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     * @return The row id of the last row that was inserted, or 0 if none.
     *
     * @throws SQLiteException if an error occurs, such as a syntax error
     * or invalid number of bind arguments.
     * @throws OperationCanceledException if the operation was canceled.
     */
    public long executeForLastInsertedRowId(String sql, Object[] bindArgs,
            CancellationSignal cancellationSignal) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null.");
        }

        final int cookie = mRecentOperations.beginOperation("executeForLastInsertedRowId",
                sql, bindArgs);
        try {
            final PreparedStatement statement = acquirePreparedStatement(sql);
            try {
                throwIfStatementForbidden(statement);
                bindArguments(statement, bindArgs);
                applyBlockGuardPolicy(statement);
                attachCancellationSignal(cancellationSignal);
                try {
                    return nativeExecuteForLastInsertedRowId(
                            mConnectionPtr, statement.mStatementPtr);
                } finally {
                    detachCancellationSignal(cancellationSignal);
                }
            } finally {
                releasePreparedStatement(statement);
            }
        } catch (RuntimeException ex) {
            mRecentOperations.failOperation(cookie, ex);
            throw ex;
        } finally {
            mRecentOperations.endOperation(cookie);
        }
    }

    /**
     * Executes a statement and populates the specified {@link CursorWindow}
     * with a range of results.  Returns the number of rows that were counted
     * during query execution.
     *
     * @param sql The SQL statement to execute.
     * @param bindArgs The arguments to bind, or null if none.
     * @param window The cursor window to clear and fill.
     * @param startPos The start position for filling the window.
     * @param requiredPos The position of a row that MUST be in the window.
     * If it won't fit, then the query should discard part of what it filled
     * so that it does.  Must be greater than or equal to <code>startPos</code>.
     * @param countAllRows True to count all rows that the query would return
     * regagless of whether they fit in the window.
     * @param cancellationSignal A signal to cancel the operation in progress, or null if none.
     * @return The number of rows that were counted during query execution.  Might
     * not be all rows in the result set unless <code>countAllRows</code> is true.
     *
     * @throws SQLiteException if an error occurs, such as a syntax error
     * or invalid number of bind arguments.
     * @throws OperationCanceledException if the operation was canceled.
     */
    public int executeForCursorWindow(String sql, Object[] bindArgs,
            CursorWindow window, int startPos, int requiredPos, boolean countAllRows,
            CancellationSignal cancellationSignal) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null.");
        }
        if (window == null) {
            throw new IllegalArgumentException("window must not be null.");
        }

        window.acquireReference();
        try {
            int actualPos = -1;
            int countedRows = -1;
            int filledRows = -1;
            final int cookie = mRecentOperations.beginOperation("executeForCursorWindow",
                    sql, bindArgs);
            try {
                final PreparedStatement statement = acquirePreparedStatement(sql);
                try {
                    throwIfStatementForbidden(statement);
                    bindArguments(statement, bindArgs);
                    applyBlockGuardPolicy(statement);
                    attachCancellationSignal(cancellationSignal);
                    try {
                        final long result = nativeExecuteForCursorWindow(
                                mConnectionPtr, statement.mStatementPtr, window.mWindowPtr,
                                startPos, requiredPos, countAllRows);
                        actualPos = (int)(result >> 32);
                        countedRows = (int)result;
                        filledRows = window.getNumRows();
                        window.setStartPosition(actualPos);
                        return countedRows;
                    } finally {
                        detachCancellationSignal(cancellationSignal);
                    }
                } finally {
                    releasePreparedStatement(statement);
                }
            } catch (RuntimeException ex) {
                mRecentOperations.failOperation(cookie, ex);
                throw ex;
            } finally {
                if (mRecentOperations.endOperationDeferLog(cookie)) {
                    mRecentOperations.logOperation(cookie, "window='" + window
                            + "', startPos=" + startPos
                            + ", actualPos=" + actualPos
                            + ", filledRows=" + filledRows
                            + ", countedRows=" + countedRows);
                }
            }
        } finally {
            window.releaseReference();
        }
    }

    /**
     * Return a {@link #PreparedStatement}, possibly from the cache.
     */
    private PreparedStatement acquirePreparedStatementLI(String sql) {
        ++mPool.mTotalPrepareStatements;
        PreparedStatement statement = mPreparedStatementCache.getStatement(sql);
        long seqNum = mPreparedStatementCache.getLastSeqNum();

        boolean skipCache = false;
        if (statement != null) {
            if (!statement.mInUse) {
                if (statement.mSeqNum == seqNum) {
                    // This is a valid statement.  Claim it and return it.
                    statement.mInUse = true;
                    return statement;
                } else {
                    // This is a stale statement.  Remove it from the cache.  Treat this as if the
                    // statement was never found, which means we should not skip the cache.
                    mPreparedStatementCache.remove(sql);
                    statement = null;
                    // Leave skipCache == false.
                }
            } else {
                // The statement is already in the cache but is in use (this statement appears to
                // be not only re-entrant but recursive!).  So prepare a new copy of the statement
                // but do not cache it.
                skipCache = true;
            }
        }
        ++mPool.mTotalPrepareStatementCacheMiss;
        final long statementPtr = mPreparedStatementCache.createStatement(sql);
        seqNum = mPreparedStatementCache.getLastSeqNum();
        try {
            final int numParameters = nativeGetParameterCount(mConnectionPtr, statementPtr);
            final int type = DatabaseUtils.getSqlStatementTypeExtended(sql);
            final boolean readOnly = nativeIsReadOnly(mConnectionPtr, statementPtr);
            statement = obtainPreparedStatement(sql, statementPtr, numParameters, type, readOnly,
                    seqNum);
            if (!skipCache && isCacheable(type)) {
                mPreparedStatementCache.put(sql, statement);
                statement.mInCache = true;
            }
        } catch (RuntimeException ex) {
            // Finalize the statement if an exception occurred and we did not add
            // it to the cache.  If it is already in the cache, then leave it there.
            if (statement == null || !statement.mInCache) {
                nativeFinalizeStatement(mConnectionPtr, statementPtr);
            }
            throw ex;
        }
        statement.mInUse = true;
        return statement;
    }

    /**
     * Return a {@link #PreparedStatement}, possibly from the cache.
     */
    PreparedStatement acquirePreparedStatement(String sql) {
        return acquirePreparedStatementLI(sql);
    }

    /**
     * Release a {@link #PreparedStatement} that was originally supplied by this connection.
     */
    private void releasePreparedStatementLI(PreparedStatement statement) {
        statement.mInUse = false;
        if (statement.mInCache) {
            try {
                nativeResetStatementAndClearBindings(mConnectionPtr, statement.mStatementPtr);
            } catch (SQLiteException ex) {
                // The statement could not be reset due to an error.  Remove it from the cache.
                // When remove() is called, the cache will invoke its entryRemoved() callback,
                // which will in turn call finalizePreparedStatement() to finalize and
                // recycle the statement.
                if (DEBUG) {
                    Log.d(TAG, "Could not reset prepared statement due to an exception.  "
                            + "Removing it from the cache.  SQL: "
                            + trimSqlForDisplay(statement.mSql), ex);
                }

                mPreparedStatementCache.remove(statement.mSql);
            }
        } else {
            finalizePreparedStatement(statement);
        }
    }

    /**
     * Release a {@link #PreparedStatement} that was originally supplied by this connection.
     */
    void releasePreparedStatement(PreparedStatement statement) {
        releasePreparedStatementLI(statement);
    }

    private void finalizePreparedStatement(PreparedStatement statement) {
        nativeFinalizeStatement(mConnectionPtr, statement.mStatementPtr);
        recyclePreparedStatement(statement);
    }

    /**
     * Return a prepared statement for use by {@link SQLiteRawStatement}.  This throws if the
     * prepared statement is incompatible with this connection.
     */
    PreparedStatement acquirePersistentStatement(@NonNull String sql) {
        final int cookie = mRecentOperations.beginOperation("prepare", sql, null);
        try {
            final PreparedStatement statement = acquirePreparedStatement(sql);
            throwIfStatementForbidden(statement);
            return statement;
        } catch (RuntimeException e) {
            mRecentOperations.failOperation(cookie, e);
            throw e;
        } finally {
            mRecentOperations.endOperation(cookie);
        }
    }

    private void attachCancellationSignal(CancellationSignal cancellationSignal) {
        if (cancellationSignal != null) {
            cancellationSignal.throwIfCanceled();

            mCancellationSignalAttachCount += 1;
            if (mCancellationSignalAttachCount == 1) {
                // Reset cancellation flag before executing the statement.
                nativeResetCancel(mConnectionPtr, true /*cancelable*/);

                // After this point, onCancel() may be called concurrently.
                cancellationSignal.setOnCancelListener(this);
            }
        }
    }

    private void detachCancellationSignal(CancellationSignal cancellationSignal) {
        if (cancellationSignal != null) {
            assert mCancellationSignalAttachCount > 0;

            mCancellationSignalAttachCount -= 1;
            if (mCancellationSignalAttachCount == 0) {
                // After this point, onCancel() cannot be called concurrently.
                cancellationSignal.setOnCancelListener(null);

                // Reset cancellation flag after executing the statement.
                nativeResetCancel(mConnectionPtr, false /*cancelable*/);
            }
        }
    }

    // CancellationSignal.OnCancelListener callback.
    // This method may be called on a different thread than the executing statement.
    // However, it will only be called between calls to attachCancellationSignal and
    // detachCancellationSignal, while a statement is executing.  We can safely assume
    // that the SQLite connection is still alive.
    @Override
    public void onCancel() {
        nativeCancel(mConnectionPtr);
    }

    private void bindArguments(PreparedStatement statement, Object[] bindArgs) {
        final int count = bindArgs != null ? bindArgs.length : 0;
        if (count != statement.mNumParameters) {
            throw new SQLiteBindOrColumnIndexOutOfRangeException(
                    "Expected " + statement.mNumParameters + " bind arguments but "
                    + count + " were provided.");
        }
        if (count == 0) {
            return;
        }

        final long statementPtr = statement.mStatementPtr;
        for (int i = 0; i < count; i++) {
            final Object arg = bindArgs[i];
            switch (DatabaseUtils.getTypeOfObject(arg)) {
                case Cursor.FIELD_TYPE_NULL:
                    nativeBindNull(mConnectionPtr, statementPtr, i + 1);
                    break;
                case Cursor.FIELD_TYPE_INTEGER:
                    nativeBindLong(mConnectionPtr, statementPtr, i + 1,
                            ((Number)arg).longValue());
                    break;
                case Cursor.FIELD_TYPE_FLOAT:
                    nativeBindDouble(mConnectionPtr, statementPtr, i + 1,
                            ((Number)arg).doubleValue());
                    break;
                case Cursor.FIELD_TYPE_BLOB:
                    nativeBindBlob(mConnectionPtr, statementPtr, i + 1, (byte[])arg);
                    break;
                case Cursor.FIELD_TYPE_STRING:
                default:
                    if (arg instanceof Boolean) {
                        // Provide compatibility with legacy applications which may pass
                        // Boolean values in bind args.
                        nativeBindLong(mConnectionPtr, statementPtr, i + 1,
                                ((Boolean)arg).booleanValue() ? 1 : 0);
                    } else {
                        nativeBindString(mConnectionPtr, statementPtr, i + 1, arg.toString());
                    }
                    break;
            }
        }
    }

    /**
     * Verify that the statement is read-only, if the connection only allows read-only
     * operations.
     * @param statement The statement to check.
     * @throws SQLiteException if the statement could update the database inside a read-only
     * transaction.
     */
    void throwIfStatementForbidden(PreparedStatement statement) {
        if (mOnlyAllowReadOnlyOperations && !statement.mReadOnly) {
            throw new SQLiteException("Cannot execute this statement because it "
                    + "might modify the database but the connection is read-only.");
        }
    }

    private static boolean isCacheable(int statementType) {
        if (statementType == DatabaseUtils.STATEMENT_UPDATE
            || statementType == DatabaseUtils.STATEMENT_SELECT
            || statementType == DatabaseUtils.STATEMENT_WITH) {
            return true;
        }
        return false;
    }

    private void applyBlockGuardPolicy(PreparedStatement statement) {
        if (!mConfiguration.isInMemoryDb()) {
            if (statement.mReadOnly) {
                BlockGuard.getThreadPolicy().onReadFromDisk();
            } else {
                BlockGuard.getThreadPolicy().onWriteToDisk();
            }
        }
    }

    /**
     * Dumps debugging information about this connection.
     *
     * @param printer The printer to receive the dump, not null.
     * @param verbose True to dump more verbose information.
     */
    public void dump(Printer printer, boolean verbose) {
        dumpUnsafe(printer, verbose);
    }

    /**
     * Dumps debugging information about this connection, in the case where the
     * caller might not actually own the connection.
     *
     * This function is written so that it may be called by a thread that does not
     * own the connection.  We need to be very careful because the connection state is
     * not synchronized.
     *
     * At worst, the method may return stale or slightly wrong data, however
     * it should not crash.  This is ok as it is only used for diagnostic purposes.
     *
     * @param printer The printer to receive the dump, not null.
     * @param verbose True to dump more verbose information.
     */
    void dumpUnsafe(Printer printer, boolean verbose) {
        printer.println("Connection #" + mConnectionId + ":");
        if (verbose) {
            printer.println("  connectionPtr: 0x" + Long.toHexString(mConnectionPtr));
        }
        printer.println("  isPrimaryConnection: " + mIsPrimaryConnection);
        printer.println("  onlyAllowReadOnlyOperations: " + mOnlyAllowReadOnlyOperations);

        mRecentOperations.dump(printer);

        if (verbose) {
            mPreparedStatementCache.dump(printer);
        }
    }

    /**
     * Describes the currently executing operation, in the case where the
     * caller might not actually own the connection.
     *
     * This function is written so that it may be called by a thread that does not
     * own the connection.  We need to be very careful because the connection state is
     * not synchronized.
     *
     * At worst, the method may return stale or slightly wrong data, however
     * it should not crash.  This is ok as it is only used for diagnostic purposes.
     *
     * @return A description of the current operation including how long it has been running,
     * or null if none.
     */
    String describeCurrentOperationUnsafe() {
        return mRecentOperations.describeCurrentOperation();
    }

    /**
     * Collects statistics about database connection memory usage.
     *
     * @param dbStatsList The list to populate.
     */
    void collectDbStats(ArrayList<DbStats> dbStatsList) {
        // Get information about the main database.
        int lookaside = nativeGetDbLookaside(mConnectionPtr);
        long pageCount = 0;
        long pageSize = 0;
        try {
            pageCount = executeForLong("PRAGMA page_count;", null, null);
            pageSize = executeForLong("PRAGMA page_size;", null, null);
        } catch (SQLiteException ex) {
            // Ignore.
        }
        dbStatsList.add(getMainDbStatsUnsafe(lookaside, pageCount, pageSize));

        // Get information about attached databases.
        // We ignore the first row in the database list because it corresponds to
        // the main database which we have already described.
        CursorWindow window = new CursorWindow("collectDbStats");
        try {
            executeForCursorWindow("PRAGMA database_list;", null, window, 0, 0, false, null);
            for (int i = 1; i < window.getNumRows(); i++) {
                String name = window.getString(i, 1);
                String path = window.getString(i, 2);
                pageCount = 0;
                pageSize = 0;
                try {
                    pageCount = executeForLong("PRAGMA " + name + ".page_count;", null, null);
                    pageSize = executeForLong("PRAGMA " + name + ".page_size;", null, null);
                } catch (SQLiteException ex) {
                    // Ignore.
                }
                StringBuilder label = new StringBuilder("  (attached) ").append(name);
                if (!path.isEmpty()) {
                    label.append(": ").append(path);
                }
                dbStatsList.add(
                        new DbStats(label.toString(), pageCount, pageSize, 0, 0, 0, 0, false));
            }
        } catch (SQLiteException ex) {
            // Ignore.
        } finally {
            window.close();
        }
    }

    /**
     * Collects statistics about database connection memory usage, in the case where the
     * caller might not actually own the connection.
     *
     * @return The statistics object, never null.
     */
    void collectDbStatsUnsafe(ArrayList<DbStats> dbStatsList) {
        dbStatsList.add(getMainDbStatsUnsafe(0, 0, 0));
    }

    private DbStats getMainDbStatsUnsafe(int lookaside, long pageCount, long pageSize) {
        // The prepared statement cache is thread-safe so we can access its statistics
        // even if we do not own the database connection.
        String label;
        if (mIsPrimaryConnection) {
            label = mConfiguration.path;
        } else {
            label = mConfiguration.path + " (" + mConnectionId + ")";
        }
        return new DbStats(label, pageCount, pageSize, lookaside,
                mPreparedStatementCache.hitCount(), mPreparedStatementCache.missCount(),
                mPreparedStatementCache.size(), false);
    }

    @Override
    public String toString() {
        return "SQLiteConnection: " + mConfiguration.path + " (" + mConnectionId + ")";
    }

    private PreparedStatement obtainPreparedStatement(String sql, long statementPtr,
            int numParameters, int type, boolean readOnly, long seqNum) {
        PreparedStatement statement = mPreparedStatementPool;
        if (statement != null) {
            mPreparedStatementPool = statement.mPoolNext;
            statement.mPoolNext = null;
            statement.mInCache = false;
        } else {
            statement = new PreparedStatement();
        }
        statement.mSql = sql;
        statement.mStatementPtr = statementPtr;
        statement.mNumParameters = numParameters;
        statement.mType = type;
        statement.mReadOnly = readOnly;
        statement.mSeqNum = seqNum;
        return statement;
    }

    private void recyclePreparedStatement(PreparedStatement statement) {
        statement.mSql = null;
        statement.mPoolNext = mPreparedStatementPool;
        mPreparedStatementPool = statement;
    }

    private static String trimSqlForDisplay(String sql) {
        // Note: Creating and caching a regular expression is expensive at preload-time
        //       and stops compile-time initialization. This pattern is only used when
        //       dumping the connection, which is a rare (mainly error) case. So:
        //       DO NOT CACHE.
        return sql.replaceAll("[\\s]*\\n+[\\s]*", " ");
    }

    // Update the database sequence number.  This number is stored in the prepared statement
    // cache.
    void setDatabaseSeqNum(long n) {
        mPreparedStatementCache.setDatabaseSeqNum(n);
    }

    /**
     * Holder type for a prepared statement.
     *
     * Although this object holds a pointer to a native statement object, it
     * does not have a finalizer.  This is deliberate.  The {@link SQLiteConnection}
     * owns the statement object and will take care of freeing it when needed.
     * In particular, closing the connection requires a guarantee of deterministic
     * resource disposal because all native statement objects must be freed before
     * the native database object can be closed.  So no finalizers here.
     *
     * The class is package-visible so that {@link SQLiteRawStatement} can use it.
     */
    static final class PreparedStatement {
        // Next item in pool.
        public PreparedStatement mPoolNext;

        // The SQL from which the statement was prepared.
        public String mSql;

        // The native sqlite3_stmt object pointer.
        // Lifetime is managed explicitly by the connection.
        public long mStatementPtr;

        // The number of parameters that the prepared statement has.
        public int mNumParameters;

        // The statement type.
        public int mType;

        // True if the statement is read-only.
        public boolean mReadOnly;

        // True if the statement is in the cache.
        public boolean mInCache;

        // The database schema ID at the time this statement was created.  The ID is left zero for
        // statements that are not cached.  This value is meaningful only if mInCache is true.
        public long mSeqNum;

        // True if the statement is in use (currently executing).
        // We need this flag because due to the use of custom functions in triggers, it's
        // possible for SQLite calls to be re-entrant.  Consequently we need to prevent
        // in use statements from being finalized until they are no longer in use.
        public boolean mInUse;
    }

    private final class PreparedStatementCache extends LruCache<String, PreparedStatement> {
        // The database sequence number.  This changes every time the database schema changes.
        private long mDatabaseSeqNum = 0;

        // The database sequence number from the last getStatement() or createStatement()
        // call. The proper use of this variable depends on the caller being single threaded.
        private long mLastSeqNum = 0;

        public PreparedStatementCache(int size) {
            super(size);
        }

        public synchronized void setDatabaseSeqNum(long n) {
            mDatabaseSeqNum = n;
        }

        // Return the last database sequence number.
        public long getLastSeqNum() {
            return mLastSeqNum;
        }

        // Return a statement from the cache.  Save the database sequence number for the caller.
        public synchronized PreparedStatement getStatement(String sql) {
            mLastSeqNum = mDatabaseSeqNum;
            return get(sql);
        }

        // Return a new native prepared statement and save the database sequence number for the
        // caller.  This does not modify the cache in any way.  However, by being synchronized,
        // callers are guaranteed that the sequence number did not change across the native
        // preparation step.
        public synchronized long createStatement(String sql) {
            mLastSeqNum = mDatabaseSeqNum;
            return nativePrepareStatement(mConnectionPtr, sql);
        }

        @Override
        protected void entryRemoved(boolean evicted, String key,
                PreparedStatement oldValue, PreparedStatement newValue) {
            oldValue.mInCache = false;
            if (!oldValue.mInUse) {
                finalizePreparedStatement(oldValue);
            }
        }

        public void dump(Printer printer) {
            printer.println("  Prepared statement cache:");
            Map<String, PreparedStatement> cache = snapshot();
            if (!cache.isEmpty()) {
                int i = 0;
                for (Map.Entry<String, PreparedStatement> entry : cache.entrySet()) {
                    PreparedStatement statement = entry.getValue();
                    if (statement.mInCache) { // might be false due to a race with entryRemoved
                        String sql = entry.getKey();
                        printer.println("    " + i + ": statementPtr=0x"
                                + Long.toHexString(statement.mStatementPtr)
                                + ", numParameters=" + statement.mNumParameters
                                + ", type=" + statement.mType
                                + ", readOnly=" + statement.mReadOnly
                                + ", sql=\"" + trimSqlForDisplay(sql) + "\"");
                    }
                    i += 1;
                }
            } else {
                printer.println("    <none>");
            }
        }
    }

    private static final class OperationLog {
        private static final int MAX_RECENT_OPERATIONS = 20;
        private static final int COOKIE_GENERATION_SHIFT = 8;
        private static final int COOKIE_INDEX_MASK = 0xff;

        private final Operation[] mOperations = new Operation[MAX_RECENT_OPERATIONS];
        private int mIndex;
        private int mGeneration;
        private final SQLiteConnectionPool mPool;
        private long mResultLong = Long.MIN_VALUE;
        private String mResultString;

        OperationLog(SQLiteConnectionPool pool) {
            mPool = pool;
        }

        public int beginOperation(String kind, String sql, Object[] bindArgs) {
            mResultLong = Long.MIN_VALUE;
            mResultString = null;

            synchronized (mOperations) {
                final int index = (mIndex + 1) % MAX_RECENT_OPERATIONS;
                Operation operation = mOperations[index];
                if (operation == null) {
                    operation = new Operation();
                    mOperations[index] = operation;
                } else {
                    operation.mFinished = false;
                    operation.mException = null;
                    if (operation.mBindArgs != null) {
                        operation.mBindArgs.clear();
                    }
                }
                operation.mStartWallTime = System.currentTimeMillis();
                operation.mStartTime = SystemClock.uptimeMillis();
                operation.mKind = kind;
                operation.mSql = sql;
                operation.mPath = mPool.getPath();
                operation.mResultLong = Long.MIN_VALUE;
                operation.mResultString = null;
                if (bindArgs != null) {
                    if (operation.mBindArgs == null) {
                        operation.mBindArgs = new ArrayList<Object>();
                    } else {
                        operation.mBindArgs.clear();
                    }
                    for (int i = 0; i < bindArgs.length; i++) {
                        final Object arg = bindArgs[i];
                        if (arg != null && arg instanceof byte[]) {
                            // Don't hold onto the real byte array longer than necessary.
                            operation.mBindArgs.add(EMPTY_BYTE_ARRAY);
                        } else {
                            operation.mBindArgs.add(arg);
                        }
                    }
                }
                operation.mCookie = newOperationCookieLocked(index);
                if (Trace.isTagEnabled(Trace.TRACE_TAG_DATABASE)) {
                    Trace.asyncTraceBegin(Trace.TRACE_TAG_DATABASE, operation.getTraceMethodName(),
                            operation.mCookie);
                }
                mIndex = index;
                return operation.mCookie;
            }
        }

        public void failOperation(int cookie, Exception ex) {
            synchronized (mOperations) {
                final Operation operation = getOperationLocked(cookie);
                if (operation != null) {
                    operation.mException = ex;
                }
            }
        }

        public void endOperation(int cookie) {
            synchronized (mOperations) {
                if (endOperationDeferLogLocked(cookie)) {
                    logOperationLocked(cookie, null);
                }
            }
        }

        public boolean endOperationDeferLog(int cookie) {
            synchronized (mOperations) {
                return endOperationDeferLogLocked(cookie);
            }
        }

        public void logOperation(int cookie, String detail) {
            synchronized (mOperations) {
                logOperationLocked(cookie, detail);
            }
        }

        public void setResult(long longResult) {
            mResultLong = longResult;
        }

        public void setResult(String stringResult) {
            mResultString = stringResult;
        }

        private boolean endOperationDeferLogLocked(int cookie) {
            final Operation operation = getOperationLocked(cookie);
            if (operation != null) {
                if (Trace.isTagEnabled(Trace.TRACE_TAG_DATABASE)) {
                    Trace.asyncTraceEnd(Trace.TRACE_TAG_DATABASE, operation.getTraceMethodName(),
                            operation.mCookie);
                }
                operation.mEndTime = SystemClock.uptimeMillis();
                operation.mFinished = true;
                final long execTime = operation.mEndTime - operation.mStartTime;
                mPool.onStatementExecuted(execTime);
                return NoPreloadHolder.DEBUG_LOG_SLOW_QUERIES && SQLiteDebug.shouldLogSlowQuery(
                        execTime);
            }
            return false;
        }

        private void logOperationLocked(int cookie, String detail) {
            final Operation operation = getOperationLocked(cookie);
            operation.mResultLong = mResultLong;
            operation.mResultString = mResultString;
            StringBuilder msg = new StringBuilder();
            operation.describe(msg, true);
            if (detail != null) {
                msg.append(", ").append(detail);
            }
            Log.d(TAG, msg.toString());
        }

        private int newOperationCookieLocked(int index) {
            final int generation = mGeneration++;
            return generation << COOKIE_GENERATION_SHIFT | index;
        }

        private Operation getOperationLocked(int cookie) {
            final int index = cookie & COOKIE_INDEX_MASK;
            final Operation operation = mOperations[index];
            return operation.mCookie == cookie ? operation : null;
        }

        public String describeCurrentOperation() {
            synchronized (mOperations) {
                final Operation operation = mOperations[mIndex];
                if (operation != null && !operation.mFinished) {
                    StringBuilder msg = new StringBuilder();
                    operation.describe(msg, false);
                    return msg.toString();
                }
                return null;
            }
        }

        public void dump(Printer printer) {
            synchronized (mOperations) {
                printer.println("  Most recently executed operations:");
                int index = mIndex;
                Operation operation = mOperations[index];
                if (operation != null) {
                    // Note: SimpleDateFormat is not thread-safe, cannot be compile-time created,
                    // and is relatively expensive to create during preloading. This method is only
                    // used when dumping a connection, which is a rare (mainly error) case.
                    SimpleDateFormat opDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
                    int n = 0;
                    do {
                        StringBuilder msg = new StringBuilder();
                        msg.append("    ").append(n).append(": [");
                        String formattedStartTime = opDF.format(new Date(operation.mStartWallTime));
                        msg.append(formattedStartTime);
                        msg.append("] ");
                        operation.describe(msg, false); // Never dump bingargs in a bugreport
                        printer.println(msg.toString());

                        if (index > 0) {
                            index -= 1;
                        } else {
                            index = MAX_RECENT_OPERATIONS - 1;
                        }
                        n += 1;
                        operation = mOperations[index];
                    } while (operation != null && n < MAX_RECENT_OPERATIONS);
                } else {
                    printer.println("    <none>");
                }
            }
        }
    }

    private static final class Operation {
        // Trim all SQL statements to 256 characters inside the trace marker.
        // This limit gives plenty of context while leaving space for other
        // entries in the trace buffer (and ensures atrace doesn't truncate the
        // marker for us, potentially losing metadata in the process).
        private static final int MAX_TRACE_METHOD_NAME_LEN = 256;

        public long mStartWallTime; // in System.currentTimeMillis()
        public long mStartTime; // in SystemClock.uptimeMillis();
        public long mEndTime; // in SystemClock.uptimeMillis();
        public String mKind;
        public String mSql;
        public ArrayList<Object> mBindArgs;
        public boolean mFinished;
        public Exception mException;
        public int mCookie;
        public String mPath;
        public long mResultLong; // MIN_VALUE means "value not set".
        public String mResultString;

        public void describe(StringBuilder msg, boolean allowDetailedLog) {
            msg.append(mKind);
            if (mFinished) {
                msg.append(" took ").append(mEndTime - mStartTime).append("ms");
            } else {
                msg.append(" started ").append(System.currentTimeMillis() - mStartWallTime)
                        .append("ms ago");
            }
            msg.append(" - ").append(getStatus());
            if (mSql != null) {
                msg.append(", sql=\"").append(trimSqlForDisplay(mSql)).append("\"");
            }
            final boolean dumpDetails = allowDetailedLog && NoPreloadHolder.DEBUG_LOG_DETAILED
                    && mBindArgs != null && mBindArgs.size() != 0;
            if (dumpDetails) {
                msg.append(", bindArgs=[");
                final int count = mBindArgs.size();
                for (int i = 0; i < count; i++) {
                    final Object arg = mBindArgs.get(i);
                    if (i != 0) {
                        msg.append(", ");
                    }
                    if (arg == null) {
                        msg.append("null");
                    } else if (arg instanceof byte[]) {
                        msg.append("<byte[]>");
                    } else if (arg instanceof String) {
                        msg.append("\"").append((String)arg).append("\"");
                    } else {
                        msg.append(arg);
                    }
                }
                msg.append("]");
            }
            msg.append(", path=").append(mPath);
            if (mException != null) {
                msg.append(", exception=\"").append(mException.getMessage()).append("\"");
            }
            if (mResultLong != Long.MIN_VALUE) {
                msg.append(", result=").append(mResultLong);
            }
            if (mResultString != null) {
                msg.append(", result=\"").append(mResultString).append("\"");
            }
        }

        private String getStatus() {
            if (!mFinished) {
                return "running";
            }
            return mException != null ? "failed" : "succeeded";
        }

        private String getTraceMethodName() {
            String methodName = mKind + " " + mSql;
            if (methodName.length() > MAX_TRACE_METHOD_NAME_LEN)
                return methodName.substring(0, MAX_TRACE_METHOD_NAME_LEN);
            return methodName;
        }

    }

    /**
     * Return the ROWID of the last row to be inserted under this connection.  Returns 0 if there
     * has never been an insert on this connection.
     * @return The ROWID of the last row to be inserted under this connection.
     * @hide
     */
    long getLastInsertRowId() {
        try {
            return nativeLastInsertRowId(mConnectionPtr);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Return the number of database changes on the current connection made by the last SQL
     * statement
     * @hide
     */
    long getLastChangedRowCount() {
        try {
            return nativeChanges(mConnectionPtr);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Return the total number of database changes made on the current connection.
     * @hide
     */
    long getTotalChangedRowCount() {
        try {
            return nativeTotalChanges(mConnectionPtr);
        } finally {
            Reference.reachabilityFence(this);
        }
    }
}
