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

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Bundle;

import java.util.Map;

/**
 * This interface provides random read-write access to the result set returned
 * by a database query.
 */
public interface Cursor {
    /**
     * Returns the numbers of rows in the cursor.
     *
     * @return the number of rows in the cursor.
     */
    int getCount();

    /**
     * Returns the current position of the cursor in the row set.
     * The value is zero-based. When the row set is first returned the cursor
     * will be at positon -1, which is before the first row. After the
     * last row is returned another call to next() will leave the cursor past
     * the last entry, at a position of count().
     *
     * @return the current cursor position.
     */
    int getPosition();

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
    boolean moveToPosition(int position);

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
     * Removes the row at the current cursor position from the underlying data
     * store. After this method returns the cursor will be pointing to the row
     * after the row that is deleted. This has the side effect of decrementing
     * the result of count() by one.
     * <p>
     * The query must have the row ID column in its selection, otherwise this
     * call will fail.
     * 
     * @hide
     * @return whether the record was successfully deleted.
     * @deprecated use {@link ContentResolver#delete(Uri, String, String[])}
     */
    @Deprecated
    boolean deleteRow();

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
    int getColumnIndex(String columnName);

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
    int getColumnIndexOrThrow(String columnName) throws IllegalArgumentException;

    /**
     * Returns the column name at the given zero-based column index.
     *
     * @param columnIndex the zero-based index of the target column.
     * @return the column name for the given column index.
     */
    String getColumnName(int columnIndex);

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
    int getColumnCount();
    
    /**
     * Returns the value of the requested column as a byte array.
     *
     * <p>If the native content of that column is not blob exception may throw
     *
     * @param columnIndex the zero-based index of the target column.
     * @return the value of that column as a byte array.
     */
    byte[] getBlob(int columnIndex);

    /**
     * Returns the value of the requested column as a String.
     *
     * <p>If the native content of that column is not text the result will be
     * the result of passing the column value to String.valueOf(x).
     *
     * @param columnIndex the zero-based index of the target column.
     * @return the value of that column as a String.
     */
    String getString(int columnIndex);
    
    /**
     * Retrieves the requested column text and stores it in the buffer provided.
     * If the buffer size is not sufficient, a new char buffer will be allocated 
     * and assigned to CharArrayBuffer.data
     * @param columnIndex the zero-based index of the target column.
     *        if the target column is null, return buffer
     * @param buffer the buffer to copy the text into. 
     */
    void copyStringToBuffer(int columnIndex, CharArrayBuffer buffer);
    
    /**
     * Returns the value of the requested column as a short.
     *
     * <p>If the native content of that column is not numeric the result will be
     * the result of passing the column value to Short.valueOf(x).
     *
     * @param columnIndex the zero-based index of the target column.
     * @return the value of that column as a short.
     */
    short getShort(int columnIndex);

    /**
     * Returns the value of the requested column as an int.
     *
     * <p>If the native content of that column is not numeric the result will be
     * the result of passing the column value to Integer.valueOf(x).
     *
     * @param columnIndex the zero-based index of the target column.
     * @return the value of that column as an int.
     */
    int getInt(int columnIndex);

    /**
     * Returns the value of the requested column as a long.
     *
     * <p>If the native content of that column is not numeric the result will be
     * the result of passing the column value to Long.valueOf(x).
     *
     * @param columnIndex the zero-based index of the target column.
     * @return the value of that column as a long.
     */
    long getLong(int columnIndex);

    /**
     * Returns the value of the requested column as a float.
     *
     * <p>If the native content of that column is not numeric the result will be
     * the result of passing the column value to Float.valueOf(x).
     *
     * @param columnIndex the zero-based index of the target column.
     * @return the value of that column as a float.
     */
    float getFloat(int columnIndex);

    /**
     * Returns the value of the requested column as a double.
     *
     * <p>If the native content of that column is not numeric the result will be
     * the result of passing the column value to Double.valueOf(x).
     *
     * @param columnIndex the zero-based index of the target column.
     * @return the value of that column as a double.
     */
    double getDouble(int columnIndex);

    /**
     * Returns <code>true</code> if the value in the indicated column is null.
     *
     * @param columnIndex the zero-based index of the target column.
     * @return whether the column value is null.
     */
    boolean isNull(int columnIndex);

    /**
     * Returns <code>true</code> if the cursor supports updates.
     *
     * @return whether the cursor supports updates.
     * @hide
     * @deprecated use the {@link ContentResolver} update methods instead of the Cursor
     * update methods
     */
    @Deprecated
    boolean supportsUpdates();

    /**
     * Returns <code>true</code> if there are pending updates that have not yet been committed.
     * 
     * @return <code>true</code> if there are pending updates that have not yet been committed.
     * @hide
     * @deprecated use the {@link ContentResolver} update methods instead of the Cursor
     * update methods
     */
    @Deprecated
    boolean hasUpdates();

    /**
     * Updates the value for the given column in the row the cursor is
     * currently pointing at. Updates are not committed to the backing store
     * until {@link #commitUpdates()} is called.
     *
     * @param columnIndex the zero-based index of the target column.
     * @param value the new value.
     * @return whether the operation succeeded.
     * @hide
     * @deprecated use the {@link ContentResolver} update methods instead of the Cursor
     * update methods
     */
    @Deprecated
    boolean updateBlob(int columnIndex, byte[] value);

    /**
     * Updates the value for the given column in the row the cursor is
     * currently pointing at. Updates are not committed to the backing store
     * until {@link #commitUpdates()} is called.
     *
     * @param columnIndex the zero-based index of the target column.
     * @param value the new value.
     * @return whether the operation succeeded.
     * @hide
     * @deprecated use the {@link ContentResolver} update methods instead of the Cursor
     * update methods
     */
    @Deprecated
    boolean updateString(int columnIndex, String value);

    /**
     * Updates the value for the given column in the row the cursor is
     * currently pointing at. Updates are not committed to the backing store
     * until {@link #commitUpdates()} is called.
     *
     * @param columnIndex the zero-based index of the target column.
     * @param value the new value.
     * @return whether the operation succeeded.
     * @hide
     * @deprecated use the {@link ContentResolver} update methods instead of the Cursor
     * update methods
     */
    @Deprecated
    boolean updateShort(int columnIndex, short value);

    /**
     * Updates the value for the given column in the row the cursor is
     * currently pointing at. Updates are not committed to the backing store
     * until {@link #commitUpdates()} is called.
     *
     * @param columnIndex the zero-based index of the target column.
     * @param value the new value.
     * @return whether the operation succeeded.
     * @hide
     * @deprecated use the {@link ContentResolver} update methods instead of the Cursor
     * update methods
     */
    @Deprecated
    boolean updateInt(int columnIndex, int value);

    /**
     * Updates the value for the given column in the row the cursor is
     * currently pointing at. Updates are not committed to the backing store
     * until {@link #commitUpdates()} is called.
     *
     * @param columnIndex the zero-based index of the target column.
     * @param value the new value.
     * @return whether the operation succeeded.
     * @hide
     * @deprecated use the {@link ContentResolver} update methods instead of the Cursor
     * update methods
     */
    @Deprecated
    boolean updateLong(int columnIndex, long value);

    /**
     * Updates the value for the given column in the row the cursor is
     * currently pointing at. Updates are not committed to the backing store
     * until {@link #commitUpdates()} is called.
     *
     * @param columnIndex the zero-based index of the target column.
     * @param value the new value.
     * @return whether the operation succeeded.
     * @hide
     * @deprecated use the {@link ContentResolver} update methods instead of the Cursor
     * update methods
     */
    @Deprecated
    boolean updateFloat(int columnIndex, float value);

    /**
     * Updates the value for the given column in the row the cursor is
     * currently pointing at. Updates are not committed to the backing store
     * until {@link #commitUpdates()} is called.
     *
     * @param columnIndex the zero-based index of the target column.
     * @param value the new value.
     * @return whether the operation succeeded.
     * @hide
     * @deprecated use the {@link ContentResolver} update methods instead of the Cursor
     * update methods
     */
    @Deprecated
    boolean updateDouble(int columnIndex, double value);

    /**
     * Removes the value for the given column in the row the cursor is
     * currently pointing at. Updates are not committed to the backing store
     * until {@link #commitUpdates()} is called.
     *
     * @param columnIndex the zero-based index of the target column.
     * @return whether the operation succeeded.
     * @hide
     * @deprecated use the {@link ContentResolver} update methods instead of the Cursor
     * update methods
     */
    @Deprecated
    boolean updateToNull(int columnIndex);

    /**
     * Atomically commits all updates to the backing store. After completion,
     * this method leaves the data in an inconsistent state and you should call
     * {@link #requery} before reading data from the cursor again.
     *
     * @return whether the operation succeeded.
     * @hide
     * @deprecated use the {@link ContentResolver} update methods instead of the Cursor
     * update methods
     */
    @Deprecated
    boolean commitUpdates();

    /**
     * Atomically commits all updates to the backing store, as well as the
     * updates included in values. After completion,
     * this method leaves the data in an inconsistent state and you should call
     * {@link #requery} before reading data from the cursor again.
     *
     * @param values A map from row IDs to Maps associating column names with
     *               updated values. A null value indicates the field should be
                     removed.
     * @return whether the operation succeeded.
     * @hide
     * @deprecated use the {@link ContentResolver} update methods instead of the Cursor
     * update methods
     */
    @Deprecated
    boolean commitUpdates(Map<? extends Long,
            ? extends Map<String,Object>> values);

    /**
     * Reverts all updates made to the cursor since the last call to
     * commitUpdates.
     * @hide
     * @deprecated use the {@link ContentResolver} update methods instead of the Cursor
     * update methods
     */
    @Deprecated
    void abortUpdates();

    /**
     * Deactivates the Cursor, making all calls on it fail until {@link #requery} is called.
     * Inactive Cursors use fewer resources than active Cursors.
     * Calling {@link #requery} will make the cursor active again.
     */
    void deactivate();

    /**
     * Performs the query that created the cursor again, refreshing its 
     * contents. This may be done at any time, including after a call to {@link
     * #deactivate}.
     *
     * @return true if the requery succeeded, false if not, in which case the
     *         cursor becomes invalid.
     */
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
     * @param cr The content resolver from the caller's context. The listener attached to 
     * this resolver will be notified.
     * @param uri The content URI to watch.
     */
    void setNotificationUri(ContentResolver cr, Uri uri);

    /**
     * onMove() will only be called across processes if this method returns true.
     * @return whether all cursor movement should result in a call to onMove().
     */
    boolean getWantsAllOnMoveCalls();

    /**
     * Returns a bundle of extra values. This is an optional way for cursors to provide out-of-band
     * metadata to their users. One use of this is for reporting on the progress of network requests
     * that are required to fetch data for the cursor.
     *
     * <p>These values may only change when requery is called.
     * @return cursor-defined values, or Bundle.EMTPY if there are no values. Never null.
     */
    Bundle getExtras();

    /**
     * This is an out-of-band way for the the user of a cursor to communicate with the cursor. The
     * structure of each bundle is entirely defined by the cursor.
     *
     * <p>One use of this is to tell a cursor that it should retry its network request after it
     * reported an error.
     * @param extras extra values, or Bundle.EMTPY. Never null.
     * @return extra values, or Bundle.EMTPY. Never null.
     */
    Bundle respond(Bundle extras);
}
