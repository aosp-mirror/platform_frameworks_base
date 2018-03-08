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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ProviderInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.StrictMode;
import android.os.StrictMode.ThreadPolicy;
import android.os.UserHandle;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * A SliceProvider allows an app to provide content to be displayed in system spaces. This content
 * is templated and can contain actions, and the behavior of how it is surfaced is specific to the
 * system surface.
 * <p>
 * Slices are not currently live content. They are bound once and shown to the user. If the content
 * changes due to a callback from user interaction, then
 * {@link ContentResolver#notifyChange(Uri, ContentObserver)} should be used to notify the system.
 * </p>
 * <p>
 * The provider needs to be declared in the manifest to provide the authority for the app. The
 * authority for most slices is expected to match the package of the application.
 * </p>
 *
 * <pre class="prettyprint">
 * {@literal
 * <provider
 *     android:name="com.example.mypkg.MySliceProvider"
 *     android:authorities="com.example.mypkg" />}
 * </pre>
 * <p>
 * Slices can be identified by a Uri or by an Intent. To link an Intent with a slice, the provider
 * must have an {@link IntentFilter} matching the slice intent. When a slice is being requested via
 * an intent, {@link #onMapIntentToUri(Intent)} can be called and is expected to return an
 * appropriate Uri representing the slice.
 *
 * <pre class="prettyprint">
 * {@literal
 * <provider
 *     android:name="com.example.mypkg.MySliceProvider"
 *     android:authorities="com.example.mypkg">
 *     <intent-filter>
 *         <action android:name="com.example.mypkg.intent.action.MY_SLICE_INTENT" />
 *     </intent-filter>
 * </provider>}
 * </pre>
 *
 * @see Slice
 */
public abstract class SliceProvider extends ContentProvider {
    /**
     * This is the Android platform's MIME type for a URI
     * containing a slice implemented through {@link SliceProvider}.
     */
    public static final String SLICE_TYPE = "vnd.android.slice";

    private static final String TAG = "SliceProvider";
    /**
     * @hide
     */
    public static final String EXTRA_BIND_URI = "slice_uri";
    /**
     * @hide
     */
    public static final String EXTRA_SUPPORTED_SPECS = "supported_specs";
    /**
     * @hide
     */
    public static final String METHOD_SLICE = "bind_slice";
    /**
     * @hide
     */
    public static final String METHOD_MAP_INTENT = "map_slice";
    /**
     * @hide
     */
    public static final String METHOD_MAP_ONLY_INTENT = "map_only";
    /**
     * @hide
     */
    public static final String METHOD_PIN = "pin";
    /**
     * @hide
     */
    public static final String METHOD_UNPIN = "unpin";
    /**
     * @hide
     */
    public static final String METHOD_GET_DESCENDANTS = "get_descendants";
    /**
     * @hide
     */
    public static final String EXTRA_INTENT = "slice_intent";
    /**
     * @hide
     */
    public static final String EXTRA_SLICE = "slice";
    /**
     * @hide
     */
    public static final String EXTRA_SLICE_DESCENDANTS = "slice_descendants";
    /**
     * @hide
     */
    public static final String EXTRA_PKG = "pkg";
    /**
     * @hide
     */
    public static final String EXTRA_PROVIDER_PKG = "provider_pkg";
    /**
     * @hide
     */
    public static final String EXTRA_OVERRIDE_PKG = "override_pkg";
    /**
     * @hide
     */
    public static final String EXTRA_OVERRIDE_UID = "override_uid";
    /**
     * @hide
     */
    public static final String EXTRA_OVERRIDE_PID = "override_pid";

    private static final boolean DEBUG = false;

    private String mBindingPkg;
    private SliceManager mSliceManager;

    /**
     * Return the package name of the caller that initiated the binding request
     * currently happening. The returned package will have been
     * verified to belong to the calling UID. Returns {@code null} if not
     * currently performing an {@link #onBindSlice(Uri, List)}.
     */
    public final @Nullable String getBindingPackage() {
        return mBindingPkg;
    }

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);
        mSliceManager = context.getSystemService(SliceManager.class);
    }

    /**
     * Implemented to create a slice. Will be called on the main thread.
     * <p>
     * onBindSlice should return as quickly as possible so that the UI tied
     * to this slice can be responsive. No network or other IO will be allowed
     * during onBindSlice. Any loading that needs to be done should happen
     * off the main thread with a call to {@link ContentResolver#notifyChange(Uri, ContentObserver)}
     * when the app is ready to provide the complete data in onBindSlice.
     * <p>
     * The slice returned should have a spec that is compatible with one of
     * the supported specs.
     *
     * @param sliceUri Uri to bind.
     * @param supportedSpecs List of supported specs.
     * @see {@link Slice}.
     * @see {@link Slice#HINT_PARTIAL}
     */
    public Slice onBindSlice(Uri sliceUri, List<SliceSpec> supportedSpecs) {
        return null;
    }

    /**
     * Called to inform an app that a slice has been pinned.
     * <p>
     * Pinning is a way that slice hosts use to notify apps of which slices
     * they care about updates for. When a slice is pinned the content is
     * expected to be relatively fresh and kept up to date.
     * <p>
     * Being pinned does not provide any escalated privileges for the slice
     * provider. So apps should do things such as turn on syncing or schedule
     * a job in response to a onSlicePinned.
     * <p>
     * Pinned state is not persisted through a reboot, and apps can expect a
     * new call to onSlicePinned for any slices that should remain pinned
     * after a reboot occurs.
     *
     * @param sliceUri The uri of the slice being unpinned.
     * @see #onSliceUnpinned(Uri)
     */
    public void onSlicePinned(Uri sliceUri) {
    }

    /**
     * Called to inform an app that a slices is no longer pinned.
     * <p>
     * This means that no other apps on the device care about updates to this
     * slice anymore and therefore it is not important to be updated. Any syncs
     * or jobs related to this slice should be cancelled.
     * @see #onSlicePinned(Uri)
     */
    public void onSliceUnpinned(Uri sliceUri) {
    }

    /**
     * Obtains a list of slices that are descendants of the specified Uri.
     * <p>
     * Implementing this is optional for a SliceProvider, but does provide a good
     * discovery mechanism for finding slice Uris.
     *
     * @param uri The uri to look for descendants under.
     * @return All slices within the space.
     * @see SliceManager#getSliceDescendants(Uri)
     */
    public @NonNull Collection<Uri> onGetSliceDescendants(@NonNull Uri uri) {
        return Collections.emptyList();
    }

    /**
     * This method must be overridden if an {@link IntentFilter} is specified on the SliceProvider.
     * In that case, this method can be called and is expected to return a non-null Uri representing
     * a slice. Otherwise this will throw {@link UnsupportedOperationException}.
     *
     * @return Uri representing the slice associated with the provided intent.
     * @see {@link Slice}
     */
    public @NonNull Uri onMapIntentToUri(Intent intent) {
        throw new UnsupportedOperationException(
                "This provider has not implemented intent to uri mapping");
    }

    @Override
    public final int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        if (DEBUG) Log.d(TAG, "update " + uri);
        return 0;
    }

    @Override
    public final int delete(Uri uri, String selection, String[] selectionArgs) {
        if (DEBUG) Log.d(TAG, "delete " + uri);
        return 0;
    }

    @Override
    public final Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        if (DEBUG) Log.d(TAG, "query " + uri);
        return null;
    }

    @Override
    public final Cursor query(Uri uri, String[] projection, String selection, String[]
            selectionArgs, String sortOrder, CancellationSignal cancellationSignal) {
        if (DEBUG) Log.d(TAG, "query " + uri);
        return null;
    }

    @Override
    public final Cursor query(Uri uri, String[] projection, Bundle queryArgs,
            CancellationSignal cancellationSignal) {
        if (DEBUG) Log.d(TAG, "query " + uri);
        return null;
    }

    @Override
    public final Uri insert(Uri uri, ContentValues values) {
        if (DEBUG) Log.d(TAG, "insert " + uri);
        return null;
    }

    @Override
    public final String getType(Uri uri) {
        if (DEBUG) Log.d(TAG, "getType " + uri);
        return SLICE_TYPE;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (method.equals(METHOD_SLICE)) {
            Uri uri = getUriWithoutUserId(extras.getParcelable(EXTRA_BIND_URI));
            List<SliceSpec> supportedSpecs = extras.getParcelableArrayList(EXTRA_SUPPORTED_SPECS);

            String callingPackage = getCallingPackage();
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            if (extras.containsKey(EXTRA_OVERRIDE_PKG)) {
                if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                    throw new SecurityException("Only the system can override calling pkg");
                }
                // This is safe because we would grant SYSTEM_UID access to all slices
                // and want to allow it to bind slices as if it were a less privileged app
                // to check their permission levels.
                callingPackage = extras.getString(EXTRA_OVERRIDE_PKG);
                callingUid = extras.getInt(EXTRA_OVERRIDE_UID);
                callingPid = extras.getInt(EXTRA_OVERRIDE_PID);
            }
            Slice s = handleBindSlice(uri, supportedSpecs, callingPackage, callingUid, callingPid);
            Bundle b = new Bundle();
            b.putParcelable(EXTRA_SLICE, s);
            return b;
        } else if (method.equals(METHOD_MAP_INTENT)) {
            Intent intent = extras.getParcelable(EXTRA_INTENT);
            if (intent == null) return null;
            Uri uri = onMapIntentToUri(intent);
            List<SliceSpec> supportedSpecs = extras.getParcelableArrayList(EXTRA_SUPPORTED_SPECS);
            Bundle b = new Bundle();
            if (uri != null) {
                Slice s = handleBindSlice(uri, supportedSpecs, getCallingPackage(),
                        Binder.getCallingUid(), Binder.getCallingPid());
                b.putParcelable(EXTRA_SLICE, s);
            } else {
                b.putParcelable(EXTRA_SLICE, null);
            }
            return b;
        } else if (method.equals(METHOD_MAP_ONLY_INTENT)) {
            Intent intent = extras.getParcelable(EXTRA_INTENT);
            if (intent == null) return null;
            Uri uri = onMapIntentToUri(intent);
            Bundle b = new Bundle();
            b.putParcelable(EXTRA_SLICE, uri);
            return b;
        } else if (method.equals(METHOD_PIN)) {
            Uri uri = getUriWithoutUserId(extras.getParcelable(EXTRA_BIND_URI));
            if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                throw new SecurityException("Only the system can pin/unpin slices");
            }
            handlePinSlice(uri);
        } else if (method.equals(METHOD_UNPIN)) {
            Uri uri = getUriWithoutUserId(extras.getParcelable(EXTRA_BIND_URI));
            if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                throw new SecurityException("Only the system can pin/unpin slices");
            }
            handleUnpinSlice(uri);
        } else if (method.equals(METHOD_GET_DESCENDANTS)) {
            Uri uri = getUriWithoutUserId(extras.getParcelable(EXTRA_BIND_URI));
            Bundle b = new Bundle();
            b.putParcelableArrayList(EXTRA_SLICE_DESCENDANTS,
                    new ArrayList<>(handleGetDescendants(uri)));
            return b;
        }
        return super.call(method, arg, extras);
    }

    private Collection<Uri> handleGetDescendants(Uri uri) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return onGetSliceDescendants(uri);
        } else {
            CountDownLatch latch = new CountDownLatch(1);
            Collection<Uri>[] output = new Collection[1];
            Handler.getMain().post(() -> {
                output[0] = onGetSliceDescendants(uri);
                latch.countDown();
            });
            try {
                latch.await();
                return output[0];
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void handlePinSlice(Uri sliceUri) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onSlicePinned(sliceUri);
        } else {
            CountDownLatch latch = new CountDownLatch(1);
            Handler.getMain().post(() -> {
                onSlicePinned(sliceUri);
                latch.countDown();
            });
            try {
                latch.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void handleUnpinSlice(Uri sliceUri) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onSliceUnpinned(sliceUri);
        } else {
            CountDownLatch latch = new CountDownLatch(1);
            Handler.getMain().post(() -> {
                onSliceUnpinned(sliceUri);
                latch.countDown();
            });
            try {
                latch.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private Slice handleBindSlice(Uri sliceUri, List<SliceSpec> supportedSpecs,
            String callingPkg, int callingUid, int callingPid) {
        // This can be removed once Slice#bindSlice is removed and everyone is using
        // SliceManager#bindSlice.
        String pkg = callingPkg != null ? callingPkg
                : getContext().getPackageManager().getNameForUid(callingUid);
        if (!UserHandle.isSameApp(callingUid, Process.myUid())) {
            try {
                mSliceManager.enforceSlicePermission(sliceUri, pkg,
                        callingPid, callingUid);
            } catch (SecurityException e) {
                return createPermissionSlice(getContext(), sliceUri, pkg);
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return onBindSliceStrict(sliceUri, supportedSpecs, pkg);
        } else {
            CountDownLatch latch = new CountDownLatch(1);
            Slice[] output = new Slice[1];
            Handler.getMain().post(() -> {
                output[0] = onBindSliceStrict(sliceUri, supportedSpecs, pkg);
                latch.countDown();
            });
            try {
                latch.await();
                return output[0];
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * @hide
     */
    public static Slice createPermissionSlice(Context context, Uri sliceUri,
            String callingPackage) {
        return new Slice.Builder(sliceUri)
                .addAction(createPermissionIntent(context, sliceUri, callingPackage),
                        new Slice.Builder(sliceUri.buildUpon().appendPath("permission").build())
                                .addText(getPermissionString(context, callingPackage), null)
                                .build())
                .addHints(Slice.HINT_LIST_ITEM)
                .build();
    }

    /**
     * @hide
     */
    public static PendingIntent createPermissionIntent(Context context, Uri sliceUri,
            String callingPackage) {
        Intent intent = new Intent(SliceManager.ACTION_REQUEST_SLICE_PERMISSION);
        intent.setComponent(new ComponentName("com.android.systemui",
                "com.android.systemui.SlicePermissionActivity"));
        intent.putExtra(EXTRA_BIND_URI, sliceUri);
        intent.putExtra(EXTRA_PKG, callingPackage);
        intent.putExtra(EXTRA_PROVIDER_PKG, context.getPackageName());
        // Unique pending intent.
        intent.setData(sliceUri.buildUpon().appendQueryParameter("package", callingPackage)
                .build());

        return PendingIntent.getActivity(context, 0, intent, 0);
    }

    /**
     * @hide
     */
    public static CharSequence getPermissionString(Context context, String callingPackage) {
        PackageManager pm = context.getPackageManager();
        try {
            return context.getString(
                    com.android.internal.R.string.slices_permission_request,
                    pm.getApplicationInfo(callingPackage, 0).loadLabel(pm),
                    context.getApplicationInfo().loadLabel(pm));
        } catch (NameNotFoundException e) {
            // This shouldn't be possible since the caller is verified.
            throw new RuntimeException("Unknown calling app", e);
        }
    }

    private Slice onBindSliceStrict(Uri sliceUri, List<SliceSpec> supportedSpecs,
            String callingPackage) {
        ThreadPolicy oldPolicy = StrictMode.getThreadPolicy();
        try {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyDeath()
                    .build());
            mBindingPkg = callingPackage;
            return onBindSlice(sliceUri, supportedSpecs);
        } finally {
            mBindingPkg = null;
            StrictMode.setThreadPolicy(oldPolicy);
        }
    }
}
