/*
 * Copyright (C) 2020 The Android Open Source Project
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


import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.RemoteException;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * This class provides global access to the centralized AppSearch index maintained by the system.
 *
 * <p>Apps can retrieve indexed documents through the query API.
 * @hide
 */
@SystemApi
public class GlobalSearchSession {

    private final IAppSearchManager mService;

    static void createGlobalSearchSession(
            @NonNull IAppSearchManager service,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<AppSearchResult<GlobalSearchSession>> callback) {
        GlobalSearchSession globalSearchSession = new GlobalSearchSession(service);
        globalSearchSession.initialize(executor, callback);
    }

    // NOTE: No instance of this class should be created or returned except via initialize().
    // Once the callback.accept has been called here, the class is ready to use.
    private void initialize(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<AppSearchResult<GlobalSearchSession>> callback) {
        try {
            mService.initialize(new IAppSearchResultCallback.Stub() {
                public void onResult(AppSearchResult result) {
                    executor.execute(() -> {
                        if (result.isSuccess()) {
                            callback.accept(
                                    AppSearchResult.newSuccessfulResult(GlobalSearchSession.this));
                        } else {
                            callback.accept(result);
                        }
                    });
                }
            });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private GlobalSearchSession(@NonNull IAppSearchManager service) {
        mService = service;
    }

    /**
     * Searches across all documents in the storage based on a given query string.
     *
     * <p>Currently we support following features in the raw query format:
     * <ul>
     *     <li>AND
     *     <p>AND joins (e.g. “match documents that have both the terms ‘dog’ and
     *     ‘cat’”).
     *     Example: hello world matches documents that have both ‘hello’ and ‘world’
     *     <li>OR
     *     <p>OR joins (e.g. “match documents that have either the term ‘dog’ or
     *     ‘cat’”).
     *     Example: dog OR puppy
     *     <li>Exclusion
     *     <p>Exclude a term (e.g. “match documents that do
     *     not have the term ‘dog’”).
     *     Example: -dog excludes the term ‘dog’
     *     <li>Grouping terms
     *     <p>Allow for conceptual grouping of subqueries to enable hierarchical structures (e.g.
     *     “match documents that have either ‘dog’ or ‘puppy’, and either ‘cat’ or ‘kitten’”).
     *     Example: (dog puppy) (cat kitten) two one group containing two terms.
     *     <li>Property restricts
     *     <p> Specifies which properties of a document to specifically match terms in (e.g.
     *     “match documents where the ‘subject’ property contains ‘important’”).
     *     Example: subject:important matches documents with the term ‘important’ in the
     *     ‘subject’ property
     *     <li>Schema type restricts
     *     <p>This is similar to property restricts, but allows for restricts on top-level document
     *     fields, such as schema_type. Clients should be able to limit their query to documents of
     *     a certain schema_type (e.g. “match documents that are of the ‘Email’ schema_type”).
     *     Example: { schema_type_filters: “Email”, “Video”,query: “dog” } will match documents
     *     that contain the query term ‘dog’ and are of either the ‘Email’ schema type or the
     *     ‘Video’ schema type.
     * </ul>
     *
     * <p> This method is lightweight. The heavy work will be done in
     * {@link SearchResults#getNextPage}.
     *
     * @param queryExpression Query String to search.
     * @param searchSpec      Spec for setting filters, raw query etc.
     * @param executor        Executor on which to invoke the callback of the following request
     *                        {@link SearchResults#getNextPage}.
     * @return The search result of performing this operation.
     */
    @NonNull
    public SearchResults query(
            @NonNull String queryExpression,
            @NonNull SearchSpec searchSpec,
            @NonNull @CallbackExecutor Executor executor) {
        Objects.requireNonNull(queryExpression);
        Objects.requireNonNull(searchSpec);
        Objects.requireNonNull(executor);
        return new SearchResults(mService, /*databaseName=*/null, queryExpression,
                searchSpec, executor);
    }
}
