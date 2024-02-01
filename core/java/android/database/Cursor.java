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

package android.database;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Bundle;

import java.io.Closeable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.List;

/**
 * This interface provides random read-write access to the result set returned
 * by a database query.
 * <p>
 * Cursor implementations are not required to be synchronized so code using a Cursor from multiple
 * threads should perform its own synchronization when using the Cursor.
 * </p><p>
 * Implementations should subclass {@link AbstractCursor}.
 * </p>
 */
public interface Cursor extends Closeable {
    /*
     * Values returned by {@link #getType(int)}.
     * These should be consistent with the corresponding types defined in CursorWindow.h
     */
    /** Value returned by {@link #getType(int)} if the specified column is null */
    static final int FIELD_TYPE_NULL = 0;

    /** Value returned by {@link #getType(int)} if the specified  column type is integer */
    static final int FIELD_TYPE_INTEGER = 1;

    /** Value returned by {@link #getType(int)} if the specified column type is float */
    static final int FIELD_TYPE_FLOAT = 2;

    /** Value returned by {@link #getType(int)} if the specified column type is string */
    static final int FIELD_TYPE_STRING = 3;

    /** Value returned by {@link #getType(int)} if the specified column type is blob */
    static final int FIELD_TYPE_BLOB = 4;

    /** @hide */
    @IntDef(prefix = { "FIELD_TYPE_" }, value = {
            FIELD_TYPE_NULL,
            FIELD_TYPE_INTEGER,
            FIELD_TYPE_FLOAT,
            FIELD_TYPE_STRING,
            FIELD_TYPE_BLOB,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FieldType {}

    /**
     * Returns the numbers of rows in the cursor.
     *
     * @return the number of rows in the cursor.
     */
    @IntRange(from = 0) int getCount();

    /**
     * Returns the current position of the cursor in the row set.
     * The value is zero-based. When the row set is first returned the cursor
     * will be at positon -1, which is before the first row. After the
     * last row is returned another call to next() will leave the cursor past
     * the last entry, at a position of count().
     *
     * @return the current cursor position.
     */
    @IntRange(from = -1) int getPosition();

    /**
     * Move the cursor by a relative amount, forward or backward, from the
     * current position. Positive offsets move forwards, negative offsets move
     * backwards. If the final position is outside of the bounds of the result
     * set then the resultant position will be pinned to -1 or count() depending
     * on whether the value is off the front or end of the set, respectively.
     *
     * <p>This method will return true if the requested destination was
     * reachable, otherwise, it returns false. For example, if the cursor is at
     * currently on the second entry in the result set and move(-5) is called,
     * the position will be pinned at -1, and false will be returned.
     *
     * @param offset the offset to be applied from the current position.
     * @return whether the requested move fully succeeded.
     */
    boolean move(int offset);

    /**
     * Move the cursor to an absolute position. The valid
     * range of values is -1 &lt;= position &lt;= count.
     *
     * <p>This method will return true if the request destination was reachable, 
     * otherwise, it returns false.
     *
     * @param position the zero-based position to move to.
     * @return whether the requested move fully succeeded.
     */
    boolean moveToPosition(@IntRange(from = -1) int position);

    /**
     * Move the cursor to the first row.
     *
     * <p>This method will return false if the cursor is empty.
     *
     * @return whether the move succeeded.
     */
    boolean moveToFirst();

    /**
     * Move the cursor to the last row.
     *
     * <p>This method will return false if the cursor is empty.
     *
     * @return whether the move succeeded.
     */
    boolean moveToLast();

    /**
     * Move the cursor to the next row.
     *
     * <p>This method will return false if the cursor is already past the
     * last entry in the result set.
     *
     * @return whether the move succeeded.
     */
    boolean moveToNext();

    /**
     * Move the cursor to the previous row.
     *
     * <p>This method will return false if the cursor is already before the
     * first entry in the result set.
     *
     * @return whether the move succeeded.
     */
    boolean moveToPrevious();

    /**
     * Returns whether the cursor is pointing to the first row.
     *
     * @return whether the cursor is pointing at the first entry.
     */
    boolean isFirst();

    /**
     * Returns whether the cursor is pointing to the last row.
     *
     * @return whether the cursor is pointing at the last entry.
     */
    boolean isLast();

    /**
     * Returns whether the cursor is pointing to the position before the first
     * row.
     *
     * @return whether the cursor is before the first result.
     */
    boolean isBeforeFirst();

    /**
     * Returns whether the cursor is pointing to the position after the last
     * row.
     *
     * @return whether the cursor is after the last result.
     */
    boolean isAfterLast();

    /**
     * Returns the zero-based index for the given column name, or -1 if the column doesn't exist.
     * If you expect the column to exist use {@link #getColumnIndexOrThrow(String)} instead, which
     * will make the error more clear.
     *
     * @param columnName the name of the target column.
     * @return the zero-based column index for the given column name, or -1 if
     * the column name does not exist.
     * @see #getColumnIndexOrThrow(String)
     */
    @IntRange(from = -1) int getColumnIndex(String columnName);

    /**
     * Returns the zero-based index for the given column name, or throws
     * {@link IllegalArgumentException} if the column doesn't exist. If you're not sure if
     * a column will exist or not use {@link #getColumnIndex(String)} and check for -1, which
     * is more efficient than catching the exceptions.
     *
     * @param columnName the name of the target column.
     * @return the zero-based column index for the given column name
     * @see #getColumnIndex(String)
     * @throws IllegalArgumentException if the column does not exist
     */
    @IntRange(from = 0) int getColumnIndexOrThrow(String columnName)
            throws IllegalArgumentException;

    /**
     * Returns the column name at the given zero-based column index.
     *
     * @param columnIndex the zero-based index of the target column.
     * @return the column name for the given column index.
     */
    String getColumnName(@IntRange(from = 0) int columnIndex);

    /**
     * Returns a string array holding the names of all of the columns in the
     * result set in the order in which they were listed in the result.
     *
     * @return the names of the columns returned in this query.
     */
    String[] getColumnNames();

    /**
     * Return total number of columns
     * @return number of columns 
     */
    @IntRange(from = 0) int getColumnCount();
    
    /**
     * Returns the value of the requested column as a byte array.
     *
     * <p>The result and whether this method throws an exception when the
     * column value is null or the column type is not a blob type is
     * implementation-defined.
     *
     * @param columnIndex the zero-based index of the target column.
     * @return the value of that column as a byte array.
     */
    byte[] getBlob(@IntRange(from = 0) int columnIndex);

    /**
     * Returns the value of the requested column as a String.
     *
     * <p>The result and whether this method throws an exception when the
     * column value is null or the column type is not a string type is
     * implementation-defined.
     *
     * @param columnIndex the zero-based index of the target column.
     * @return the value of that column as a String.
     */
    String getString(@IntRange(from = 0) int columnIndex);
    
    /**
     * Retrieves the requested column text and stores it in the buffer provided.
     * If the buffer size is not sufficient, a new char buffer will be allocated 
     * and assigned to CharArrayBuffer.data
     * @param columnIndex the zero-based index of the target column.
     *        if the target column is null, return buffer
     * @param buffer the buffer to copy the text into. 
     */
    void copyStringToBuffer(@IntRange(from = 0) int columnIndex, CharArrayBuffer buffer);
    
    /**
     * Returns the value of the requested column as a short.
     *
     * <p>The result and whether this method throws an exception when the
     * column value is null, the column type is not an integral type, or the
     * integer value is outside the range [<code>Short.MIN_VALUE</code>,
     * <code>Short.MAX_VALUE</code>] is implementation-defined.
     *
     * @param columnIndex the zero-based index of the target column.
     * @return the value of that column as a short.
     */
    short getShort(@IntRange(from = 0) int columnIndex);

    /**
     * Returns the value of the requested column as an int.
     *
     * <p>The result and whether this method throws an exception when the
     * column value is null, the column type is not an integral type, or the
     * integer value is outside the range [<code>Integer.MIN_VALUE</code>,
     * <code>Integer.MAX_VALUE</code>] is implementation-defined.
     *
     * @param columnIndex the zero-based index of the target column.
     * @return the value of that column as an int.
     */
    int getInt(@IntRange(from = 0) int columnIndex);

    /**
     * Returns the value of the requested column as a long.
     *
     * <p>The result and whether this method throws an exception when the
     * column value is null, the column type is not an integral type, or the
     * integer value is outside the range [<code>Long.MIN_VALUE</code>,
     * <code>Long.MAX_VALUE</code>] is implementation-defined.
     *
     * @param columnIndex the zero-based index of the target column.
     * @return the value of that column as a long.
     */
    long getLong(@IntRange(from = 0) int columnIndex);

    /**
     * Returns the value of the requested column as a float.
     *
     * <p>The result and whether this method throws an exception when the
     * column value is null, the column type is not a floating-point type, or the
     * floating-point value is not representable as a <code>float</code> value is
     * implementation-defined.
     *
     * @param columnIndex the zero-based index of the target column.
     * @return the value of that column as a float.
     */
    float getFloat(@IntRange(from = 0) int columnIndex);

    /**
     * Returns the value of the requested column as a double.
     *
     * <p>The result and whether this method throws an exception when the
     * column value is null, the column type is not a floating-point type, or the
     * floating-point value is not representable as a <code>double</code> value is
     * implementation-defined.
     *
     * @param columnIndex the zero-based index of the target column.
     * @return the value of that column as a double.
     */
    double getDouble(@IntRange(from = 0) int columnIndex);

    /**
     * Returns data type of the given column's value.
     * The preferred type of the column is returned but the data may be converted to other types
     * as documented in the get-type methods such as {@link #getInt(int)}, {@link #getFloat(int)}
     * etc.
     *
     * @param columnIndex the zero-based index of the target column.
     * @return column value type
     */
    @FieldType int getType(@IntRange(from = 0) int columnIndex);

    /**
     * Returns <code>true</code> if the value in the indicated column is null.
     *
     * @param columnIndex the zero-based index of the target column.
     * @return whether the column value is null.
     */
    boolean isNull(@IntRange(from = 0) int columnIndex);

    /**
     * Deactivates the Cursor, making all calls on it fail until {@link #requery} is called.
     * Inactive Cursors use fewer resources than active Cursors.
     * Calling {@link #requery} will make the cursor active again.
     * @deprecated Since {@link #requery()} is deprecated, so too is this.
     */
    @Deprecated
    void deactivate();

    /**
     * Performs the query that created the cursor again, refreshing its 
     * contents. This may be done at any time, including after a call to {@link
     * #deactivate}.
     *
     * Since this method could execute a query on the database and potentially take
     * a while, it could cause ANR if it is called on Main (UI) thread.
     * A warning is printed if this method is being executed on Main thread.
     *
     * @return true if the requery succeeded, false if not, in which case the
     *         cursor becomes invalid.
     * @deprecated Don't use this. Just request a new cursor, so you can do this
     * asynchronously and update your list view once the new cursor comes back.
     */
    @Deprecated
    boolean requery();

    /**
     * Closes the Cursor, releasing all of its resources and making it completely invalid.
     * Unlike {@link #deactivate()} a call to {@link #requery()} will not make the Cursor valid
     * again.
     */
    void close();

    /**
     * return true if the cursor is closed
     * @return true if the cursor is closed.
     */
    boolean isClosed();
    
    /**
     * Register an observer that is called when changes happen to the content backing this cursor.
     * Typically the data set won't change until {@link #requery()} is called.
     *
     * @param observer the object that gets notified when the content backing the cursor changes.
     * @see #unregisterContentObserver(ContentObserver)
     */
    void registerContentObserver(ContentObserver observer);

    /**
     * Unregister an observer that has previously been registered with this
     * cursor via {@link #registerContentObserver}.
     *
     * @param observer the object to unregister.
     * @see #registerContentObserver(ContentObserver)
     */
    void unregisterContentObserver(ContentObserver observer);
    
    /**
     * Register an observer that is called when changes happen to the contents
     * of the this cursors data set, for example, when the data set is changed via
     * {@link #requery()}, {@link #deactivate()}, or {@link #close()}.
     *
     * @param observer the object that gets notified when the cursors data set changes.
     * @see #unregisterDataSetObserver(DataSetObserver)
     */
    void registerDataSetObserver(DataSetObserver observer);

    /**
     * Unregister an observer that has previously been registered with this
     * cursor via {@link #registerContentObserver}.
     *
     * @param observer the object to unregister.
     * @see #registerDataSetObserver(DataSetObserver)
     */
    void unregisterDataSetObserver(DataSetObserver observer);

    /**
     * Register to watch a content URI for changes. This can be the URI of a specific data row (for 
     * example, "content://my_provider_type/23"), or a a generic URI for a content type.
     *
     * <p>Calling this overrides any previous call to
     * {@link #setNotificationUris(ContentResolver, List)}.
     *
     * @param cr The content resolver from the caller's context. The listener attached to 
     * this resolver will be notified.
     * @param uri The content URI to watch.
     */
    void setNotificationUri(ContentResolver cr, Uri uri);

    /**
     * Similar to {@link #setNotificationUri(ContentResolver, Uri)}, except this version allows
     * to watch multiple content URIs for changes.
     *
     * <p>If this is not implemented, this is equivalent to calling
     * {@link #setNotificationUri(ContentResolver, Uri)} with the first URI in {@code uris}.
     *
     * <p>Calling this overrides any previous call to
     * {@link #setNotificationUri(ContentResolver, Uri)}.
     *
     * @param cr The content resolver from the caller's context. The listener attached to
     * this resolver will be notified.
     * @param uris The content URIs to watch.
     */
    default void setNotificationUris(@NonNull ContentResolver cr, @NonNull List<Uri> uris) {
        setNotificationUri(cr, uris.get(0));
    }

    /**
     * Return the URI at which notifications of changes in this Cursor's data
     * will be delivered, as previously set by {@link #setNotificationUri}.
     * @return Returns a URI that can be used with
     * {@link ContentResolver#registerContentObserver(android.net.Uri, boolean, ContentObserver)
     * ContentResolver.registerContentObserver} to find out about changes to this Cursor's
     * data.  May be null if no notification URI has been set.
     */
    Uri getNotificationUri();

    /**
     * Return the URIs at which notifications of changes in this Cursor's data
     * will be delivered, as previously set by {@link #setNotificationUris}.
     *
     * <p>If this is not implemented, this is equivalent to calling {@link #getNotificationUri()}.
     *
     * @return Returns URIs that can be used with
     * {@link ContentResolver#registerContentObserver(android.net.Uri, boolean, ContentObserver)
     * ContentResolver.registerContentObserver} to find out about changes to this Cursor's
     * data. May be null if no notification URI has been set.
     */
    default @Nullable List<Uri> getNotificationUris() {
        final Uri notifyUri = getNotificationUri();
        return notifyUri == null ? null : Arrays.asList(notifyUri);
    }

    /**
     * onMove() will only be called across processes if this method returns true.
     * @return whether all cursor movement should result in a call to onMove().
     */
    boolean getWantsAllOnMoveCalls();

    /**
     * Sets a {@link Bundle} that will be returned by {@link #getExtras()}.
     *
     * @param extras {@link Bundle} to set, or null to set an empty bundle.
     */
    void setExtras(Bundle extras);

    /**
     * Returns a bundle of extra values. This is an optional way for cursors to provide out-of-band
     * metadata to their users. One use of this is for reporting on the progress of network requests
     * that are required to fetch data for the cursor.
     *
     * <p>These values may only change when requery is called.
     * @return cursor-defined values, or {@link android.os.Bundle#EMPTY Bundle.EMPTY} if there
     *         are no values. Never <code>null</code>.
     */
    Bundle getExtras();

    /**
     * This is an out-of-band way for the user of a cursor to communicate with the cursor. The
     * structure of each bundle is entirely defined by the cursor.
     *
     * <p>One use of this is to tell a cursor that it should retry its network request after it
     * reported an error.
     * @param extras extra values, or {@link android.os.Bundle#EMPTY Bundle.EMPTY}.
     *         Never <code>null</code>.
     * @return extra values, or {@link android.os.Bundle#EMPTY Bundle.EMPTY}.
     *         Never <code>null</code>.
     */
    Bundle respond(Bundle extras);
}
