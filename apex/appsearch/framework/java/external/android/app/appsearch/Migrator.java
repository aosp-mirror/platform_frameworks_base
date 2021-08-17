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
import android.annotation.WorkerThread;

/**
 * A migrator class to translate {@link GenericDocument} from different version of {@link
 * AppSearchSchema}
 *
 * <p>Make non-backwards-compatible changes will delete all stored documents in old schema. You can
 * save your documents by setting {@link Migrator} via the {@link
 * SetSchemaRequest.Builder#setMigrator} for each type and target version you want to save.
 *
 * <p>{@link #onDowngrade} or {@link #onUpgrade} will be triggered if the version number of the
 * schema stored in AppSearch is different with the version in the request.
 *
 * <p>If any error or Exception occurred in the {@link #onDowngrade} or {@link #onUpgrade}, all the
 * setSchema request will be rejected unless the schema changes are backwards-compatible, and stored
 * documents won't have any observable changes.
 */
public abstract class Migrator {
    /**
     * Returns {@code true} if this migrator's source type needs to be migrated to update from
     * currentVersion to finalVersion.
     *
     * <p>Migration won't be triggered if currentVersion is equal to finalVersion even if {@link
     * #shouldMigrate} return true;
     */
    public abstract boolean shouldMigrate(int currentVersion, int finalVersion);

    /**
     * Migrates {@link GenericDocument} to a newer version of {@link AppSearchSchema}.
     *
     * <p>This method will be invoked only if the {@link SetSchemaRequest} is setting a higher
     * version number than the current {@link AppSearchSchema} saved in AppSearch.
     *
     * <p>If this {@link Migrator} is provided to cover a compatible schema change via {@link
     * AppSearchSession#setSchema}, documents under the old version won't be removed unless you use
     * the same document ID.
     *
     * <p>This method will be invoked on the background worker thread provided via {@link
     * AppSearchSession#setSchema}.
     *
     * @param currentVersion The current version of the document's schema.
     * @param finalVersion The final version that documents need to be migrated to.
     * @param document The {@link GenericDocument} need to be translated to new version.
     * @return A {@link GenericDocument} in new version.
     */
    @WorkerThread
    @NonNull
    public abstract GenericDocument onUpgrade(
            int currentVersion, int finalVersion, @NonNull GenericDocument document);

    /**
     * Migrates {@link GenericDocument} to an older version of {@link AppSearchSchema}.
     *
     * <p>This method will be invoked only if the {@link SetSchemaRequest} is setting a lower
     * version number than the current {@link AppSearchSchema} saved in AppSearch.
     *
     * <p>If this {@link Migrator} is provided to cover a compatible schema change via {@link
     * AppSearchSession#setSchema}, documents under the old version won't be removed unless you use
     * the same document ID.
     *
     * <p>This method will be invoked on the background worker thread.
     *
     * @param currentVersion The current version of the document's schema.
     * @param finalVersion The final version that documents need to be migrated to.
     * @param document The {@link GenericDocument} need to be translated to new version.
     * @return A {@link GenericDocument} in new version.
     */
    @WorkerThread
    @NonNull
    public abstract GenericDocument onDowngrade(
            int currentVersion, int finalVersion, @NonNull GenericDocument document);
}
