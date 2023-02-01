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

package android.provider;

import static android.provider.Settings.CALL_METHOD_USER_KEY;
import static android.provider.Settings.ContentProviderHolder;
import static android.provider.Settings.LOCAL_LOGV;
import static android.provider.Settings.NameValueCache.NAME_EQ_PLACEHOLDER;
import static android.provider.Settings.NameValueCache.SELECT_VALUE_PROJECTION;
import static android.provider.Settings.TAG;

import android.annotation.NonNull;
import android.content.ContentResolver;
import android.content.IContentProvider;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IpcDataCache;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Objects;

/** @hide */
final class SettingsIpcDataCache {
    private static final boolean DEBUG = true;
    private static final int NUM_MAX_ENTRIES = 2048;

    static class GetQuery {
        @NonNull final ContentResolver mContentResolver;
        @NonNull final String mName;

        GetQuery(@NonNull ContentResolver contentResolver, @NonNull String name) {
            mContentResolver = contentResolver;
            mName = name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GetQuery)) return false;
            GetQuery getQuery = (GetQuery) o;
            return mContentResolver.equals(
                    getQuery.mContentResolver) && mName.equals(getQuery.mName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mContentResolver, mName);
        }
    }

    private static class GetQueryHandler extends IpcDataCache.QueryHandler<GetQuery, String> {
        @NonNull final ContentProviderHolder mContentProviderHolder;
        @NonNull final String mCallGetCommand;
        @NonNull final Uri mUri;
        final int mUserId;

        private GetQueryHandler(
                ContentProviderHolder contentProviderHolder, String callGetCommand, Uri uri) {
            mContentProviderHolder = contentProviderHolder;
            mCallGetCommand = callGetCommand;
            mUri = uri;
            mUserId = UserHandle.myUserId();
        }

        @Nullable
        @Override
        public String apply(GetQuery query) {
            try {
                return getValueFromContentProviderCall(mContentProviderHolder, mCallGetCommand,
                        mUri, mUserId, query);
            } catch (RemoteException e) {
                // Throw to prevent caching
                e.rethrowAsRuntimeException();
            }
            return null;
        }
    }

    @NonNull
    static IpcDataCache<GetQuery, String> createValueCache(
            @NonNull ContentProviderHolder contentProviderHolder,
            @NonNull String callGetCommand, @NonNull Uri uri, @NonNull String type) {
        if (DEBUG) {
            Log.i(TAG, "Creating value cache for type:" + type);
        }
        IpcDataCache.Config config = new IpcDataCache.Config(
                NUM_MAX_ENTRIES, IpcDataCache.MODULE_SYSTEM, type /* apiName */);
        return new IpcDataCache<>(config.child("get"),
                new GetQueryHandler(contentProviderHolder, callGetCommand, uri));
    }

    @Nullable
    private static String getValueFromContentProviderCall(
            @NonNull ContentProviderHolder providerHolder, @NonNull String callGetCommand,
            @NonNull Uri uri, int userId, @NonNull GetQuery query)
            throws RemoteException {
        final ContentResolver cr = query.mContentResolver;
        final String name = query.mName;
        return getValueFromContentProviderCall(providerHolder, callGetCommand, uri, userId, cr,
                name);
    }

    @Nullable
    static String getValueFromContentProviderCall(
            @NonNull ContentProviderHolder providerHolder, @NonNull String callGetCommand,
            @NonNull Uri uri, int userId, ContentResolver cr, String name) throws RemoteException {
        final IContentProvider cp = providerHolder.getProvider(cr);

        // Try the fast path first, not using query().  If this
        // fails (alternate Settings provider that doesn't support
        // this interface?) then we fall back to the query/table
        // interface.
        if (callGetCommand != null) {
            try {
                Bundle args = new Bundle();
                if (userId != UserHandle.myUserId()) {
                    args.putInt(CALL_METHOD_USER_KEY, userId);
                }
                Bundle b;
                // If we're in system server and in a binder transaction we need to clear the
                // calling uid. This works around code in system server that did not call
                // clearCallingIdentity, previously this wasn't needed because reading settings
                // did not do permission checking but that's no longer the case.
                // Long term this should be removed and callers should properly call
                // clearCallingIdentity or use a ContentResolver from the caller as needed.
                if (Settings.isInSystemServer() && Binder.getCallingUid() != Process.myUid()) {
                    final long token = Binder.clearCallingIdentity();
                    try {
                        b = cp.call(cr.getAttributionSource(),
                                providerHolder.mUri.getAuthority(), callGetCommand, name,
                                args);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                } else {
                    b = cp.call(cr.getAttributionSource(),
                            providerHolder.mUri.getAuthority(), callGetCommand, name, args);
                }
                if (b != null) {
                    return b.getString(Settings.NameValueTable.VALUE);
                }
                // If the response Bundle is null, we fall through
                // to the query interface below.
            } catch (RemoteException e) {
                // Not supported by the remote side?  Fall through
                // to query().
            }
        }

        Cursor c = null;
        try {
            Bundle queryArgs = ContentResolver.createSqlQueryBundle(
                    NAME_EQ_PLACEHOLDER, new String[]{name}, null);
            // Same workaround as above.
            if (Settings.isInSystemServer() && Binder.getCallingUid() != Process.myUid()) {
                final long token = Binder.clearCallingIdentity();
                try {
                    c = cp.query(cr.getAttributionSource(), uri,
                            SELECT_VALUE_PROJECTION, queryArgs, null);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            } else {
                c = cp.query(cr.getAttributionSource(), uri,
                        SELECT_VALUE_PROJECTION, queryArgs, null);
            }
            if (c == null) {
                Log.w(TAG, "Can't get key " + name + " from " + uri);
                return null;
            }
            String value = c.moveToNext() ? c.getString(0) : null;
            if (LOCAL_LOGV) {
                Log.v(TAG, "cache miss [" + uri.getLastPathSegment() + "]: "
                        + name + " = " + (value == null ? "(null)" : value));
            }
            return value;
        } finally {
            if (c != null) c.close();
        }
    }

    static class ListQuery {
        @NonNull ContentResolver mContentResolver;
        @NonNull final String mPrefix;
        ListQuery(@NonNull ContentResolver contentResolver, String prefix) {
            mContentResolver = contentResolver;
            mPrefix = prefix;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ListQuery)) return false;
            ListQuery listQuery = (ListQuery) o;
            return mContentResolver.equals(listQuery.mContentResolver) && mPrefix.equals(
                    listQuery.mPrefix);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mContentResolver, mPrefix);
        }
    }

    private static class ListQueryHandler extends
            IpcDataCache.QueryHandler<ListQuery, HashMap<String, String>> {
        @NonNull final ContentProviderHolder mContentProviderHolder;
        @NonNull final String mCallListCommand;

        ListQueryHandler(@NonNull ContentProviderHolder contentProviderHolder,
                @NonNull String callListCommand) {
            mContentProviderHolder = contentProviderHolder;
            mCallListCommand = callListCommand;
        }

        @Nullable
        @Override
        public HashMap<String, String> apply(@NonNull ListQuery query) {
            try {
                return getListFromContentProviderCall(query);
            } catch (RemoteException e) {
                // Throw to prevent caching
                e.rethrowAsRuntimeException();
            }
            return null;
        }

        @Nullable
        private HashMap<String, String> getListFromContentProviderCall(ListQuery query)
                throws RemoteException {
            final ContentResolver cr = query.mContentResolver;
            final IContentProvider cp = mContentProviderHolder.getProvider(cr);
            final String prefix = query.mPrefix;
            final String namespace = prefix.substring(0, prefix.length() - 1);
            HashMap<String, String> keyValues = new HashMap<>();

            Bundle args = new Bundle();
            args.putString(Settings.CALL_METHOD_PREFIX_KEY, prefix);

            Bundle b;
            // b/252663068: if we're in system server and the caller did not call
            // clearCallingIdentity, the read would fail due to mismatched AttributionSources.
            // TODO(b/256013480): remove this bypass after fixing the callers in system server.
            if (namespace.equals(DeviceConfig.NAMESPACE_DEVICE_POLICY_MANAGER)
                    && Settings.isInSystemServer()
                    && Binder.getCallingUid() != Process.myUid()) {
                final long token = Binder.clearCallingIdentity();
                try {
                    // Fetch all flags for the namespace at once for caching purposes
                    b = cp.call(cr.getAttributionSource(),
                            mContentProviderHolder.mUri.getAuthority(), mCallListCommand, null,
                            args);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            } else {
                // Fetch all flags for the namespace at once for caching purposes
                b = cp.call(cr.getAttributionSource(),
                        mContentProviderHolder.mUri.getAuthority(), mCallListCommand, null, args);
            }
            if (b == null) {
                // Invalid response, return an empty map
                return keyValues;
            }

            // Cache all flags for the namespace
            HashMap<String, String> flagsToValues =
                    (HashMap) b.getSerializable(Settings.NameValueTable.VALUE,
                            java.util.HashMap.class);
            return flagsToValues;
        }
    }

    @NonNull
    static IpcDataCache<ListQuery, HashMap<String, String>> createListCache(
            @NonNull ContentProviderHolder providerHolder,
            @NonNull String callListCommand, String type) {
        if (DEBUG) {
            Log.i(TAG, "Creating cache for settings type:" + type);
        }
        IpcDataCache.Config config = new IpcDataCache.Config(
                NUM_MAX_ENTRIES, IpcDataCache.MODULE_SYSTEM, type /* apiName */);
        return new IpcDataCache<>(config.child("get"),
                new ListQueryHandler(providerHolder, callListCommand));
    }

    static void invalidateCache(String type) {
        if (DEBUG) {
            Log.i(TAG, "Cache invalidated for type:" + type);
        }
        IpcDataCache.invalidateCache(IpcDataCache.MODULE_SYSTEM, type);
    }
}
