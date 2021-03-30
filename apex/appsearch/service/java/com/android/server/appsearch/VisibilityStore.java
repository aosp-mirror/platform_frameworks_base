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

// TODO(b/169883602): This is purposely a different package from the path so that it can access
// AppSearchImpl's methods without having to make them public. This should be moved into a proper
// package once AppSearchImpl-VisibilityStore's dependencies are refactored.
package com.android.server.appsearch.external.localstorage;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.GetSchemaResponse;
import android.app.appsearch.PackageIdentifier;
import android.app.appsearch.exceptions.AppSearchException;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

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
 *
 * @hide
 */
public class VisibilityStore {

    private static final String TAG = "AppSearchVisibilityStore";

    /** No-op user id that won't have any visibility settings. */
    public static final int NO_OP_USER_ID = -1;

    /** Schema type for documents that hold AppSearch's metadata, e.g. visibility settings */
    private static final String VISIBILITY_TYPE = "VisibilityType";

    /** Version for the visibility schema */
    private static final int SCHEMA_VERSION = 0;

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
                            new AppSearchSchema.StringPropertyConfig.Builder(
                                            NOT_PLATFORM_SURFACEABLE_PROPERTY)
                                    .setCardinality(
                                            AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                                    .build())
                    .addProperty(
                            new AppSearchSchema.DocumentPropertyConfig.Builder(
                                            PACKAGE_ACCESSIBLE_PROPERTY)
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
                            new AppSearchSchema.StringPropertyConfig.Builder(PACKAGE_NAME_PROPERTY)
                                    .setCardinality(
                                            AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                    .build())
                    .addProperty(
                            new AppSearchSchema.BytesPropertyConfig.Builder(SHA_256_CERT_PROPERTY)
                                    .setCardinality(
                                            AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                    .build())
                    .addProperty(
                            new AppSearchSchema.StringPropertyConfig.Builder(
                                            ACCESSIBLE_SCHEMA_PROPERTY)
                                    .setCardinality(
                                            AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                    .build())
                    .build();

    /**
     * These cannot have any of the special characters used by AppSearchImpl (e.g. {@code
     * AppSearchImpl#PACKAGE_DELIMITER} or {@code AppSearchImpl#DATABASE_DELIMITER}.
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
    private static final String NAMESPACE = "";

    /**
     * Prefix to add to all visibility document uri's. IcingSearchEngine doesn't allow empty uri's.
     */
    private static final String URI_PREFIX = "uri:";

    private final AppSearchImpl mAppSearchImpl;

    // Context of the system service.
    private final Context mContext;

    // User ID of the caller who we're checking visibility settings for.
    private final int mUserId;

    // UID of the package that has platform-query privileges, i.e. can query for all
    // platform-surfaceable content.
    private int mGlobalQuerierUid;

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
    public VisibilityStore(
            @NonNull AppSearchImpl appSearchImpl,
            @NonNull Context context,
            @UserIdInt int userId,
            @NonNull String globalQuerierPackage) {
        mAppSearchImpl = appSearchImpl;
        mContext = context;
        mUserId = userId;
        mGlobalQuerierUid = getGlobalQuerierUid(globalQuerierPackage);
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
        GetSchemaResponse getSchemaResponse = mAppSearchImpl.getSchema(PACKAGE_NAME, DATABASE_NAME);
        boolean hasVisibilityType = false;
        boolean hasPackageAccessibleType = false;
        for (AppSearchSchema schema : getSchemaResponse.getSchemas()) {
            if (schema.getSchemaType().equals(VISIBILITY_TYPE)) {
                hasVisibilityType = true;
            } else if (schema.getSchemaType().equals(PACKAGE_ACCESSIBLE_TYPE)) {
                hasPackageAccessibleType = true;
            }

            if (hasVisibilityType && hasPackageAccessibleType) {
                // Found both our types, can exit early.
                break;
            }
        }
        if (!hasVisibilityType || !hasPackageAccessibleType) {
            // Schema type doesn't exist yet. Add it.
            mAppSearchImpl.setSchema(
                    PACKAGE_NAME,
                    DATABASE_NAME,
                    Arrays.asList(VISIBILITY_SCHEMA, PACKAGE_ACCESSIBLE_SCHEMA),
                    /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                    /*schemasPackageAccessible=*/ Collections.emptyMap(),
                    /*forceOverride=*/ false,
                    /*version=*/ SCHEMA_VERSION);
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
                                /*uri=*/ addUriPrefix(prefix),
                                /*typePropertyPaths=*/ Collections.emptyMap());

                // Update platform visibility settings
                String[] notPlatformSurfaceableSchemas =
                        document.getPropertyStringArray(NOT_PLATFORM_SURFACEABLE_PROPERTY);
                if (notPlatformSurfaceableSchemas != null) {
                    mNotPlatformSurfaceableMap.put(
                            prefix, new ArraySet<>(Arrays.asList(notPlatformSurfaceableSchemas)));
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
        GenericDocument.Builder<?> visibilityDocument =
                new GenericDocument.Builder<>(
                        NAMESPACE, /*uri=*/ addUriPrefix(prefix), VISIBILITY_TYPE);
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
                GenericDocument packageAccessibleDocument = new GenericDocument.Builder<>(
                        NAMESPACE, /*uri=*/ "", PACKAGE_ACCESSIBLE_TYPE)
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

        mAppSearchImpl.putDocument(
                PACKAGE_NAME, DATABASE_NAME, visibilityDocument.build(), /*logger=*/ null);

        // Update derived data structures.
        mNotPlatformSurfaceableMap.put(prefix, schemasNotPlatformSurfaceable);
        mPackageAccessibleMap.put(prefix, schemaToPackageIdentifierMap);
    }

    /** Checks whether {@code prefixedSchema} can be searched over by the {@code callerUid}. */
    public boolean isSchemaSearchableByCaller(
            @NonNull String prefix, @NonNull String prefixedSchema, int callerUid) {
        Preconditions.checkNotNull(prefix);
        Preconditions.checkNotNull(prefixedSchema);

        // We compare appIds here rather than direct uids because the package's uid may change based
        // on the user that's running.
        if (UserHandle.isSameApp(mGlobalQuerierUid, callerUid)
                && isSchemaPlatformSurfaceable(prefix, prefixedSchema)) {
            return true;
        }

        // May not be platform surfaceable, but might still be accessible through 3p access.
        return isSchemaPackageAccessible(prefix, prefixedSchema, callerUid);
    }

    /**
     * Returns whether the caller has platform query privileges, and if so, that the schema is
     * surfaceable on the platform.
     */
    private boolean isSchemaPlatformSurfaceable(
            @NonNull String prefix, @NonNull String prefixedSchema) {
        if (prefix.equals(VISIBILITY_STORE_PREFIX)) {
            // VisibilityStore schemas are for internal bookkeeping.
            return false;
        }

        Set<String> notPlatformSurfaceableSchemas = mNotPlatformSurfaceableMap.get(prefix);
        if (notPlatformSurfaceableSchemas == null) {
            // No schemas were opted out of being platform-surfaced. So by default, it can be
            // surfaced.
            return true;
        }

        // Some schemas were opted out of being platform-surfaced. As long as this schema
        // isn't one of those opt-outs, it's surfaceable.
        return !notPlatformSurfaceableSchemas.contains(prefixedSchema);
    }

    /**
     * Returns whether the schema is accessible by the {@code callerUid}. Checks that the callerUid
     * has one of the allowed PackageIdentifier's package. And if so, that the package also has the
     * matching certificate.
     *
     * <p>This supports packages that have certificate rotation. As long as the specified
     * certificate was once used to sign the package, the package will still be granted access. This
     * does not handle packages that have been signed by multiple certificates.
     */
    private boolean isSchemaPackageAccessible(
            @NonNull String prefix, @NonNull String prefixedSchema, int callerUid) {
        Map<String, Set<PackageIdentifier>> schemaToPackageIdentifierMap =
                mPackageAccessibleMap.get(prefix);
        if (schemaToPackageIdentifierMap == null) {
            // No schemas under this prefix have granted package access, return early.
            return false;
        }

        Set<PackageIdentifier> packageIdentifiers =
                schemaToPackageIdentifierMap.get(prefixedSchema);
        if (packageIdentifiers == null) {
            // No package identifiers were granted access for this schema, return early.
            return false;
        }

        for (PackageIdentifier packageIdentifier : packageIdentifiers) {
            // Check that the caller uid matches this allowlisted PackageIdentifier.
            // TODO(b/169883602): Consider caching the UIDs of packages. Looking this up in the
            // package manager could be costly. We would also need to update the cache on
            // package-removals.
            if (getPackageUidAsUser(packageIdentifier.getPackageName()) != callerUid) {
                continue;
            }

            // Check that the package also has the matching certificate
            if (mContext.getPackageManager()
                    .hasSigningCertificate(
                            packageIdentifier.getPackageName(),
                            packageIdentifier.getSha256Certificate(),
                            PackageManager.CERT_INPUT_SHA256)) {
                // The caller has the right package name and right certificate!
                return true;
            }
        }

        // If we can't verify the schema is package accessible, default to no access.
        return false;
    }

    /**
     * Handles an {@code AppSearchImpl#reset()} by clearing any cached state.
     *
     * <p>{@link #initialize()} must be called after this.
     */
    public void handleReset() {
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

    /**
     * Finds the uid of the {@code globalQuerierPackage}. {@code globalQuerierPackage} must be a
     * pre-installed, system app. Returns {@link Process#INVALID_UID} if unable to find the UID.
     */
    private int getGlobalQuerierUid(@NonNull String globalQuerierPackage) {
        try {
            int flags =
                    PackageManager.MATCH_DISABLED_COMPONENTS
                            | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
                            | PackageManager.MATCH_SYSTEM_ONLY;
            // It doesn't matter that we're using the caller's userId here. We'll eventually check
            // that the two uids in question belong to the same appId.
            return mContext.getPackageManager()
                    .getPackageUidAsUser(globalQuerierPackage, flags, mUserId);
        } catch (PackageManager.NameNotFoundException e) {
            // Global querier doesn't exist.
            Log.i(
                    TAG,
                    "AppSearch global querier package not found on device:  '"
                            + globalQuerierPackage
                            + "'");
        }
        return Process.INVALID_UID;
    }

    /**
     * Finds the UID of the {@code packageName}. Returns {@link Process#INVALID_UID} if unable to
     * find the UID.
     */
    private int getPackageUidAsUser(@NonNull String packageName) {
        try {
            return mContext.getPackageManager().getPackageUidAsUser(packageName, mUserId);
        } catch (PackageManager.NameNotFoundException e) {
            // Package doesn't exist, continue
        }
        return Process.INVALID_UID;
    }
}
