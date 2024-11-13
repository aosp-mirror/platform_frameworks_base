/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.appfunctions;

import static android.app.appfunctions.AppFunctionRuntimeMetadata.RUNTIME_SCHEMA_TYPE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.app.appfunctions.AppFunctionRuntimeMetadata;
import android.app.appfunctions.AppFunctionStaticMetadataHelper;
import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchManager.SearchContext;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.PackageIdentifier;
import android.app.appsearch.PropertyPath;
import android.app.appsearch.PutDocumentsRequest;
import android.app.appsearch.RemoveByDocumentIdRequest;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaRequest;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.infra.AndroidFuture;
import com.android.server.appfunctions.FutureAppSearchSession.FutureSearchResults;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * This class implements helper methods for synchronously interacting with AppSearch while
 * synchronizing AppFunction runtime and static metadata.
 *
 * <p>This class is not thread safe.
 */
public class MetadataSyncAdapter {
    private static final String TAG = MetadataSyncAdapter.class.getSimpleName();

    private final ExecutorService mExecutor;

    private final AppSearchManager mAppSearchManager;
    private final PackageManager mPackageManager;
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private Future<?> mCurrentSyncTask;

    // Hidden constants in {@link SetSchemaRequest} that restricts runtime metadata visibility
    // by permissions.
    public static final int EXECUTE_APP_FUNCTIONS = 9;
    public static final int EXECUTE_APP_FUNCTIONS_TRUSTED = 10;

    public MetadataSyncAdapter(
            @NonNull PackageManager packageManager, @NonNull AppSearchManager appSearchManager) {
        mPackageManager = Objects.requireNonNull(packageManager);
        mAppSearchManager = Objects.requireNonNull(appSearchManager);
        mExecutor = Executors.newSingleThreadExecutor();
    }

    /**
     * This method submits a request to synchronize the AppFunction runtime and static metadata.
     *
     * @return A {@link AndroidFuture} that completes with a boolean value indicating whether the
     *     synchronization was successful.
     */
    public AndroidFuture<Boolean> submitSyncRequest() {
        SearchContext staticMetadataSearchContext =
                new SearchContext.Builder(
                                AppFunctionStaticMetadataHelper.APP_FUNCTION_STATIC_METADATA_DB)
                        .build();
        SearchContext runtimeMetadataSearchContext =
                new SearchContext.Builder(
                                AppFunctionRuntimeMetadata.APP_FUNCTION_RUNTIME_METADATA_DB)
                        .build();
        AndroidFuture<Boolean> settableSyncStatus = new AndroidFuture<>();
        Runnable runnable =
                () -> {
                    try (FutureAppSearchSession staticMetadataSearchSession =
                                    new FutureAppSearchSessionImpl(
                                            mAppSearchManager,
                                            AppFunctionExecutors.THREAD_POOL_EXECUTOR,
                                            staticMetadataSearchContext);
                            FutureAppSearchSession runtimeMetadataSearchSession =
                                    new FutureAppSearchSessionImpl(
                                            mAppSearchManager,
                                            AppFunctionExecutors.THREAD_POOL_EXECUTOR,
                                            runtimeMetadataSearchContext)) {

                        trySyncAppFunctionMetadataBlocking(
                                staticMetadataSearchSession, runtimeMetadataSearchSession);
                        settableSyncStatus.complete(true);

                    } catch (Exception ex) {
                        settableSyncStatus.completeExceptionally(ex);
                    }
                };

        synchronized (mLock) {
            if (mCurrentSyncTask != null && !mCurrentSyncTask.isDone()) {
                var unused = mCurrentSyncTask.cancel(false);
            }
            mCurrentSyncTask = mExecutor.submit(runnable);
        }

        return settableSyncStatus;
    }

    /** This method shuts down the {@link MetadataSyncAdapter} scheduler. */
    public void shutDown() {
        mExecutor.shutdown();
    }

