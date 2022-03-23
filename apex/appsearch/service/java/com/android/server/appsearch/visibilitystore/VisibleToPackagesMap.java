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
import android.app.appsearch.PackageIdentifier;
import android.util.ArrayMap;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Stores information about what types are accessible to which packages through the
 * {@link android.app.appsearch.SetSchemaRequest.Builder#setSchemaTypeVisibilityForPackage} API.
 *
 * This object is not thread safe.
 */
class VisibleToPackagesMap {
    /**
     * Maps packages to databases to prefixed schemas to PackageIdentifiers that have access to that
     * schema.
     */
    private final Map<String, Map<String, Map<String, Set<PackageIdentifier>>>> mMap =
            new ArrayMap<>();

    /**
     * Sets the prefixed schemas that have package visibility in the given database.
     *
     * <p>Any existing mappings for this prefix are overwritten.
     */
    public void setVisibleToPackages(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull Map<String, Set<PackageIdentifier>> schemaToPackageIdentifier) {
        Map<String, Map<String, Set<PackageIdentifier>>> databaseToSchemaTypeToVisibility =
                mMap.get(packageName);
        if (databaseToSchemaTypeToVisibility == null) {
            databaseToSchemaTypeToVisibility = new ArrayMap<>();
            mMap.put(packageName, databaseToSchemaTypeToVisibility);
        }
        databaseToSchemaTypeToVisibility.put(databaseName, schemaToPackageIdentifier);
    }

    /**
     * Returns the set of all {@link android.app.appsearch.PackageIdentifier}s which can access the
     * given schema type.
     *
     * <p>If no such settings exist, returns the empty set.
     */
    @NonNull
    public Set<PackageIdentifier> getAccessiblePackages(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull String prefixedSchema) {
        Map<String, Map<String, Set<PackageIdentifier>>> databaseToSchemaTypeToVisibility =
                mMap.get(packageName);
        if (databaseToSchemaTypeToVisibility == null) {
            return Collections.emptySet();
        }
        Map<String, Set<PackageIdentifier>> schemaTypeToVisibility =
                databaseToSchemaTypeToVisibility.get(databaseName);
        if (schemaTypeToVisibility == null) {
            return Collections.emptySet();
        }
        Set<PackageIdentifier> accessiblePackages = schemaTypeToVisibility.get(prefixedSchema);
        if (accessiblePackages == null) {
            return Collections.emptySet();
        }
        return accessiblePackages;
    }
}
