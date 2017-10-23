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

import android.Manifest.permission;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.os.StrictMode.ThreadPolicy;
import android.util.Log;

import java.util.concurrent.CountDownLatch;

/**
 * A SliceProvider allows app to provide content to be displayed in system
 * spaces. This content is templated and can contain actions, and the behavior
 * of how it is surfaced is specific to the system surface.
 *
 * <p>Slices are not currently live content. They are bound once and shown to the
 * user. If the content changes due to a callback from user interaction, then
 * {@link ContentResolver#notifyChange(Uri, ContentObserver)}
 * should be used to notify the system.</p>
 *
 * <p>The provider needs to be declared in the manifest to provide the authority
 * for the app. The authority for most slices is expected to match the package
 * of the application.</p>
 * <pre class="prettyprint">
 * {@literal
 * <provider
 *     android:name="com.android.mypkg.MySliceProvider"
 *     android:authorities="com.android.mypkg" />}
 * </pre>
 *
 * @see Slice
 */
public abstract class SliceProvider extends ContentProvider {

    /**
     * This is the Android platform's MIME type for a slice: URI
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
    public static final String METHOD_SLICE = "bind_slice";
    /**
     * @hide
     */
    public static final String EXTRA_SLICE = "slice";

    private static final boolean DEBUG = false;

    /**
     * Implemented to create a slice. Will be called on the main thread.
     * <p>
     * onBindSlice should return as quickly as possible so that the UI tied
     * to this slice can be responsive. No network or other IO will be allowed
     * during onBindSlice. Any loading that needs to be done should happen
     * off the main thread with a call to {@link ContentResolver#notifyChange(Uri, ContentObserver)}
     * when the app is ready to provide the complete data in onBindSlice.
     * <p>
     *
     * @see {@link Slice}.
     * @see {@link Slice#HINT_PARTIAL}
     */
    // TODO: Provide alternate notifyChange that takes in the slice (i.e. notifyChange(Uri, Slice)).
    public abstract Slice onBindSlice(Uri sliceUri);

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
            getContext().enforceCallingPermission(permission.BIND_SLICE,
                    "Slice binding requires the permission BIND_SLICE");
            Uri uri = extras.getParcelable(EXTRA_BIND_URI);

            Slice s = handleBindSlice(uri);
            Bundle b = new Bundle();
            b.putParcelable(EXTRA_SLICE, s);
            return b;
        }
        return super.call(method, arg, extras);
    }

    private Slice handleBindSlice(Uri sliceUri) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return onBindSliceStrict(sliceUri);
        } else {
            CountDownLatch latch = new CountDownLatch(1);
            Slice[] output = new Slice[1];
            Handler.getMain().post(() -> {
                output[0] = onBindSliceStrict(sliceUri);
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

    private Slice onBindSliceStrict(Uri sliceUri) {
        ThreadPolicy oldPolicy = StrictMode.getThreadPolicy();
        try {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyDeath()
                    .build());
            return onBindSlice(sliceUri);
        } finally {
            StrictMode.setThreadPolicy(oldPolicy);
        }
    }
}
