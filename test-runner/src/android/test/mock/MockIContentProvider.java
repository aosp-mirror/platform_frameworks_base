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

package android.test.mock;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentValues;
import android.content.EntityIterator;
import android.content.IContentProvider;
import android.content.ICancelationSignal;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.FileNotFoundException;
import java.util.ArrayList;

/**
 * Mock implementation of IContentProvider.  All methods are non-functional and throw
 * {@link java.lang.UnsupportedOperationException}.  Tests can extend this class to
 * implement behavior needed for tests.
 *
 * @hide - @hide because this exposes bulkQuery() and call(), which must also be hidden.
 */
public class MockIContentProvider implements IContentProvider {
    public int bulkInsert(Uri url, ContentValues[] initialValues) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @SuppressWarnings("unused")
    public int delete(Uri url, String selection, String[] selectionArgs)
            throws RemoteException {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public String getType(Uri url) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @SuppressWarnings("unused")
    public Uri insert(Uri url, ContentValues initialValues) throws RemoteException {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public ParcelFileDescriptor openFile(Uri url, String mode) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public AssetFileDescriptor openAssetFile(Uri uri, String mode) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public Cursor query(Uri url, String[] projection, String selection, String[] selectionArgs,
            String sortOrder, ICancelationSignal cancelationSignal) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public EntityIterator queryEntities(Uri url, String selection, String[] selectionArgs,
            String sortOrder) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public int update(Uri url, ContentValues values, String selection, String[] selectionArgs)
            throws RemoteException {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public Bundle call(String method, String request, Bundle args)
            throws RemoteException {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public IBinder asBinder() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public String[] getStreamTypes(Uri url, String mimeTypeFilter) throws RemoteException {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public AssetFileDescriptor openTypedAssetFile(Uri url, String mimeType, Bundle opts)
            throws RemoteException, FileNotFoundException {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public ICancelationSignal createCancelationSignal() throws RemoteException {
        throw new UnsupportedOperationException("unimplemented mock method");
    }
}
