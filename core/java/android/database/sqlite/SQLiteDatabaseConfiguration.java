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

import android.compat.annotation.UnsupportedAppUsage;
import android.database.sqlite.SQLiteDebug.NoPreloadHolder;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Pair;

import java.util.ArrayList;
import java.util.Locale;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Describes how to configure a database.
 * <p>
 * The purpose of this object is to keep track of all of the little
 * configuration settings that are applied to a database after it
 * is opened so that they can be applied to all connections in the
 * connection pool uniformly.
 * </p><p>
 * Each connection maintains its own copy of this object so it can
 * keep track of which settings have already been applied.
 * </p>
 *
 * @hide
 */
public final class SQLiteDatabaseConfiguration {
    // The pattern we use to strip email addresses from database paths
    // when constructing a label to use in log messages.
    private static final Pattern EMAIL_IN_DB_PATTERN =
            Pattern.compile("[\\w\\.\\-]+@[\\w\\.\\-]+");

    /**
     * Special path used by in-memory databases.
     */
    public static final String MEMORY_DB_PATH = ":memory:";

    /**
     * The database path.
     */
    public final String path;

    /**
     * The label to use to describe the database when it appears in logs.
     * This is derived from the path but is stripped to remove PII.
     */
    public final String label;

    /**
     * The flags used to open the database.
     */
    public int openFlags;

    /**
     * The maximum size of the prepared statement cache for each database connection.
     * Must be non-negative.
     *
     * Default is 25.
     */
    @UnsupportedAppUsage
    public int maxSqlCacheSize;

    /**
     * The database locale.
     *
     * Default is the value returned by {@link Locale#getDefault()}.
     */
    public Locale locale;

    /**
     * True if foreign key constraints are enabled.
     *
     * Default is false.
     */
    public boolean foreignKeyConstraintsEnabled;

    /**
     * The custom scalar functions to register.
     */
    public final ArrayMap<String, UnaryOperator<String>> customScalarFunctions
            = new ArrayMap<>();

    /**
     * The custom aggregate functions to register.
     */
    public final ArrayMap<String, BinaryOperator<String>> customAggregateFunctions
            = new ArrayMap<>();

    /**
     * The statements to execute to initialize each connection.
     */
    public final ArrayList<Pair<String, Object[]>> perConnectionSql = new ArrayList<>();

    /**
     * The size in bytes of each lookaside slot
     *
     * <p>If negative, the default lookaside configuration will be used
     */
    public int lookasideSlotSize = -1;

    /**
     * The total number of lookaside memory slots per database connection
     *
     * <p>If negative, the default lookaside configuration will be used
     */
    public int lookasideSlotCount = -1;

    /**
     * The number of milliseconds that SQLite connection is allowed to be idle before it
     * is closed and removed from the pool.
     * <p>By default, idle connections are not closed
     */
    public long idleConnectionTimeoutMs = Long.MAX_VALUE;

    /**
     * Journal mode to use when {@link SQLiteDatabase#ENABLE_WRITE_AHEAD_LOGGING} is not set.
     * <p>Default is returned by {@link SQLiteGlobal#getDefaultJournalMode()}
     */
    public @SQLiteDatabase.JournalMode String journalMode;

    /**
     * Synchronous mode to use.
     * <p>Default is returned by {@link SQLiteGlobal#getDefaultSyncMode()}
     * or {@link SQLiteGlobal#getWALSyncMode()} depending on journal mode
     */
    public @SQLiteDatabase.SyncMode String syncMode;

    public boolean shouldTruncateWalFile;

    /**
     * Creates a database configuration with the required parameters for opening a
     * database and default values for all other parameters.
     *
     * @param path The database path.
     * @param openFlags Open flags for the database, such as {@link SQLiteDatabase#OPEN_READWRITE}.
     */
    public SQLiteDatabaseConfiguration(String path, int openFlags) {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null.");
        }

        if (NoPreloadHolder.DEBUG_SQL_STATEMENTS) {
            openFlags |= SQLiteDatabase.ENABLE_PROFILE;
        }
        if (NoPreloadHolder.DEBUG_SQL_TIME) {
            openFlags |= SQLiteDatabase.ENABLE_TRACE;
        }

        this.path = path;
        label = stripPathForLogs(path);
        this.openFlags = openFlags;

