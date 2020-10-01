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

    private class Logger implements AutoCloseable {
        private final StringBuilder sb = new StringBuilder();

        public Logger(String method, Object... args) {
            // First, force-unparcel any bundles so we can log them
            for (Object arg : args) {
                if (arg instanceof Bundle) {
                    ((Bundle) arg).size();
                }
            }

            sb.append("callingUid=").append(Binder.getCallingUid()).append(' ');
            sb.append(method);
            sb.append('(').append(deepToString(args)).append(')');
        }

        private String deepToString(Object value) {
            if (value != null && value.getClass().isArray()) {
                return Arrays.deepToString((Object[]) value);
            } else {
                return String.valueOf(value);
            }
        }

        public <T> T setResult(T res) {
            if (res instanceof Cursor) {
                sb.append('\n');
                DatabaseUtils.dumpCursor((Cursor) res, sb);
            } else {
                sb.append(" = ").append(deepToString(res));
            }
            return res;
        }

        @Override
        public void close() {
            Log.v(tag, sb.toString());
        }
    }

    @Override
    public @Nullable Cursor query(@NonNull Uri uri, @Nullable String[] projection,
            @Nullable Bundle queryArgs, @Nullable CancellationSignal cancellationSignal)
            throws RemoteException {
        try (Logger l = new Logger("query", uri, projection, queryArgs, cancellationSignal)) {
            try {
                return l.setResult(delegate.query(uri, projection, queryArgs, cancellationSignal));
            } catch (Exception res) {
                l.setResult(res);
                throw res;
            }
        }
    }

    @Override
    public @Nullable String getType(@NonNull Uri uri) throws RemoteException {
        try (Logger l = new Logger("getType", uri)) {
            try {
                return l.setResult(delegate.getType(uri));
            } catch (Exception res) {
                l.setResult(res);
                throw res;
            }
        }
    }

    @Override
    public @Nullable String[] getStreamTypes(@NonNull Uri uri, @NonNull String mimeTypeFilter)
            throws RemoteException {
        try (Logger l = new Logger("getStreamTypes", uri, mimeTypeFilter)) {
            try {
                return l.setResult(delegate.getStreamTypes(uri, mimeTypeFilter));
            } catch (Exception res) {
                l.setResult(res);
                throw res;
            }
        }
    }

    @Override
    public @Nullable Uri canonicalize(@NonNull Uri uri) throws RemoteException {
        try (Logger l = new Logger("canonicalize", uri)) {
            try {
                return l.setResult(delegate.canonicalize(uri));
            } catch (Exception res) {
                l.setResult(res);
                throw res;
            }
        }
    }

    @Override
    public @Nullable Uri uncanonicalize(@NonNull Uri uri) throws RemoteException {
        try (Logger l = new Logger("uncanonicalize", uri)) {
            try {
                return l.setResult(delegate.uncanonicalize(uri));
            } catch (Exception res) {
                l.setResult(res);
                throw res;
            }
        }
    }

    @Override
    public boolean refresh(@NonNull Uri uri, @Nullable Bundle args,
            @Nullable CancellationSignal cancellationSignal) throws RemoteException {
        try (Logger l = new Logger("refresh", uri, args, cancellationSignal)) {
            try {
                return l.setResult(delegate.refresh(uri, args, cancellationSignal));
            } catch (Exception res) {
                l.setResult(res);
                throw res;
            }
        }
    }

    @Override
    public int checkUriPermission(@NonNull Uri uri, int uid, @Intent.AccessUriMode int modeFlags)
            throws RemoteException {
        try (Logger l = new Logger("checkUriPermission", uri, uid, modeFlags)) {
            try {
                return l.setResult(delegate.checkUriPermission(uri, uid, modeFlags));
            } catch (Exception res) {
                l.setResult(res);
                throw res;
            }
        }
    }

    @Override
    public @Nullable Uri insert(@NonNull Uri uri, @Nullable ContentValues initialValues,
            @Nullable Bundle extras) throws RemoteException {
        try (Logger l = new Logger("insert", uri, initialValues, extras)) {
            try {
                return l.setResult(delegate.insert(uri, initialValues, extras));
            } catch (Exception res) {
                l.setResult(res);
                throw res;
            }
        }
    }

    @Override
    public int bulkInsert(@NonNull Uri uri, @NonNull ContentValues[] initialValues)
            throws RemoteException {
        try (Logger l = new Logger("bulkInsert", uri, initialValues)) {
            try {
                return l.setResult(delegate.bulkInsert(uri, initialValues));
            } catch (Exception res) {
                l.setResult(res);
                throw res;
            }
        }
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable Bundle extras) throws RemoteException {
        try (Logger l = new Logger("delete", uri, extras)) {
            try {
                return l.setResult(delegate.delete(uri, extras));
            } catch (Exception res) {
                l.setResult(res);
                throw res;
            }
        }
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable Bundle extras)
            throws RemoteException {
        try (Logger l = new Logger("update", uri, values, extras)) {
            try {
                return l.setResult(delegate.update(uri, values, extras));
            } catch (Exception res) {
                l.setResult(res);
                throw res;
            }
        }
    }

    @Override
    public @Nullable ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode,
            @Nullable CancellationSignal signal) throws RemoteException, FileNotFoundException {
        try (Logger l = new Logger("openFile", uri, mode, signal)) {
            try {
                return l.setResult(delegate.openFile(uri, mode, signal));
            } catch (Exception res) {
                l.setResult(res);
                throw res;
            }
        }
    }

    @Override
    public @Nullable AssetFileDescriptor openAssetFile(@NonNull Uri uri, @NonNull String mode,
            @Nullable CancellationSignal signal) throws RemoteException, FileNotFoundException {
        try (Logger l = new Logger("openAssetFile", uri, mode, signal)) {
            try {
                return l.setResult(delegate.openAssetFile(uri, mode, signal));
            } catch (Exception res) {
                l.setResult(res);
                throw res;
            }
        }
    }

    @Override
    public @Nullable AssetFileDescriptor openTypedAssetFile(@NonNull Uri uri,
            @NonNull String mimeTypeFilter, @Nullable Bundle opts,
            @Nullable CancellationSignal signal) throws RemoteException, FileNotFoundException {
        try (Logger l = new Logger("openTypedAssetFile", uri, mimeTypeFilter, opts, signal)) {
            try {
                return l.setResult(delegate.openTypedAssetFile(uri, mimeTypeFilter, opts, signal));
            } catch (Exception res) {
                l.setResult(res);
                throw res;
            }
        }
    }

    @Override
    public @NonNull ContentProviderResult[] applyBatch(@NonNull String authority,
            @NonNull ArrayList<ContentProviderOperation> operations)
            throws RemoteException, OperationApplicationException {
        try (Logger l = new Logger("applyBatch", authority, operations)) {
            try {
                return l.setResult(delegate.applyBatch(authority, operations));
            } catch (Exception res) {
                l.setResult(res);
                throw res;
            }
        }
    }

    @Override
    public @Nullable Bundle call(@NonNull String authority, @NonNull String method,
            @Nullable String arg, @Nullable Bundle extras) throws RemoteException {
        try (Logger l = new Logger("call", authority, method, arg, extras)) {
            try {
                return l.setResult(delegate.call(authority, method, arg, extras));
            } catch (Exception res) {
                l.setResult(res);
                throw res;
            }
        }
    }
}
