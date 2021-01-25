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

package android.app.appsearch;

import android.annotation.NonNull;
import android.annotation.SuppressLint;

/**
 * The helper class for {@link AppSearchSchema} migration.
 *
 * <p>It will query and migrate {@link GenericDocument} in given type to a new version.
 */
public interface AppSearchMigrationHelper {

    /**
     * Queries all documents that need to be migrated to the different version, and transform
     * documents to that version by passing them to the provided {@link Transformer}.
     *
     * @param schemaType The schema that need be updated and migrated {@link GenericDocument} under
     *     this type.
     * @param transformer The {@link Transformer} that will upgrade or downgrade a {@link
     *     GenericDocument} to new version.
     * @see Transformer#transform
     */
    // Rethrow the Generic Exception thrown from the Transformer.
    @SuppressLint("GenericException")
    void queryAndTransform(@NonNull String schemaType, @NonNull Transformer transformer)
            throws Exception;

    /** The class to migrate {@link GenericDocument} between different version. */
    interface Transformer {

        /**
         * Translates a {@link GenericDocument} from a version to a different version.
         *
         * <p>If the uri, schema type or namespace is changed via the transform, it will apply to
         * the new {@link GenericDocument}.
         *
         * @param currentVersion The current version of the document's schema.
         * @param finalVersion The final version that documents need to be migrated to.
         * @param document The {@link GenericDocument} need to be translated to new version.
         * @return A {@link GenericDocument} in new version.
         */
        @NonNull
        // This method will be overridden by users, allow them to throw any customer Exceptions.
        @SuppressLint("GenericException")
        GenericDocument transform(
                int currentVersion, int finalVersion, @NonNull GenericDocument document)
                throws Exception;
    }
}
