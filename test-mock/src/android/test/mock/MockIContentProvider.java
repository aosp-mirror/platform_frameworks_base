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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.AttributionSource;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.EntityIterator;
import android.content.IContentProvider;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallback;
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
    @Override
    public int bulkInsert(@NonNull AttributionSource attributionSource, Uri url,
            ContentValues[] initialValues) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    @SuppressWarnings("unused")
    public int delete(@NonNull AttributionSource attributionSource, Uri url,
            Bundle extras) throws RemoteException {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public String getType(Uri url) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    @SuppressWarnings("deprecation")
    public void getTypeAsync(Uri uri, RemoteCallback remoteCallback) {
        AsyncTask.SERIAL_EXECUTOR.execute(() -> {
            final Bundle bundle = new Bundle();
            bundle.putString(ContentResolver.REMOTE_CALLBACK_RESULT, getType(uri));
            remoteCallback.sendResult(bundle);
        });
    }

    @Override
    @SuppressWarnings("unused")
    public Uri insert(@NonNull AttributionSource attributionSource, Uri url,
            ContentValues initialValues, Bundle extras) throws RemoteException {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public ParcelFileDescriptor openFile(@NonNull AttributionSource attributionSource,
            Uri url, String mode, ICancellationSignal signal) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public AssetFileDescriptor openAssetFile(@NonNull AttributionSource attributionSource,
            Uri uri, String mode, ICancellationSignal signal) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public ContentProviderResult[] applyBatch(@NonNull AttributionSource attributionSource,
            String authority, ArrayList<ContentProviderOperation> operations) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public Cursor query(@NonNull AttributionSource attributionSource, Uri url,
            @Nullable String[] projection, @Nullable Bundle queryArgs,
            @Nullable ICancellationSignal cancellationSignal) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public EntityIterator queryEntities(Uri url, String selection, String[] selectionArgs,
            String sortOrder) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public int update(@NonNull AttributionSource attributionSource, Uri url,
            ContentValues values, Bundle extras) throws RemoteException {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public Bundle call(@NonNull AttributionSource attributionSource, String authority,
            String method, String request, Bundle args) throws RemoteException {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public IBinder asBinder() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public String[] getStreamTypes(Uri url, String mimeTypeFilter) throws RemoteException {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public AssetFileDescriptor openTypedAssetFile(@NonNull AttributionSource attributionSource,
            Uri url, String mimeType, Bundle opts, ICancellationSignal signal)
            throws RemoteException, FileNotFoundException {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public ICancellationSignal createCancellationSignal() throws RemoteException {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public Uri canonicalize(@NonNull AttributionSource attributionSource, Uri uri) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    @SuppressWarnings("deprecation")
    public void canonicalizeAsync(@NonNull AttributionSource attributionSource, Uri uri,
            RemoteCallback remoteCallback) {
        AsyncTask.SERIAL_EXECUTOR.execute(() -> {
            final Bundle bundle = new Bundle();
            bundle.putParcelable(ContentResolver.REMOTE_CALLBACK_RESULT,
                    canonicalize(attributionSource, uri));
            remoteCallback.sendResult(bundle);
        });
    }

    @Override
    public Uri uncanonicalize(@NonNull AttributionSource attributionSource, Uri uri) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    @SuppressWarnings("deprecation")
    public void uncanonicalizeAsync(@NonNull AttributionSource attributionSource, Uri uri,
            RemoteCallback remoteCallback) {
        AsyncTask.SERIAL_EXECUTOR.execute(() -> {
            final Bundle bundle = new Bundle();
            bundle.putParcelable(ContentResolver.REMOTE_CALLBACK_RESULT,
                    uncanonicalize(attributionSource, uri));
            remoteCallback.sendResult(bundle);
        });
    }

    @Override
    public boolean refresh(@NonNull AttributionSource attributionSource, Uri url, Bundle args,
            ICancellationSignal cancellationSignal) throws RemoteException {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    /** {@hide} */
    @Override
    public int checkUriPermission(@NonNull AttributionSource attributionSource, Uri uri, int uid,
            int modeFlags) {
        throw new UnsupportedOperationException("unimplemented mock method call");
    }
}
