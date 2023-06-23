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
 * Represents a SQLite statement. The methods correspond very closely to SQLite APIs that operate
 * on a sqlite_stmt object. See the SQLite API documentation for complete details.  In general,
 * the APIs in this class correspond to the SQLite APIs with the same name, except that snake-case
 * is changed to camel-case.
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
 * Note that this class is unrelated to {@link SQLiteStatement}.
 * @hide
 */
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
    @IntDef(value = {SQLITE_INTEGER, SQLITE_FLOAT, SQLITE_TEXT, SQLITE_BLOB, SQLITE_NULL})
    public @interface SQLiteDataType {}

    public static final int SQLITE_INTEGER  = 1;
    public static final int SQLITE_FLOAT = 2;
    public static final int SQLITE_TEXT = 3;
    public static final int SQLITE_BLOB = 4;
    public static final int SQLITE_NULL = 5;

    /**
     * SQLite error codes that are used by this class.  Refer to the sqlite documentation for
     * other error codes.
     */
    public static final int SQLITE_OK = 0;
    public static final int SQLITE_BUSY = 5;
    public static final int SQLITE_LOCKED = 6;
    public static final int SQLITE_ROW = 100;
    public static final int SQLITE_DONE = 101;

    /**
     * Create the statement with empty bindings. The construtor will throw
     * {@link IllegalStateException} if a transaction is not in progress. Clients should call
     * {@link SQLiteDatabase.createRawStatement} to create a new instance.
     */
    SQLiteRawStatement(@NonNull SQLiteDatabase db, @NonNull String sql) throws SQLiteException {
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
     * @return True if the statement is open.
     */
    public boolean isOpen() {
        return mThread != null;
    }

    /**
     * Step to the next result. This returns true if the statement stepped to a new row, and
     * false if the statement is done.  The method throws on any other result, including a busy or
     * locked database.  If WAL is enabled then the database should never be locked or busy.
     * @return True if a row is available and false otherwise.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteDatabaseLockedException if the database is locked or busy.
     * @throws SQLiteException if a native error occurs.
     */
    public boolean step() throws SQLiteException {
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
     * Step to the next result. This returns the raw error code code from the native method.  The
     * expected values are SQLITE_ROW and SQLITE_DONE.  For other return values, clients must
     * decode the error and handle it themselves.
     * @return The native result code from the sqlite3_step() operation.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
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
     * Reset the statement. The sqlite3 API returns an error code if the last call to step
     * generated an error; this function discards those error codes.
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
     * @return The number of parameters in the statement.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     */
    public int bindParameterCount() {
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
     * @param name The name of a parameter.
     * @return The index of the parameter or 0 if the name does not identify a parameter.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     */
    public int bindParameterIndex(@NonNull String name) {
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
     * @param parameter The index of the parameter.
     * @return The name of the parameter.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the parameter is out of range.
     */
    @Nullable
    public String bindParameterName(int parameter) {
        throwIfInvalid();
        try {
            return nativeBindParameterName(mStatement, parameter);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Bind a blob to a parameter. Parameter indices start at 1. The function throws if the
     * parameter index is out of bounds.
     * @param parameter The index of the parameter in the query. It is one-based.
     * @param value The value to be bound to the parameter.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the parameter is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    public void bindBlob(int parameter, @NonNull byte[] value) throws SQLiteException {
        Objects.requireNonNull(value);
        throwIfInvalid();
        try {
            nativeBindBlob(mStatement, parameter, value, 0, value.length);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Bind a blob to a parameter. Parameter indices start at 1. The function throws if the
     * parameter index is out of bounds.  The sub-array value[offset] to value[offset+length-1] is
     * bound.
     * @param parameter The index of the parameter in the query. It is one-based.
     * @param value The value to be bound to the parameter.
     * @param offset An offset into the value array
     * @param length The number of bytes to bind from the value array.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws IllegalArgumentException if the sub-array exceeds the bounds of the value array.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the parameter is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    public void bindBlob(int parameter, @NonNull byte[] value, int offset, int length)
            throws SQLiteException {
        Objects.requireNonNull(value);
        throwIfInvalid();
        throwIfInvalidBounds(value.length, offset, length);
        try {
            nativeBindBlob(mStatement, parameter, value, offset, length);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Bind a double to a parameter. Parameter indices start at 1. The function throws if the
     * parameter index is out of bounds.
     * @param parameter The index of the parameter in the query. It is one-based.
     * @param value The value to be bound to the parameter.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the parameter is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    public void bindDouble(int parameter, double value) throws SQLiteException {
        throwIfInvalid();
        try {
            nativeBindDouble(mStatement, parameter, value);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Bind an int to a parameter. Parameter indices start at 1. The function throws if the
     * parameter index is out of bounds.
     * @param parameter The index of the parameter in the query. It is one-based.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the parameter is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    public void bindInt(int parameter, int value) throws SQLiteException {
        throwIfInvalid();
        try {
            nativeBindInt(mStatement, parameter, value);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Bind a long to the parameter. Parameter indices start at 1. The function throws if the
     * parameter index is out of bounds.
     * @param value The value to be bound to the parameter.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the parameter is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    public void bindLong(int parameter, long value) throws SQLiteException {
        throwIfInvalid();
        try {
            nativeBindLong(mStatement, parameter, value);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Bind a null to the parameter. Parameter indices start at 1. The function throws if the
     * parameter index is out of bounds.
     * @param parameter The index of the parameter in the query. It is one-based.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the parameter is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    public void bindNull(int parameter) throws SQLiteException {
        throwIfInvalid();
        try {
            nativeBindNull(mStatement, parameter);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Bind a string to the parameter. Parameter indices start at 1. The function throws if the
     * parameter index is out of bounds. The string may not be null.
     * @param parameter The index of the parameter in the query. It is one-based.
     * @param value The value to be bound to the parameter.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the parameter is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    public void bindText(int parameter, @NonNull String value) throws SQLiteException {
        Objects.requireNonNull(value);
        throwIfInvalid();
        try {
            nativeBindText(mStatement, parameter, value);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Return the number of columns in the current result row.
     * @return The number of columns in the result row.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     */
    public int getResultColumnsCount() {
        throwIfInvalid();
        try {
            return nativeColumnCount(mStatement);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Return the type of the column in the result row. Column indices start at 0.
     * @param column The index of a column in the result row. It is zero-based.
     * @return The type of the value in the column of the result row.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the column is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    @SQLiteDataType
    public int getType(int column) throws SQLiteException {
        throwIfInvalid();
        try {
            return nativeColumnType(mStatement, column);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Return the name of the column in the result row. Column indices start at 0. This throws
     * an exception if column is not in the result.
     * @param column The index of a column in the result row. It is zero-based.
     * @return The name of the column in the result row.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the column is out of range.
     * @throws SQLiteOutOfMemoryException if the database cannot allocate memory for the name.
     */
    @NonNull
    public String getName(int column) throws SQLiteException {
        throwIfInvalid();
        try {
            return nativeColumnName(mStatement, column);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Return the length of the column value in the result row. Column indices start at 0. This
     * returns 0 for a null and number of bytes for text or blob. Numeric values are converted to
     * a string and the length of the string is returned. Note that this cannot be used to
     * distinguish a null value from an empty text or blob.  Note that this returns the number of
     * bytes in the text value, not the number of characters.
     * @param column The index of a column in the result row. It is zero-based.
     * @return The length, in bytes, of the value in the column.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the column is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    public int getLength(int column) throws SQLiteException {
        throwIfInvalid();
        try {
            return nativeColumnBytes(mStatement, column);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Return the column value of the result row as a blob. Column indices start at 0. This
     * throws an exception if column is not in the result.  This returns null if the column value
     * is null.
     * @param column The index of a column in the result row. It is zero-based.
     * @return The value of the column as a blob, or null if the column is NULL.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the column is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    @Nullable
    public byte[] getBlob(int column) throws SQLiteException {
        throwIfInvalid();
        try {
            return nativeColumnBlob(mStatement, column);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Copy the column value of the result row, interpreted as a blob, into the buffer. Column
     * indices start at 0. This throws an exception if column is not in the result row. Bytes are
     * copied into the buffer until the buffer is full or the end of the blob value is reached.
     * The function returns the number of bytes copied.
     * @param column The index of a column in the result row. It is zero-based.
     * @param buffer A pre-allocated array to be filled with the value of the column.
     * @return the number of bytes that were copied
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the column is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    public int getBlob(int column, @NonNull byte[] buffer) throws SQLiteException {
        Objects.requireNonNull(buffer);
        throwIfInvalid();
        try {
            return nativeColumnBuffer(mStatement, column, buffer, 0, buffer.length, 0);
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
     * @param column The index of a column in the result row. It is zero-based.
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
    public int getBlob(int column, @NonNull byte[] buffer, int offset, int length, int srcOffset)
            throws SQLiteException {
        Objects.requireNonNull(buffer);
        throwIfInvalid();
        throwIfInvalidBounds(buffer.length, offset, length);
        try {
            return nativeColumnBuffer(mStatement, column, buffer, offset, length, srcOffset);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Return the column value as a double. Column indices start at 0. This throws an exception
     * if column is not in the result.
     * @param column The index of a column in the result row. It is zero-based.
     * @return The value of a column as a double.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the column is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    public double getDouble(int column) throws SQLiteException {
        throwIfInvalid();
        try {
            return nativeColumnDouble(mStatement, column);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Return the column value as a int. Column indices start at 0. This throws an exception if
     * column is not in the result.
     * @param column The index of a column in the result row. It is zero-based.
     * @return The value of the column as an int.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the column is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    public int getInt(int column) throws SQLiteException {
        throwIfInvalid();
        try {
            return nativeColumnInt(mStatement, column);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Return the column value as a long. Column indices start at 0. This throws an exception if
     * column is not in the result.
     * @param column The index of a column in the result row. It is zero-based.
     * @return The value of the column as an long.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the column is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    public long getLong(int column) throws SQLiteException {
        throwIfInvalid();
        try {
            return nativeColumnLong(mStatement, column);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Return the column value as a text. Column indices start at 0. This throws an exception if
     * column is not in the result.
     * @param column The index of a column in the result row. It is zero-based.
     * @return The value of the column as a string.
     * @throws IllegalStateException if the statement is closed or this is a foreign thread.
     * @throws SQLiteBindOrColumnIndexOutOfRangeException if the column is out of range.
     * @throws SQLiteException if a native error occurs.
     */
    @NonNull
    public String getText(int column) throws SQLiteException {
        throwIfInvalid();
        try {
            return nativeColumnText(mStatement, column);
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
     * Methods that return information (including the values) of columns from the current result
     * row.
     */
    @FastNative
    private static native int nativeColumnType(long stmt, int col);
    @FastNative
    private static native String nativeColumnName(long stmt, int col);

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
