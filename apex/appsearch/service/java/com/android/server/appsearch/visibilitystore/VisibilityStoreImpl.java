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

import static android.Manifest.permission.READ_GLOBAL_APP_SEARCH_DATA;

import android.annotation.NonNull;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.GetSchemaResponse;
import android.app.appsearch.PackageIdentifier;
import android.app.appsearch.exceptions.AppSearchException;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.server.appsearch.external.localstorage.AppSearchImpl;
import com.android.server.appsearch.external.localstorage.util.PrefixUtil;
import com.android.server.appsearch.external.localstorage.visibilitystore.VisibilityStore;
import com.android.server.appsearch.util.PackageUtil;

import com.google.android.icing.proto.PersistType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * @hide
 */
public class VisibilityStoreImpl implements VisibilityStore {
    /** Version for the visibility schema */
    private static final int SCHEMA_VERSION = 0;

    /** Namespace of documents that contain visibility settings */
    private static final String NAMESPACE = "";

    /** Prefix to add to all visibility document ids. IcingSearchEngine doesn't allow empty ids. */
    private static final String ID_PREFIX = "uri:";

    private final AppSearchImpl mAppSearchImpl;

    // Context of the user that the call is being made as.
    private final Context mUserContext;

    /** Stores the schemas that are platform-hidden. All values are prefixed. */
    private final NotDisplayedBySystemMap mNotDisplayedBySystemMap = new NotDisplayedBySystemMap();

    /** Stores the schemas that are visible to 3p packages. All values are prefixed. */
    private final VisibleToPackagesMap mVisibleToPackagesMap = new VisibleToPackagesMap();

    /**
     * Creates and initializes VisibilityStore.
     *
     * @param appSearchImpl AppSearchImpl instance
     * @param userContext Context of the user that the call is being made as
     */
    @NonNull
    public static VisibilityStoreImpl create(
            @NonNull AppSearchImpl appSearchImpl, @NonNull Context userContext)
            throws AppSearchException {
        return new VisibilityStoreImpl(appSearchImpl, userContext);
    }

