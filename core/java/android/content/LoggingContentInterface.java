/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Instance of {@link ContentInterface} that logs all inputs and outputs while
 * delegating to another {@link ContentInterface}.
 *
 * @hide
 */
public class LoggingContentInterface implements ContentInterface {
    private final String tag;
    private final ContentInterface delegate;

    public LoggingContentInterface(String tag, ContentInterface delegate) {
        this.tag = tag;
        this.delegate = delegate;
    }

    private void log(String method, Object res, Object... args) {
        // First, force-unparcel any bundles so we can log them
        for (Object arg : args) {
            if (arg instanceof Bundle) {
                ((Bundle) arg).size();
            }
        }

        final StringBuilder sb = new StringBuilder();
        sb.append("callingUid=").append(Binder.getCallingUid()).append(' ');
        sb.append(method);
        sb.append('(').append(deepToString(args)).append(')');
        if (res instanceof Cursor) {
            sb.append('\n');
            DatabaseUtils.dumpCursor((Cursor) res, sb);
        } else {
            sb.append(" = ").append(deepToString(res));
        }

        if (res instanceof Exception) {
            Log.e(tag, sb.toString());
        } else {
            Log.v(tag, sb.toString());
        }
    }

    private String deepToString(Object value) {
        if (value != null && value.getClass().isArray()) {
            return Arrays.deepToString((Object[]) value);
        } else {
            return String.valueOf(value);
        }
    }

    @Override
    public @Nullable Cursor query(@NonNull Uri uri, @Nullable String[] projection,
            @Nullable Bundle queryArgs, @Nullable CancellationSignal cancellationSignal)
            throws RemoteException {
        try {
            final Cursor res = delegate.query(uri, projection, queryArgs, cancellationSignal);
            log("query", res, uri, projection, queryArgs, cancellationSignal);
            return res;
        } catch (Exception res) {
            log("query", res, uri, projection, queryArgs, cancellationSignal);
            throw res;
        }
    }

    @Override
    public @Nullable String getType(@NonNull Uri uri) throws RemoteException {
        try {
            final String res = delegate.getType(uri);
            log("getType", res, uri);
            return res;
        } catch (Exception res) {
            log("getType", res, uri);
            throw res;
        }
    }

    @Override
    public @Nullable String[] getStreamTypes(@NonNull Uri uri, @NonNull String mimeTypeFilter)
            throws RemoteException {
        try {
            final String[] res = delegate.getStreamTypes(uri, mimeTypeFilter);
            log("getStreamTypes", res, uri, mimeTypeFilter);
            return res;
        } catch (Exception res) {
            log("getStreamTypes", res, uri, mimeTypeFilter);
            throw res;
        }
    }

    @Override
    public @Nullable Uri canonicalize(@NonNull Uri uri) throws RemoteException {
        try {
            final Uri res = delegate.canonicalize(uri);
            log("canonicalize", res, uri);
            return res;
        } catch (Exception res) {
            log("canonicalize", res, uri);
            throw res;
        }
    }

    @Override
    public @Nullable Uri uncanonicalize(@NonNull Uri uri) throws RemoteException {
        try {
            final Uri res = delegate.uncanonicalize(uri);
            log("uncanonicalize", res, uri);
            return res;
        } catch (Exception res) {
            log("uncanonicalize", res, uri);
            throw res;
        }
    }

    @Override
    public boolean refresh(@NonNull Uri uri, @Nullable Bundle args,
            @Nullable CancellationSignal cancellationSignal) throws RemoteException {
        try {
            final boolean res = delegate.refresh(uri, args, cancellationSignal);
            log("refresh", res, uri, args, cancellationSignal);
            return res;
        } catch (Exception res) {
            log("refresh", res, uri, args, cancellationSignal);
            throw res;
        }
    }

    @Override
    public @Nullable Uri insert(@NonNull Uri uri, @Nullable ContentValues initialValues)
            throws RemoteException {
        try {
            final Uri res = delegate.insert(uri, initialValues);
            log("insert", res, uri, initialValues);
            return res;
        } catch (Exception res) {
            log("insert", res, uri, initialValues);
            throw res;
        }
    }

    @Override
    public int bulkInsert(@NonNull Uri uri, @NonNull ContentValues[] initialValues)
            throws RemoteException {
        try {
            final int res = delegate.bulkInsert(uri, initialValues);
            log("bulkInsert", res, uri, initialValues);
            return res;
        } catch (Exception res) {
            log("bulkInsert", res, uri, initialValues);
            throw res;
        }
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection,
            @Nullable String[] selectionArgs) throws RemoteException {
        try {
            final int res = delegate.delete(uri, selection, selectionArgs);
            log("delete", res, uri, selection, selectionArgs);
            return res;
        } catch (Exception res) {
            log("delete", res, uri, selection, selectionArgs);
            throw res;
        }
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
            @Nullable String[] selectionArgs) throws RemoteException {
        try {
            final int res = delegate.update(uri, values, selection, selectionArgs);
            log("update", res, uri, values, selection, selectionArgs);
            return res;
        } catch (Exception res) {
            log("update", res, uri, values, selection, selectionArgs);
            throw res;
        }
    }

    @Override
    public @Nullable ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode,
            @Nullable CancellationSignal signal) throws RemoteException, FileNotFoundException {
        try {
            final ParcelFileDescriptor res = delegate.openFile(uri, mode, signal);
            log("openFile", res, uri, mode, signal);
            return res;
        } catch (Exception res) {
            log("openFile", res, uri, mode, signal);
            throw res;
        }
    }

    @Override
    public @Nullable AssetFileDescriptor openAssetFile(@NonNull Uri uri, @NonNull String mode,
            @Nullable CancellationSignal signal) throws RemoteException, FileNotFoundException {
        try {
            final AssetFileDescriptor res = delegate.openAssetFile(uri, mode, signal);
            log("openAssetFile", res, uri, mode, signal);
            return res;
        } catch (Exception res) {
            log("openAssetFile", res, uri, mode, signal);
            throw res;
        }
    }

    @Override
    public @Nullable AssetFileDescriptor openTypedAssetFile(@NonNull Uri uri,
            @NonNull String mimeTypeFilter, @Nullable Bundle opts,
            @Nullable CancellationSignal signal) throws RemoteException, FileNotFoundException {
        try {
            final AssetFileDescriptor res = delegate.openTypedAssetFile(uri, mimeTypeFilter, opts, signal);
            log("openTypedAssetFile", res, uri, mimeTypeFilter, opts, signal);
            return res;
        } catch (Exception res) {
            log("openTypedAssetFile", res, uri, mimeTypeFilter, opts, signal);
            throw res;
        }
    }

    @Override
    public @NonNull ContentProviderResult[] applyBatch(@NonNull String authority,
            @NonNull ArrayList<ContentProviderOperation> operations)
            throws RemoteException, OperationApplicationException {
        try {
            final ContentProviderResult[] res = delegate.applyBatch(authority, operations);
            log("applyBatch", res, authority, operations);
            return res;
        } catch (Exception res) {
            log("applyBatch", res, authority, operations);
            throw res;
        }
    }

    @Override
    public @Nullable Bundle call(@NonNull String authority, @NonNull String method,
            @Nullable String arg, @Nullable Bundle extras) throws RemoteException {
        try {
            final Bundle res = delegate.call(authority, method, arg, extras);
            log("call", res, authority, method, arg, extras);
            return res;
        } catch (Exception res) {
            log("call", res, authority, method, arg, extras);
            throw res;
        }
    }
}
