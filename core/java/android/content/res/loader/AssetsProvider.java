/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.annotation.Nullable;
import android.content.res.AssetManager;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.io.InputStream;

/**
 * Provides callbacks that allow for the value of a file-based resources or assets of a
 * {@link ResourcesProvider} to be specified or overridden.
 */
public interface AssetsProvider {

    /**
     * Callback that allows the value of a file-based resources or asset to be specified or
     * overridden.
     *
     * <p>There are two situations in which this method will be called:
     * <ul>
     * <li>AssetManager is queried for an InputStream of an asset using APIs like
     * {@link AssetManager#open} and {@link AssetManager#openXmlResourceParser}.
     * <li>AssetManager is resolving the value of a file-based resource provided by the
     * {@link ResourcesProvider} this instance is associated with.
     * </ul>
     *
     * <p>If the value retrieved from this callback is null, AssetManager will attempt to find the
     * file-based resource or asset within the APK provided by the ResourcesProvider this instance
     * is associated with.
     *
     * @param path the asset path being loaded
     * @param accessMode the {@link AssetManager} access mode
     *
     * @see AssetManager#open
     */
    @Nullable
    default InputStream loadAsset(@NonNull String path, int accessMode) throws IOException {
        return null;
    }

    /**
     * {@link ParcelFileDescriptor} variant of {@link #loadAsset(String, int)}.
     *
     * @param path the asset path being loaded
     */
    @Nullable
    default ParcelFileDescriptor loadAssetParcelFd(@NonNull String path) throws IOException {
        return null;
    }
}