    private VisibilityStoreImpl(@NonNull AppSearchImpl appSearchImpl, @NonNull Context userContext)
            throws AppSearchException {
        mAppSearchImpl = Objects.requireNonNull(appSearchImpl);
        mUserContext = Objects.requireNonNull(userContext);

        GetSchemaResponse getSchemaResponse = mAppSearchImpl.getSchema(PACKAGE_NAME, DATABASE_NAME);
        boolean hasVisibilityType = false;
        boolean hasVisibleToPackagesType = false;
        for (AppSearchSchema schema : getSchemaResponse.getSchemas()) {
            if (schema.getSchemaType().equals(VisibilityDocument.SCHEMA_TYPE)) {
                hasVisibilityType = true;
            } else if (schema.getSchemaType().equals(VisibleToPackagesDocument.SCHEMA_TYPE)) {
                hasVisibleToPackagesType = true;
            }

            if (hasVisibilityType && hasVisibleToPackagesType) {
                // Found both our types, can exit early.
                break;
            }
        }
        if (!hasVisibilityType || !hasVisibleToPackagesType) {
            // Schema type doesn't exist yet. Add it.
            mAppSearchImpl.setSchema(
                    PACKAGE_NAME,
                    DATABASE_NAME,
                    Arrays.asList(VisibilityDocument.SCHEMA, VisibleToPackagesDocument.SCHEMA),
                    /*visibilityStore=*/ null,  // Avoid recursive calls
                    /*schemasNotDisplayedBySystem=*/ Collections.emptyList(),
                    /*schemasVisibleToPackages=*/ Collections.emptyMap(),
                    /*forceOverride=*/ false,
                    /*version=*/ SCHEMA_VERSION);
        }

        // Populate visibility settings set
        for (Map.Entry<String, Set<String>> entry :
                mAppSearchImpl.getPackageToDatabases().entrySet()) {
            String packageName = entry.getKey();
            if (packageName.equals(PACKAGE_NAME)) {
                continue; // Our own package. Skip.
            }

            for (String databaseName : entry.getValue()) {
                VisibilityDocument visibilityDocument;
                try {
                    // Note: We use the other clients' prefixed names as ids
                    visibilityDocument =
                            new VisibilityDocument(
                                    mAppSearchImpl.getDocument(
                                            PACKAGE_NAME,
                                            DATABASE_NAME,
                                            NAMESPACE,
                                            /*id=*/ getVisibilityDocumentId(
                                                    packageName, databaseName),
                                            /*typePropertyPaths=*/ Collections.emptyMap()));
                } catch (AppSearchException e) {
                    if (e.getResultCode() == AppSearchResult.RESULT_NOT_FOUND) {
                        // TODO(b/172068212): This indicates some desync error. We were expecting a
                        //  document, but didn't find one. Should probably reset AppSearch instead
                        //  of ignoring it.
                        continue;
                    }
                    // Otherwise, this is some other error we should pass up.
                    throw e;
                }

                // Update platform visibility settings
                String[] notDisplayedBySystemSchemas = visibilityDocument.getNotDisplayedBySystem();
                if (notDisplayedBySystemSchemas != null) {
                    mNotDisplayedBySystemMap.setNotDisplayedBySystem(
                            packageName,
                            databaseName,
                            new ArraySet<>(notDisplayedBySystemSchemas));
                }

                // Update 3p package visibility settings
                Map<String, Set<PackageIdentifier>> schemaToPackageIdentifierMap = new ArrayMap<>();
                GenericDocument[] visibleToPackagesDocuments =
                        visibilityDocument.getVisibleToPackages();
                if (visibleToPackagesDocuments != null) {
                    for (int i = 0; i < visibleToPackagesDocuments.length; i++) {
                        VisibleToPackagesDocument visibleToPackagesDocument =
                                new VisibleToPackagesDocument(visibleToPackagesDocuments[i]);
                        PackageIdentifier packageIdentifier =
                                visibleToPackagesDocument.getPackageIdentifier();
                        String prefixedSchema = visibleToPackagesDocument.getAccessibleSchemaType();
                        Set<PackageIdentifier> packageIdentifiers =
                                schemaToPackageIdentifierMap.get(prefixedSchema);
                        if (packageIdentifiers == null) {
                            packageIdentifiers = new ArraySet<>();
                        }
                        packageIdentifiers.add(packageIdentifier);
                        schemaToPackageIdentifierMap.put(prefixedSchema, packageIdentifiers);
                    }
                }
                mVisibleToPackagesMap.setVisibleToPackages(
                        packageName, databaseName, schemaToPackageIdentifierMap);
            }
        }
    }

    @Override
    public void setVisibility(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull Set<String> schemasNotDisplayedBySystem,
            @NonNull Map<String, List<PackageIdentifier>> schemasVisibleToPackages)
            throws AppSearchException {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(databaseName);
        Objects.requireNonNull(schemasNotDisplayedBySystem);
        Objects.requireNonNull(schemasVisibleToPackages);

        // Persist the document
        VisibilityDocument.Builder visibilityDocument =
                new VisibilityDocument.Builder(
                        NAMESPACE, /*id=*/ getVisibilityDocumentId(packageName, databaseName));
        if (!schemasNotDisplayedBySystem.isEmpty()) {
            visibilityDocument.setNotDisplayedBySystem(
                    schemasNotDisplayedBySystem.toArray(new String[0]));
        }

        Map<String, Set<PackageIdentifier>> schemaToPackageIdentifierMap = new ArrayMap<>();
        List<VisibleToPackagesDocument> visibleToPackagesDocuments = new ArrayList<>();
        for (Map.Entry<String, List<PackageIdentifier>> entry :
                schemasVisibleToPackages.entrySet()) {
            for (int i = 0; i < entry.getValue().size(); i++) {
                VisibleToPackagesDocument visibleToPackagesDocument =
                        new VisibleToPackagesDocument.Builder(NAMESPACE, /*id=*/ "")
                                .setAccessibleSchemaType(entry.getKey())
                                .setPackageIdentifier(entry.getValue().get(i))
                                .build();
                visibleToPackagesDocuments.add(visibleToPackagesDocument);
            }
            schemaToPackageIdentifierMap.put(entry.getKey(), new ArraySet<>(entry.getValue()));
        }
        if (!visibleToPackagesDocuments.isEmpty()) {
            visibilityDocument.setVisibleToPackages(
                    visibleToPackagesDocuments.toArray(new VisibleToPackagesDocument[0]));
        }

        mAppSearchImpl.putDocument(
                PACKAGE_NAME, DATABASE_NAME, visibilityDocument.build(), /*logger=*/ null);
        // Now that the visibility document has been written. Persist the newly written data.
        mAppSearchImpl.persistToDisk(PersistType.Code.LITE);

        // Update derived data structures.
        mNotDisplayedBySystemMap.setNotDisplayedBySystem(
                packageName, databaseName, schemasNotDisplayedBySystem);
        mVisibleToPackagesMap.setVisibleToPackages(
                packageName, databaseName, schemaToPackageIdentifierMap);
    }

