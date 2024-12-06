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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IContentProvider;
import android.database.ContentObserver;
import android.net.Uri;
import android.util.Log;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * <p>
 *      An extension of {@link android.content.ContentResolver} that is designed for
 *      testing.
 * </p>
 * <p>
 *      MockContentResolver overrides Android's normal way of resolving providers by
 *      authority. To have access to a provider based on its authority, users of
 *      MockContentResolver first instantiate the provider and
 *      use {@link MockContentResolver#addProvider(String, ContentProvider)}. Resolution of an
 *      authority occurs entirely within MockContentResolver.
 * </p>
 * <p>
 *      Users can also set an authority's entry in the map to null, so that a provider is completely
 *      mocked out.
 * </p>
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about application testing, read the
 * <a href="{@docRoot}guide/topics/testing/index.html">Testing</a> developer guide.</p>
 * </div>
 */
public class MockContentResolver extends ContentResolver {
    private static final String TAG = "MockContentResolver";
    Map<String, ContentProvider> mProviders;

    /**
     * Creates a local map of providers. This map is used instead of the global
     * map when an API call tries to acquire a provider.
     */
    public MockContentResolver() {
        this(null);
    }

    /**
     * Creates a local map of providers. This map is used instead of the global
     * map when an API call tries to acquire a provider.
     */
    public MockContentResolver(Context context) {
        super(context);
        mProviders = new HashMap<>();
    }

    /**
     * Adds access to a provider based on its authority
     *
     * @param name The authority name associated with the provider.
     * @param provider An instance of {@link android.content.ContentProvider} or one of its
     * subclasses, or null.
     */
    public void addProvider(String name, ContentProvider provider) {

        /*
         * Maps the authority to the provider locally.
         */
        mProviders.put(name, provider);
    }

    /** @hide */
    @Override
    protected IContentProvider acquireProvider(Context context, String name) {
        return acquireExistingProvider(context, name);
    }

    /** @hide */
    @Override
    protected IContentProvider acquireExistingProvider(Context context, String name) {

        /*
         * Gets the content provider from the local map
         */
        final ContentProvider provider = mProviders.get(name);

        if (provider != null) {
            return provider.getIContentProvider();
        } else {
            Log.w(TAG, "Provider does not exist: " + name);
            return null;
        }
    }

    /** @hide */
    @Override
    public boolean releaseProvider(IContentProvider provider) {
        return true;
    }

    /** @hide */
    @Override
    protected IContentProvider acquireUnstableProvider(Context c, String name) {
        return acquireProvider(c, name);
    }

    /** @hide */
    @Override
    public boolean releaseUnstableProvider(IContentProvider icp) {
        return releaseProvider(icp);
    }

    /** @hide */
    @Override
    public void unstableProviderDied(IContentProvider icp) {
    }

    /**
     * Overrides the behavior from the parent class to completely ignore any
     * content notifications sent to this object. This effectively hides clients
     * from observers elsewhere in the system.
     */
    @Override
    public void notifyChange(@NonNull Uri uri, @Nullable ContentObserver observer) {
    }

    /**
     * Overrides the behavior from the parent class to completely ignore any
     * content notifications sent to this object. This effectively hides clients
     * from observers elsewhere in the system.
     *
     * @deprecated callers should consider migrating to
     *             {@link #notifyChange(Uri, ContentObserver, int)}, as it
     *             offers support for many more options than just
     *             {@link #NOTIFY_SYNC_TO_NETWORK}.
     */
    @Override
    @Deprecated
    public void notifyChange(@NonNull Uri uri, @Nullable ContentObserver observer,
            boolean syncToNetwork) {
    }

    /**
     * Overrides the behavior from the parent class to completely ignore any
     * content notifications sent to this object. This effectively hides clients
     * from observers elsewhere in the system.
     */
    @Override
    public void notifyChange(@NonNull Uri uri, @Nullable ContentObserver observer,
            @NotifyFlags int flags) {
    }

    /**
     * Overrides the behavior from the parent class to completely ignore any
     * content notifications sent to this object. This effectively hides clients
     * from observers elsewhere in the system.
     */
    @Override
    public void notifyChange(@NonNull Collection<Uri> uris, @Nullable ContentObserver observer,
            @NotifyFlags int flags) {
    }
}
