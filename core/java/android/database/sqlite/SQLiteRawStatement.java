/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

import dalvik.annotation.optimization.FastNative;

import java.io.Closeable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.Reference;
import java.util.Objects;

/**
 * A {@link SQLiteRawStatement} represents a SQLite prepared statement. The methods correspond very
 * closely to SQLite APIs that operate on a sqlite_stmt object.  In general, each API in this class
 * corresponds to a single SQLite API.

 * <p>
 * A {@link SQLiteRawStatement} must be created through a database, and there must be a
 * transaction open at the time. Statements are implicitly closed when the outermost transaction
 * ends, or if the current transaction is marked successful. Statements may be explicitly
 * closed at any time with {@link #close}.  The {@link #close} operation is idempotent and may be
 * called multiple times without harm.
 * <p>
 * Multiple {@link SQLiteRawStatement}s may be open simultaneously.  They are independent of each
 * other.  Closing one statement does not affect any other statement nor does it have any effect
 * on the enclosing transaction.
 * <p>
 * Once a {@link SQLiteRawStatement} has been closed, no further database operations are
 * permitted on that statement. An {@link IllegalStateException} will be thrown if a database
 * operation is attempted on a closed statement.
 * <p>
 * All operations on a {@link SQLiteRawStatement} must be invoked from the thread that created
 * it. A {@link IllegalStateException} will be thrown if cross-thread use is detected.
 * <p>
 * A common pattern for statements is try-with-resources.
 * <code><pre>
 * // Begin a transaction.
 * database.beginTransaction();
 * try (SQLiteRawStatement statement = database.createRawStatement("SELECT * FROM ...")) {
 *     while (statement.step()) {
 *         // Fetch columns from the result rows.
 *     }
 *     database.setTransactionSuccessful();
 * } finally {
 *     database.endTransaction();
 * }
 * </pre></code>
 * Note that {@link SQLiteRawStatement} is unrelated to {@link SQLiteStatement}.
 *
 * @see <a href="http://sqlite.org/c3ref/stmt.html">sqlite3_stmt</a>
 */
@FlaggedApi(Flags.FLAG_SQLITE_APIS_35)
public final class SQLiteRawStatement implements Closeable {

    private static final String TAG = "SQLiteRawStatement";

    /**
     * The database for this object.
     */
    private final SQLiteDatabase mDatabase;

    /**
     * The session for this object.
     */
    private final SQLiteSession mSession;

    /**
     * The PreparedStatement associated with this object. This is returned to
     * {@link SQLiteSession} when the object is closed.  This also retains immutable attributes of
     * the statement, like the parameter count.
     */
    private SQLiteConnection.PreparedStatement mPreparedStatement;

    /**
     * The native statement associated with this object.  This is pulled from the
     * PreparedStatement for faster access.
     */
    private final long mStatement;

    /**
     * The SQL string, for logging.
     */
    private final String mSql;

    /**
     * The thread that created this object.  The object is tied to a connection, which is tied to
     * its session, which is tied to the thread.  (The lifetime of this object is bounded by the
     * lifetime of the enclosing transaction, so there are more rules than just the relationships
     * in the second sentence.)  This variable is set to null when the statement is closed.
     */
    private Thread mThread;

    /**
     * The field types for SQLite columns.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
                SQLITE_DATA_TYPE_INTEGER,
                SQLITE_DATA_TYPE_FLOAT,
                SQLITE_DATA_TYPE_TEXT,
                SQLITE_DATA_TYPE_BLOB,
                SQLITE_DATA_TYPE_NULL})
    public @interface SQLiteDataType {}

    /**
     * The constant returned by {@link #getColumnType} when the column value is SQLITE_INTEGER.
     */
    public static final int SQLITE_DATA_TYPE_INTEGER  = 1;

    /**
     * The constant returned by {@link #getColumnType} when the column value is SQLITE_FLOAT.
     */
    public static final int SQLITE_DATA_TYPE_FLOAT = 2;

