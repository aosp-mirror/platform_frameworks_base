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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.database.DatabaseErrorHandler;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.os.FileUtils;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A helper class to manage database creation and version management.
 *
 * <p>You create a subclass implementing {@link #onCreate}, {@link #onUpgrade} and
 * optionally {@link #onOpen}, and this class takes care of opening the database
 * if it exists, creating it if it does not, and upgrading it as necessary.
 * Transactions are used to make sure the database is always in a sensible state.
 *
 * <p>This class makes it easy for {@link android.content.ContentProvider}
 * implementations to defer opening and upgrading the database until first use,
 * to avoid blocking application startup with long-running database upgrades.
 *
 * <p>For an example, see the NotePadProvider class in the NotePad sample application,
 * in the <em>samples/</em> directory of the SDK.</p>
 *
 * <p class="note"><strong>Note:</strong> this class assumes
 * monotonically increasing version numbers for upgrades.</p>
 *
 * <p class="note"><strong>Note:</strong> the {@link AutoCloseable} interface was
 * first added in the {@link android.os.Build.VERSION_CODES#Q} release.</p>
 */
public abstract class SQLiteOpenHelper implements AutoCloseable {
    private static final String TAG = SQLiteOpenHelper.class.getSimpleName();

    // Every database file has a lock, saved in this map.  The lock is held while the database is
    // opened.
    private static final ConcurrentHashMap<String, Object> sDbLock = new ConcurrentHashMap<>();

    // The lock that this open helper instance must hold when the database is opened.
    private final Object mLock;

    private final Context mContext;
    @UnsupportedAppUsage
    private final String mName;
    private final int mNewVersion;
    private final int mMinimumSupportedVersion;

    private SQLiteDatabase mDatabase;
    private boolean mIsInitializing;
    private SQLiteDatabase.OpenParams.Builder mOpenParamsBuilder;

    /**
     * Create a helper object to create, open, and/or manage a database.
     * This method always returns very quickly.  The database is not actually
     * created or opened until one of {@link #getWritableDatabase} or
     * {@link #getReadableDatabase} is called.
     *
     * @param context to use for locating paths to the the database
     * @param name of the database file, or null for an in-memory database
     * @param factory to use for creating cursor objects, or null for the default
     * @param version number of the database (starting at 1); if the database is older,
     *     {@link #onUpgrade} will be used to upgrade the database; if the database is
     *     newer, {@link #onDowngrade} will be used to downgrade the database
     */
    public SQLiteOpenHelper(@Nullable Context context, @Nullable String name,
            @Nullable CursorFactory factory, int version) {
        this(context, name, factory, version, null);
    }

    /**
     * Create a helper object to create, open, and/or manage a database.
     * The database is not actually created or opened until one of
     * {@link #getWritableDatabase} or {@link #getReadableDatabase} is called.
     *
     * <p>Accepts input param: a concrete instance of {@link DatabaseErrorHandler} to be
     * used to handle corruption when sqlite reports database corruption.</p>
     *
     * @param context to use for locating paths to the the database
     * @param name of the database file, or null for an in-memory database
     * @param factory to use for creating cursor objects, or null for the default
     * @param version number of the database (starting at 1); if the database is older,
     *     {@link #onUpgrade} will be used to upgrade the database; if the database is
     *     newer, {@link #onDowngrade} will be used to downgrade the database
     * @param errorHandler the {@link DatabaseErrorHandler} to be used when sqlite reports database
     * corruption, or null to use the default error handler.
     */
    public SQLiteOpenHelper(@Nullable Context context, @Nullable String name,
            @Nullable CursorFactory factory, int version,
            @Nullable DatabaseErrorHandler errorHandler) {
        this(context, name, factory, version, 0, errorHandler);
    }

    /**
     * Create a helper object to create, open, and/or manage a database.
     * This method always returns very quickly.  The database is not actually
     * created or opened until one of {@link #getWritableDatabase} or
     * {@link #getReadableDatabase} is called.
     *
     * @param context to use for locating paths to the the database
     * @param name of the database file, or null for an in-memory database
     * @param version number of the database (starting at 1); if the database is older,
     *     {@link #onUpgrade} will be used to upgrade the database; if the database is
     *     newer, {@link #onDowngrade} will be used to downgrade the database
     * @param openParams configuration parameters that are used for opening {@link SQLiteDatabase}.
     *        Please note that {@link SQLiteDatabase#CREATE_IF_NECESSARY} flag will always be
     *        set when the helper opens the database
     */
    public SQLiteOpenHelper(@Nullable Context context, @Nullable String name, int version,
            @NonNull SQLiteDatabase.OpenParams openParams) {
        this(context, name, version, 0, openParams.toBuilder());
    }

    /**
     * Same as {@link #SQLiteOpenHelper(Context, String, CursorFactory, int, DatabaseErrorHandler)}
     * but also accepts an integer minimumSupportedVersion as a convenience for upgrading very old
     * versions of this database that are no longer supported. If a database with older version that
     * minimumSupportedVersion is found, it is simply deleted and a new database is created with the
     * given name and version
     *
     * @param context to use for locating paths to the the database
     * @param name the name of the database file, null for a temporary in-memory database
     * @param factory to use for creating cursor objects, null for default
     * @param version the required version of the database
     * @param minimumSupportedVersion the minimum version that is supported to be upgraded to
     *            {@code version} via {@link #onUpgrade}. If the current database version is lower
     *            than this, database is simply deleted and recreated with the version passed in
     *            {@code version}. {@link #onBeforeDelete} is called before deleting the database
     *            when this happens. This is 0 by default.
     * @param errorHandler the {@link DatabaseErrorHandler} to be used when sqlite reports database
     *            corruption, or null to use the default error handler.
     * @see #onBeforeDelete(SQLiteDatabase)
     * @see #SQLiteOpenHelper(Context, String, CursorFactory, int, DatabaseErrorHandler)
     * @see #onUpgrade(SQLiteDatabase, int, int)
     * @hide
     */
    public SQLiteOpenHelper(@Nullable Context context, @Nullable String name,
            @Nullable CursorFactory factory, int version,
            int minimumSupportedVersion, @Nullable DatabaseErrorHandler errorHandler) {
        this(context, name, version, minimumSupportedVersion,
                new SQLiteDatabase.OpenParams.Builder());
        mOpenParamsBuilder.setCursorFactory(factory);
        mOpenParamsBuilder.setErrorHandler(errorHandler);
    }

    private SQLiteOpenHelper(@Nullable Context context, @Nullable String name, int version,
            int minimumSupportedVersion,
            @NonNull SQLiteDatabase.OpenParams.Builder openParamsBuilder) {
        Objects.requireNonNull(openParamsBuilder);
        if (version < 1) throw new IllegalArgumentException("Version must be >= 1, was " + version);

        mContext = context;
        mName = name;
        mNewVersion = version;
        mMinimumSupportedVersion = Math.max(0, minimumSupportedVersion);
        setOpenParamsBuilder(openParamsBuilder);

        Object lock = null;
        if (!Flags.concurrentOpenHelper() || mName == null) {
            lock = new Object();
        } else {
            lock = sDbLock.computeIfAbsent(mName, (String k) -> new Object());
        }
        mLock = lock;
    }

    /**
     * Return the name of the SQLite database being opened, as given to
     * the constructor.
     */
    public String getDatabaseName() {
        return mName;
    }

    /**
     * Enables or disables the use of write-ahead logging for the database.
     *
     * Write-ahead logging cannot be used with read-only databases so the value of
     * this flag is ignored if the database is opened read-only.
     *
     * @param enabled True if write-ahead logging should be enabled, false if it
     * should be disabled.
     *
     * @see SQLiteDatabase#enableWriteAheadLogging()
     */
    public void setWriteAheadLoggingEnabled(boolean enabled) {
        synchronized (this) {
            if (mOpenParamsBuilder.isWriteAheadLoggingEnabled() != enabled) {
                if (mDatabase != null && mDatabase.isOpen() && !mDatabase.isReadOnly()) {
                    if (enabled) {
                        mDatabase.enableWriteAheadLogging();
                    } else {
                        mDatabase.disableWriteAheadLogging();
                    }
                }
                mOpenParamsBuilder.setWriteAheadLoggingEnabled(enabled);
            }

            // Compatibility WAL is disabled if an app disables or enables WAL
            mOpenParamsBuilder.removeOpenFlags(SQLiteDatabase.ENABLE_LEGACY_COMPATIBILITY_WAL);
        }
    }

    /**
     * Configures <a href="https://sqlite.org/malloc.html#lookaside">lookaside memory allocator</a>
     *
     * <p>This method should be called from the constructor of the subclass,
     * before opening the database, since lookaside memory configuration can only be changed
     * when no connection is using it
     *
     * <p>SQLite default settings will be used, if this method isn't called.
     * Use {@code setLookasideConfig(0,0)} to disable lookaside
     *
     * <p><strong>Note:</strong> Provided slotSize/slotCount configuration is just a recommendation.
     * The system may choose different values depending on a device, e.g. lookaside allocations
     * can be disabled on low-RAM devices
     *
     * @param slotSize The size in bytes of each lookaside slot.
     * @param slotCount The total number of lookaside memory slots per database connection.
     */
    public void setLookasideConfig(@IntRange(from = 0) final int slotSize,
            @IntRange(from = 0) final int slotCount) {
        synchronized (this) {
            if (mDatabase != null && mDatabase.isOpen()) {
                throw new IllegalStateException(
                        "Lookaside memory config cannot be changed after opening the database");
            }
            mOpenParamsBuilder.setLookasideConfig(slotSize, slotCount);
        }
    }

    /**
     * Sets configuration parameters that are used for opening {@link SQLiteDatabase}.
     * <p>Please note that {@link SQLiteDatabase#CREATE_IF_NECESSARY} flag will always be set when
     * opening the database
     *
     * @param openParams configuration parameters that are used for opening {@link SQLiteDatabase}.
     * @throws IllegalStateException if the database is already open
     */
    public void setOpenParams(@NonNull SQLiteDatabase.OpenParams openParams) {
        Objects.requireNonNull(openParams);
        synchronized (this) {
            if (mDatabase != null && mDatabase.isOpen()) {
                throw new IllegalStateException(
                        "OpenParams cannot be set after opening the database");
            }
            setOpenParamsBuilder(new SQLiteDatabase.OpenParams.Builder(openParams));
        }
    }

    private void setOpenParamsBuilder(SQLiteDatabase.OpenParams.Builder openParamsBuilder) {
        mOpenParamsBuilder = openParamsBuilder;
        mOpenParamsBuilder.addOpenFlags(SQLiteDatabase.CREATE_IF_NECESSARY);
    }

    /**
     * Sets the maximum number of milliseconds that SQLite connection is allowed to be idle
     * before it is closed and removed from the pool.
     *
     * <p>This method should be called from the constructor of the subclass,
     * before opening the database
     *
     * <p><b>DO NOT USE</b> this method.
     * This feature has negative side effects that are very hard to foresee.
     * See the javadoc of
     * {@link SQLiteDatabase.OpenParams.Builder#setIdleConnectionTimeout(long)}
     * for the details.
     *
     * @param idleConnectionTimeoutMs timeout in milliseconds. Use {@link Long#MAX_VALUE} value
     * to allow unlimited idle connections.
     *
     * @see SQLiteDatabase.OpenParams.Builder#setIdleConnectionTimeout(long)
     *
     * @deprecated DO NOT USE this method. See the javadoc of
     * {@link SQLiteDatabase.OpenParams.Builder#setIdleConnectionTimeout(long)}
     * for the details.
     */
    @Deprecated
    public void setIdleConnectionTimeout(@IntRange(from = 0) final long idleConnectionTimeoutMs) {
        synchronized (this) {
            if (mDatabase != null && mDatabase.isOpen()) {
                throw new IllegalStateException(
                        "Connection timeout setting cannot be changed after opening the database");
            }
            mOpenParamsBuilder.setIdleConnectionTimeout(idleConnectionTimeoutMs);
        }
    }

    /**
     * Create and/or open a database that will be used for reading and writing.
     * The first time this is called, the database will be opened and
     * {@link #onCreate}, {@link #onUpgrade} and/or {@link #onOpen} will be
     * called.
     *
     * <p>Once opened successfully, the database is cached, so you can
     * call this method every time you need to write to the database.
     * (Make sure to call {@link #close} when you no longer need the database.)
     * Errors such as bad permissions or a full disk may cause this method
     * to fail, but future attempts may succeed if the problem is fixed.</p>
     *
     * <p class="caution">Database upgrade may take a long time, you
     * should not call this method from the application main thread, including
     * from {@link android.content.ContentProvider#onCreate ContentProvider.onCreate()}.
     *
     * @throws SQLiteException if the database cannot be opened for writing
     * @return a read/write database object valid until {@link #close} is called
     */
    public SQLiteDatabase getWritableDatabase() {
        synchronized (this) {
            return getDatabaseLocked(true);
        }
    }

    /**
     * Create and/or open a database.  This will be the same object returned by
     * {@link #getWritableDatabase} unless some problem, such as a full disk,
     * requires the database to be opened read-only.  In that case, a read-only
     * database object will be returned.  If the problem is fixed, a future call
     * to {@link #getWritableDatabase} may succeed, in which case the read-only
     * database object will be closed and the read/write object will be returned
     * in the future.
     *
     * <p class="caution">Like {@link #getWritableDatabase}, this method may
     * take a long time to return, so you should not call it from the
     * application main thread, including from
     * {@link android.content.ContentProvider#onCreate ContentProvider.onCreate()}.
     *
     * @throws SQLiteException if the database cannot be opened
     * @return a database object valid until {@link #getWritableDatabase}
     *     or {@link #close} is called.
     */
    public SQLiteDatabase getReadableDatabase() {
        synchronized (this) {
            return getDatabaseLocked(false);
        }
    }

    private SQLiteDatabase getDatabaseLocked(boolean writable) {
        if (mDatabase != null) {
            if (!mDatabase.isOpen()) {
                // Darn!  The user closed the database by calling mDatabase.close().
                mDatabase = null;
            } else if (!writable || !mDatabase.isReadOnly()) {
                // The database is already open for business.
                return mDatabase;
            }
        }

        if (mIsInitializing) {
            throw new IllegalStateException("getDatabase called recursively");
        }

        SQLiteDatabase db = mDatabase;
        try {
            synchronized (mLock) {
                mIsInitializing = true;

                if (db != null) {
                    if (writable && db.isReadOnly()) {
                        db.reopenReadWrite();
                    }
                } else if (mName == null) {
                    db = SQLiteDatabase.createInMemory(mOpenParamsBuilder.build());
                } else {
                    final File filePath = mContext.getDatabasePath(mName);
                    SQLiteDatabase.OpenParams params = mOpenParamsBuilder.build();
                    try {
                        db = SQLiteDatabase.openDatabase(filePath, params);
                        // Keep pre-O-MR1 behavior by resetting file permissions to 660
                        setFilePermissionsForDb(filePath.getPath());
                    } catch (SQLException ex) {
                        if (writable) {
                            throw ex;
                        }
                        Log.e(TAG, "Couldn't open database for writing (will try read-only):", ex);
                        params = params.toBuilder()
                                 .addOpenFlags(SQLiteDatabase.OPEN_READONLY).build();
                        db = SQLiteDatabase.openDatabase(filePath, params);
                    }
                }

                onConfigure(db);

                final int version = db.getVersion();
                if (version != mNewVersion) {
                    if (db.isReadOnly()) {
                        throw new SQLiteException("Can't upgrade read-only database from version "
                                + db.getVersion() + " to " + mNewVersion + ": " + mName);
                    }

                    if (version > 0 && version < mMinimumSupportedVersion) {
                        File databaseFile = new File(db.getPath());
                        onBeforeDelete(db);
                        db.close();
                        if (SQLiteDatabase.deleteDatabase(databaseFile)) {
                            mIsInitializing = false;
                            return getDatabaseLocked(writable);
                        } else {
                            throw new IllegalStateException("Unable to delete obsolete database "
                                    + mName + " with version " + version);
                        }
                    } else {
                        db.beginTransaction();
                        try {
                            if (version == 0) {
                                onCreate(db);
                            } else {
                                if (version > mNewVersion) {
                                    onDowngrade(db, version, mNewVersion);
                                } else {
                                    onUpgrade(db, version, mNewVersion);
                                }
                            }
                            db.setVersion(mNewVersion);
                            db.setTransactionSuccessful();
                        } finally {
                            db.endTransaction();
                        }
                    }
                }

                onOpen(db);
                mDatabase = db;
                return db;
            }
        } finally {
            mIsInitializing = false;
            if (db != null && db != mDatabase) {
                db.close();
            }
        }
    }

    private static void setFilePermissionsForDb(String dbPath) {
        int perms = FileUtils.S_IRUSR | FileUtils.S_IWUSR | FileUtils.S_IRGRP | FileUtils.S_IWGRP;
        FileUtils.setPermissions(dbPath, perms, -1, -1);
    }

    /**
     * Close any open database object.
     */
    public synchronized void close() {
        if (mIsInitializing) throw new IllegalStateException("Closed during initialization");

        if (mDatabase != null && mDatabase.isOpen()) {
            mDatabase.close();
            mDatabase = null;
        }
    }

    /**
     * Called when the database connection is being configured, to enable features such as
     * write-ahead logging or foreign key support.
     * <p>
     * This method is called before {@link #onCreate}, {@link #onUpgrade}, {@link #onDowngrade}, or
     * {@link #onOpen} are called. It should not modify the database except to configure the
     * database connection as required.
     * </p>
     * <p>
     * This method should only call methods that configure the parameters of the database
     * connection, such as {@link SQLiteDatabase#enableWriteAheadLogging}
     * {@link SQLiteDatabase#setForeignKeyConstraintsEnabled}, {@link SQLiteDatabase#setLocale},
     * {@link SQLiteDatabase#setMaximumSize}, or executing PRAGMA statements.
     * </p>
     *
     * @param db The database.
     */
    public void onConfigure(SQLiteDatabase db) {}

    /**
     * Called before the database is deleted when the version returned by
     * {@link SQLiteDatabase#getVersion()} is lower than the minimum supported version passed (if at
     * all) while creating this helper. After the database is deleted, a fresh database with the
     * given version is created. This will be followed by {@link #onConfigure(SQLiteDatabase)} and
     * {@link #onCreate(SQLiteDatabase)} being called with a new SQLiteDatabase object
     *
     * @param db the database opened with this helper
     * @see #SQLiteOpenHelper(Context, String, CursorFactory, int, int, DatabaseErrorHandler)
     * @hide
     */
    public void onBeforeDelete(SQLiteDatabase db) {
    }

    /**
     * Called when the database is created for the first time. This is where the
     * creation of tables and the initial population of the tables should happen.
     *
     * @param db The database.
     */
    public abstract void onCreate(SQLiteDatabase db);

    /**
     * Called when the database needs to be upgraded. The implementation
     * should use this method to drop tables, add tables, or do anything else it
     * needs to upgrade to the new schema version.
     *
     * <p>
     * The SQLite ALTER TABLE documentation can be found
     * <a href="http://sqlite.org/lang_altertable.html">here</a>. If you add new columns
     * you can use ALTER TABLE to insert them into a live table. If you rename or remove columns
     * you can use ALTER TABLE to rename the old table, then create the new table and then
     * populate the new table with the contents of the old table.
     * </p><p>
     * This method executes within a transaction.  If an exception is thrown, all changes
     * will automatically be rolled back.
     * </p>
     * <p>
     * <em>Important:</em> You should NOT modify an existing migration step from version X to X+1
     * once a build has been released containing that migration step.  If a migration step has an
     * error and it runs on a device, the step will NOT re-run itself in the future if a fix is made
     * to the migration step.</p>
     * <p>For example, suppose a migration step renames a database column from {@code foo} to
     * {@code bar} when the name should have been {@code baz}.  If that migration step is released
     * in a build and runs on a user's device, the column will be renamed to {@code bar}.  If the
     * developer subsequently edits this same migration step to change the name to {@code baz} as
     * intended, the user devices which have already run this step will still have the name
     * {@code bar}.  Instead, a NEW migration step should be created to correct the error and rename
     * {@code bar} to {@code baz}, ensuring the error is corrected on devices which have already run
     * the migration step with the error.</p>
     *
     * @param db The database.
     * @param oldVersion The old database version.
     * @param newVersion The new database version.
     */
    public abstract void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion);

    /**
     * Called when the database needs to be downgraded. This is strictly similar to
     * {@link #onUpgrade} method, but is called whenever current version is newer than requested one.
     * However, this method is not abstract, so it is not mandatory for a customer to
     * implement it. If not overridden, default implementation will reject downgrade and
     * throws SQLiteException
     *
     * <p>
     * This method executes within a transaction.  If an exception is thrown, all changes
     * will automatically be rolled back.
     * </p>
     *
     * @param db The database.
     * @param oldVersion The old database version.
     * @param newVersion The new database version.
     */
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new SQLiteException("Can't downgrade database from version " +
                oldVersion + " to " + newVersion);
    }

    /**
     * Called when the database has been opened.  The implementation
     * should check {@link SQLiteDatabase#isReadOnly} before updating the
     * database.
     * <p>
     * This method is called after the database connection has been configured
     * and after the database schema has been created, upgraded or downgraded as necessary.
     * If the database connection must be configured in some way before the schema
     * is created, upgraded, or downgraded, do it in {@link #onConfigure} instead.
     * </p>
     *
     * @param db The database.
     */
    public void onOpen(SQLiteDatabase db) {}
}
