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

import android.annotation.Nullable;
import android.content.res.ApkAssets;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.ResourcesImpl;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @hide
 */
public class ResourceLoaderManager {

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final List<Pair<ResourceLoader, ResourcesProvider>> mResourceLoaders =
            new ArrayList<>();

    @GuardedBy("mLock")
    private ResourcesImpl mResourcesImpl;

    public ResourceLoaderManager(ResourcesImpl resourcesImpl) {
        this.mResourcesImpl = resourcesImpl;
        this.mResourcesImpl.getAssets().setResourceLoaderManager(this);
    }

    /**
     * Copies the list to ensure that ongoing mutations don't affect the list if it's being used
     * as a search set.
     *
     * @see Resources#getLoaders()
     */
    public List<Pair<ResourceLoader, ResourcesProvider>> getLoaders() {
        synchronized (mLock) {
            return new ArrayList<>(mResourceLoaders);
        }
    }

    /**
     * Returns a list for searching for a loader. Locks and copies the list to ensure that
     * ongoing mutations don't affect the search set.
     */
    public List<Pair<ResourceLoader, ResourcesProvider>> getInternalList() {
        synchronized (mLock) {
            return new ArrayList<>(mResourceLoaders);
        }
    }

    /**
     * TODO(b/136251855): Consider optional boolean ignoreConfigurations to allow ResourceLoader
     * to override every configuration in the target package
     *
     * @see Resources#addLoader(ResourceLoader, ResourcesProvider)
     */
    public void addLoader(ResourceLoader resourceLoader, ResourcesProvider resourcesProvider,
            int index) {
        synchronized (mLock) {
            for (int listIndex = 0; listIndex < mResourceLoaders.size(); listIndex++) {
                if (Objects.equals(mResourceLoaders.get(listIndex).first, resourceLoader)) {
                    throw new IllegalArgumentException("Cannot add the same ResourceLoader twice");
                }
            }

            mResourceLoaders.add(index, Pair.create(resourceLoader, resourcesProvider));
            updateLoaders();
        }
    }

    /**
     * @see Resources#removeLoader(ResourceLoader)
     */
    public int removeLoader(ResourceLoader resourceLoader) {
        synchronized (mLock) {
            int indexOfLoader = -1;

            for (int index = 0; index < mResourceLoaders.size(); index++) {
                if (mResourceLoaders.get(index).first == resourceLoader) {
                    indexOfLoader = index;
                    break;
                }
            }

            if (indexOfLoader < 0) {
                return indexOfLoader;
            }

            mResourceLoaders.remove(indexOfLoader);
            updateLoaders();
            return indexOfLoader;
        }
    }

    /**
     * @see Resources#setLoaders(List)
     */
    public void setLoaders(
            @Nullable List<Pair<ResourceLoader, ResourcesProvider>> newLoadersAndProviders) {
        synchronized (mLock) {
            if (ArrayUtils.isEmpty(newLoadersAndProviders)) {
                mResourceLoaders.clear();
                updateLoaders();
                return;
            }

            int size = newLoadersAndProviders.size();
            for (int newIndex = 0; newIndex < size; newIndex++) {
                ResourceLoader resourceLoader = newLoadersAndProviders.get(newIndex).first;
                for (int oldIndex = 0; oldIndex < mResourceLoaders.size(); oldIndex++) {
                    if (Objects.equals(mResourceLoaders.get(oldIndex).first, resourceLoader)) {
                        throw new IllegalArgumentException(
                                "Cannot add the same ResourceLoader twice");
                    }
                }
            }

            mResourceLoaders.clear();
            mResourceLoaders.addAll(newLoadersAndProviders);

            updateLoaders();
        }
    }

    /**
     * Swap the tracked {@link ResourcesImpl} and reattach any loaders to it.
     */
    public void onImplUpdate(ResourcesImpl resourcesImpl) {
        synchronized (mLock) {
            this.mResourcesImpl = resourcesImpl;
            updateLoaders();
        }
    }

    private void updateLoaders() {
        synchronized (mLock) {
            AssetManager assetManager = mResourcesImpl.getAssets();
            ApkAssets[] existingApkAssets = assetManager.getApkAssets();
            int baseApkAssetsSize = 0;
            for (int index = existingApkAssets.length - 1; index >= 0; index--) {
                // Loaders are always last, so the first non-loader is the end of the base assets
                if (!existingApkAssets[index].isForLoader()) {
                    baseApkAssetsSize = index + 1;
                    break;
                }
            }

            List<ApkAssets> newAssets = new ArrayList<>();
            for (int index = 0; index < baseApkAssetsSize; index++) {
                newAssets.add(existingApkAssets[index]);
            }

            int size = mResourceLoaders.size();
            for (int index = 0; index < size; index++) {
                ApkAssets apkAssets = mResourceLoaders.get(index).second.getApkAssets();
                newAssets.add(apkAssets);
            }

            assetManager.setApkAssets(newAssets.toArray(new ApkAssets[0]), true);

            // Short of resolving every resource, it's too difficult to determine what has changed
            // when a resource loader is changed, so just clear everything.
            mResourcesImpl.clearAllCaches();
        }
    }
}