    /**
     * The constant returned by {@link #getColumnType} when the column value is SQLITE_TEXT.
     */
    public static final int SQLITE_DATA_TYPE_TEXT = 3;

    /**
     * The constant returned by {@link #getColumnType} when the column value is SQLITE_BLOB.
     */
    public static final int SQLITE_DATA_TYPE_BLOB = 4;

    /**
     * The constant returned by {@link #getColumnType} when the column value is SQLITE_NULL.
     */
    public static final int SQLITE_DATA_TYPE_NULL = 5;

    /**
     * SQLite error codes that are used by this class.
     */
    private static final int SQLITE_BUSY = 5;
    private static final int SQLITE_LOCKED = 6;
    private static final int SQLITE_ROW = 100;
    private static final int SQLITE_DONE = 101;

    /**
     * Create the statement with empty bindings. The construtor will throw
     * {@link IllegalStateException} if a transaction is not in progress. Clients should call
     * {@link SQLiteDatabase.createRawStatement} to create a new instance.
     */
    SQLiteRawStatement(@NonNull SQLiteDatabase db, @NonNull String sql) {
        mThread = Thread.currentThread();
        mDatabase = db;
        mSession = mDatabase.getThreadSession();
        mSession.throwIfNoTransaction();
        mSql = sql;
        // Acquire a connection and prepare the statement.
        mPreparedStatement = mSession.acquirePersistentStatement(mSql, this);
        mStatement = mPreparedStatement.mStatementPtr;
    }

    /**
     * Throw if the current session is not the session under which the object was created. Throw
     * if the object has been closed.  The actual check is that the current thread is not equal to
     * the creation thread.
     */
    private void throwIfInvalid() {
        if (mThread != Thread.currentThread()) {
            // Disambiguate the reasons for a mismatch.
            if (mThread == null) {
                throw new IllegalStateException("method called on a closed statement");
            } else {
                throw new IllegalStateException("method called on a foreign thread: " + mThread);
            }
        }
    }

    /**
     * Throw {@link IllegalArgumentException} if the length + offset are invalid with respect to
     * the array length.
     */
    private void throwIfInvalidBounds(int arrayLength, int offset, int length) {
        if (arrayLength < 0) {
            throw new IllegalArgumentException("invalid array length " + arrayLength);
        }
        if (offset < 0 || offset >= arrayLength) {
            throw new IllegalArgumentException("invalid offset " + offset
                    + " for array length " + arrayLength);
        }
        if (length <= 0 || ((arrayLength - offset) < length)) {
            throw new IllegalArgumentException("invalid offset " + offset
                    + " and length " + length
                    + " for array length " + arrayLength);
        }
    }

    /**
     * Close the object and release any native resources. It is not an error to call this on an
     * already-closed object.
     */
    @Override
    public void close() {
        if (mThread != null) {
            // The object is known not to be closed, so this only throws if the caller is not in
            // the creation thread.
            throwIfInvalid();
            mSession.releasePersistentStatement(mPreparedStatement, this);
            mThread = null;
        }
    }

    /**
     * Return true if the statement is still open and false otherwise.
     *
     * @return True if the statement is open.
     */
    public boolean isOpen() {
        return mThread != null;
    }

