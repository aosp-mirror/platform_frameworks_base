/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.test.mock;

import android.content.ContentValues;
import android.content.IContentProvider;
import android.content.ISyncAdapter;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.CursorWindow;
import android.database.IBulkCursor;
import android.database.IContentObserver;
import android.net.Uri;
import android.os.RemoteException;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import java.io.FileNotFoundException;

/**
 * Mock implementation of IContentProvider that does nothing.  All methods are non-functional and 
 * throw {@link java.lang.UnsupportedOperationException}.  Tests can extend this class to
 * implement behavior needed for tests.
 * 
 * @hide - Because IContentProvider hides bulkQuery(), this doesn't pass through JavaDoc
 * without generating errors.
 *
 */
public class MockContentProvider implements IContentProvider {

    @SuppressWarnings("unused")
    public int bulkInsert(Uri url, ContentValues[] initialValues) throws RemoteException {
        // TODO Auto-generated method stub
        return 0;
    }

    @SuppressWarnings("unused")
    public IBulkCursor bulkQuery(Uri url, String[] projection, String selection,
            String[] selectionArgs, String sortOrder, IContentObserver observer, 
            CursorWindow window) throws RemoteException {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @SuppressWarnings("unused")
    public int delete(Uri url, String selection, String[] selectionArgs) 
            throws RemoteException {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @SuppressWarnings("unused")
    public ISyncAdapter getSyncAdapter() throws RemoteException {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @SuppressWarnings("unused")
    public String getType(Uri url) throws RemoteException {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @SuppressWarnings("unused")
    public Uri insert(Uri url, ContentValues initialValues) throws RemoteException {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @SuppressWarnings("unused")
    public ParcelFileDescriptor openFile(Uri url, String mode) throws RemoteException,
            FileNotFoundException {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @SuppressWarnings("unused")
    public AssetFileDescriptor openAssetFile(Uri uri, String mode)
            throws FileNotFoundException {
        throw new UnsupportedOperationException("unimplemented mock method");
    }
    
    @SuppressWarnings("unused")
    public Cursor query(Uri url, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) throws RemoteException {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @SuppressWarnings("unused")
    public int update(Uri url, ContentValues values, String selection, String[] selectionArgs)
            throws RemoteException {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public IBinder asBinder() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

}
