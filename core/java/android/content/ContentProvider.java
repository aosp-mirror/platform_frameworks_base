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

package android.content;

import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.CursorToBulkCursorAdaptor;
import android.database.CursorWindow;
import android.database.IBulkCursor;
import android.database.IContentObserver;
import android.database.SQLException;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Content providers are one of the primary building blocks of Android applications, providing
 * content to applications. They encapsulate data and provide it to applications through the single
 * {@link ContentResolver} interface. A content provider is only required if you need to share
 * data between multiple applications. For example, the contacts data is used by multiple
 * applications and must be stored in a content provider. If you don't need to share data amongst
 * multiple applications you can use a database directly via
 * {@link android.database.sqlite.SQLiteDatabase}.
 *
 * <p>For more information, read <a href="{@docRoot}guide/topics/providers/content-providers.html">Content
 * Providers</a>.</p>
 *
 * <p>When a request is made via
 * a {@link ContentResolver} the system inspects the authority of the given URI and passes the
 * request to the content provider registered with the authority. The content provider can interpret
 * the rest of the URI however it wants. The {@link UriMatcher} class is helpful for parsing
 * URIs.</p>
 *
 * <p>The primary methods that need to be implemented are:
 * <ul>
 *   <li>{@link #query} which returns data to the caller</li>
 *   <li>{@link #insert} which inserts new data into the content provider</li>
 *   <li>{@link #update} which updates existing data in the content provider</li>
 *   <li>{@link #delete} which deletes data from the content provider</li>
 *   <li>{@link #getType} which returns the MIME type of data in the content provider</li>
 * </ul></p>
 *
 * <p>This class takes care of cross process calls so subclasses don't have to worry about which
 * process a request is coming from.</p>
 */
public abstract class ContentProvider implements ComponentCallbacks {
    private Context mContext = null;
    private String mReadPermission;
    private String mWritePermission;

    private Transport mTransport = new Transport();

    /**
     * Given an IContentProvider, try to coerce it back to the real
     * ContentProvider object if it is running in the local process.  This can
     * be used if you know you are running in the same process as a provider,
     * and want to get direct access to its implementation details.  Most
     * clients should not nor have a reason to use it.
     *
     * @param abstractInterface The ContentProvider interface that is to be
     *              coerced.
     * @return If the IContentProvider is non-null and local, returns its actual
     * ContentProvider instance.  Otherwise returns null.
     * @hide
     */
    public static ContentProvider coerceToLocalContentProvider(
            IContentProvider abstractInterface) {
        if (abstractInterface instanceof Transport) {
            return ((Transport)abstractInterface).getContentProvider();
        }
        return null;
    }

    /**
     * Binder object that deals with remoting.
     *
     * @hide
     */
    class Transport extends ContentProviderNative {
        ContentProvider getContentProvider() {
            return ContentProvider.this;
        }

        /**
         * Remote version of a query, which returns an IBulkCursor. The bulk
         * cursor should be wrapped with BulkCursorToCursorAdaptor before use.
         */
        public IBulkCursor bulkQuery(Uri uri, String[] projection,
                String selection, String[] selectionArgs, String sortOrder,
                IContentObserver observer, CursorWindow window) {
            checkReadPermission(uri);
            Cursor cursor = ContentProvider.this.query(uri, projection,
                    selection, selectionArgs, sortOrder);
            if (cursor == null) {
                return null;
            }
            String wperm = getWritePermission();
            return new CursorToBulkCursorAdaptor(cursor, observer,
                    ContentProvider.this.getClass().getName(),
                    wperm == null ||
                    getContext().checkCallingOrSelfPermission(getWritePermission())
                            == PackageManager.PERMISSION_GRANTED,
                    window);
        }

        public Cursor query(Uri uri, String[] projection,
                String selection, String[] selectionArgs, String sortOrder) {
            checkReadPermission(uri);
            return ContentProvider.this.query(uri, projection, selection,
                    selectionArgs, sortOrder);
        }

        public String getType(Uri uri) {
            return ContentProvider.this.getType(uri);
        }


        public Uri insert(Uri uri, ContentValues initialValues) {
            checkWritePermission(uri);
            return ContentProvider.this.insert(uri, initialValues);
        }

        public int bulkInsert(Uri uri, ContentValues[] initialValues) {
            checkWritePermission(uri);
            return ContentProvider.this.bulkInsert(uri, initialValues);
        }

        public int delete(Uri uri, String selection, String[] selectionArgs) {
            checkWritePermission(uri);
            return ContentProvider.this.delete(uri, selection, selectionArgs);
        }

        public int update(Uri uri, ContentValues values, String selection,
                String[] selectionArgs) {
            checkWritePermission(uri);
            return ContentProvider.this.update(uri, values, selection, selectionArgs);
        }

        public ParcelFileDescriptor openFile(Uri uri, String mode)
                throws FileNotFoundException {
            if (mode != null && mode.startsWith("rw")) checkWritePermission(uri);
            else checkReadPermission(uri);
            return ContentProvider.this.openFile(uri, mode);
        }

        public AssetFileDescriptor openAssetFile(Uri uri, String mode)
                throws FileNotFoundException {
            if (mode != null && mode.startsWith("rw")) checkWritePermission(uri);
            else checkReadPermission(uri);
            return ContentProvider.this.openAssetFile(uri, mode);
        }

        public ISyncAdapter getSyncAdapter() {
            checkWritePermission(null);
            return ContentProvider.this.getSyncAdapter().getISyncAdapter();
        }

        private void checkReadPermission(Uri uri) {
            final String rperm = getReadPermission();
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            if (getContext().checkUriPermission(uri, rperm, null, pid, uid,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    == PackageManager.PERMISSION_GRANTED) {
                return;
            }
            String msg = "Permission Denial: reading "
                    + ContentProvider.this.getClass().getName()
                    + " uri " + uri + " from pid=" + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + rperm;
            throw new SecurityException(msg);
        }

        private void checkWritePermission(Uri uri) {
            final String wperm = getWritePermission();
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            if (getContext().checkUriPermission(uri, null, wperm, pid, uid,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    == PackageManager.PERMISSION_GRANTED) {
                return;
            }
            String msg = "Permission Denial: writing "
                    + ContentProvider.this.getClass().getName()
                    + " uri " + uri + " from pid=" + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + wperm;
            throw new SecurityException(msg);
        }
    }


    /**
     * Retrieve the Context this provider is running in.  Only available once
     * onCreate(Map icicle) has been called -- this will be null in the
     * constructor.
     */
    public final Context getContext() {
        return mContext;
    }

    /**
     * Change the permission required to read data from the content
     * provider.  This is normally set for you from its manifest information
     * when the provider is first created.
     *
     * @param permission Name of the permission required for read-only access.
     */
    protected final void setReadPermission(String permission) {
        mReadPermission = permission;
    }

    /**
     * Return the name of the permission required for read-only access to
     * this content provider.  This method can be called from multiple
     * threads, as described in
     * <a href="{@docRoot}guide/topics/fundamentals.html#procthread">Application Fundamentals:
     * Processes and Threads</a>.
     */
    public final String getReadPermission() {
        return mReadPermission;
    }

    /**
     * Change the permission required to read and write data in the content
     * provider.  This is normally set for you from its manifest information
     * when the provider is first created.
     *
     * @param permission Name of the permission required for read/write access.
     */
    protected final void setWritePermission(String permission) {
        mWritePermission = permission;
    }

    /**
     * Return the name of the permission required for read/write access to
     * this content provider.  This method can be called from multiple
     * threads, as described in
     * <a href="{@docRoot}guide/topics/fundamentals.html#procthread">Application Fundamentals:
     * Processes and Threads</a>.
     */
    public final String getWritePermission() {
        return mWritePermission;
    }

    /**
     * Called when the provider is being started.
     *
     * @return true if the provider was successfully loaded, false otherwise
     */
    public abstract boolean onCreate();

    public void onConfigurationChanged(Configuration newConfig) {
    }
    
    public void onLowMemory() {
    }

    /**
     * Receives a query request from a client in a local process, and
     * returns a Cursor. This is called internally by the {@link ContentResolver}.
     * This method can be called from multiple
     * threads, as described in
     * <a href="{@docRoot}guide/topics/fundamentals.html#procthread">Application Fundamentals:
     * Processes and Threads</a>.
     * <p>
     * Example client call:<p>
     * <pre>// Request a specific record.
     * Cursor managedCursor = managedQuery(
                Contacts.People.CONTENT_URI.addId(2),
                projection,    // Which columns to return.
                null,          // WHERE clause.
                People.NAME + " ASC");   // Sort order.</pre>
     * Example implementation:<p>
     * <pre>// SQLiteQueryBuilder is a helper class that creates the
        // proper SQL syntax for us.
        SQLiteQueryBuilder qBuilder = new SQLiteQueryBuilder();

        // Set the table we're querying.
        qBuilder.setTables(DATABASE_TABLE_NAME);

        // If the query ends in a specific record number, we're
        // being asked for a specific record, so set the
        // WHERE clause in our query.
        if((URI_MATCHER.match(uri)) == SPECIFIC_MESSAGE){
            qBuilder.appendWhere("_id=" + uri.getPathLeafId());
        }

        // Make the query.
        Cursor c = qBuilder.query(mDb,
                projection,
                selection,
                selectionArgs,
                groupBy,
                having,
                sortOrder);
        c.setNotificationUri(getContext().getContentResolver(), uri);
        return c;</pre>
     *
     * @param uri The URI to query. This will be the full URI sent by the client;
     * if the client is requesting a specific record, the URI will end in a record number
     * that the implementation should parse and add to a WHERE or HAVING clause, specifying
     * that _id value.
     * @param projection The list of columns to put into the cursor. If
     *      null all columns are included.
     * @param selection A selection criteria to apply when filtering rows.
     *      If null then all rows are included.
     * @param sortOrder How the rows in the cursor should be sorted.
     *        If null then the provider is free to define the sort order.
     * @return a Cursor or null.
     */
    public abstract Cursor query(Uri uri, String[] projection,
            String selection, String[] selectionArgs, String sortOrder);

    /**
     * Return the MIME type of the data at the given URI. This should start with
     * <code>vnd.android.cursor.item</code> for a single record,
     * or <code>vnd.android.cursor.dir/</code> for multiple items.
     * This method can be called from multiple
     * threads, as described in
     * <a href="{@docRoot}guide/topics/fundamentals.html#procthread">Application Fundamentals:
     * Processes and Threads</a>.
     *
     * @param uri the URI to query.
     * @return a MIME type string, or null if there is no type.
     */
    public abstract String getType(Uri uri);

    /**
     * Implement this to insert a new row.
     * As a courtesy, call {@link ContentResolver#notifyChange(android.net.Uri ,android.database.ContentObserver) notifyChange()}
     * after inserting.
     * This method can be called from multiple
     * threads, as described in
     * <a href="{@docRoot}guide/topics/fundamentals.html#procthread">Application Fundamentals:
     * Processes and Threads</a>.
     * @param uri The content:// URI of the insertion request.
     * @param values A set of column_name/value pairs to add to the database.
     * @return The URI for the newly inserted item.
     */
    public abstract Uri insert(Uri uri, ContentValues values);

    /**
     * Implement this to insert a set of new rows, or the default implementation will
     * iterate over the values and call {@link #insert} on each of them.
     * As a courtesy, call {@link ContentResolver#notifyChange(android.net.Uri ,android.database.ContentObserver) notifyChange()}
     * after inserting.
     * This method can be called from multiple
     * threads, as described in
     * <a href="{@docRoot}guide/topics/fundamentals.html#procthread">Application Fundamentals:
     * Processes and Threads</a>.
     *
     * @param uri The content:// URI of the insertion request.
     * @param values An array of sets of column_name/value pairs to add to the database.
     * @return The number of values that were inserted.
     */
    public int bulkInsert(Uri uri, ContentValues[] values) {
        int numValues = values.length;
        for (int i = 0; i < numValues; i++) {
            insert(uri, values[i]);
        }
        return numValues;
    }

    /**
     * A request to delete one or more rows. The selection clause is applied when performing
     * the deletion, allowing the operation to affect multiple rows in a
     * directory.
     * As a courtesy, call {@link ContentResolver#notifyChange(android.net.Uri ,android.database.ContentObserver) notifyDelete()}
     * after deleting.
     * This method can be called from multiple
     * threads, as described in
     * <a href="{@docRoot}guide/topics/fundamentals.html#procthread">Application Fundamentals:
     * Processes and Threads</a>.
     *
     * <p>The implementation is responsible for parsing out a row ID at the end
     * of the URI, if a specific row is being deleted. That is, the client would
     * pass in <code>content://contacts/people/22</code> and the implementation is
     * responsible for parsing the record number (22) when creating a SQL statement.
     *
     * @param uri The full URI to query, including a row ID (if a specific record is requested).
     * @param selection An optional restriction to apply to rows when deleting.
     * @return The number of rows affected.
     * @throws SQLException
     */
    public abstract int delete(Uri uri, String selection, String[] selectionArgs);

    /**
     * Update a content URI. All rows matching the optionally provided selection
     * will have their columns listed as the keys in the values map with the
     * values of those keys.
     * As a courtesy, call {@link ContentResolver#notifyChange(android.net.Uri ,android.database.ContentObserver) notifyChange()}
     * after updating.
     * This method can be called from multiple
     * threads, as described in
     * <a href="{@docRoot}guide/topics/fundamentals.html#procthread">Application Fundamentals:
     * Processes and Threads</a>.
     *
     * @param uri The URI to query. This can potentially have a record ID if this
     * is an update request for a specific record.
     * @param values A Bundle mapping from column names to new column values (NULL is a
     *               valid value).
     * @param selection An optional filter to match rows to update.
     * @return the number of rows affected.
     */
    public abstract int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs);

    /**
     * Open a file blob associated with a content URI.
     * This method can be called from multiple
     * threads, as described in
     * <a href="{@docRoot}guide/topics/fundamentals.html#procthread">Application Fundamentals:
     * Processes and Threads</a>.
     * 
     * <p>Returns a
     * ParcelFileDescriptor, from which you can obtain a
     * {@link java.io.FileDescriptor} for use with
     * {@link java.io.FileInputStream}, {@link java.io.FileOutputStream}, etc.
     * This can be used to store large data (such as an image) associated with
     * a particular piece of content.
     *
     * <p>The returned ParcelFileDescriptor is owned by the caller, so it is
     * their responsibility to close it when done.  That is, the implementation
     * of this method should create a new ParcelFileDescriptor for each call.
     *
     * @param uri The URI whose file is to be opened.
     * @param mode Access mode for the file.  May be "r" for read-only access,
     * "rw" for read and write access, or "rwt" for read and write access
     * that truncates any existing file.
     *
     * @return Returns a new ParcelFileDescriptor which you can use to access
     * the file.
     *
     * @throws FileNotFoundException Throws FileNotFoundException if there is
     * no file associated with the given URI or the mode is invalid.
     * @throws SecurityException Throws SecurityException if the caller does
     * not have permission to access the file.
     * 
     * @see #openAssetFile(Uri, String)
     * @see #openFileHelper(Uri, String)
     */    
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        throw new FileNotFoundException("No files supported by provider at "
                + uri);
    }
    
    /**
     * This is like {@link #openFile}, but can be implemented by providers
     * that need to be able to return sub-sections of files, often assets
     * inside of their .apk.  Note that when implementing this your clients
     * must be able to deal with such files, either directly with
     * {@link ContentResolver#openAssetFileDescriptor
     * ContentResolver.openAssetFileDescriptor}, or by using the higher-level
     * {@link ContentResolver#openInputStream ContentResolver.openInputStream}
     * or {@link ContentResolver#openOutputStream ContentResolver.openOutputStream}
     * methods.
     * 
     * <p><em>Note: if you are implementing this to return a full file, you
     * should create the AssetFileDescriptor with
     * {@link AssetFileDescriptor#UNKNOWN_LENGTH} to be compatible with
     * applications that can not handle sub-sections of files.</em></p>
     *
     * @param uri The URI whose file is to be opened.
     * @param mode Access mode for the file.  May be "r" for read-only access,
     * "w" for write-only access (erasing whatever data is currently in
     * the file), "wa" for write-only access to append to any existing data,
     * "rw" for read and write access on any existing data, and "rwt" for read
     * and write access that truncates any existing file.
     *
     * @return Returns a new AssetFileDescriptor which you can use to access
     * the file.
     *
     * @throws FileNotFoundException Throws FileNotFoundException if there is
     * no file associated with the given URI or the mode is invalid.
     * @throws SecurityException Throws SecurityException if the caller does
     * not have permission to access the file.
     * 
     * @see #openFile(Uri, String)
     * @see #openFileHelper(Uri, String)
     */
    public AssetFileDescriptor openAssetFile(Uri uri, String mode)
            throws FileNotFoundException {
        ParcelFileDescriptor fd = openFile(uri, mode);
        return fd != null ? new AssetFileDescriptor(fd, 0, -1) : null;
    }

    /**
     * Convenience for subclasses that wish to implement {@link #openFile}
     * by looking up a column named "_data" at the given URI.
     *
     * @param uri The URI to be opened.
     * @param mode The file mode.  May be "r" for read-only access,
     * "w" for write-only access (erasing whatever data is currently in
     * the file), "wa" for write-only access to append to any existing data,
     * "rw" for read and write access on any existing data, and "rwt" for read
     * and write access that truncates any existing file.
     *
     * @return Returns a new ParcelFileDescriptor that can be used by the
     * client to access the file.
     */
    protected final ParcelFileDescriptor openFileHelper(Uri uri,
            String mode) throws FileNotFoundException {
        Cursor c = query(uri, new String[]{"_data"}, null, null, null);
        int count = (c != null) ? c.getCount() : 0;
        if (count != 1) {
            // If there is not exactly one result, throw an appropriate
            // exception.
            if (c != null) {
                c.close();
            }
            if (count == 0) {
                throw new FileNotFoundException("No entry for " + uri);
            }
            throw new FileNotFoundException("Multiple items at " + uri);
        }

        c.moveToFirst();
        int i = c.getColumnIndex("_data");
        String path = (i >= 0 ? c.getString(i) : null);
        c.close();
        if (path == null) {
            throw new FileNotFoundException("Column _data not found.");
        }

        int modeBits = ContentResolver.modeToMode(uri, mode);
        return ParcelFileDescriptor.open(new File(path), modeBits);
    }

    /**
     * Get the sync adapter that is to be used by this content provider.
     * This is intended for use by the sync system. If null then this
     * content provider is considered not syncable.
     * This method can be called from multiple
     * threads, as described in
     * <a href="{@docRoot}guide/topics/fundamentals.html#procthread">Application Fundamentals:
     * Processes and Threads</a>.
     * 
     * @return the SyncAdapter that is to be used by this ContentProvider, or null
     *   if this ContentProvider is not syncable
     * @hide
     */
    public SyncAdapter getSyncAdapter() {
        return null;
    }

    /**
     * Returns true if this instance is a temporary content provider.
     * @return true if this instance is a temporary content provider
     */
    protected boolean isTemporary() {
        return false;
    }

    /**
     * Returns the Binder object for this provider.
     *
     * @return the Binder object for this provider
     * @hide
     */
    public IContentProvider getIContentProvider() {
        return mTransport;
    }

    /**
     * After being instantiated, this is called to tell the content provider
     * about itself.
     *
     * @param context The context this provider is running in
     * @param info Registered information about this content provider
     */
    public void attachInfo(Context context, ProviderInfo info) {

        /*
         * Only allow it to be set once, so after the content service gives
         * this to us clients can't change it.
         */
        if (mContext == null) {
            mContext = context;
            if (info != null) {
                setReadPermission(info.readPermission);
                setWritePermission(info.writePermission);
            }
            ContentProvider.this.onCreate();
        }
    }
}