    @WorkerThread
    @VisibleForTesting
    void trySyncAppFunctionMetadataBlocking(
            @NonNull FutureAppSearchSession staticMetadataSearchSession,
            @NonNull FutureAppSearchSession runtimeMetadataSearchSession)
            throws ExecutionException, InterruptedException {
        ArrayMap<String, ArraySet<String>> staticPackageToFunctionMap =
                getPackageToFunctionIdMap(
                        staticMetadataSearchSession,
                        AppFunctionStaticMetadataHelper.STATIC_SCHEMA_TYPE,
                        AppFunctionStaticMetadataHelper.PROPERTY_FUNCTION_ID,
                        AppFunctionStaticMetadataHelper.PROPERTY_PACKAGE_NAME);
        ArrayMap<String, ArraySet<String>> runtimePackageToFunctionMap =
                getPackageToFunctionIdMap(
                        runtimeMetadataSearchSession,
                        RUNTIME_SCHEMA_TYPE,
                        AppFunctionRuntimeMetadata.PROPERTY_FUNCTION_ID,
                        AppFunctionRuntimeMetadata.PROPERTY_PACKAGE_NAME);

        ArrayMap<String, ArraySet<String>> addedFunctionsDiffMap =
                getAddedFunctionsDiffMap(staticPackageToFunctionMap, runtimePackageToFunctionMap);
        ArrayMap<String, ArraySet<String>> removedFunctionsDiffMap =
                getRemovedFunctionsDiffMap(staticPackageToFunctionMap, runtimePackageToFunctionMap);

        if (!staticPackageToFunctionMap.keySet().equals(runtimePackageToFunctionMap.keySet())) {
            // Drop removed packages from removedFunctionsDiffMap, as setSchema() deletes them
            ArraySet<String> removedPackages =
                    getRemovedPackages(
                            staticPackageToFunctionMap.keySet(), removedFunctionsDiffMap.keySet());
            for (String packageName : removedPackages) {
                removedFunctionsDiffMap.remove(packageName);
            }
            Set<AppSearchSchema> appRuntimeMetadataSchemas =
                    getAllRuntimeMetadataSchemas(staticPackageToFunctionMap.keySet());
            appRuntimeMetadataSchemas.add(
                    AppFunctionRuntimeMetadata.createParentAppFunctionRuntimeSchema());
            SetSchemaRequest addSetSchemaRequest =
                    buildSetSchemaRequestForRuntimeMetadataSchemas(
                            mPackageManager, appRuntimeMetadataSchemas);
            Objects.requireNonNull(
                    runtimeMetadataSearchSession.setSchema(addSetSchemaRequest).get());
        }

        if (!removedFunctionsDiffMap.isEmpty()) {
            RemoveByDocumentIdRequest removeByDocumentIdRequest =
                    buildRemoveRuntimeMetadataRequest(removedFunctionsDiffMap);
            AppSearchBatchResult<String, Void> removeDocumentBatchResult =
                    runtimeMetadataSearchSession.remove(removeByDocumentIdRequest).get();
            if (!removeDocumentBatchResult.isSuccess()) {
                throw convertFailedAppSearchResultToException(
                        removeDocumentBatchResult.getFailures().values());
            }
        }

        if (!addedFunctionsDiffMap.isEmpty()) {
            PutDocumentsRequest putDocumentsRequest =
                    buildPutRuntimeMetadataRequest(addedFunctionsDiffMap);
            AppSearchBatchResult<String, Void> putDocumentBatchResult =
                    runtimeMetadataSearchSession.put(putDocumentsRequest).get();
            if (!putDocumentBatchResult.isSuccess()) {
                throw convertFailedAppSearchResultToException(
                        putDocumentBatchResult.getFailures().values());
            }
        }
    }

    @NonNull
    private static IllegalStateException convertFailedAppSearchResultToException(
            @NonNull Collection<AppSearchResult<Void>> appSearchResult) {
        Objects.requireNonNull(appSearchResult);
        StringBuilder errorMessages = new StringBuilder();
        for (AppSearchResult<Void> result : appSearchResult) {
            errorMessages.append(result.getErrorMessage());
        }
        return new IllegalStateException(errorMessages.toString());
    }