    /**
     * Checks whether the given package has access to system-surfaceable schemas.
     *
     * @param callerPackageName Package name of the caller.
     */
    public boolean doesCallerHaveSystemAccess(@NonNull String callerPackageName) {
        Objects.requireNonNull(callerPackageName);
        return mUserContext.getPackageManager()
                .checkPermission(READ_GLOBAL_APP_SEARCH_DATA, callerPackageName)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public boolean isSchemaSearchableByCaller(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull String prefixedSchema,
            int callerUid,
            boolean callerHasSystemAccess) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(databaseName);
        Objects.requireNonNull(prefixedSchema);

        if (packageName.equals(PACKAGE_NAME)) {
            return false; // VisibilityStore schemas are for internal bookkeeping.
        }

        if (callerHasSystemAccess
                && mNotDisplayedBySystemMap.isSchemaDisplayedBySystem(
                packageName, databaseName, prefixedSchema)) {
            return true;
        }

        // May not be platform surfaceable, but might still be accessible through 3p access.
        return isSchemaVisibleToPackages(packageName, databaseName, prefixedSchema, callerUid);
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
    private boolean isSchemaVisibleToPackages(
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull String prefixedSchema,
            int callerUid) {
        Set<PackageIdentifier> packageIdentifiers =
                mVisibleToPackagesMap.getAccessiblePackages(
                        packageName, databaseName, prefixedSchema);
        if (packageIdentifiers.isEmpty()) {
            return false;
        }
        for (PackageIdentifier packageIdentifier : packageIdentifiers) {
            // TODO(b/169883602): Consider caching the UIDs of packages. Looking this up in the
            // package manager could be costly. We would also need to update the cache on
            // package-removals.

            // 'callerUid' is the uid of the caller. The 'user' doesn't have to be the same one as
            // the callerUid since clients can createContextAsUser with some other user, and then
            // make calls to us. So just check if the appId portion of the uid is the same. This is
            // essentially UserHandle.isSameApp, but that's not a system API for us to use.
            int callerAppId = UserHandle.getAppId(callerUid);
            int packageUid =
                    PackageUtil.getPackageUid(mUserContext, packageIdentifier.getPackageName());
            int userAppId = UserHandle.getAppId(packageUid);
            if (callerAppId != userAppId) {
                continue;
            }

            // Check that the package also has the matching certificate
            if (mUserContext
                    .getPackageManager()
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
     * Adds a prefix to create a visibility store document's id.
     *
     * @param packageName Package to which the visibility doc refers
     * @param databaseName Database to which the visibility doc refers
     * @return Prefixed id
     */
    @NonNull
    private static String getVisibilityDocumentId(
            @NonNull String packageName, @NonNull String databaseName) {
        return ID_PREFIX + PrefixUtil.createPrefix(packageName, databaseName);
    }
}
