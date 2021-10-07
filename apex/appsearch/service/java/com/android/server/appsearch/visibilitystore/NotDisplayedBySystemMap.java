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
 */
class NotDisplayedBySystemMap {
    /**
     * Maps packages to databases to the set of prefixed schemas that are platform-hidden within
     * that database.
     */
    private final Map<String, Map<String, Set<String>>> mMap = new ArrayMap<>();

    /**
     * Sets the prefixed schemas that are opted out of platform surfacing for the database.
     *
     * <p>Any existing mappings for this prefix are overwritten.
     */
    public void setNotDisplayedBySystem(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull Set<String> prefixedSchemas) {
        Map<String, Set<String>> databaseToSchemas = mMap.get(packageName);
        if (databaseToSchemas == null) {
            databaseToSchemas = new ArrayMap<>();
            mMap.put(packageName, databaseToSchemas);
        }
        databaseToSchemas.put(databaseName, prefixedSchemas);
    }

    /**
     * Returns whether the given prefixed schema is platform surfaceable (has not opted out) in the
     * given database.
     */
    public boolean isSchemaDisplayedBySystem(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull String prefixedSchema) {
        Map<String, Set<String>> databaseToSchemaType = mMap.get(packageName);
        if (databaseToSchemaType == null) {
            // No opt-outs for this package
            return true;
        }
        Set<String> schemaTypes = databaseToSchemaType.get(databaseName);
        if (schemaTypes == null) {
            // No opt-outs for this database
            return true;
        }
        // Some schemas were opted out of being platform-surfaced. As long as this schema
        // isn't one of those opt-outs, it's surfaceable.
        return !schemaTypes.contains(prefixedSchema);
    }
}
