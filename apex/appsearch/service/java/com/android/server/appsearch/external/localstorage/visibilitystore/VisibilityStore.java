/*
 * Copyright 2021 The Android Open Source Project
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
package com.android.server.appsearch.external.localstorage.visibilitystore;

import android.annotation.NonNull;
import android.app.appsearch.PackageIdentifier;
import android.app.appsearch.exceptions.AppSearchException;

import com.android.internal.annotations.VisibleForTesting;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An interface for classes that store and validate document visibility data.
 *
 * @hide
 */
public interface VisibilityStore {
    /**
     * These cannot have any of the special characters used by AppSearchImpl (e.g. {@code
     * AppSearchImpl#PACKAGE_DELIMITER} or {@code AppSearchImpl#DATABASE_DELIMITER}.
     */
    String PACKAGE_NAME = "VS#Pkg";

    @VisibleForTesting String DATABASE_NAME = "VS#Db";

    /**
     * Sets visibility settings for the given database. Any previous visibility settings will be
     * overwritten.
     *
     * @param packageName Package of app that owns the schemas.
     * @param databaseName Database that owns the schemas.
     * @param schemasNotDisplayedBySystem Set of prefixed schemas that should be hidden from
     *     platform surfaces.
     * @param schemasVisibleToPackages Map of prefixed schemas to a list of package identifiers that
     *     have access to the schema.
     * @throws AppSearchException on AppSearchImpl error.
     */
    void setVisibility(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull Set<String> schemasNotDisplayedBySystem,
            @NonNull Map<String, List<PackageIdentifier>> schemasVisibleToPackages)
            throws AppSearchException;

    /**
     * Checks whether the given package has access to system-surfaceable schemas.
     *
     * @param callerUid UID of the app that wants to see the data.
     */
    boolean isSchemaSearchableByCaller(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull String prefixedSchema,
            int callerUid,
            boolean callerHasSystemAccess);
}
