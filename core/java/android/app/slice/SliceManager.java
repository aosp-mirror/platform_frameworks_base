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

import static android.content.pm.PackageManager.PERMISSION_DENIED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemService;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.PermissionResult;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

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

    /**
     * Category used to resolve intents that can be rendered as slices.
     * <p>
     * This category should be included on intent filters on providers that extend
     * {@link SliceProvider}.
     * @see SliceProvider
     * @see SliceProvider#onMapIntentToUri(Intent)
     * @see #mapIntentToUri(Intent)
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String CATEGORY_SLICE = "android.app.slice.category.SLICE";

    /**
     * The meta-data key that allows an activity to easily be linked directly to a slice.
     * <p>
     * An activity can be statically linked to a slice uri by including a meta-data item
     * for this key that contains a valid slice uri for the same application declaring
     * the activity.
     *
     * <pre class="prettyprint">
     * {@literal
     * <activity android:name="com.example.mypkg.MyActivity">
     *     <meta-data android:name="android.metadata.SLICE_URI"
     *                android:value="content://com.example.mypkg/main_slice" />
     *  </activity>}
     * </pre>
     *
     * @see #mapIntentToUri(Intent)
     * @see SliceProvider#onMapIntentToUri(Intent)
     */
    public static final String SLICE_METADATA_KEY = "android.metadata.SLICE_URI";

    private final ISliceManager mService;
    private final Context mContext;
    private final IBinder mToken = new Binder();

    /**
     * @hide
     */
    public SliceManager(Context context, Handler handler) throws ServiceNotFoundException {
        mContext = context;
        mService = ISliceManager.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.SLICE_SERVICE));
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
    public void pinSlice(@NonNull Uri uri, @NonNull Set<SliceSpec> specs) {
        try {
            mService.pinSlice(mContext.getPackageName(), uri,
                    specs.toArray(new SliceSpec[specs.size()]), mToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @deprecated TO BE REMOVED
     */
    @Deprecated
    public void pinSlice(@NonNull Uri uri, @NonNull List<SliceSpec> specs) {
        pinSlice(uri, new ArraySet<>(specs));
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
    public @NonNull Set<SliceSpec> getPinnedSpecs(Uri uri) {
        try {
            return new ArraySet<>(Arrays.asList(mService.getPinnedSpecs(uri,
                    mContext.getPackageName())));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the list of currently pinned slices for this app.
     * @see SliceProvider#onSlicePinned
     */
    public @NonNull List<Uri> getPinnedSlices() {
        try {
            return Arrays.asList(mService.getPinnedSlices(mContext.getPackageName()));
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
    public @Nullable Slice bindSlice(@NonNull Uri uri, @NonNull Set<SliceSpec> supportedSpecs) {
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
     * @deprecated TO BE REMOVED
     */
    @Deprecated
    public @Nullable Slice bindSlice(@NonNull Uri uri, @NonNull List<SliceSpec> supportedSpecs) {
        return bindSlice(uri, new ArraySet<>(supportedSpecs));
    }

    /**
     * Turns a slice intent into a slice uri. Expects an explicit intent.
     * <p>
     * This goes through a several stage resolution process to determine if any slice
     * can represent this intent.
     * <ol>
     *  <li> If the intent contains data that {@link ContentResolver#getType} is
     *  {@link SliceProvider#SLICE_TYPE} then the data will be returned.</li>
     *  <li>If the intent with {@link #CATEGORY_SLICE} added resolves to a provider, then
     *  the provider will be asked to {@link SliceProvider#onMapIntentToUri} and that result
     *  will be returned.</li>
     *  <li>Lastly, if the intent explicitly points at an activity, and that activity has
     *  meta-data for key {@link #SLICE_METADATA_KEY}, then the Uri specified there will be
     *  returned.</li>
     *  <li>If no slice is found, then {@code null} is returned.</li>
     * </ol>
     * @param intent The intent associated with a slice.
     * @return The Slice Uri provided by the app or null if none exists.
     * @see Slice
     * @see SliceProvider#onMapIntentToUri(Intent)
     * @see Intent
     */
    public @Nullable Uri mapIntentToUri(@NonNull Intent intent) {
        Preconditions.checkNotNull(intent, "intent");
        Preconditions.checkArgument(intent.getComponent() != null || intent.getPackage() != null
                || intent.getData() != null,
                "Slice intent must be explicit %s", intent);
        ContentResolver resolver = mContext.getContentResolver();

        // Check if the intent has data for the slice uri on it and use that
        final Uri intentData = intent.getData();
        if (intentData != null && SliceProvider.SLICE_TYPE.equals(resolver.getType(intentData))) {
            return intentData;
        }
        // Otherwise ask the app
        Intent queryIntent = new Intent(intent);
        if (!queryIntent.hasCategory(CATEGORY_SLICE)) {
            queryIntent.addCategory(CATEGORY_SLICE);
        }
        List<ResolveInfo> providers =
                mContext.getPackageManager().queryIntentContentProviders(queryIntent, 0);
        if (providers == null || providers.isEmpty()) {
            // There are no providers, see if this activity has a direct link.
            ResolveInfo resolve = mContext.getPackageManager().resolveActivity(intent,
                    PackageManager.GET_META_DATA);
            if (resolve != null && resolve.activityInfo != null
                    && resolve.activityInfo.metaData != null
                    && resolve.activityInfo.metaData.containsKey(SLICE_METADATA_KEY)) {
                return Uri.parse(
                        resolve.activityInfo.metaData.getString(SLICE_METADATA_KEY));
            }
            return null;
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
            @NonNull Set<SliceSpec> supportedSpecs) {
        Preconditions.checkNotNull(intent, "intent");
        Preconditions.checkArgument(intent.getComponent() != null || intent.getPackage() != null
                || intent.getData() != null,
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
            // There are no providers, see if this activity has a direct link.
            ResolveInfo resolve = mContext.getPackageManager().resolveActivity(intent,
                    PackageManager.GET_META_DATA);
            if (resolve != null && resolve.activityInfo != null
                    && resolve.activityInfo.metaData != null
                    && resolve.activityInfo.metaData.containsKey(SLICE_METADATA_KEY)) {
                return bindSlice(Uri.parse(resolve.activityInfo.metaData
                        .getString(SLICE_METADATA_KEY)), supportedSpecs);
            }
            return null;
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
     * Determine whether a particular process and user ID has been granted
     * permission to access a specific slice URI.
     *
     * @param uri The uri that is being checked.
     * @param pid The process ID being checked against.  Must be &gt; 0.
     * @param uid The user ID being checked against.  A uid of 0 is the root
     * user, which will pass every permission check.
     *
     * @return {@link PackageManager#PERMISSION_GRANTED} if the given
     * pid/uid is allowed to access that uri, or
     * {@link PackageManager#PERMISSION_DENIED} if it is not.
     *
     * @see #grantSlicePermission(String, Uri)
     */
    public @PermissionResult int checkSlicePermission(@NonNull Uri uri, int pid, int uid) {
        try {
            return mService.checkSlicePermission(uri, null, pid, uid, null);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Grant permission to access a specific slice Uri to another package.
     *
     * @param toPackage The package you would like to allow to access the Uri.
     * @param uri The Uri you would like to grant access to.
     *
     * @see #revokeSlicePermission
     */
    public void grantSlicePermission(@NonNull String toPackage, @NonNull Uri uri) {
        try {
            mService.grantSlicePermission(mContext.getPackageName(), toPackage, uri);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove permissions to access a particular content provider Uri
     * that were previously added with {@link #grantSlicePermission} for a specific target
     * package.  The given Uri will match all previously granted Uris that are the same or a
     * sub-path of the given Uri.  That is, revoking "content://foo/target" will
     * revoke both "content://foo/target" and "content://foo/target/sub", but not
     * "content://foo".  It will not remove any prefix grants that exist at a
     * higher level.
     *
     * @param toPackage The package you would like to allow to access the Uri.
     * @param uri The Uri you would like to revoke access to.
     *
     * @see #grantSlicePermission
     */
    public void revokeSlicePermission(@NonNull String toPackage, @NonNull Uri uri) {
        try {
            mService.revokeSlicePermission(mContext.getPackageName(), toPackage, uri);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Does the permission check to see if a caller has access to a specific slice.
     * @hide
     */
    public void enforceSlicePermission(Uri uri, String pkg, int pid, int uid,
            String[] autoGrantPermissions) {
        try {
            if (UserHandle.isSameApp(uid, Process.myUid())) {
                return;
            }
            if (pkg == null) {
                throw new SecurityException("No pkg specified");
            }
            int result = mService.checkSlicePermission(uri, pkg, pid, uid, autoGrantPermissions);
            if (result == PERMISSION_DENIED) {
                throw new SecurityException("User " + uid + " does not have slice permission for "
                        + uri + ".");
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
}