    @NonNull
    private PutDocumentsRequest buildPutRuntimeMetadataRequest(
            @NonNull ArrayMap<String, ArraySet<String>> addedFunctionsDiffMap) {
        Objects.requireNonNull(addedFunctionsDiffMap);
        PutDocumentsRequest.Builder putDocumentRequestBuilder = new PutDocumentsRequest.Builder();

        for (int i = 0; i < addedFunctionsDiffMap.size(); i++) {
            String packageName = addedFunctionsDiffMap.keyAt(i);
            ArraySet<String> addedFunctionIds = addedFunctionsDiffMap.valueAt(i);
            for (String addedFunctionId : addedFunctionIds) {
                putDocumentRequestBuilder.addGenericDocuments(
                        new AppFunctionRuntimeMetadata.Builder(packageName, addedFunctionId)
                                .build());
            }
        }
        return putDocumentRequestBuilder.build();
    }

    @NonNull
    private RemoveByDocumentIdRequest buildRemoveRuntimeMetadataRequest(
            @NonNull ArrayMap<String, ArraySet<String>> removedFunctionsDiffMap) {
        Objects.requireNonNull(AppFunctionRuntimeMetadata.APP_FUNCTION_RUNTIME_NAMESPACE);
        Objects.requireNonNull(removedFunctionsDiffMap);
        RemoveByDocumentIdRequest.Builder removeDocumentRequestBuilder =
                new RemoveByDocumentIdRequest.Builder(
                        AppFunctionRuntimeMetadata.APP_FUNCTION_RUNTIME_NAMESPACE);

        for (int i = 0; i < removedFunctionsDiffMap.size(); i++) {
            String packageName = removedFunctionsDiffMap.keyAt(i);
            ArraySet<String> removedFunctionIds = removedFunctionsDiffMap.valueAt(i);
            for (String functionId : removedFunctionIds) {
                String documentId =
                        AppFunctionRuntimeMetadata.getDocumentIdForAppFunction(
                                packageName, functionId);
                removeDocumentRequestBuilder.addIds(documentId);
            }
        }
        return removeDocumentRequestBuilder.build();
    }

    @NonNull
    private SetSchemaRequest buildSetSchemaRequestForRuntimeMetadataSchemas(
            @NonNull PackageManager packageManager,
            @NonNull Set<AppSearchSchema> metadataSchemaSet) {
        Objects.requireNonNull(metadataSchemaSet);
        SetSchemaRequest.Builder setSchemaRequestBuilder =
                new SetSchemaRequest.Builder().setForceOverride(true).addSchemas(metadataSchemaSet);

        for (AppSearchSchema runtimeMetadataSchema : metadataSchemaSet) {
            String packageName =
                    AppFunctionRuntimeMetadata.getPackageNameFromSchema(
                            runtimeMetadataSchema.getSchemaType());
            byte[] packageCert = getCertificate(packageManager, packageName);
            if (packageCert == null) {
                continue;
            }
            setSchemaRequestBuilder.setSchemaTypeVisibilityForPackage(
                    runtimeMetadataSchema.getSchemaType(),
                    true,
                    new PackageIdentifier(packageName, packageCert));
            setSchemaRequestBuilder.addRequiredPermissionsForSchemaTypeVisibility(
                    runtimeMetadataSchema.getSchemaType(), Set.of(EXECUTE_APP_FUNCTIONS));
            setSchemaRequestBuilder.addRequiredPermissionsForSchemaTypeVisibility(
                    runtimeMetadataSchema.getSchemaType(), Set.of(EXECUTE_APP_FUNCTIONS_TRUSTED));
        }
        return setSchemaRequestBuilder.build();
    }

    @NonNull
    @WorkerThread
    private Set<AppSearchSchema> getAllRuntimeMetadataSchemas(
            @NonNull Set<String> staticMetadataPackages) {
        Objects.requireNonNull(staticMetadataPackages);

        Set<AppSearchSchema> appRuntimeMetadataSchemas = new ArraySet<>();
        for (String packageName : staticMetadataPackages) {
            appRuntimeMetadataSchemas.add(
                    AppFunctionRuntimeMetadata.createAppFunctionRuntimeSchema(packageName));
        }

        return appRuntimeMetadataSchemas;
    }

