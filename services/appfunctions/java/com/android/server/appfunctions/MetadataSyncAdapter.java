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

import android.annotation.NonNull;
import android.annotation.WorkerThread;
import android.app.appsearch.PropertyPath;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchSpec;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appfunctions.FutureAppSearchSession.FutureSearchResults;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

/**
 * This class implements helper methods for synchronously interacting with AppSearch while
 * synchronizing AppFunction runtime and static metadata.
 */
public class MetadataSyncAdapter {
    private final FutureAppSearchSession mFutureAppSearchSession;
    private final Executor mSyncExecutor;

    public MetadataSyncAdapter(
            @NonNull Executor syncExecutor,
            @NonNull FutureAppSearchSession futureAppSearchSession) {
        mSyncExecutor = Objects.requireNonNull(syncExecutor);
        mFutureAppSearchSession = Objects.requireNonNull(futureAppSearchSession);
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
            ArrayMap<String, ArraySet<String>> staticPackageToFunctionMap,
            ArrayMap<String, ArraySet<String>> runtimePackageToFunctionMap) {
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
            ArrayMap<String, ArraySet<String>> staticPackageToFunctionMap,
            ArrayMap<String, ArraySet<String>> runtimePackageToFunctionMap) {
        return getFunctionsDiffMap(runtimePackageToFunctionMap, staticPackageToFunctionMap);
    }

    @NonNull
    private static ArrayMap<String, ArraySet<String>> getFunctionsDiffMap(
            ArrayMap<String, ArraySet<String>> packageToFunctionMapA,
            ArrayMap<String, ArraySet<String>> packageToFunctionMapB) {
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
     * @param schemaType The name space of the AppFunction metadata.
     * @return A map of package names to a set of function ids from the AppFunction metadata.
     */
    @NonNull
    @VisibleForTesting
    @WorkerThread
    ArrayMap<String, ArraySet<String>> getPackageToFunctionIdMap(
            @NonNull String schemaType,
            @NonNull String propertyFunctionId,
            @NonNull String propertyPackageName)
            throws ExecutionException, InterruptedException {
        ArrayMap<String, ArraySet<String>> packageToFunctionIds = new ArrayMap<>();

        FutureSearchResults futureSearchResults =
                mFutureAppSearchSession
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
        return new SearchSpec.Builder()
                .addFilterSchemas(schemaType)
                .addProjectionPaths(
                        schemaType,
                        List.of(
                                new PropertyPath(propertyFunctionId),
                                new PropertyPath(propertyPackageName)))
                .build();
    }
}
