/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.server.appsearch.visibilitystore;

import android.annotation.NonNull;
import android.util.ArrayMap;

import java.util.Map;
import java.util.Set;

/**
 * Stores information about what types are hidden from platform surfaces through the
 * {@link android.app.appsearch.SetSchemaRequest.Builder#setSchemaTypeDisplayedBySystem} API.
 *
 * This object is not thread safe.
 * @hide
 */
public class NotPlatformSurfaceableMap {
    /**
     * Maps prefixes to the set of prefixed schemas that are platform-hidden within that prefix.
     */
    private final Map<String, Set<String>> mMap = new ArrayMap<>();

    /**
     * Sets the prefixed schemas that are opted out of platform surfacing for the prefix.
     *
     * <p>Any existing mappings for this prefix are overwritten.
     */
    public void setNotPlatformSurfaceable(@NonNull String prefix, @NonNull Set<String> schemas) {
        mMap.put(prefix, schemas);
    }

    /**
     * Returns whether the given prefixed schema is platform surfaceable (has not opted out) in the
     * given prefix.
     */
    public boolean isSchemaPlatformSurfaceable(@NonNull String prefix, @NonNull String schemaType) {
        Set<String> schemaTypes = mMap.get(prefix);
        if (schemaTypes == null) {
            // No opt-outs for this prefix
            return true;
        }
        // Some schemas were opted out of being platform-surfaced. As long as this schema
        // isn't one of those opt-outs, it's surfaceable.
        return !schemaTypes.contains(schemaType);
    }

    /** Discards all data in the map. */
    public void clear() {
        mMap.clear();
    }
}
