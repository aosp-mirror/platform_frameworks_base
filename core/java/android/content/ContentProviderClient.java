/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;
import android.os.ParcelFileDescriptor;
import android.content.res.AssetFileDescriptor;

import java.io.FileNotFoundException;
import java.util.ArrayList;

/**
 * The public interface object used to interact with a {@link ContentProvider}. This is obtained by
 * calling {@link ContentResolver#acquireContentProviderClient}. This object must be released
 * using {@link #release} in order to indicate to the system that the {@link ContentProvider} is
 * no longer needed and can be killed to free up resources.
 */
public class ContentProviderClient {
    private final IContentProvider mContentProvider;
    private final ContentResolver mContentResolver;

    /**
     * @hide
     */
    ContentProviderClient(ContentResolver contentResolver, IContentProvider contentProvider) {
        mContentProvider = contentProvider;
        mContentResolver = contentResolver;
    }

    /** see {@link ContentProvider#query} */
    public Cursor query(Uri url, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) throws RemoteException {
        return mContentProvider.query(url, projection, selection,  selectionArgs, sortOrder);
    }

    /** see {@link ContentProvider#getType} */
    public String getType(Uri url) throws RemoteException {
        return mContentProvider.getType(url);
    }

    /** see {@link ContentProvider#insert} */
    public Uri insert(Uri url, ContentValues initialValues)
            throws RemoteException {
        return mContentProvider.insert(url, initialValues);
    }

    /** see {@link ContentProvider#bulkInsert} */
    public int bulkInsert(Uri url, ContentValues[] initialValues) throws RemoteException {
        return mContentProvider.bulkInsert(url, initialValues);
    }

    /** see {@link ContentProvider#delete} */
    public int delete(Uri url, String selection, String[] selectionArgs)
            throws RemoteException {
        return mContentProvider.delete(url, selection, selectionArgs);
    }

    /** see {@link ContentProvider#update} */
    public int update(Uri url, ContentValues values, String selection,
            String[] selectionArgs) throws RemoteException {
        return mContentProvider.update(url, values, selection, selectionArgs);
    }

    /** see {@link ContentProvider#openFile} */
    public ParcelFileDescriptor openFile(Uri url, String mode)
            throws RemoteException, FileNotFoundException {
        return mContentProvider.openFile(url, mode);
    }

    /** see {@link ContentProvider#openAssetFile} */
    public AssetFileDescriptor openAssetFile(Uri url, String mode)
            throws RemoteException, FileNotFoundException {
        return mContentProvider.openAssetFile(url, mode);
    }

     /** see {@link ContentProvider#applyBatch} */
    public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations)
            throws RemoteException, OperationApplicationException {
        return mContentProvider.applyBatch(operations);
    }

    /**
     * Call this to indicate to the system that the associated {@link ContentProvider} is no
     * longer needed by this {@link ContentProviderClient}.
     * @return true if this was release, false if it was already released
     */
    public boolean release() {
        return mContentResolver.releaseProvider(mContentProvider);
    }

    /**
     * Get a reference to the {@link ContentProvider} that is associated with this
     * client. If the {@link ContentProvider} is running in a different process then
     * null will be returned. This can be used if you know you are running in the same
     * process as a provider, and want to get direct access to its implementation details.
     *
     * @return If the associated {@link ContentProvider} is local, returns it.
     * Otherwise returns null.
     */
    public ContentProvider getLocalContentProvider() {
        return ContentProvider.coerceToLocalContentProvider(mContentProvider);
    }
}
