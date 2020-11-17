/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.server.appsearch.external.localstorage;

import android.annotation.NonNull;
import com.android.internal.annotations.VisibleForTesting;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.exceptions.AppSearchException;
import android.util.ArrayMap;
import android.util.ArraySet;
import com.android.internal.util.Preconditions;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Manages any visibility settings for all the databases that AppSearchImpl knows about. Persists
 * the visibility settings and reloads them on initialization.
 *
 * <p>The VisibilityStore creates a document for each database. This document holds the visibility
 * settings that apply to that database. The VisibilityStore also creates a schema for these
 * documents and has its own database so that its data doesn't interfere with any clients' data.
 * It persists the document and schema through AppSearchImpl.
 *
 * <p>These visibility settings are used to ensure AppSearch queries respect the clients'
 * settings on who their data is visible to.
 *
 * <p>This class doesn't handle any locking itself. Its callers should handle the locking at a
 * higher level.
 *
 * <p>NOTE: This class holds an instance of AppSearchImpl and AppSearchImpl holds an instance of
 * this class. Take care to not cause any circular dependencies.
 */
class VisibilityStore {
    // Schema type for documents that hold AppSearch's metadata, e.g. visibility settings
    @VisibleForTesting
    static final String SCHEMA_TYPE = "Visibility";
    // Property that holds the list of platform-hidden schemas, as part of the visibility
    // settings.
    @VisibleForTesting
    static final String PLATFORM_HIDDEN_PROPERTY = "platformHidden";
    // Database name to prefix all visibility schemas and documents with. Special-cased to
    // minimize the chance of collision with a client-supplied database.
    @VisibleForTesting
    static final String DATABASE_NAME = "$$__AppSearch__Database";
    // Namespace of documents that contain visibility settings
    private static final String NAMESPACE = "namespace";
    private final AppSearchImpl mAppSearchImpl;

    // The map contains schemas that are platform-hidden for each database. All schemas in the map
    // have a database name prefix.
    private final Map<String, Set<String>> mPlatformHiddenMap = new ArrayMap<>();

    /**
     * Creates an uninitialized VisibilityStore object. Callers must also call {@link #initialize()}
     * before using the object.
     *
     * @param appSearchImpl AppSearchImpl instance
     */
    VisibilityStore(@NonNull AppSearchImpl appSearchImpl) {
        mAppSearchImpl = appSearchImpl;
    }

