/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package android.testing;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IContentProvider;
import android.database.ContentObserver;
import android.net.Uri;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.google.android.collect.Maps;

import java.util.Map;

/**
 * A version of ContentResolver that allows easy mocking of providers.
 * By default it acts as a normal ContentResolver and returns all the
 * same providers.
 * @see #addProvider(String, ContentProvider)
 * @see #setFallbackToExisting(boolean)
 */
public class TestableContentResolver extends ContentResolver {

    public static final int STABLE = 1;
    public static final int UNSTABLE = 2;

    private final Map<String, ContentProvider> mProviders = new ArrayMap<>();
    private final Map<String, ContentProvider> mUnstableProviders = new ArrayMap<>();
    private final ContentResolver mParent;
    private final ArraySet<ContentProvider> mInUse = new ArraySet<>();
    private boolean mFallbackToExisting;

    public TestableContentResolver(Context context) {
        super(context);
        mParent = context.getContentResolver();
        mFallbackToExisting = true;
    }

    /**
     * Sets whether existing providers should be returned when a mock does not exist.
     * The default is true.
     */
    public void setFallbackToExisting(boolean fallbackToExisting) {
        mFallbackToExisting = fallbackToExisting;
    }

    /**
     * Adds access to a provider based on its authority
     *
     * @param name The authority name associated with the provider.
     * @param provider An instance of {@link android.content.ContentProvider} or one of its
     * subclasses, or null.
     */
    public void addProvider(String name, ContentProvider provider) {
        addProvider(name, provider, STABLE | UNSTABLE);
    }

    /**
     * Adds access to a provider based on its authority
     *
     * @param name The authority name associated with the provider.
     * @param provider An instance of {@link android.content.ContentProvider} or one of its
     * subclasses, or null.
     */
    public void addProvider(String name, ContentProvider provider, int flags) {
        if ((flags & STABLE) != 0) {
            mProviders.put(name, provider);
        }
        if ((flags & UNSTABLE) != 0) {
            mUnstableProviders.put(name, provider);
        }
    }

    @Override
    protected IContentProvider acquireProvider(Context context, String name) {
        final ContentProvider provider = mProviders.get(name);
        if (provider != null) {
            return provider.getIContentProvider();
        } else {
            return mFallbackToExisting ? mParent.acquireProvider(name) : null;
        }
    }

    @Override
    protected IContentProvider acquireExistingProvider(Context context, String name) {
        final ContentProvider provider = mProviders.get(name);
        if (provider != null) {
            return provider.getIContentProvider();
        } else {
            return mFallbackToExisting ? mParent.acquireExistingProvider(
                    new Uri.Builder().authority(name).build()) : null;
        }
    }

    @Override
    public boolean releaseProvider(IContentProvider provider) {
        if (!mFallbackToExisting) return true;
        if (mInUse.contains(provider)) {
            mInUse.remove(provider);
            return true;
        }
        return mParent.releaseProvider(provider);
    }

    @Override
    protected IContentProvider acquireUnstableProvider(Context c, String name) {
        final ContentProvider provider = mUnstableProviders.get(name);
        if (provider != null) {
            return provider.getIContentProvider();
        } else {
            return mFallbackToExisting ? mParent.acquireUnstableProvider(name) : null;
        }
    }

    @Override
    public boolean releaseUnstableProvider(IContentProvider icp) {
        if (!mFallbackToExisting) return true;
        if (mInUse.contains(icp)) {
            mInUse.remove(icp);
            return true;
        }
        return mParent.releaseUnstableProvider(icp);
    }

    @Override
    public void unstableProviderDied(IContentProvider icp) {
        if (!mFallbackToExisting) return;
        if (mInUse.contains(icp)) {
            return;
        }
        mParent.unstableProviderDied(icp);
    }

    @Override
    public void notifyChange(Uri uri, ContentObserver observer, boolean syncToNetwork) {
        if (!mFallbackToExisting) return;
        if (!mProviders.containsKey(uri.getAuthority())
                && !mUnstableProviders.containsKey(uri.getAuthority())) {
            super.notifyChange(uri, observer, syncToNetwork);
        }
    }
}
