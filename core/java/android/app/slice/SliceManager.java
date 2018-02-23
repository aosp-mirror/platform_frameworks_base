/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.app.slice;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Class to handle interactions with {@link Slice}s.
 * <p>
 * The SliceManager manages permissions and pinned state for slices.
 */
@SystemService(Context.SLICE_SERVICE)
public class SliceManager {

    private static final String TAG = "SliceManager";

    /**
     * @hide
     */
    public static final String ACTION_REQUEST_SLICE_PERMISSION =
            "android.intent.action.REQUEST_SLICE_PERMISSION";

    private final ISliceManager mService;
    private final Context mContext;
    private final ArrayMap<Pair<Uri, SliceCallback>, ISliceListener> mListenerLookup =
            new ArrayMap<>();
    private final IBinder mToken = new Binder();

    /**
     * Permission denied.
     * @hide
     */
    public static final int PERMISSION_DENIED = -1;
    /**
     * Permission granted.
     * @hide
     */
    public static final int PERMISSION_GRANTED = 0;
    /**
     * Permission just granted by the user, and should be granted uri permission as well.
     * @hide
     */
    public static final int PERMISSION_USER_GRANTED = 1;

    /**
     * @hide
     */
    public SliceManager(Context context, Handler handler) throws ServiceNotFoundException {
        mContext = context;
        mService = ISliceManager.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.SLICE_SERVICE));
    }

    /**
     * @deprecated TO BE REMOVED.
     */
    @Deprecated
    public void registerSliceCallback(@NonNull Uri uri, @NonNull SliceCallback callback,
            @NonNull List<SliceSpec> specs) {
    }

    /**
     * @deprecated TO BE REMOVED.
     */
    @Deprecated
    public void registerSliceCallback(@NonNull Uri uri, @NonNull SliceCallback callback,
            @NonNull List<SliceSpec> specs, Executor executor) {
    }

    /**
     * Adds a callback to a specific slice uri.
     * <p>
     * This is a convenience that performs a few slice actions at once. It will put
     * the slice in a pinned state since there is a callback attached. It will also
     * listen for content changes, when a content change observes, the android system
     * will bind the new slice and provide it to all registered {@link SliceCallback}s.
     *
     * @param uri The uri of the slice being listened to.
     * @param callback The listener that should receive the callbacks.
     * @param specs The list of supported {@link SliceSpec}s of the callback.
     * @see SliceProvider#onSlicePinned(Uri)
     */
    public void registerSliceCallback(@NonNull Uri uri, @NonNull List<SliceSpec> specs,
            @NonNull SliceCallback callback) {
    }

    /**
     * Adds a callback to a specific slice uri.
     * <p>
     * This is a convenience that performs a few slice actions at once. It will put
     * the slice in a pinned state since there is a callback attached. It will also
     * listen for content changes, when a content change observes, the android system
     * will bind the new slice and provide it to all registered {@link SliceCallback}s.
     *
     * @param uri The uri of the slice being listened to.
     * @param callback The listener that should receive the callbacks.
     * @param specs The list of supported {@link SliceSpec}s of the callback.
     * @see SliceProvider#onSlicePinned(Uri)
     */
    public void registerSliceCallback(@NonNull Uri uri, @NonNull List<SliceSpec> specs,
            @NonNull @CallbackExecutor Executor executor, @NonNull SliceCallback callback) {

    }

    /**
     * Removes a callback for a specific slice uri.
     * <p>
     * Removes the app from the pinned state (if there are no other apps/callbacks pinning it)
     * in addition to removing the callback.
     *
     * @param uri The uri of the slice being listened to
     * @param callback The listener that should no longer receive callbacks.
     * @see #registerSliceCallback
     */
    public void unregisterSliceCallback(@NonNull Uri uri, @NonNull SliceCallback callback) {

    }

    /**
     * Ensures that a slice is in a pinned state.
     * <p>
     * Pinned state is not persisted across reboots, so apps are expected to re-pin any slices
     * they still care about after a reboot.
     * <p>
     * This may only be called by apps that are the default launcher for the device
     * or the default voice interaction service. Otherwise will throw {@link SecurityException}.
     *
     * @param uri The uri of the slice being pinned.
     * @param specs The list of supported {@link SliceSpec}s of the callback.
     * @see SliceProvider#onSlicePinned(Uri)
     * @see Intent#ACTION_ASSIST
     * @see Intent#CATEGORY_HOME
     */
    public void pinSlice(@NonNull Uri uri, @NonNull List<SliceSpec> specs) {
        try {
            mService.pinSlice(mContext.getPackageName(), uri,
                    specs.toArray(new SliceSpec[specs.size()]), mToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove a pin for a slice.
     * <p>
     * If the slice has no other pins/callbacks then the slice will be unpinned.
     * <p>
     * This may only be called by apps that are the default launcher for the device
     * or the default voice interaction service. Otherwise will throw {@link SecurityException}.
     *
     * @param uri The uri of the slice being unpinned.
     * @see #pinSlice
     * @see SliceProvider#onSliceUnpinned(Uri)
     * @see Intent#ACTION_ASSIST
     * @see Intent#CATEGORY_HOME
     */
    public void unpinSlice(@NonNull Uri uri) {
        try {
            mService.unpinSlice(mContext.getPackageName(), uri, mToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    public boolean hasSliceAccess() {
        try {
            return mService.hasSliceAccess(mContext.getPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the current set of specs for a pinned slice.
     * <p>
     * This is the set of specs supported for a specific pinned slice. It will take
     * into account all clients and returns only specs supported by all.
     * @see SliceSpec
     */
    public @NonNull List<SliceSpec> getPinnedSpecs(Uri uri) {
        try {
            return Arrays.asList(mService.getPinnedSpecs(uri, mContext.getPackageName()));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Obtains a list of slices that are descendants of the specified Uri.
     * <p>
     * Not all slice providers will implement this functionality, in which case,
     * an empty collection will be returned.
     *
     * @param uri The uri to look for descendants under.
     * @return All slices within the space.
     * @see SliceProvider#onGetSliceDescendants(Uri)
     */
    public @NonNull Collection<Uri> getSliceDescendants(@NonNull Uri uri) {
        ContentResolver resolver = mContext.getContentResolver();
        try (ContentProviderClient provider = resolver.acquireContentProviderClient(uri)) {
            Bundle extras = new Bundle();
            extras.putParcelable(SliceProvider.EXTRA_BIND_URI, uri);
            final Bundle res = provider.call(SliceProvider.METHOD_GET_DESCENDANTS, null, extras);
            return res.getParcelableArrayList(SliceProvider.EXTRA_SLICE_DESCENDANTS);
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to get slice descendants", e);
        }
        return Collections.emptyList();
    }

    /**
     * Turns a slice Uri into slice content.
     *
     * @param uri The URI to a slice provider
     * @param supportedSpecs List of supported specs.
     * @return The Slice provided by the app or null if none is given.
     * @see Slice
     */
    public @Nullable Slice bindSlice(@NonNull Uri uri, @NonNull List<SliceSpec> supportedSpecs) {
        Preconditions.checkNotNull(uri, "uri");
        ContentResolver resolver = mContext.getContentResolver();
        try (ContentProviderClient provider = resolver.acquireContentProviderClient(uri)) {
            if (provider == null) {
                throw new IllegalArgumentException("Unknown URI " + uri);
            }
            Bundle extras = new Bundle();
            extras.putParcelable(SliceProvider.EXTRA_BIND_URI, uri);
            extras.putParcelableArrayList(SliceProvider.EXTRA_SUPPORTED_SPECS,
                    new ArrayList<>(supportedSpecs));
            final Bundle res = provider.call(SliceProvider.METHOD_SLICE, null, extras);
            Bundle.setDefusable(res, true);
            if (res == null) {
                return null;
            }
            return res.getParcelable(SliceProvider.EXTRA_SLICE);
        } catch (RemoteException e) {
            // Arbitrary and not worth documenting, as Activity
            // Manager will kill this process shortly anyway.
            return null;
        }
    }

    /**
     * Turns a slice intent into a slice uri. Expects an explicit intent. If there is no
     * {@link android.content.ContentProvider} associated with the given intent this will throw
     * {@link IllegalArgumentException}.
     *
     * @param intent The intent associated with a slice.
     * @return The Slice Uri provided by the app or null if none is given.
     * @see Slice
     * @see SliceProvider#onMapIntentToUri(Intent)
     * @see Intent
     */
    public @Nullable Uri mapIntentToUri(@NonNull Intent intent) {
        Preconditions.checkNotNull(intent, "intent");
        Preconditions.checkArgument(intent.getComponent() != null || intent.getPackage() != null,
                "Slice intent must be explicit %s", intent);
        ContentResolver resolver = mContext.getContentResolver();

        // Check if the intent has data for the slice uri on it and use that
        final Uri intentData = intent.getData();
        if (intentData != null && SliceProvider.SLICE_TYPE.equals(resolver.getType(intentData))) {
            return intentData;
        }
        // Otherwise ask the app
        List<ResolveInfo> providers =
                mContext.getPackageManager().queryIntentContentProviders(intent, 0);
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("Unable to resolve intent " + intent);
        }
        String authority = providers.get(0).providerInfo.authority;
        Uri uri = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority).build();
        try (ContentProviderClient provider = resolver.acquireContentProviderClient(uri)) {
            if (provider == null) {
                throw new IllegalArgumentException("Unknown URI " + uri);
            }
            Bundle extras = new Bundle();
            extras.putParcelable(SliceProvider.EXTRA_INTENT, intent);
            final Bundle res = provider.call(SliceProvider.METHOD_MAP_ONLY_INTENT, null, extras);
            if (res == null) {
                return null;
            }
            return res.getParcelable(SliceProvider.EXTRA_SLICE);
        } catch (RemoteException e) {
            // Arbitrary and not worth documenting, as Activity
            // Manager will kill this process shortly anyway.
            return null;
        }
    }

    /**
     * Turns a slice intent into slice content. Expects an explicit intent. If there is no
     * {@link android.content.ContentProvider} associated with the given intent this will throw
     * {@link IllegalArgumentException}.
     *
     * @param intent The intent associated with a slice.
     * @param supportedSpecs List of supported specs.
     * @return The Slice provided by the app or null if none is given.
     * @see Slice
     * @see SliceProvider#onMapIntentToUri(Intent)
     * @see Intent
     */
    public @Nullable Slice bindSlice(@NonNull Intent intent,
            @NonNull List<SliceSpec> supportedSpecs) {
        Preconditions.checkNotNull(intent, "intent");
        Preconditions.checkArgument(intent.getComponent() != null || intent.getPackage() != null,
                "Slice intent must be explicit %s", intent);
        ContentResolver resolver = mContext.getContentResolver();

        // Check if the intent has data for the slice uri on it and use that
        final Uri intentData = intent.getData();
        if (intentData != null && SliceProvider.SLICE_TYPE.equals(resolver.getType(intentData))) {
            return bindSlice(intentData, supportedSpecs);
        }
        // Otherwise ask the app
        List<ResolveInfo> providers =
                mContext.getPackageManager().queryIntentContentProviders(intent, 0);
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("Unable to resolve intent " + intent);
        }
        String authority = providers.get(0).providerInfo.authority;
        Uri uri = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority).build();
        try (ContentProviderClient provider = resolver.acquireContentProviderClient(uri)) {
            if (provider == null) {
                throw new IllegalArgumentException("Unknown URI " + uri);
            }
            Bundle extras = new Bundle();
            extras.putParcelable(SliceProvider.EXTRA_INTENT, intent);
            extras.putParcelableArrayList(SliceProvider.EXTRA_SUPPORTED_SPECS,
                    new ArrayList<>(supportedSpecs));
            final Bundle res = provider.call(SliceProvider.METHOD_MAP_INTENT, null, extras);
            if (res == null) {
                return null;
            }
            return res.getParcelable(SliceProvider.EXTRA_SLICE);
        } catch (RemoteException e) {
            // Arbitrary and not worth documenting, as Activity
            // Manager will kill this process shortly anyway.
            return null;
        }
    }

    /**
     * Does the permission check to see if a caller has access to a specific slice.
     * @hide
     */
    public void enforceSlicePermission(Uri uri, String pkg, int pid, int uid) {
        try {
            if (pkg == null) {
                throw new SecurityException("No pkg specified");
            }
            int result = mService.checkSlicePermission(uri, pkg, pid, uid);
            if (result == PERMISSION_DENIED) {
                throw new SecurityException("User " + uid + " does not have slice permission for "
                        + uri + ".");
            }
            if (result == PERMISSION_USER_GRANTED) {
                // We just had a user grant of this permission and need to grant this to the app
                // permanently.
                mContext.grantUriPermission(pkg, uri.buildUpon().path("").build(),
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Called by SystemUI to grant a slice permission after a dialog is shown.
     * @hide
     */
    public void grantPermissionFromUser(Uri uri, String pkg, boolean allSlices) {
        try {
            mService.grantPermissionFromUser(uri, pkg, mContext.getPackageName(), allSlices);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Class that listens to changes in {@link Slice}s.
     */
    public interface SliceCallback {

        /**
         * Called when slice is updated.
         *
         * @param s The updated slice.
         * @see #registerSliceCallback
         */
        void onSliceUpdated(Slice s);
    }
}