    /**
     * Initializes schemas and member variables to track visibility settings.
     *
     * <p>This is kept separate from the constructor because this will call methods on
     * AppSearchImpl. Some may even then recursively call back into VisibilityStore (for example,
     * {@link AppSearchImpl#setSchema} will call {@link #updateSchemas}. We need to have both
     * AppSearchImpl and VisibilityStore fully initialized for this call flow to work.
     *
     * @throws AppSearchException AppSearchException on AppSearchImpl error.
     */
    public void initialize() throws AppSearchException {
        if (!mAppSearchImpl.hasSchemaType(DATABASE_NAME, SCHEMA_TYPE)) {
            // Schema type doesn't exist yet. Add it.
            mAppSearchImpl.setSchema(DATABASE_NAME,
                    Collections.singleton(new AppSearchSchema.Builder(SCHEMA_TYPE)
                            .addProperty(new AppSearchSchema.PropertyConfig.Builder(
                                    PLATFORM_HIDDEN_PROPERTY)
                                    .setDataType(AppSearchSchema.PropertyConfig.DATA_TYPE_STRING)
                                    .setCardinality(
                                            AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                                    .build())
                            .build()),
                    /*forceOverride=*/ false);
        }

        // Populate visibility settings map
        for (String database : mAppSearchImpl.getDatabases()) {
            if (database.equals(DATABASE_NAME)) {
                // Our own database. Skip
                continue;
            }

            try {
                // Note: We use the other clients' database names as uris
                GenericDocument document = mAppSearchImpl.getDocument(
                        DATABASE_NAME, NAMESPACE, /*uri=*/ database);

                String[] schemas = document.getPropertyStringArray(PLATFORM_HIDDEN_PROPERTY);
                mPlatformHiddenMap.put(database, new ArraySet<>(Arrays.asList(schemas)));
            } catch (AppSearchException e) {
                if (e.getResultCode() == AppSearchResult.RESULT_NOT_FOUND) {
                    // TODO(b/172068212): This indicates some desync error. We were expecting a
                    //  document, but didn't find one. Should probably reset AppSearch instead of
                    //  ignoring it.
                    continue;
                }
                // Otherwise, this is some other error we should pass up.
                throw e;
            }
        }
    }

    /**
     * Update visibility settings for the {@code databaseName}.
     *
     * @param schemasToRemove Database-prefixed schemas that should be removed
     */
    public void updateSchemas(@NonNull String databaseName,
            @NonNull Set<String> schemasToRemove) throws AppSearchException {
        Preconditions.checkNotNull(databaseName);
        Preconditions.checkNotNull(schemasToRemove);

        GenericDocument visibilityDocument;
        try {
            visibilityDocument = mAppSearchImpl.getDocument(
                    DATABASE_NAME, NAMESPACE, /*uri=*/ databaseName);
        } catch (AppSearchException e) {
            if (e.getResultCode() == AppSearchResult.RESULT_NOT_FOUND) {
                // This might be the first time we're seeing visibility changes for a database.
                // Create a new visibility document.
                mAppSearchImpl.putDocument(DATABASE_NAME, new GenericDocument.Builder(
                        /*uri=*/ databaseName, SCHEMA_TYPE)
                        .setNamespace(NAMESPACE).build());

                // Since we know there was nothing that existed before, we don't need to remove
                // anything either. Return early.
                return;
            }
            // Otherwise, this is some real error we should pass up.
            throw e;
        }

        String[] hiddenSchemas =
                visibilityDocument.getPropertyStringArray(PLATFORM_HIDDEN_PROPERTY);
        if (hiddenSchemas == null) {
            // Nothing to remove.
            return;
        }

        // Create a new set so we can remove from it.
        Set<String> remainingSchemas = new ArraySet<>(Arrays.asList(hiddenSchemas));
        boolean changed = remainingSchemas.removeAll(schemasToRemove);
        if (!changed) {
            // Nothing was actually removed. Can return early.
            return;
        }

        // Update our persisted document
        // TODO(b/171882200): Switch to a .toBuilder API when it's available.
        GenericDocument.Builder newVisibilityDocument = new GenericDocument.Builder(
                /*uri=*/ databaseName, SCHEMA_TYPE)
                .setNamespace(NAMESPACE);
        if (!remainingSchemas.isEmpty()) {
            newVisibilityDocument.setPropertyString(PLATFORM_HIDDEN_PROPERTY,
                    remainingSchemas.toArray(new String[0]));
        }
        mAppSearchImpl.putDocument(DATABASE_NAME, newVisibilityDocument.build());

        // Update derived data structures
        mPlatformHiddenMap.put(databaseName, remainingSchemas);
    }

    /**
     * Sets visibility settings for {@code databaseName}. Any previous visibility settings will be
     * overwritten.
     *
     * @param databaseName          Database name that owns the {@code platformHiddenSchemas}.
     * @param platformHiddenSchemas Set of database-qualified schemas that should be hidden from
     *                              the platform.
     * @throws AppSearchException on AppSearchImpl error.
     */
    public void setVisibility(@NonNull String databaseName,
            @NonNull Set<String> platformHiddenSchemas) throws AppSearchException {
        Preconditions.checkNotNull(databaseName);
        Preconditions.checkNotNull(platformHiddenSchemas);

        // Persist the document
        GenericDocument.Builder visibilityDocument = new GenericDocument.Builder(
                /*uri=*/ databaseName, SCHEMA_TYPE)
                .setNamespace(NAMESPACE);
        if (!platformHiddenSchemas.isEmpty()) {
            visibilityDocument.setPropertyString(PLATFORM_HIDDEN_PROPERTY,
                    platformHiddenSchemas.toArray(new String[0]));
        }
        mAppSearchImpl.putDocument(DATABASE_NAME, visibilityDocument.build());

        // Update derived data structures.
        mPlatformHiddenMap.put(databaseName, platformHiddenSchemas);
    }

    /**
     * Returns the set of database-qualified schemas in {@code databaseName} that are hidden from
     * the platform.
     *
     * @param databaseName Database name to retrieve schemas for
     * @return Set of database-qualified schemas that are hidden from the platform. Empty set if
     * none exist.
     */
    @NonNull
    public Set<String> getPlatformHiddenSchemas(@NonNull String databaseName) {
        Preconditions.checkNotNull(databaseName);
        Set<String> platformHiddenSchemas = mPlatformHiddenMap.get(databaseName);
        if (platformHiddenSchemas == null) {
            return Collections.emptySet();
        }
        return platformHiddenSchemas;
    }

    /**
     * Handles an {@link AppSearchImpl#reset()} by clearing any cached state and resetting to a
     * first-initialized state.
     *
     * @throws AppSearchException on AppSearchImpl error.
     */
    public void handleReset() throws AppSearchException {
        mPlatformHiddenMap.clear();
        initialize();
    }
}
