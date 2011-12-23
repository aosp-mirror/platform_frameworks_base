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

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IContentProvider;
import android.database.ContentObserver;
import android.net.Uri;

import com.google.android.collect.Maps;

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
    Map<String, ContentProvider> mProviders;

    /*
     * Creates a local map of providers. This map is used instead of the global map when an
     * API call tries to acquire a provider.
     */
    public MockContentResolver() {
        super(null);
        mProviders = Maps.newHashMap();
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
            return null;
        }
    }

    /** @hide */
    @Override
    public boolean releaseProvider(IContentProvider provider) {
        return true;
    }

    /**
     * Overrides {@link android.content.ContentResolver#notifyChange(Uri, ContentObserver, boolean)
     * ContentResolver.notifChange(Uri, ContentObserver, boolean)}. All parameters are ignored.
     * The method hides providers linked to MockContentResolver from other observers in the system.
     *
     * @param uri (Ignored) The uri of the content provider.
     * @param observer (Ignored) The observer that originated the change.
     * @param syncToNetwork (Ignored) If true, attempt to sync the change to the network.
     */
    @Override
    public void notifyChange(Uri uri,
            ContentObserver observer,
            boolean syncToNetwork) {
    }
}
