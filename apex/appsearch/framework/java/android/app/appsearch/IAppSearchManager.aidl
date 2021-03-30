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
import android.os.ParcelFileDescriptor;
import com.android.internal.infra.AndroidFuture;

parcelable SearchResults;

/** {@hide} */
interface IAppSearchManager {
    /**
     * Updates the AppSearch schema for this database.
     *
     * @param packageName The name of the package that owns this schema.
     * @param databaseName  The name of the database where this schema lives.
     * @param schemaBundles List of {@link AppSearchSchema} bundles.
     * @param schemasNotDisplayedBySystem Schema types that should not be surfaced on platform
     *     surfaces.
     * @param schemasPackageAccessibleBundles Schema types that are visible to the specified
     *     packages. The value List contains PackageIdentifier Bundles.
     * @param forceOverride Whether to apply the new schema even if it is incompatible. All
     *     incompatible documents will be deleted.
     * @param userId Id of the calling user
     * @param callback {@link IAppSearchResultCallback#onResult} will be called with an
     *     {@link AppSearchResult}&lt;{@link Bundle}&gt;, where the value are
     *     {@link SetSchemaResponse} bundle.
     */
    void setSchema(
        in String packageName,
        in String databaseName,
        in List<Bundle> schemaBundles,
        in List<String> schemasNotDisplayedBySystem,
        in Map<String, List<Bundle>> schemasPackageAccessibleBundles,
        boolean forceOverride,
        in int userId,
        in int schemaVersion,
        in IAppSearchResultCallback callback);

    /**
     * Retrieves the AppSearch schema for this database.
     *
     * @param packageName The name of the package that owns the schema.
     * @param databaseName  The name of the database to retrieve.
     * @param userId Id of the calling user
     * @param callback {@link IAppSearchResultCallback#onResult} will be called with an
     *     {@link AppSearchResult}&lt;{@link Bundle}&gt; where the bundle is a GetSchemaResponse.
     */
    void getSchema(
        in String packageName,
        in String databaseName,
        in int userId,
        in IAppSearchResultCallback callback);

    /**
     * Retrieves the set of all namespaces in the current database with at least one document.
     *
     * @param packageName The name of the package that owns the schema.
     * @param databaseName  The name of the database to retrieve.
     * @param userId Id of the calling user
     * @param callback {@link IAppSearchResultCallback#onResult} will be called with an
     *     {@link AppSearchResult}&lt;{@link List}&lt;{@link String}&gt;&gt;.
     */
    void getNamespaces(
        in String packageName,
        in String databaseName,
        in int userId,
        in IAppSearchResultCallback callback);

    /**
     * Inserts documents into the index.
     *
     * @param packageName The name of the package that owns this document.
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
        in String packageName,
        in String databaseName,
        in List<Bundle> documentBundles,
        in int userId,
        in IAppSearchBatchResultCallback callback);

    /**
     * Retrieves documents from the index.
     *
     * @param packageName The name of the package that owns this document.
     * @param databaseName  The databaseName this document resides in.
     * @param namespace    The namespace this document resides in.
     * @param uris The URIs of the documents to retrieve
     * @param typePropertyPaths A map of schema type to a list of property paths to return in the
     *     result.
     * @param userId Id of the calling user
     * @param callback
     *     If the call fails to start, {@link IAppSearchBatchResultCallback#onSystemError}
     *     will be called with the cause throwable. Otherwise,
     *     {@link IAppSearchBatchResultCallback#onResult} will be called with an
     *     {@link AppSearchBatchResult}&lt;{@link String}, {@link Bundle}&gt;
     *     where the keys are document URIs, and the values are Document bundles.
     */
    void getDocuments(
        in String packageName,
        in String databaseName,
        in String namespace,
        in List<String> uris,
        in Map<String, List<String>> typePropertyPaths,
        in int userId,
        in IAppSearchBatchResultCallback callback);

    /**
     * Searches a document based on a given specifications.
     *
     * @param packageName The name of the package to query over.
     * @param databaseName The databaseName this query for.
     * @param queryExpression String to search for
     * @param searchSpecBundle SearchSpec bundle
     * @param userId Id of the calling user
     * @param callback {@link AppSearchResult}&lt;{@link Bundle}&gt; of performing this
     *         operation.
     */
    void query(
        in String packageName,
        in String databaseName,
        in String queryExpression,
        in Bundle searchSpecBundle,
        in int userId,
        in IAppSearchResultCallback callback);

