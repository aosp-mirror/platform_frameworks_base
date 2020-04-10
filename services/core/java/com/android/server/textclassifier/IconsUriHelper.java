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
package com.android.server.textclassifier;

import android.annotation.Nullable;
import android.net.Uri;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A helper for mapping an icon resource to a content uri.
 *
 * <p>NOTE: Care must be taken to avoid passing resource uris to non-permitted apps via this helper.
 */
@VisibleForTesting(visibility = Visibility.PACKAGE)
public final class IconsUriHelper {

    public static final String AUTHORITY = "com.android.textclassifier.icons";

    private static final String TAG = "IconsUriHelper";
    private static final Supplier<String> DEFAULT_ID_SUPPLIER = () -> UUID.randomUUID().toString();

    // TODO: Consider using an LRU cache to limit resource usage.
    // This may depend on the expected number of packages that a device typically has.
    @GuardedBy("mPackageIds")
    private final Map<String, String> mPackageIds = new ArrayMap<>();

    private final Supplier<String> mIdSupplier;

    private static final IconsUriHelper sSingleton = new IconsUriHelper(null);

    private IconsUriHelper(@Nullable Supplier<String> idSupplier) {
        mIdSupplier = (idSupplier != null) ? idSupplier : DEFAULT_ID_SUPPLIER;

        // Useful for testing:
        // Magic id for the android package so it is the same across classloaders.
        // This is okay as this package does not have access restrictions, and
        // the TextClassifierService hardly returns icons from this package.
        mPackageIds.put("android", "android");
    }

    /**
     * Returns a new instance of this object for testing purposes.
     */
    public static IconsUriHelper newInstanceForTesting(@Nullable Supplier<String> idSupplier) {
        return new IconsUriHelper(idSupplier);
    }

    static IconsUriHelper getInstance() {
        return sSingleton;
    }

    /**
     * Returns a Uri for the specified icon resource.
     *
     * @param packageName the resource's package name
     * @param resId       the resource id
     * @see #getResourceInfo(Uri)
     */
    public Uri getContentUri(String packageName, int resId) {
        Objects.requireNonNull(packageName);
        synchronized (mPackageIds) {
            if (!mPackageIds.containsKey(packageName)) {
                // TODO: Ignore packages that don't actually exist on the device.
                mPackageIds.put(packageName, mIdSupplier.get());
            }
            return new Uri.Builder()
                    .scheme("content")
                    .authority(AUTHORITY)
                    .path(mPackageIds.get(packageName))
                    .appendPath(Integer.toString(resId))
                    .build();
        }
    }

    /**
     * Returns a valid {@link ResourceInfo} for the specified uri. Returns {@code null} if a valid
     * {@link ResourceInfo} cannot be returned for the specified uri.
     *
     * @see #getContentUri(String, int);
     */
    @Nullable
    public ResourceInfo getResourceInfo(Uri uri) {
        if (!"content".equals(uri.getScheme())) {
            return null;
        }
        if (!AUTHORITY.equals(uri.getAuthority())) {
            return null;
        }

        final List<String> pathItems = uri.getPathSegments();
        try {
            synchronized (mPackageIds) {
                final String packageId = pathItems.get(0);
                final int resId = Integer.parseInt(pathItems.get(1));
                for (String packageName : mPackageIds.keySet()) {
                    if (packageId.equals(mPackageIds.get(packageName))) {
                        return new ResourceInfo(packageName, resId);
                    }
                }
            }
        } catch (Exception e) {
            Log.v(TAG, "Could not get resource info. Reason: " + e.getMessage());
        }
        return null;
    }

    /**
     * A holder for a resource's package name and id.
     */
    public static final class ResourceInfo {

        public final String packageName;
        public final int id;

        private ResourceInfo(String packageName, int id) {
            this.packageName = Objects.requireNonNull(packageName);
            this.id = id;
        }
    }
}
