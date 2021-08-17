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
import android.app.appsearch.SetSchemaResponse;
import android.app.appsearch.exceptions.AppSearchException;
import android.util.ArrayMap;
import android.util.ArraySet;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Utilities for schema migration.
 *
 * @hide
 */
public final class SchemaMigrationUtil {
    private SchemaMigrationUtil() {}

    /**
     * Returns all active {@link Migrator}s that need to be triggered in this migration.
     *
     * <p>{@link Migrator#shouldMigrate} returns {@code true} will make the {@link Migrator} active.
     */
    @NonNull
    public static Map<String, Migrator> getActiveMigrators(
            @NonNull Set<AppSearchSchema> existingSchemas,
            @NonNull Map<String, Migrator> migrators,
            int currentVersion,
            int finalVersion) {
        if (currentVersion == finalVersion) {
            return Collections.emptyMap();
        }
        Set<String> existingTypes = new ArraySet<>(existingSchemas.size());
        for (AppSearchSchema schema : existingSchemas) {
            existingTypes.add(schema.getSchemaType());
        }

        Map<String, Migrator> activeMigrators = new ArrayMap<>();
        for (Map.Entry<String, Migrator> entry : migrators.entrySet()) {
            // The device contains the source type, and we should trigger migration for the type.
            String schemaType = entry.getKey();
            Migrator migrator = entry.getValue();
            if (existingTypes.contains(schemaType)
                    && migrator.shouldMigrate(currentVersion, finalVersion)) {
                activeMigrators.put(schemaType, migrator);
            }
        }
        return activeMigrators;
    }

    /**
     * Checks the setSchema() call won't delete any types or has incompatible types after all {@link
     * Migrator} has been triggered.
     */
    public static void checkDeletedAndIncompatibleAfterMigration(
            @NonNull SetSchemaResponse setSchemaResponse, @NonNull Set<String> activeMigrators)
            throws AppSearchException {
        Set<String> unmigratedIncompatibleTypes =
                new ArraySet<>(setSchemaResponse.getIncompatibleTypes());
        unmigratedIncompatibleTypes.removeAll(activeMigrators);

        Set<String> unmigratedDeletedTypes = new ArraySet<>(setSchemaResponse.getDeletedTypes());
        unmigratedDeletedTypes.removeAll(activeMigrators);

        // check if there are any unmigrated incompatible types or deleted types. If there
        // are, we will getActiveMigratorsthrow an exception. That's the only case we
        // swallowed in the AppSearchImpl#setSchema().
        // Since the force override is false, the schema will not have been set if there are
        // any incompatible or deleted types.
        checkDeletedAndIncompatible(unmigratedDeletedTypes, unmigratedIncompatibleTypes);
    }

    /** Checks the setSchema() call won't delete any types or has incompatible types. */
    public static void checkDeletedAndIncompatible(
            @NonNull Set<String> deletedTypes, @NonNull Set<String> incompatibleTypes)
            throws AppSearchException {
        if (deletedTypes.size() > 0 || incompatibleTypes.size() > 0) {
            String newMessage =
                    "Schema is incompatible."
                            + "\n  Deleted types: "
                            + deletedTypes
                            + "\n  Incompatible types: "
                            + incompatibleTypes;
            throw new AppSearchException(AppSearchResult.RESULT_INVALID_SCHEMA, newMessage);
        }
    }
}