    /**
     * Step to the next result row. This returns true if the statement stepped to a new row, and
     * false if the statement is done.  The method throws on any other result, including a busy or
     * locked database.  If WAL is enabled then the database should never be locked or busy.
     *
     * @see <a href="http://sqlite.org/c3ref/step.html">sqlite3_step</a>
     *
     * @return True if a row is available and false otherwise.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteDatabaseLockedException if the database is locked or busy.
     * @throws SQLiteException if a native error occurs.
     */
    public boolean step() {
        throwIfInvalid();
        try {
            int err = nativeStep(mStatement, true);
            switch (err) {
                case SQLITE_ROW:
                    return true;
                case SQLITE_DONE:
                    return false;
                case SQLITE_BUSY:
                    throw new SQLiteDatabaseLockedException("database " + mDatabase + " busy");
                case SQLITE_LOCKED:
                    throw new SQLiteDatabaseLockedException("database " + mDatabase + " locked");
            }
            // This line of code should never be reached, because the native method should already
            // have thrown an exception.
            throw new SQLiteException("unknown error " + err);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Step to the next result. This returns the raw result code code from the native method.  The
     * expected values are SQLITE_ROW and SQLITE_DONE.  For other return values, clients must
     * decode the error and handle it themselves.  http://sqlite.org/rescode.html for the current
     * list of result codes.
     *
     * @return The native result code from the sqlite3_step() operation.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @hide
     */
    public int stepNoThrow() {
        throwIfInvalid();
        try {
            return nativeStep(mStatement, false);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Reset the statement.
     *
     * @see <a href="http://sqlite.org/c3ref/reset.html">sqlite3_reset</a>
     *
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteException if a native error occurs.
     */
    public void reset() {
        throwIfInvalid();
        try {
            nativeReset(mStatement, false);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Clear all parameter bindings.
     *
     * @see <a href="http://sqlite.org/c3ref/clear_bindings.html">sqlite3_clear_bindings</a>
     *
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteException if a native error occurs.
     */
    public void clearBindings() {
        throwIfInvalid();
        try {
            nativeClearBindings(mStatement);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Return the number of parameters in the statement.
     *
     * @see
     * <a href="http://sqlite.org/c3ref/bind_parameter_count.html">sqlite3_bind_parameter_count</a>
     *
     * @return The number of parameters in the statement.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     */
    public int getParameterCount() {
        throwIfInvalid();
        try {
            return nativeBindParameterCount(mStatement);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Return the index of the parameter with specified name.  If the name does not match any
     * parameter, 0 is returned.
     *
     * @see
     * <a href="http://sqlite.org/c3ref/bind_parameter_index.html">sqlite3_bind_parameter_index</a>
     *
     * @param name The name of a parameter.
     * @return The index of the parameter or 0 if the name does not identify a parameter.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     */
    public int getParameterIndex(@NonNull String name) {
        Objects.requireNonNull(name);
        throwIfInvalid();
        try {
            return nativeBindParameterIndex(mStatement, name);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Return the name of the parameter at the specified index.  Null is returned if there is no
     * such parameter or if the parameter does not have a name.
     *
     * @see
     * <a href="http://sqlite.org/c3ref/bind_parameter_name.html">sqlite3_bind_parameter_name</a>
     *
     * @param parameterIndex The index of the parameter.
     * @return The name of the parameter.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     */
    @Nullable
    public String getParameterName(int parameterIndex) {
        throwIfInvalid();
        try {
            return nativeBindParameterName(mStatement, parameterIndex);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Bind a blob to a parameter. Parameter indices start at 1. The function throws if the
     * parameter index is out of bounds.
     *
     * @see <a href="http://sqlite.org/c3ref/bind_blob.html">sqlite3_bind_blob</a>
     *
     * @param parameterIndex The index of the parameter in the query. It is one-based.
     * @param value The value to be bound to the parameter.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the parameter is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    public void bindBlob(int parameterIndex, @NonNull byte[] value) {
        Objects.requireNonNull(value);
        throwIfInvalid();
        try {
            nativeBindBlob(mStatement, parameterIndex, value, 0, value.length);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Bind a blob to a parameter. Parameter indices start at 1. The function throws if the
     * parameter index is out of bounds.  The sub-array value[offset] to value[offset+length-1] is
     * bound.
     *
     * @see <a href="http://sqlite.org/c3ref/bind_blob.html">sqlite3_bind_blob</a>
     *
     * @param parameterIndex The index of the parameter in the query. It is one-based.
     * @param value The value to be bound to the parameter.
     * @param offset An offset into the value array
     * @param length The number of bytes to bind from the value array.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws IllegalArgumentException if the sub-array exceeds the bounds of the value array.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the parameter is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    public void bindBlob(int parameterIndex, @NonNull byte[] value, int offset, int length) {
        Objects.requireNonNull(value);
        throwIfInvalid();
        throwIfInvalidBounds(value.length, offset, length);
        try {
            nativeBindBlob(mStatement, parameterIndex, value, offset, length);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Bind a double to a parameter. Parameter indices start at 1. The function throws if the
     * parameter index is out of bounds.
     *
     * @see <a href="http://sqlite.org/c3ref/bind_blob.html">sqlite3_bind_double</a>
     *
     * @param parameterIndex The index of the parameter in the query. It is one-based.
     * @param value The value to be bound to the parameter.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the parameter is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    public void bindDouble(int parameterIndex, double value) {
        throwIfInvalid();
        try {
            nativeBindDouble(mStatement, parameterIndex, value);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Bind an int to a parameter. Parameter indices start at 1. The function throws if the
     * parameter index is out of bounds.
     *
     * @see <a href="http://sqlite.org/c3ref/bind_blob.html">sqlite3_bind_int</a>
     *
     * @param parameterIndex The index of the parameter in the query. It is one-based.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the parameter is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    public void bindInt(int parameterIndex, int value) {
        throwIfInvalid();
        try {
            nativeBindInt(mStatement, parameterIndex, value);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Bind a long to the parameter. Parameter indices start at 1. The function throws if the
     * parameter index is out of bounds.
     *
     * @see <a href="http://sqlite.org/c3ref/bind_blob.html">sqlite3_bind_int64</a>
     *
     * @param value The value to be bound to the parameter.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the parameter is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    public void bindLong(int parameterIndex, long value) {
        throwIfInvalid();
        try {
            nativeBindLong(mStatement, parameterIndex, value);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Bind a null to the parameter. Parameter indices start at 1. The function throws if the
     * parameter index is out of bounds.
     *
     * @see <a href="http://sqlite.org/c3ref/bind_blob.html">sqlite3_bind_null</a>
     *
     * @param parameterIndex The index of the parameter in the query. It is one-based.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the parameter is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    public void bindNull(int parameterIndex) {
        throwIfInvalid();
        try {
            nativeBindNull(mStatement, parameterIndex);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Bind a string to the parameter. Parameter indices start at 1. The function throws if the
     * parameter index is out of bounds. The string may not be null.
     *
     * @see <a href="http://sqlite.org/c3ref/bind_blob.html">sqlite3_bind_text16</a>
     *
     * @param parameterIndex The index of the parameter in the query. It is one-based.
     * @param value The value to be bound to the parameter.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the parameter is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    public void bindText(int parameterIndex, @NonNull String value) {
        Objects.requireNonNull(value);
        throwIfInvalid();
        try {
            nativeBindText(mStatement, parameterIndex, value);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Return the number of columns in the current result row.
     *
     * @see <a href="http://sqlite.org/c3ref/column_count.html">sqlite3_column_count</a>
     *
     * @return The number of columns in the result row.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     */
    public int getResultColumnCount() {
        throwIfInvalid();
        try {
            return nativeColumnCount(mStatement);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Return the type of the column in the result row. Column indices start at 0.
     *
     * @see <a href="http://sqlite.org/c3ref/column_blob.html">sqlite3_column_type</a>
     *
     * @param columnIndex The index of a column in the result row. It is zero-based.
     * @return The type of the value in the column of the result row.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the column is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    @SQLiteDataType
    public int getColumnType(int columnIndex) {
        throwIfInvalid();
        try {
            return nativeColumnType(mStatement, columnIndex);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Return the name of the column in the result row. Column indices start at 0. This throws
     * an exception if column is not in the result.
     *
     * @see <a href="http://sqlite.org/c3ref/column_name.html">sqlite3_column_name</a>
     *
     * @param columnIndex The index of a column in the result row. It is zero-based.
     * @return The name of the column in the result row.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the column is out of range.
     * @throws SQLiteOutOfMemoryException if the database cannot allocate memory for the name.
     */
    @NonNull
    public String getColumnName(int columnIndex) {
        throwIfInvalid();
        try {
            return nativeColumnName(mStatement, columnIndex);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Return the length of the column value in the result row. Column indices start at 0. This
     * returns 0 for a null and number of bytes for text or blob. Numeric values are converted to a
     * string and the length of the string is returned.  See the sqlite documentation for
     * details. Note that this cannot be used to distinguish a null value from an empty text or
     * blob.  Note that this returns the number of bytes in the text value, not the number of
     * characters.
     *
     * @see <a href="http://sqlite.org/c3ref/column_blob.html">sqlite3_column_bytes</a>
     *
     * @param columnIndex The index of a column in the result row. It is zero-based.
     * @return The length, in bytes, of the value in the column.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the column is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    public int getColumnLength(int columnIndex) {
        throwIfInvalid();
        try {
            return nativeColumnBytes(mStatement, columnIndex);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Return the column value of the result row as a blob. Column indices start at 0. This
     * throws an exception if column is not in the result.  This returns null if the column value
     * is null.
     *
     * The column value will be converted if it is not of type {@link #SQLITE_DATA_TYPE_BLOB}; see
     * the sqlite documentation for details.
     *
     * @see <a href="http://sqlite.org/c3ref/column_blob.html">sqlite3_column_blob</a>
     *
     * @param columnIndex The index of a column in the result row. It is zero-based.
     * @return The value of the column as a blob, or null if the column is NULL.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the column is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    @Nullable
    public byte[] getColumnBlob(int columnIndex) {
        throwIfInvalid();
        try {
            return nativeColumnBlob(mStatement, columnIndex);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Copy the column value of the result row, interpreted as a blob, into the buffer. Column
     * indices start at 0. This throws an exception if column is not in the result row. Bytes are
     * copied into the buffer starting at the offset. Bytes are copied from the blob starting at
     * srcOffset.  Length bytes are copied unless the column value has fewer bytes available. The
     * function returns the number of bytes copied.
     *
     * The column value will be converted if it is not of type {@link #SQLITE_DATA_TYPE_BLOB}; see
     * the sqlite documentation for details.
     *
     * @see <a href="http://sqlite.org/c3ref/column_blob.html">sqlite3_column_blob</a>
     *
     * @param columnIndex The index of a column in the result row. It is zero-based.
     * @param buffer A pre-allocated array to be filled with the value of the column.
     * @param offset An offset into the buffer: copying starts here.
     * @param length The number of bytes to copy.
     * @param srcOffset The offset into the blob from which to start copying.
     * @return the number of bytes that were copied.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws IllegalArgumentException if the buffer is too small for offset+length.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the column is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    public int readColumnBlob(int columnIndex, @NonNull byte[] buffer, int offset,
            int length, int srcOffset) {
        Objects.requireNonNull(buffer);
        throwIfInvalid();
        throwIfInvalidBounds(buffer.length, offset, length);
        try {
            return nativeColumnBuffer(mStatement, columnIndex, buffer, offset, length, srcOffset);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Return the column value as a double. Column indices start at 0. This throws an exception
     * if column is not in the result.
     *
     * The column value will be converted if it is not of type {@link #SQLITE_DATA_TYPE_FLOAT}; see
     * the sqlite documentation for details.
     *
     * @see <a href="http://sqlite.org/c3ref/column_blob.html">sqlite3_column_double</a>
     *
     * @param columnIndex The index of a column in the result row. It is zero-based.
     * @return The value of a column as a double.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the column is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    public double getColumnDouble(int columnIndex) {
        throwIfInvalid();
        try {
            return nativeColumnDouble(mStatement, columnIndex);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Return the column value as a int. Column indices start at 0. This throws an exception if
     * column is not in the result.
     *
     * The column value will be converted if it is not of type {@link #SQLITE_DATA_TYPE_INTEGER};
     * see the sqlite documentation for details.
     *
     * @see <a href="http://sqlite.org/c3ref/column_blob.html">sqlite3_column_int</a>
     *
     * @param columnIndex The index of a column in the result row. It is zero-based.
     * @return The value of the column as an int.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the column is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    public int getColumnInt(int columnIndex) {
        throwIfInvalid();
        try {
            return nativeColumnInt(mStatement, columnIndex);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Return the column value as a long. Column indices start at 0. This throws an exception if
     * column is not in the result.
     *
     * The column value will be converted if it is not of type {@link #SQLITE_DATA_TYPE_INTEGER};
     * see the sqlite documentation for details.
     *
     * @see <a href="http://sqlite.org/c3ref/column_blob.html">sqlite3_column_long</a>
     *
     * @param columnIndex The index of a column in the result row. It is zero-based.
     * @return The value of the column as an long.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the column is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    public long getColumnLong(int columnIndex) {
        throwIfInvalid();
        try {
            return nativeColumnLong(mStatement, columnIndex);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Return the column value as a text. Column indices start at 0. This throws an exception if
     * column is not in the result.
     *
     * The column value will be converted if it is not of type {@link #SQLITE_DATA_TYPE_TEXT}; see
     * the sqlite documentation for details.
     *
     * @see <a href="http://sqlite.org/c3ref/column_blob.html">sqlite3_column_text16</a>
     *
     * @param columnIndex The index of a column in the result row. It is zero-based.
     * @return The value of the column as a string.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the column is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    @NonNull
    public String getColumnText(int columnIndex) {
        throwIfInvalid();
        try {
            return nativeColumnText(mStatement, columnIndex);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    @Override
    public String toString() {
        if (isOpen()) {
            return "SQLiteRawStatement: " + mSql;
        } else {
            return "SQLiteRawStatement: (closed) " + mSql;
        }
    }

    /**
     * Native methods that only require a statement.
     */

    /**
     * Metadata about the prepared statement.  The results are a property of the statement itself
     * and not of any data in the database.
     */
    @FastNative
    private static native int nativeBindParameterCount(long stmt);
    @FastNative
    private static native int nativeBindParameterIndex(long stmt, String name);
    @FastNative
    private static native String nativeBindParameterName(long stmt, int param);

    @FastNative
    private static native int nativeColumnCount(long stmt);

    /**
     * Operations on the statement
     */
    private static native int nativeStep(long stmt, boolean throwOnError);
    private static native void nativeReset(long stmt, boolean clear);
    @FastNative
    private static native void nativeClearBindings(long stmt);

    /**
     * Methods that bind values to parameters.
     */
    @FastNative
    private static native void nativeBindBlob(long stmt, int param, byte[] val, int off, int len);
    @FastNative
    private static native void nativeBindDouble(long stmt, int param, double val);
    @FastNative
    private static native void nativeBindInt(long stmt, int param, int val);
    @FastNative
    private static native void nativeBindLong(long stmt, int param, long val);
    @FastNative
    private static native void nativeBindNull(long stmt, int param);
    @FastNative
    private static native void nativeBindText(long stmt, int param, String val);

    /**
     * Methods that return information about the columns int the current result row.
     */
    @FastNative
    private static native int nativeColumnType(long stmt, int col);
    @FastNative
    private static native String nativeColumnName(long stmt, int col);

    /**
     * Methods that return information about the value columns in the current result row.
     */
    @FastNative
    private static native int nativeColumnBytes(long stmt, int col);

    @FastNative
    private static native byte[] nativeColumnBlob(long stmt, int col);
    @FastNative
    private static native int nativeColumnBuffer(long stmt, int col,
            byte[] val, int off, int len, int srcOffset);
    @FastNative
    private static native double nativeColumnDouble(long stmt, int col);
    @FastNative
    private static native int nativeColumnInt(long stmt, int col);
    @FastNative
    private static native long nativeColumnLong(long stmt, int col);
    @FastNative
    private static native String nativeColumnText(long stmt, int col);
}