    /**
     * Executes a global query, i.e. over all permitted databases, against the AppSearch index and
     * returns results.
     *
     * @param packageName The name of the package making the query.
     * @param queryExpression String to search for
     * @param searchSpecBundle SearchSpec bundle
     * @param userId Id of the calling user
     * @param callback {@link AppSearchResult}&lt;{@link Bundle}&gt; of performing this
     *         operation.
     */
    void globalQuery(
        in String packageName,
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
    * Searches a document based on a given specifications.
    *
    * <p>Documents will be save to the given ParcelFileDescriptor
    *
    * @param packageName The name of the package to query over.
    * @param databaseName The databaseName this query for.
    * @param fileDescriptor The ParcelFileDescriptor where documents should be written to.
    * @param queryExpression String to search for.
    * @param searchSpecBundle SearchSpec bundle.
    * @param userId Id of the calling user.
    * @param callback {@link IAppSearchResultCallback#onResult} will be called with an
    *        {@link AppSearchResult}&lt;{@code null}&gt;.
    */
    void writeQueryResultsToFile(
        in String packageName,
        in String databaseName,
        in ParcelFileDescriptor fileDescriptor,
        in String queryExpression,
        in Bundle searchSpecBundle,
        in int userId,
        in IAppSearchResultCallback callback);

    /**
    * Inserts documents from the given file into the index.
    *
    * @param packageName The name of the package that owns this document.
    * @param databaseName  The name of the database where this document lives.
    * @param fileDescriptor The ParcelFileDescriptor where documents should be read from.
    * @param userId Id of the calling user.
    * @param callback {@link IAppSearchResultCallback#onResult} will be called with an
    *     {@link AppSearchResult}&lt;{@link List}&lt;{@link Bundle}&gt;&gt;, where the value are
    *     MigrationFailure bundles.
    */
    void putDocumentsFromFile(
        in String packageName,
        in String databaseName,
        in ParcelFileDescriptor fileDescriptor,
        in int userId,
        in IAppSearchResultCallback callback);

    /**
     * Reports usage of a particular document by URI and namespace.
     *
     * <p>A usage report represents an event in which a user interacted with or viewed a document.
     *
     * <p>For each call to {@link #reportUsage}, AppSearch updates usage count and usage recency
     * metrics for that particular document. These metrics are used for ordering {@link #query}
     * results by the {@link SearchSpec#RANKING_STRATEGY_USAGE_COUNT} and
     * {@link SearchSpec#RANKING_STRATEGY_USAGE_LAST_USED_TIMESTAMP} ranking strategies.
     *
     * <p>Reporting usage of a document is optional.
     *
     * @param packageName The name of the package that owns this document.
     * @param databaseName  The name of the database to report usage against.
     * @param namespace Namespace the document being used belongs to.
     * @param uri URI of the document being used.
     * @param usageTimeMillis The timestamp at which the document was used.
     * @param systemUsage Whether the usage was reported by a system app against another app's doc.
     * @param userId Id of the calling user
     * @param callback {@link IAppSearchResultCallback#onResult} will be called with an
     *     {@link AppSearchResult}&lt;{@link Void}&gt;.
     */
     void reportUsage(
         in String packageName,
         in String databaseName,
         in String namespace,
         in String uri,
         in long usageTimeMillis,
         in boolean systemUsage,
         in int userId,
         in IAppSearchResultCallback callback);

    /**
     * Removes documents by URI.
     *
     * @param packageName The name of the package the document is in.
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
        in String packageName,
        in String databaseName,
        in String namespace,
        in List<String> uris,
        in int userId,
        in IAppSearchBatchResultCallback callback);

    /**
     * Removes documents by given query.
     *
     * @param packageName The name of the package to query over.
     * @param databaseName The databaseName this query for.
     * @param queryExpression String to search for
     * @param searchSpecBundle SearchSpec bundle
     * @param userId Id of the calling user
     * @param callback {@link IAppSearchResultCallback#onResult} will be called with an
     *     {@link AppSearchResult}&lt;{@link Void}&gt;.
     */
    void removeByQuery(
        in String packageName,
        in String databaseName,
        in String queryExpression,
        in Bundle searchSpecBundle,
        in int userId,
        in IAppSearchResultCallback callback);

    /**
     * Gets the storage info.
     *
     * @param packageName The name of the package to get the storage info for.
     * @param databaseName The databaseName to get the storage info for.
     * @param userId Id of the calling user
     * @param callback {@link IAppSearchResultCallback#onResult} will be called with an
     *     {@link AppSearchResult}&lt;{@link Bundle}&gt;, where the value is a
     *     {@link StorageInfo}.
     */
    void getStorageInfo(
        in String packageName,
        in String databaseName,
        in int userId,
        in IAppSearchResultCallback callback);

    /**
     * Persists all update/delete requests to the disk.
     *
     * @param userId Id of the calling user
     */
    void persistToDisk(in int userId);

    /**
     * Creates and initializes AppSearchImpl for the calling app.
     *
     * @param userId Id of the calling user
     * @param callback {@link IAppSearchResultCallback#onResult} will be called with an
     *     {@link AppSearchResult}&lt;{@link Void}&gt;.
     */
    void initialize(in int userId, in IAppSearchResultCallback callback);
}
