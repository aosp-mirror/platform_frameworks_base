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

import com.android.internal.infra.AndroidFuture;

parcelable AppSearchResult;
parcelable AppSearchBatchResult;
parcelable SearchResults;

/** {@hide} */
interface IAppSearchManager {
    /**
     * Sets the schema.
     *
     * @param databaseName  The databaseName this document resides in.
     * @param schemaBundles List of AppSearchSchema bundles.
     * @param forceOverride Whether to apply the new schema even if it is incompatible. All
     *     incompatible documents will be deleted.
     * @param callback {@link AndroidFuture}&lt;{@link AppSearchResult}&lt;{@link Void}&gt&gt;.
     *     The results of the call.
     */
    void setSchema(
        in String databaseName,
        in List<Bundle> schemaBundles,
        boolean forceOverride,
        in AndroidFuture<AppSearchResult> callback);

    /**
     * Inserts documents into the index.
     *
     * @param databaseName  The name of the database where this document lives.
     * @param documentBundes List of GenericDocument bundles.
     * @param callback
     *     {@link AndroidFuture}&lt;{@link AppSearchBatchResult}&lt;{@link String}, {@link Void}&gt;&gt;.
     *     If the call fails to start, {@code callback} will be completed exceptionally. Otherwise,
     *     {@code callback} will be completed with an
     *     {@link AppSearchBatchResult}&lt;{@link String}, {@link Void}&gt;
     *     where the keys are document URIs, and the values are {@code null}.
     */
    void putDocuments(
        in String databaseName,
        in List<Bundle> documentBundles,
        in AndroidFuture<AppSearchBatchResult> callback);

    /**
     * Retrieves documents from the index.
     *
     * @param databaseName  The databaseName this document resides in.
     * @param namespace    The namespace this document resides in.
     * @param uris The URIs of the documents to retrieve
     * @param callback
     *     {@link AndroidFuture}&lt;{@link AppSearchBatchResult}&lt;{@link String}, {@link Bundle}&gt;&gt;.
     *     If the call fails to start, {@code callback} will be completed exceptionally. Otherwise,
     *     {@code callback} will be completed with an
     *     {@link AppSearchBatchResult}&lt;{@link String}, {@link Bundle}&gt;
     *     where the keys are document URIs, and the values are Document bundles.
     */
    void getDocuments(
        in String databaseName,
        in String namespace,
        in List<String> uris,
        in AndroidFuture<AppSearchBatchResult> callback);

    /**
     * Searches a document based on a given specifications.
     *
     * @param databaseName The databaseName this query for.
     * @param queryExpression String to search for
     * @param searchSpecBundle SearchSpec bundle
     * @param callback {@link AndroidFuture}&lt;{@link AppSearchResult}&lt;{@link SearchResults}&gt;&gt;
     */
    void query(
        in String databaseName,
        in String queryExpression,
        in Bundle searchSpecBundle,
        in AndroidFuture<AppSearchResult> callback);

    /**
     * Removes documents by URI.
     *
     * @param databaseName The databaseName the document is in.
     * @param namespace    Namespace of the document to remove.
     * @param uris The URIs of the documents to delete
     * @param callback
     *     {@link AndroidFuture}&lt;{@link AppSearchBatchResult}&lt;{@link String}, {@link Void}&gt;&gt;.
     *     If the call fails to start, {@code callback} will be completed exceptionally. Otherwise,
     *     {@code callback} will be completed with an
     *     {@link AppSearchBatchResult}&lt;{@link String}, {@link Void}&gt;
     *     where the keys are document URIs. If a document doesn't exist, it will be reported as a
     *     failure where the {@code throwable} is {@code null}.
     */
    void removeByUri(
        in String databaseName,
        in String namespace,
        in List<String> uris,
        in AndroidFuture<AppSearchBatchResult> callback);

    /**
     * Removes documents by given query.
     *
     * @param databaseName The databaseName this query for.
     * @param queryExpression String to search for
     * @param searchSpecBundle SearchSpec bundle
     * @param callback {@link AndroidFuture}&lt;{@link AppSearchResult}&lt;{@link SearchResults}&gt;&gt;
     */
    void removeByQuery(
        in String databaseName,
        in String queryExpression,
        in Bundle searchSpecBundle,
        in AndroidFuture<AppSearchResult> callback);
}
