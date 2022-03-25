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

package android.content.res.loader;

import android.annotation.NonNull;
import android.content.res.ApkAssets;
import android.content.res.Resources;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A container for supplying {@link ResourcesProvider ResourcesProvider(s)} to {@link Resources}
 * objects.
 *
 * <p>{@link ResourcesLoader ResourcesLoader(s)} are added to Resources objects to supply
 * additional resources and assets or modify the values of existing resources and assets. Multiple
 * Resources objects can share the same ResourcesLoaders and ResourcesProviders. Changes to the list
 * of {@link ResourcesProvider ResourcesProvider(s)} a loader contains propagates to all Resources
 * objects that use the loader.
 *
 * <p>Loaders must be added to Resources objects in increasing precedence order. A loader will
 * override the resources and assets of loaders added before itself.
 *
 * <p>Providers retrieved with {@link #getProviders()} are listed in increasing precedence order. A
 * provider will override the resources and assets of providers listed before itself.
 *
 * <p>Modifying the list of providers a loader contains or the list of loaders a Resources object
 * contains can cause lock contention with the UI thread. APIs that modify the lists of loaders or
 * providers should only be used on the UI thread. Providers can be instantiated on any thread
 * without causing lock contention.
 */
public class ResourcesLoader {
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private ApkAssets[] mApkAssets;

    @GuardedBy("mLock")
    private ResourcesProvider[] mPreviousProviders;

    @GuardedBy("mLock")
    private ResourcesProvider[] mProviders;

    @GuardedBy("mLock")
    private ArrayMap<WeakReference<Object>, UpdateCallbacks> mChangeCallbacks = new ArrayMap<>();

    /** @hide */
    public interface UpdateCallbacks {

        /**
         * Invoked when a {@link ResourcesLoader} has a {@link ResourcesProvider} added, removed,
         * or reordered.
         *
         * @param loader the loader that was updated
         */
        void onLoaderUpdated(@NonNull ResourcesLoader loader);
    }

    /**
     * Retrieves the list of providers loaded into this instance. Providers are listed in increasing
     * precedence order. A provider will override the values of providers listed before itself.
     */
    @NonNull
    public List<ResourcesProvider> getProviders() {
        synchronized (mLock) {
            return mProviders == null ? Collections.emptyList() : Arrays.asList(mProviders);
        }
    }

    /**
     * Appends a provider to the end of the provider list. If the provider is already present in the
     * loader list, the list will not be modified.
     *
     * <p>This should only be called from the UI thread to avoid lock contention when propagating
     * provider changes.
     *
     * @param resourcesProvider the provider to add
     */
    public void addProvider(@NonNull ResourcesProvider resourcesProvider) {
        synchronized (mLock) {
            mProviders = ArrayUtils.appendElement(ResourcesProvider.class, mProviders,
                    resourcesProvider);
            notifyProvidersChangedLocked();
        }
    }

    /**
     * Removes a provider from the provider list. If the provider is not present in the provider
     * list, the list will not be modified.
     *
     * <p>This should only be called from the UI thread to avoid lock contention when propagating
     * provider changes.
     *
     * @param resourcesProvider the provider to remove
     */
    public void removeProvider(@NonNull ResourcesProvider resourcesProvider) {
        synchronized (mLock) {
            mProviders = ArrayUtils.removeElement(ResourcesProvider.class, mProviders,
                    resourcesProvider);
            notifyProvidersChangedLocked();
        }
    }

    /**
     * Sets the list of providers.
     *
     * <p>This should only be called from the UI thread to avoid lock contention when propagating
     * provider changes.
     *
     * @param resourcesProviders the new providers
     */
    public void setProviders(@NonNull List<ResourcesProvider> resourcesProviders) {
        synchronized (mLock) {
            mProviders = resourcesProviders.toArray(new ResourcesProvider[0]);
            notifyProvidersChangedLocked();
        }
    }

    /**
     * Removes all {@link ResourcesProvider ResourcesProvider(s)}.
     *
     * <p>This should only be called from the UI thread to avoid lock contention when propagating
     * provider changes.
     */
    public void clearProviders() {
        synchronized (mLock) {
            mProviders = null;
            notifyProvidersChangedLocked();
        }
    }

    /**
     * Retrieves the list of {@link ApkAssets} used by the providers.
     *
     * @hide
     */
    @NonNull
    public List<ApkAssets> getApkAssets() {
        synchronized (mLock) {
            if (mApkAssets == null) {
                return Collections.emptyList();
            }
            return Arrays.asList(mApkAssets);
        }
    }

    /**
     * Registers a callback to be invoked when {@link ResourcesProvider ResourcesProvider(s)}
     * change.
     * @param instance the instance tied to the callback
     * @param callbacks the callback to invoke
     *
     * @hide
     */
    public void registerOnProvidersChangedCallback(@NonNull Object instance,
            @NonNull UpdateCallbacks callbacks) {
        synchronized (mLock) {
            mChangeCallbacks.put(new WeakReference<>(instance), callbacks);
        }
    }

    /**
     * Removes a previously registered callback.
     * @param instance the instance tied to the callback
     *
     * @hide
     */
    public void unregisterOnProvidersChangedCallback(@NonNull Object instance) {
        synchronized (mLock) {
            for (int i = 0, n = mChangeCallbacks.size(); i < n; i++) {
                final WeakReference<Object> key = mChangeCallbacks.keyAt(i);
                if (instance == key.get()) {
                    mChangeCallbacks.removeAt(i);
                    return;
                }
            }
        }
    }

    /** Returns whether the arrays contain the same provider instances in the same order. */
    private static boolean arrayEquals(ResourcesProvider[] a1, ResourcesProvider[] a2) {
        if (a1 == a2) {
            return true;
        }

        if (a1 == null || a2 == null) {
            return false;
        }

        if (a1.length != a2.length) {
            return false;
        }

        // Check that the arrays contain the exact same instances in the same order. Providers do
        // not have any form of equivalence checking of whether the contents of two providers have
        // equivalent apk assets.
        for (int i = 0, n = a1.length; i < n; i++) {
            if (a1[i] != a2[i]) {
                return false;
            }
        }

        return true;
    }

    /**
     * Invokes registered callbacks when the list of {@link ResourcesProvider} instances this loader
     * uses changes.
     */
    private void notifyProvidersChangedLocked() {
        final ArraySet<UpdateCallbacks> uniqueCallbacks = new ArraySet<>();
        if (arrayEquals(mPreviousProviders, mProviders)) {
            return;
        }

        if (mProviders == null || mProviders.length == 0) {
            mApkAssets = null;
        } else {
            mApkAssets = new ApkAssets[mProviders.length];
            for (int i = 0, n = mProviders.length; i < n; i++) {
                mProviders[i].incrementRefCount();
                mApkAssets[i] = mProviders[i].getApkAssets();
            }
        }

        // Decrement the ref count after incrementing the new provider ref count so providers
        // present before and after this method do not drop to zero references.
        if (mPreviousProviders != null) {
            for (ResourcesProvider provider : mPreviousProviders) {
                provider.decrementRefCount();
            }
        }

        mPreviousProviders = mProviders;

        for (int i = mChangeCallbacks.size() - 1; i >= 0; i--) {
            final WeakReference<Object> key = mChangeCallbacks.keyAt(i);
            if (key.refersTo(null)) {
                mChangeCallbacks.removeAt(i);
            } else {
                uniqueCallbacks.add(mChangeCallbacks.valueAt(i));
            }
        }

        for (int i = 0, n = uniqueCallbacks.size(); i < n; i++) {
            uniqueCallbacks.valueAt(i).onLoaderUpdated(this);
        }
    }
}