    /**
     * This method returns a set of packages that are in the removed function packages but not in
     * the all existing static packages.
     *
     * @param allExistingStaticPackages A set of all existing static metadata packages.
     * @param removedFunctionPackages A set of all removed function packages.
     * @return A set of packages that are in the removed function packages but not in the all
     *     existing static packages.
     */
    @NonNull
    private static ArraySet<String> getRemovedPackages(
            @NonNull Set<String> allExistingStaticPackages,
            @NonNull Set<String> removedFunctionPackages) {
        ArraySet<String> removedPackages = new ArraySet<>();

        for (String packageName : removedFunctionPackages) {
            if (!allExistingStaticPackages.contains(packageName)) {
                removedPackages.add(packageName);
            }
        }

        return removedPackages;
    }

    /**
     * This method returns a map of package names to a set of function ids that are in the static
     * metadata but not in the runtime metadata.
     *
     * @param staticPackageToFunctionMap A map of package names to a set of function ids from the
     *     static metadata.
     * @param runtimePackageToFunctionMap A map of package names to a set of function ids from the
     *     runtime metadata.
     * @return A map of package names to a set of function ids that are in the static metadata but
     *     not in the runtime metadata.
     */
    @NonNull
    @VisibleForTesting
    static ArrayMap<String, ArraySet<String>> getAddedFunctionsDiffMap(
            @NonNull ArrayMap<String, ArraySet<String>> staticPackageToFunctionMap,
            @NonNull ArrayMap<String, ArraySet<String>> runtimePackageToFunctionMap) {
        Objects.requireNonNull(staticPackageToFunctionMap);
        Objects.requireNonNull(runtimePackageToFunctionMap);

        return getFunctionsDiffMap(staticPackageToFunctionMap, runtimePackageToFunctionMap);
    }

    /**
     * This method returns a map of package names to a set of function ids that are in the runtime
     * metadata but not in the static metadata.
     *
     * @param staticPackageToFunctionMap A map of package names to a set of function ids from the
     *     static metadata.
     * @param runtimePackageToFunctionMap A map of package names to a set of function ids from the
     *     runtime metadata.
     * @return A map of package names to a set of function ids that are in the runtime metadata but
     *     not in the static metadata.
     */
    @NonNull
    @VisibleForTesting
    static ArrayMap<String, ArraySet<String>> getRemovedFunctionsDiffMap(
            @NonNull ArrayMap<String, ArraySet<String>> staticPackageToFunctionMap,
            @NonNull ArrayMap<String, ArraySet<String>> runtimePackageToFunctionMap) {
        Objects.requireNonNull(staticPackageToFunctionMap);
        Objects.requireNonNull(runtimePackageToFunctionMap);

        return getFunctionsDiffMap(runtimePackageToFunctionMap, staticPackageToFunctionMap);
    }

    @NonNull
    private static ArrayMap<String, ArraySet<String>> getFunctionsDiffMap(
            @NonNull ArrayMap<String, ArraySet<String>> packageToFunctionMapA,
            @NonNull ArrayMap<String, ArraySet<String>> packageToFunctionMapB) {
        Objects.requireNonNull(packageToFunctionMapA);
        Objects.requireNonNull(packageToFunctionMapB);

        ArrayMap<String, ArraySet<String>> diffMap = new ArrayMap<>();
        for (String packageName : packageToFunctionMapA.keySet()) {
            if (!packageToFunctionMapB.containsKey(packageName)) {
                diffMap.put(packageName, packageToFunctionMapA.get(packageName));
                continue;
            }
            ArraySet<String> diffFunctions = new ArraySet<>();
            for (String functionId :
                    Objects.requireNonNull(packageToFunctionMapA.get(packageName))) {
                if (!Objects.requireNonNull(packageToFunctionMapB.get(packageName))
                        .contains(functionId)) {
                    diffFunctions.add(functionId);
                }
            }
            if (!diffFunctions.isEmpty()) {
                diffMap.put(packageName, diffFunctions);
            }
        }
        return diffMap;
    }

