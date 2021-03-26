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

package android.app.appsearch.util;

import android.annotation.NonNull;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.Migrator;
import android.app.appsearch.exceptions.AppSearchException;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Utilities for schema migration.
 *
 * @hide
 */
public final class SchemaMigrationUtil {
    private static final String TAG = "AppSearchMigrateUtil";

    private SchemaMigrationUtil() {}

    /**
     * Finds out which incompatible schema type won't be migrated by comparing its current and final
     * version number.
     */
    @NonNull
    public static Set<String> getUnmigratedIncompatibleTypes(
            @NonNull Set<String> incompatibleSchemaTypes,
            @NonNull Map<String, Migrator> migrators,
            @NonNull Map<String, Integer> currentVersionMap,
            @NonNull Map<String, Integer> finalVersionMap)
            throws AppSearchException {
        Set<String> unmigratedSchemaTypes = new ArraySet<>();
        for (String unmigratedSchemaType : incompatibleSchemaTypes) {
            Integer currentVersion = currentVersionMap.get(unmigratedSchemaType);
            Integer finalVersion = finalVersionMap.get(unmigratedSchemaType);
            if (currentVersion == null) {
                // impossible, we have done something wrong.
                throw new AppSearchException(
                        AppSearchResult.RESULT_UNKNOWN_ERROR,
                        "Cannot find the current version number for schema type: "
                                + unmigratedSchemaType);
            }
            if (finalVersion == null) {
                // The schema doesn't exist in the SetSchemaRequest.
                unmigratedSchemaTypes.add(unmigratedSchemaType);
                continue;
            }
            // we don't have migrator or won't trigger migration for this schema type.
            Migrator migrator = migrators.get(unmigratedSchemaType);
            if (migrator == null
                    || !migrator.shouldMigrate(currentVersion, finalVersion)) {
                unmigratedSchemaTypes.add(unmigratedSchemaType);
            }
        }
        return Collections.unmodifiableSet(unmigratedSchemaTypes);
    }

    /**
     * Triggers upgrade or downgrade migration for the given schema type if its version stored in
     * AppSearch is different with the version in the request.
     *
     * @return {@code True} if we trigger the migration for the given type.
     */
    public static boolean shouldTriggerMigration(
            @NonNull String schemaType,
            @NonNull Migrator migrator,
            @NonNull Map<String, Integer> currentVersionMap,
            @NonNull Map<String, Integer> finalVersionMap)
            throws AppSearchException {
        Integer currentVersion = currentVersionMap.get(schemaType);
        Integer finalVersion = finalVersionMap.get(schemaType);
        if (currentVersion == null) {
            Log.d(TAG, "The SchemaType: " + schemaType + " not present in AppSearch.");
            return false;
        }
        if (finalVersion == null) {
            throw new AppSearchException(
                    AppSearchResult.RESULT_INVALID_ARGUMENT,
                    "Receive a migrator for schema type : "
                            + schemaType
                            + ", but the schema doesn't exist in the request.");
        }
        return migrator.shouldMigrate(currentVersion, finalVersion);
    }

    /** Builds a Map of SchemaType and its version of given set of {@link AppSearchSchema}. */
    //TODO(b/182620003) remove this method once support migrate to another type
    @NonNull
    public static Map<String, Integer> buildVersionMap(
            @NonNull Collection<AppSearchSchema> schemas, int version) {
        Map<String, Integer> currentVersionMap = new ArrayMap<>(schemas.size());
        for (AppSearchSchema currentSchema : schemas) {
            currentVersionMap.put(currentSchema.getSchemaType(), version);
        }
        return currentVersionMap;
    }
}
