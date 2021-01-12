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
import android.app.appsearch.PackageIdentifier;
import android.app.appsearch.exceptions.AppSearchException;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages any visibility settings for all the package's databases that AppSearchImpl knows about.
 * Persists the visibility settings and reloads them on initialization.
 *
 * <p>The VisibilityStore creates a document for each package's databases. This document holds the
 * visibility settings that apply to that package's database. The VisibilityStore also creates a
 * schema for these documents and has its own package and database so that its data doesn't
 * interfere with any clients' data. It persists the document and schema through AppSearchImpl.
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
    @VisibleForTesting static final String VISIBILITY_TYPE = "VisibilityType";

    /**
     * Property that holds the list of platform-hidden schemas, as part of the visibility settings.
     */
    private static final String NOT_PLATFORM_SURFACEABLE_PROPERTY = "notPlatformSurfaceable";

    /** Property that holds nested documents of package accessible schemas. */
    private static final String PACKAGE_ACCESSIBLE_PROPERTY = "packageAccessible";

    /** Schema type for nested documents that hold package accessible information. */
    private static final String PACKAGE_ACCESSIBLE_TYPE = "PackageAccessibleType";

    /** Property that holds the package name that can access a schema. */
    private static final String PACKAGE_NAME_PROPERTY = "packageName";

    /** Property that holds the SHA 256 certificate of the app that can access a schema. */
    private static final String SHA_256_CERT_PROPERTY = "sha256Cert";

    /** Property that holds the prefixed schema type that is accessible by some package. */
    private static final String ACCESSIBLE_SCHEMA_PROPERTY = "accessibleSchema";

    /** Schema for the VisibilityStore's documents. */
    private static final AppSearchSchema VISIBILITY_SCHEMA =
            new AppSearchSchema.Builder(VISIBILITY_TYPE)
                    .addProperty(
                            new AppSearchSchema.PropertyConfig.Builder(
                                            NOT_PLATFORM_SURFACEABLE_PROPERTY)
                                    .setDataType(AppSearchSchema.PropertyConfig.DATA_TYPE_STRING)
                                    .setCardinality(
                                            AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                                    .build())
                    .addProperty(
                            new AppSearchSchema.PropertyConfig.Builder(PACKAGE_ACCESSIBLE_PROPERTY)
                                    .setDataType(AppSearchSchema.PropertyConfig.DATA_TYPE_DOCUMENT)
                                    .setSchemaType(PACKAGE_ACCESSIBLE_TYPE)
                                    .setCardinality(
                                            AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                                    .build())
                    .build();

    /**
     * Schema for package accessible documents, these will be nested in a top-level visibility
     * document.
     */
    private static final AppSearchSchema PACKAGE_ACCESSIBLE_SCHEMA =
            new AppSearchSchema.Builder(PACKAGE_ACCESSIBLE_TYPE)
                    .addProperty(
                            new AppSearchSchema.PropertyConfig.Builder(PACKAGE_NAME_PROPERTY)
                                    .setCardinality(
                                            AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                    .setDataType(AppSearchSchema.PropertyConfig.DATA_TYPE_STRING)
                                    .build())
                    .addProperty(
                            new AppSearchSchema.PropertyConfig.Builder(SHA_256_CERT_PROPERTY)
                                    .setCardinality(
                                            AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                    .setDataType(AppSearchSchema.PropertyConfig.DATA_TYPE_BYTES)
                                    .build())
                    .addProperty(
                            new AppSearchSchema.PropertyConfig.Builder(ACCESSIBLE_SCHEMA_PROPERTY)
                                    .setCardinality(
                                            AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                    .setDataType(AppSearchSchema.PropertyConfig.DATA_TYPE_STRING)
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
    static final String VISIBILITY_STORE_PREFIX =
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
     *
     * <p>Although the prefix key isn't used for lookup, it's helpful in ensuring that all previous
     * visibility settings for a prefix are completely overridden by new visibility settings.
     */
    private final Map<String, Set<String>> mNotPlatformSurfaceableMap = new ArrayMap<>();

    /**
     * Maps prefixes to a an internal map. The internal map maps prefixed schemas to the set of
     * PackageIdentifiers that have access to that schema.
     *
     * <p>Although the prefix key isn't used for lookup, it's helpful in ensuring that all previous
     * visibility settings for a prefix are completely overridden by new visibility settings.
     */
    private final Map<String, Map<String, Set<PackageIdentifier>>> mPackageAccessibleMap =
            new ArrayMap<>();

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
     * {@link AppSearchImpl#setSchema} will call {@link #setVisibility}. We need to have both
     * AppSearchImpl and VisibilityStore fully initialized for this call flow to work.
     *
     * @throws AppSearchException AppSearchException on AppSearchImpl error.
     */
    public void initialize() throws AppSearchException {
        if (!mAppSearchImpl.hasSchemaTypeLocked(PACKAGE_NAME, DATABASE_NAME, VISIBILITY_TYPE)
                || !mAppSearchImpl.hasSchemaTypeLocked(
                        PACKAGE_NAME, DATABASE_NAME, PACKAGE_ACCESSIBLE_TYPE)) {
            // Schema type doesn't exist yet. Add it.
            mAppSearchImpl.setSchema(
                    PACKAGE_NAME,
                    DATABASE_NAME,
                    Arrays.asList(VISIBILITY_SCHEMA, PACKAGE_ACCESSIBLE_SCHEMA),
                    /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                    /*schemasPackageAccessible=*/ Collections.emptyMap(),
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

                // Update platform visibility settings
                String[] schemas =
                        document.getPropertyStringArray(NOT_PLATFORM_SURFACEABLE_PROPERTY);
                if (schemas != null) {
                    mNotPlatformSurfaceableMap.put(prefix, new ArraySet<>(Arrays.asList(schemas)));
                }

                // Update 3p package visibility settings
                Map<String, Set<PackageIdentifier>> schemaToPackageIdentifierMap = new ArrayMap<>();
                GenericDocument[] packageAccessibleDocuments =
                        document.getPropertyDocumentArray(PACKAGE_ACCESSIBLE_PROPERTY);
                if (packageAccessibleDocuments != null) {
                    for (int i = 0; i < packageAccessibleDocuments.length; i++) {
                        String packageName =
                                packageAccessibleDocuments[i].getPropertyString(
                                        PACKAGE_NAME_PROPERTY);
                        byte[] sha256Cert =
                                packageAccessibleDocuments[i].getPropertyBytes(
                                        SHA_256_CERT_PROPERTY);
                        PackageIdentifier packageIdentifier =
                                new PackageIdentifier(packageName, sha256Cert);

                        String prefixedSchema =
                                packageAccessibleDocuments[i].getPropertyString(
                                        ACCESSIBLE_SCHEMA_PROPERTY);
                        Set<PackageIdentifier> packageIdentifiers =
                                schemaToPackageIdentifierMap.get(prefixedSchema);
                        if (packageIdentifiers == null) {
                            packageIdentifiers = new ArraySet<>();
                        }
                        packageIdentifiers.add(packageIdentifier);
                        schemaToPackageIdentifierMap.put(prefixedSchema, packageIdentifiers);
                    }
                }
                mPackageAccessibleMap.put(prefix, schemaToPackageIdentifierMap);
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
     * @param schemasPackageAccessible Map of prefixed schemas to a list of package identifiers that
     *     have access to the schema.
     * @throws AppSearchException on AppSearchImpl error.
     */
    public void setVisibility(
            @NonNull String prefix,
            @NonNull Set<String> schemasNotPlatformSurfaceable,
            @NonNull Map<String, List<PackageIdentifier>> schemasPackageAccessible)
            throws AppSearchException {
        Preconditions.checkNotNull(prefix);
        Preconditions.checkNotNull(schemasNotPlatformSurfaceable);
        Preconditions.checkNotNull(schemasPackageAccessible);

        // Persist the document
        GenericDocument.Builder visibilityDocument =
                new GenericDocument.Builder(/*uri=*/ addUriPrefix(prefix), VISIBILITY_TYPE)
                        .setNamespace(NAMESPACE);
        if (!schemasNotPlatformSurfaceable.isEmpty()) {
            visibilityDocument.setPropertyString(
                    NOT_PLATFORM_SURFACEABLE_PROPERTY,
                    schemasNotPlatformSurfaceable.toArray(new String[0]));
        }

        Map<String, Set<PackageIdentifier>> schemaToPackageIdentifierMap = new ArrayMap<>();
        List<GenericDocument> packageAccessibleDocuments = new ArrayList<>();
        for (Map.Entry<String, List<PackageIdentifier>> entry :
                schemasPackageAccessible.entrySet()) {
            for (int i = 0; i < entry.getValue().size(); i++) {
                // TODO(b/169883602): remove the "placeholder" uri once upstream changes to relax
                // nested
                // document uri rules gets synced down.
                GenericDocument packageAccessibleDocument =
                        new GenericDocument.Builder(/*uri=*/ "placeholder", PACKAGE_ACCESSIBLE_TYPE)
                                .setNamespace(NAMESPACE)
                                .setPropertyString(
                                        PACKAGE_NAME_PROPERTY,
                                        entry.getValue().get(i).getPackageName())
                                .setPropertyBytes(
                                        SHA_256_CERT_PROPERTY,
                                        entry.getValue().get(i).getSha256Certificate())
                                .setPropertyString(ACCESSIBLE_SCHEMA_PROPERTY, entry.getKey())
                                .build();
                packageAccessibleDocuments.add(packageAccessibleDocument);
            }
            schemaToPackageIdentifierMap.put(entry.getKey(), new ArraySet<>(entry.getValue()));
        }
        if (!packageAccessibleDocuments.isEmpty()) {
            visibilityDocument.setPropertyDocument(
                    PACKAGE_ACCESSIBLE_PROPERTY,
                    packageAccessibleDocuments.toArray(new GenericDocument[0]));
        }

        mAppSearchImpl.putDocument(PACKAGE_NAME, DATABASE_NAME, visibilityDocument.build());

        // Update derived data structures.
        mNotPlatformSurfaceableMap.put(prefix, schemasNotPlatformSurfaceable);
        mPackageAccessibleMap.put(prefix, schemaToPackageIdentifierMap);
    }

    /** Returns if the schema is surfaceable by the platform. */
    // TODO(b/169883602): check permissions against the allowlisted global querier package name.
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

    /** Returns whether the schema is accessible by {@code accessingPackage}. */
    // TODO(b/169883602): check certificate and package against the incoming querier's uid/package.
    public boolean isSchemaPackageAccessible(
            @NonNull String prefix,
            @NonNull String prefixedSchema,
            @NonNull PackageIdentifier accessingPackage) {
        Preconditions.checkNotNull(prefix);
        Preconditions.checkNotNull(prefixedSchema);
        Preconditions.checkNotNull(accessingPackage);

        Map<String, Set<PackageIdentifier>> schemaToPackageIdentifierMap =
                mPackageAccessibleMap.get(prefix);
        if (schemaToPackageIdentifierMap == null) {
            return false;
        }

        Set<PackageIdentifier> packageIdentifiers =
                schemaToPackageIdentifierMap.get(prefixedSchema);
        if (packageIdentifiers == null) {
            return false;
        }

        return packageIdentifiers.contains(accessingPackage);
    }

    /**
     * Handles an {@code AppSearchImpl#reset()} by clearing any cached state.
     *
     * <p>{@link #initialize()} must be called after this.
     */
    void handleReset() {
        mNotPlatformSurfaceableMap.clear();
        mPackageAccessibleMap.clear();
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