    /**
     * This method returns a map of package names to a set of function ids from the AppFunction
     * metadata.
     *
     * @param searchSession The {@link FutureAppSearchSession} to search the AppFunction metadata.
     * @param schemaType The schema type of the AppFunction metadata.
     * @param propertyFunctionId The property name of the function id in the AppFunction metadata.
     * @param propertyPackageName The property name of the package name in the AppFunction metadata.
     * @return A map of package names to a set of function ids from the AppFunction metadata.
     */
    @NonNull
    @VisibleForTesting
    @WorkerThread
    static ArrayMap<String, ArraySet<String>> getPackageToFunctionIdMap(
            @NonNull FutureAppSearchSession searchSession,
            @NonNull String schemaType,
            @NonNull String propertyFunctionId,
            @NonNull String propertyPackageName)
            throws ExecutionException, InterruptedException {
        Objects.requireNonNull(schemaType);
        Objects.requireNonNull(propertyFunctionId);
        Objects.requireNonNull(propertyPackageName);
        ArrayMap<String, ArraySet<String>> packageToFunctionIds = new ArrayMap<>();

        FutureSearchResults futureSearchResults =
                searchSession
                        .search(
                                "",
                                buildMetadataSearchSpec(
                                        schemaType, propertyFunctionId, propertyPackageName))
                        .get();
        List<SearchResult> searchResultsList = futureSearchResults.getNextPage().get();
        // TODO(b/357551503): This could be expensive if we have more functions
        while (!searchResultsList.isEmpty()) {
            for (SearchResult searchResult : searchResultsList) {
                String packageName =
                        searchResult.getGenericDocument().getPropertyString(propertyPackageName);
                String functionId =
                        searchResult.getGenericDocument().getPropertyString(propertyFunctionId);
                packageToFunctionIds
                        .computeIfAbsent(packageName, k -> new ArraySet<>())
                        .add(functionId);
            }
            searchResultsList = futureSearchResults.getNextPage().get();
        }
        return packageToFunctionIds;
    }

    /**
     * This method returns a {@link SearchSpec} for searching the AppFunction metadata.
     *
     * @param schemaType The schema type of the AppFunction metadata.
     * @param propertyFunctionId The property name of the function id in the AppFunction metadata.
     * @param propertyPackageName The property name of the package name in the AppFunction metadata.
     * @return A {@link SearchSpec} for searching the AppFunction metadata.
     */
    @NonNull
    private static SearchSpec buildMetadataSearchSpec(
            @NonNull String schemaType,
            @NonNull String propertyFunctionId,
            @NonNull String propertyPackageName) {
        Objects.requireNonNull(schemaType);
        Objects.requireNonNull(propertyFunctionId);
        Objects.requireNonNull(propertyPackageName);
        return new SearchSpec.Builder()
                .addFilterSchemas(schemaType)
                .addProjectionPaths(
                        schemaType,
                        List.of(
                                new PropertyPath(propertyFunctionId),
                                new PropertyPath(propertyPackageName)))
                .build();
    }

    /** Gets the SHA-256 certificate from a {@link PackageManager}, or null if it is not found. */
    @Nullable
    private byte[] getCertificate(
            @NonNull PackageManager packageManager, @NonNull String packageName) {
        Objects.requireNonNull(packageManager);
        Objects.requireNonNull(packageName);
        PackageInfo packageInfo;
        try {
            packageInfo =
                    Objects.requireNonNull(
                            packageManager.getPackageInfo(
                                    packageName,
                                    PackageManager.GET_META_DATA
                                            | PackageManager.GET_SIGNING_CERTIFICATES));
        } catch (Exception e) {
            Slog.d(TAG, "Package name info not found for package: " + packageName);
            return null;
        }
        if (packageInfo.signingInfo == null) {
            Slog.d(TAG, "Signing info not found for package: " + packageInfo.packageName);
            return null;
        }

        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA256");
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
        Signature[] signatures = packageInfo.signingInfo.getSigningCertificateHistory();
        if (signatures == null || signatures.length == 0) {
            return null;
        }
        md.update(signatures[0].toByteArray());
        return md.digest();
    }
}
