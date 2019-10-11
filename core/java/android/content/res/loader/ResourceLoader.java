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

import android.annotation.AnyRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.os.ParcelFileDescriptor;
import android.util.TypedValue;

import java.io.IOException;
import java.io.InputStream;

/**
 * Exposes methods for overriding file-based resource loading from a {@link Resources}.
 *
 * To be used with {@link Resources#addLoader(ResourceLoader, ResourcesProvider, int)} and related
 * methods to override resource loading.
 *
 * Note that this class doesn't actually contain any resource data. Non-file-based resources are
 * loaded directly from the {@link ResourcesProvider}'s .arsc representation.
 *
 * An instance's methods will only be called if its corresponding {@link ResourcesProvider}'s
 * resources table contains an entry for the resource ID being resolved,
 * with the exception of the non-cookie variants of {@link AssetManager}'s openAsset and
 * openNonAsset.
 *
 * Those methods search backwards through all {@link ResourceLoader}s and then any paths provided
 * by the application or system.
 *
 * Otherwise, an ARSC that defines R.drawable.some_id must be provided if a {@link ResourceLoader}
 * wants to point R.drawable.some_id to a different file on disk.
 */
public interface ResourceLoader {

    /**
     * Given the value resolved from the string pool of the {@link ResourcesProvider} passed to
     * {@link Resources#addLoader(ResourceLoader, ResourcesProvider, int)}, return a
     * {@link Drawable} which should be returned by the parent
     * {@link Resources#getDrawable(int, Resources.Theme)}.
     *
     * @param value   the resolved {@link TypedValue} before it has been converted to a Drawable
     *                object
     * @param id      the R.drawable ID this resolution is for
     * @param density the requested density
     * @param theme   the {@link Resources.Theme} resolved under
     * @return null if resolution should try to find an entry inside the {@link ResourcesProvider},
     * including calling through to {@link #loadAsset(String, int)} or {@link #loadAssetFd(String)}
     */
    @Nullable
    default Drawable loadDrawable(@NonNull TypedValue value, int id, int density,
            @Nullable Resources.Theme theme) {
        return null;
    }

    /**
     * Given the value resolved from the string pool of the {@link ResourcesProvider} passed to
     * {@link Resources#addLoader(ResourceLoader, ResourcesProvider, int)}, return an
     * {@link XmlResourceParser} which should be returned by the parent
     * {@link Resources#getDrawable(int, Resources.Theme)}.
     *
     * @param path the string that was found in the string pool
     * @param id   the XML ID this resolution is for, can be R.anim, R.layout, or R.xml
     * @return null if resolution should try to find an entry inside the {@link ResourcesProvider},
     * including calling through to {@link #loadAssetFd(String)} (String, int)}
     */
    @Nullable
    default XmlResourceParser loadXmlResourceParser(@NonNull String path, @AnyRes int id) {
        return null;
    }

    /**
     * Given the value resolved from the string pool of the {@link ResourcesProvider} passed to
     * {@link Resources#addLoader(ResourceLoader, ResourcesProvider, int)}, return an
     * {@link InputStream} which should be returned when an asset is loaded by {@link AssetManager}.
     * Assets will be loaded from a provider's root, with anything in its assets subpath prefixed
     * with "assets/".
     *
     * @param path       the asset path to load
     * @param accessMode {@link AssetManager} access mode; does not have to be respected
     * @return null if resolution should try to find an entry inside the {@link ResourcesProvider}
     */
    @Nullable
    default InputStream loadAsset(@NonNull String path, int accessMode) throws IOException {
        return null;
    }

    /**
     * {@link ParcelFileDescriptor} variant of {@link #loadAsset(String, int)}.
     *
     * @param path the asset path to load
     * @return null if resolution should try to find an entry inside the {@link ResourcesProvider}
     */
    @Nullable
    default ParcelFileDescriptor loadAssetFd(@NonNull String path) throws IOException {
        return null;
    }
}
