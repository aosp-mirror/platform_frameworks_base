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
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.exceptions.AppSearchException;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
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
 * documents and has its own database so that its data doesn't interfere with any clients' data. It
 * persists the document and schema through AppSearchImpl.
 *
 * <p>These visibility settings are used to ensure AppSearch queries respect the clients' settings
 * on who their data is visible to.
 *
 * <p>This class doesn't handle any locking itself. Its callers should handle the locking at a
 * higher level.
 *
 * <p>NOTE: This class holds an instance of AppSearchImpl and AppSearchImpl holds an instance of
 * this class. Take care to not cause any circular dependencies.
 */
class VisibilityStore {
    /** Schema type for documents that hold AppSearch's metadata, e.g. visibility settings */
    @VisibleForTesting static final String SCHEMA_TYPE = "Visibility";

    /**
     * Property that holds the list of platform-hidden schemas, as part of the visibility settings.
     */
    @VisibleForTesting
    static final String NOT_PLATFORM_SURFACEABLE_PROPERTY = "notPlatformSurfaceable";

    /** Schema for the VisibilityStore's docuemnts. */
    @VisibleForTesting
    static final AppSearchSchema SCHEMA =
            new AppSearchSchema.Builder(SCHEMA_TYPE)
                    .addProperty(
                            new AppSearchSchema.PropertyConfig.Builder(
                                            NOT_PLATFORM_SURFACEABLE_PROPERTY)
                                    .setDataType(AppSearchSchema.PropertyConfig.DATA_TYPE_STRING)
                                    .setCardinality(
                                            AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                                    .build())
                    .build();

    /**
     * These cannot have any of the special characters used by AppSearchImpl (e.g. {@link
     * AppSearchImpl#PACKAGE_DELIMITER} or {@link AppSearchImpl#DATABASE_DELIMITER}.
     */
    static final String PACKAGE_NAME = "VS#Pkg";

    static final String DATABASE_NAME = "VS#Db";

    /**
     * Prefix that AppSearchImpl creates for the VisibilityStore based on our package name and
     * database name. Tracked here to tell when we're looking at our own prefix when looking through
     * AppSearchImpl.
     */
    private static final String VISIBILITY_STORE_PREFIX =
            AppSearchImpl.createPrefix(PACKAGE_NAME, DATABASE_NAME);

    /** Namespace of documents that contain visibility settings */
    private static final String NAMESPACE = GenericDocument.DEFAULT_NAMESPACE;

    /**
     * Prefix to add to all visibility document uri's. IcingSearchEngine doesn't allow empty uri's.
     */
    private static final String URI_PREFIX = "uri:";

    private final AppSearchImpl mAppSearchImpl;

    /**
     * Maps prefixes to the set of schemas that are platform-hidden within that prefix. All schemas
     * in the map are prefixed.
     */
    private final Map<String, Set<String>> mNotPlatformSurfaceableMap = new ArrayMap<>();

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
     * {@link AppSearchImpl#setSchema} will call {@link #setVisibility(String, Set)}. We need to
     * have both AppSearchImpl and VisibilityStore fully initialized for this call flow to work.
     *
     * @throws AppSearchException AppSearchException on AppSearchImpl error.
     */
    public void initialize() throws AppSearchException {
        if (!mAppSearchImpl.hasSchemaTypeLocked(PACKAGE_NAME, DATABASE_NAME, SCHEMA_TYPE)) {
            // Schema type doesn't exist yet. Add it.
            mAppSearchImpl.setSchema(
                    PACKAGE_NAME,
                    DATABASE_NAME,
                    Collections.singletonList(SCHEMA),
                    /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                    /*forceOverride=*/ false);
        }

        // Populate visibility settings set
        mNotPlatformSurfaceableMap.clear();
        for (String prefix : mAppSearchImpl.getPrefixesLocked()) {
            if (prefix.equals(VISIBILITY_STORE_PREFIX)) {
                // Our own prefix. Skip
                continue;
            }

            try {
                // Note: We use the other clients' prefixed names as uris
                GenericDocument document =
                        mAppSearchImpl.getDocument(
                                PACKAGE_NAME,
                                DATABASE_NAME,
                                NAMESPACE,
                                /*uri=*/ addUriPrefix(prefix));

                String[] schemas =
                        document.getPropertyStringArray(NOT_PLATFORM_SURFACEABLE_PROPERTY);
                mNotPlatformSurfaceableMap.put(prefix, new ArraySet<>(Arrays.asList(schemas)));
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
     * Sets visibility settings for {@code prefix}. Any previous visibility settings will be
     * overwritten.
     *
     * @param prefix Prefix that identifies who owns the {@code schemasNotPlatformSurfaceable}.
     * @param schemasNotPlatformSurfaceable Set of prefixed schemas that should be hidden from the
     *     platform.
     * @throws AppSearchException on AppSearchImpl error.
     */
    public void setVisibility(
            @NonNull String prefix, @NonNull Set<String> schemasNotPlatformSurfaceable)
            throws AppSearchException {
        Preconditions.checkNotNull(prefix);
        Preconditions.checkNotNull(schemasNotPlatformSurfaceable);

        // Persist the document
        GenericDocument.Builder visibilityDocument =
                new GenericDocument.Builder(/*uri=*/ addUriPrefix(prefix), SCHEMA_TYPE)
                        .setNamespace(NAMESPACE);
        if (!schemasNotPlatformSurfaceable.isEmpty()) {
            visibilityDocument.setPropertyString(
                    NOT_PLATFORM_SURFACEABLE_PROPERTY,
                    schemasNotPlatformSurfaceable.toArray(new String[0]));
        }
        mAppSearchImpl.putDocument(PACKAGE_NAME, DATABASE_NAME, visibilityDocument.build());

        // Update derived data structures.
        mNotPlatformSurfaceableMap.put(prefix, schemasNotPlatformSurfaceable);
    }

    /** Returns if the schema is surfaceable by the platform. */
    @NonNull
    public boolean isSchemaPlatformSurfaceable(
            @NonNull String prefix, @NonNull String prefixedSchema) {
        Preconditions.checkNotNull(prefix);
        Preconditions.checkNotNull(prefixedSchema);
        Set<String> notPlatformSurfaceableSchemas = mNotPlatformSurfaceableMap.get(prefix);
        if (notPlatformSurfaceableSchemas == null) {
            return true;
        }
        return !notPlatformSurfaceableSchemas.contains(prefixedSchema);
    }

    /**
     * Handles an {@link AppSearchImpl#reset()} by clearing any cached state and resetting to a
     * first-initialized state.
     *
     * @throws AppSearchException on AppSearchImpl error.
     */
    public void handleReset() throws AppSearchException {
        mNotPlatformSurfaceableMap.clear();
        initialize();
    }

    /**
     * Adds a uri prefix to create a visibility store document's uri.
     *
     * @param uri Non-prefixed uri
     * @return Prefixed uri
     */
    private static String addUriPrefix(String uri) {
        return URI_PREFIX + uri;
    }
}
