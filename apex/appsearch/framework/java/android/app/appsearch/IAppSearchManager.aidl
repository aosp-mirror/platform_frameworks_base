/**
 * Copyright 2020, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.app.appsearch;

import android.os.Bundle;

import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.IAppSearchBatchResultCallback;
import android.app.appsearch.IAppSearchResultCallback;
import com.android.internal.infra.AndroidFuture;

parcelable SearchResults;

/** {@hide} */
interface IAppSearchManager {
    /**
     * Updates the AppSearch schema for this database.
     *
     * @param databaseName  The name of the database where this schema lives.
     * @param schemaBundles List of {@link AppSearchSchema} bundles.
     * @param schemasNotPlatformSurfaceable Schema types that should not be surfaced on platform
     *     surfaces.
     * @param forceOverride Whether to apply the new schema even if it is incompatible. All
     *     incompatible documents will be deleted.
     * @param userId Id of the calling user
     * @param callback {@link IAppSearchResultCallback#onResult} will be called with an
     *     {@link AppSearchResult}&lt;{@link Void}&gt;.
     */
    void setSchema(
        in String databaseName,
        in List<Bundle> schemaBundles,
        in List<String> schemasNotPlatformSurfaceable,
        boolean forceOverride,
        in int userId,
        in IAppSearchResultCallback callback);


    /**
     * Retrieves the AppSearch schema for this database.
     *
     * @param databaseName  The name of the database to retrieve.
     * @param userId Id of the calling user
     * @param callback {@link IAppSearchResultCallback#onResult} will be called with an
     *     {@link AppSearchResult}&lt;{@link List}&lt;{@link Bundle}&gt;&gt;, where the value are
     *     AppSearchSchema bundle.
     */
    void getSchema(in String databaseName, in int userId, in IAppSearchResultCallback callback);

    /**
     * Inserts documents into the index.
     *
     * @param databaseName  The name of the database where this document lives.
     * @param documentBundes List of GenericDocument bundles.
     * @param userId Id of the calling user
     * @param callback
     *     If the call fails to start, {@link IAppSearchBatchResultCallback#onSystemError}
     *     will be called with the cause throwable. Otherwise,
     *     {@link IAppSearchBatchResultCallback#onResult} will be called with an
     *     {@link AppSearchBatchResult}&lt;{@link String}, {@link Void}&gt;
     *     where the keys are document URIs, and the values are {@code null}.
     */
    void putDocuments(
        in String databaseName,
        in List<Bundle> documentBundles,
        in int userId,
        in IAppSearchBatchResultCallback callback);

    /**
     * Retrieves documents from the index.
     *
     * @param databaseName  The databaseName this document resides in.
     * @param namespace    The namespace this document resides in.
     * @param uris The URIs of the documents to retrieve
     * @param userId Id of the calling user
     * @param callback
     *     If the call fails to start, {@link IAppSearchBatchResultCallback#onSystemError}
     *     will be called with the cause throwable. Otherwise,
     *     {@link IAppSearchBatchResultCallback#onResult} will be called with an
     *     {@link AppSearchBatchResult}&lt;{@link String}, {@link Bundle}&gt;
     *     where the keys are document URIs, and the values are Document bundles.
     */
    void getDocuments(
        in String databaseName,
        in String namespace,
        in List<String> uris,
        in int userId,
        in IAppSearchBatchResultCallback callback);

    /**
     * Searches a document based on a given specifications.
     *
     * @param databaseName The databaseName this query for.
     * @param queryExpression String to search for
     * @param searchSpecBundle SearchSpec bundle
     * @param userId Id of the calling user
     * @param callback {@link AppSearchResult}&lt;{@link Bundle}&gt; of performing this
     *         operation.
     */
    void query(
        in String databaseName,
        in String queryExpression,
        in Bundle searchSpecBundle,
        in int userId,
        in IAppSearchResultCallback callback);

    /**
     * Executes a global query, i.e. over all permitted databases, against the AppSearch index and
     * returns results.
     *
     * @param queryExpression String to search for
     * @param searchSpecBundle SearchSpec bundle
     * @param userId Id of the calling user
     * @param callback {@link AppSearchResult}&lt;{@link Bundle}&gt; of performing this
     *         operation.
     */
    void globalQuery(
        in String queryExpression,
        in Bundle searchSpecBundle,
        in int userId,
        in IAppSearchResultCallback callback);

    /**
     * Fetches the next page of results of a previously executed query. Results can be empty if
     * next-page token is invalid or all pages have been returned.
     *
     * @param nextPageToken The token of pre-loaded results of previously executed query.
     * @param userId Id of the calling user
     * @param callback {@link AppSearchResult}&lt;{@link Bundle}&gt; of performing this
     *                  operation.
     */
    void getNextPage(in long nextPageToken, in int userId, in IAppSearchResultCallback callback);

    /**
     * Invalidates the next-page token so that no more results of the related query can be returned.
     *
     * @param nextPageToken The token of pre-loaded results of previously executed query to be
     *                      Invalidated.
     * @param userId Id of the calling user
     */
    void invalidateNextPageToken(in long nextPageToken, in int userId);

    /**
     * Removes documents by URI.
     *
     * @param databaseName The databaseName the document is in.
     * @param namespace    Namespace of the document to remove.
     * @param uris The URIs of the documents to delete
     * @param userId Id of the calling user
     * @param callback
     *     If the call fails to start, {@link IAppSearchBatchResultCallback#onSystemError}
     *     will be called with the cause throwable. Otherwise,
     *     {@link IAppSearchBatchResultCallback#onResult} will be called with an
     *     {@link AppSearchBatchResult}&lt;{@link String}, {@link Void}&gt;
     *     where the keys are document URIs. If a document doesn't exist, it will be reported as a
     *     failure where the {@code throwable} is {@code null}.
     */
    void removeByUri(
        in String databaseName,
        in String namespace,
        in List<String> uris,
        in int userId,
        in IAppSearchBatchResultCallback callback);

    /**
     * Removes documents by given query.
     *
     * @param databaseName The databaseName this query for.
     * @param queryExpression String to search for
     * @param searchSpecBundle SearchSpec bundle
     * @param userId Id of the calling user
     * @param callback {@link IAppSearchResultCallback#onResult} will be called with an
     *     {@link AppSearchResult}&lt;{@link Void}&gt;.
     */
    void removeByQuery(
        in String databaseName,
        in String queryExpression,
        in Bundle searchSpecBundle,
        in int userId,
        in IAppSearchResultCallback callback);

    /**
     * Creates and initializes AppSearchImpl for the calling app.
     *
     * @param userId Id of the calling user
     * @param callback {@link IAppSearchResultCallback#onResult} will be called with an
     *     {@link AppSearchResult}&lt;{@link Void}&gt;.
     */
    void initialize(in int userId, in IAppSearchResultCallback callback);
}