        // Set default values for optional parameters.
        maxSqlCacheSize = 25;
        locale = Locale.getDefault();
    }

    /**
     * Creates a database configuration as a copy of another configuration.
     *
     * @param other The other configuration.
     */
    public SQLiteDatabaseConfiguration(SQLiteDatabaseConfiguration other) {
        if (other == null) {
            throw new IllegalArgumentException("other must not be null.");
        }

        this.path = other.path;
        this.label = other.label;
        updateParametersFrom(other);
    }

    /**
     * Updates the non-immutable parameters of this configuration object
     * from the other configuration object.
     *
     * @param other The object from which to copy the parameters.
     */
    public void updateParametersFrom(SQLiteDatabaseConfiguration other) {
        if (other == null) {
            throw new IllegalArgumentException("other must not be null.");
        }
        if (!path.equals(other.path)) {
            throw new IllegalArgumentException("other configuration must refer to "
                    + "the same database.");
        }

        openFlags = other.openFlags;
        maxSqlCacheSize = other.maxSqlCacheSize;
        locale = other.locale;
        foreignKeyConstraintsEnabled = other.foreignKeyConstraintsEnabled;
        customScalarFunctions.clear();
        customScalarFunctions.putAll(other.customScalarFunctions);
        customAggregateFunctions.clear();
        customAggregateFunctions.putAll(other.customAggregateFunctions);
        perConnectionSql.clear();
        perConnectionSql.addAll(other.perConnectionSql);
        lookasideSlotSize = other.lookasideSlotSize;
        lookasideSlotCount = other.lookasideSlotCount;
        idleConnectionTimeoutMs = other.idleConnectionTimeoutMs;
        journalMode = other.journalMode;
        syncMode = other.syncMode;
    }

    /**
     * Returns true if the database is in-memory.
     * @return True if the database is in-memory.
     */
    public boolean isInMemoryDb() {
        return path.equalsIgnoreCase(MEMORY_DB_PATH);
    }

    public boolean isReadOnlyDatabase() {
        return (openFlags & SQLiteDatabase.OPEN_READONLY) != 0;
    }

    boolean isLegacyCompatibilityWalEnabled() {
        return journalMode == null && syncMode == null
                && (openFlags & SQLiteDatabase.ENABLE_LEGACY_COMPATIBILITY_WAL) != 0;
    }

    private static String stripPathForLogs(String path) {
        if (path.indexOf('@') == -1) {
            return path;
        }
        return EMAIL_IN_DB_PATTERN.matcher(path).replaceAll("XX@YY");
    }

    boolean isLookasideConfigSet() {
        return lookasideSlotCount >= 0 && lookasideSlotSize >= 0;
    }

    /**
     * Resolves the journal mode that should be used when opening a connection to the database.
     *
     * Note: assumes openFlags have already been set.
     *
     * @return Resolved journal mode that should be used for this database connection or an empty
     * string if no journal mode should be set.
     */
    public @SQLiteDatabase.JournalMode String resolveJournalMode() {
        if (isReadOnlyDatabase()) {
            // No need to specify a journal mode when only reading.
            return "";
        }

        if (isInMemoryDb()) {
            if (journalMode != null
                    && journalMode.equalsIgnoreCase(SQLiteDatabase.JOURNAL_MODE_OFF)) {
                return SQLiteDatabase.JOURNAL_MODE_OFF;
            }
            return SQLiteDatabase.JOURNAL_MODE_MEMORY;
        }

        shouldTruncateWalFile = false;

        if (isWalEnabledInternal()) {
            shouldTruncateWalFile = true;
            return SQLiteDatabase.JOURNAL_MODE_WAL;
        } else {
            // WAL is not explicitly set so use requested journal mode or platform default
            return this.journalMode != null ? this.journalMode
                                            : SQLiteGlobal.getDefaultJournalMode();
        }
    }

    /**
     * Resolves the sync mode that should be used when opening a connection to the database.
     *
     * Note: assumes openFlags have already been set.
     * @return Resolved journal mode that should be used for this database connection or null
     * if no journal mode should be set.
     */
    public @SQLiteDatabase.SyncMode String resolveSyncMode() {
        if (isReadOnlyDatabase()) {
            // No sync mode will be used since database will be only used for reading.
            return "";
        }

        if (isInMemoryDb()) {
            // No sync mode will be used since database will be in volatile memory
            return "";
        }

        if (!TextUtils.isEmpty(syncMode)) {
            return syncMode;
        }

        if (isWalEnabledInternal()) {
            if (isLegacyCompatibilityWalEnabled()) {
                return SQLiteCompatibilityWalFlags.getWALSyncMode();
            } else {
                return SQLiteGlobal.getWALSyncMode();
            }
        } else {
            return SQLiteGlobal.getDefaultSyncMode();
        }
    }

    private boolean isWalEnabledInternal() {
        final boolean walEnabled = (openFlags & SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING) != 0;
        // Use compatibility WAL unless an app explicitly set journal/synchronous mode
        // or DISABLE_COMPATIBILITY_WAL flag is set
        final boolean isCompatibilityWalEnabled = isLegacyCompatibilityWalEnabled();
        return walEnabled || isCompatibilityWalEnabled
                || (journalMode != null
                        && journalMode.equalsIgnoreCase(SQLiteDatabase.JOURNAL_MODE_WAL));
    }
}
