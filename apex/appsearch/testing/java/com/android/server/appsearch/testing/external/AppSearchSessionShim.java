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

package android.app.appsearch;

import android.annotation.NonNull;
import android.annotation.SuppressLint;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.Closeable;
import java.util.Set;

/**
 * Represents a connection to an AppSearch storage system where {@link GenericDocument}s can be
 * placed and queried.
 *
 * <p>All implementations of this interface must be thread safe.
 */
public interface AppSearchSessionShim extends Closeable {

    /**
     * Sets the schema that will be used by documents provided to the {@link #put} method.
     *
     * <p>The schema provided here is compared to the stored copy of the schema previously supplied
     * to {@link #setSchema}, if any, to determine how to treat existing documents. The following
     * types of schema modifications are always safe and are made without deleting any existing
     * documents:
     *
     * <ul>
     *   <li>Addition of new types
     *   <li>Addition of new {@link AppSearchSchema.PropertyConfig#CARDINALITY_OPTIONAL OPTIONAL} or
     *       {@link AppSearchSchema.PropertyConfig#CARDINALITY_REPEATED REPEATED} properties to a
     *       type
     *   <li>Changing the cardinality of a data type to be less restrictive (e.g. changing an {@link
     *       AppSearchSchema.PropertyConfig#CARDINALITY_OPTIONAL OPTIONAL} property into a {@link
     *       AppSearchSchema.PropertyConfig#CARDINALITY_REPEATED REPEATED} property.
     * </ul>
     *
     * <p>The following types of schema changes are not backwards-compatible:
     *
     * <ul>
     *   <li>Removal of an existing type
     *   <li>Removal of a property from a type
     *   <li>Changing the data type ({@code boolean}, {@code long}, etc.) of an existing property
     *   <li>For properties of {@code Document} type, changing the schema type of {@code Document}s
     *       of that property
     *   <li>Changing the cardinality of a data type to be more restrictive (e.g. changing an {@link
     *       AppSearchSchema.PropertyConfig#CARDINALITY_OPTIONAL OPTIONAL} property into a {@link
     *       AppSearchSchema.PropertyConfig#CARDINALITY_REQUIRED REQUIRED} property).
     *   <li>Adding a {@link AppSearchSchema.PropertyConfig#CARDINALITY_REQUIRED REQUIRED} property.
     * </ul>
     *
     * <p>Supplying a schema with such changes will, by default, result in this call completing its
     * future with an {@link android.app.appsearch.exceptions.AppSearchException} with a code of
     * {@link AppSearchResult#RESULT_INVALID_SCHEMA} and a message describing the incompatibility.
     * In this case the previously set schema will remain active.
     *
     * <p>If you need to make non-backwards-compatible changes as described above, you can either:
     *
     * <ul>
     *   <li>Set the {@link SetSchemaRequest.Builder#setForceOverride} method to {@code true}. In
     *       this case, instead of completing its future with an {@link
     *       android.app.appsearch.exceptions.AppSearchException} with the {@link
     *       AppSearchResult#RESULT_INVALID_SCHEMA} error code, all documents which are not
     *       compatible with the new schema will be deleted and the incompatible schema will be
     *       applied. Incompatible types and deleted types will be set into {@link
     *       SetSchemaResponse#getIncompatibleTypes()} and {@link
     *       SetSchemaResponse#getDeletedTypes()}, respectively.
     *   <li>Add a {@link android.app.appsearch.AppSearchSchema.Migrator} for each incompatible type
     *       and make no deletion. The migrator will migrate documents from it's old schema version
     *       to the new version. Migrated types will be set into both {@link
     *       SetSchemaResponse#getIncompatibleTypes()} and {@link
     *       SetSchemaResponse#getMigratedTypes()}. See the migration section below.
     * </ul>
     *
     * <p>It is a no-op to set the same schema as has been previously set; this is handled
     * efficiently.
     *
     * <p>By default, documents are visible on platform surfaces. To opt out, call
     * {@link SetSchemaRequest.Builder#setSchemaTypeVisibilityForSystemUi} with {@code visible} as
     * false. Any visibility settings apply only to the schemas that are included in the
     * {@code request}. Visibility settings for a schema type do not persist across
     * {@link #setSchema} calls.
     *
     * <p>Migration: make non-backwards-compatible changes will delete all stored documents in old
     * schema. You can save your documents by setting {@link
     * android.app.appsearch.AppSearchSchema.Migrator} via the {@link
     * SetSchemaRequest.Builder#setMigrator} for each type you want to save.
     *
     * <p>{@link android.app.appsearch.AppSearchSchema.Migrator#onDowngrade} or {@link
     * android.app.appsearch.AppSearchSchema.Migrator#onUpgrade} will be triggered if the version
     * number of the schema stored in AppSearch is different with the version in the request.
     *
     * <p>If any error or Exception occurred in the {@link
     * android.app.appsearch.AppSearchSchema.Migrator#onDowngrade}, {@link
     * android.app.appsearch.AppSearchSchema.Migrator#onUpgrade} or {@link
     * android.app.appsearch.AppSearchMigrationHelper.Transformer#transform}, the migration will be
     * terminated, the setSchema request will be rejected unless the schema changes are
     * backwards-compatible, and stored documents won't have any observable changes.
     *
     * @param request The schema update request.
     * @return A {@link ListenableFuture} with exception if we hit any error. Or the pending {@link
     *     SetSchemaResponse} of performing this operation, if the schema has been successfully set.
     * @see android.app.appsearch.AppSearchSchema.Migrator
     * @see android.app.appsearch.AppSearchMigrationHelper.Transformer
     */
    @NonNull
    ListenableFuture<SetSchemaResponse> setSchema(@NonNull SetSchemaRequest request);

    /**
     * Retrieves the schema most recently successfully provided to {@link #setSchema}.
     *
     * @return The pending result of performing this operation.
     */
    // This call hits disk; async API prevents us from treating these calls as properties.
    @SuppressLint("KotlinPropertyAccess")
    @NonNull
    ListenableFuture<Set<AppSearchSchema>> getSchema();

    /**
     * Indexes documents into AppSearch.
     *
     * <p>Each {@link GenericDocument}'s {@code schemaType} field must be set to the name of a
     * schema type previously registered via the {@link #setSchema} method.
     *
     * @param request {@link PutDocumentsRequest} containing documents to be indexed
     * @return The pending result of performing this operation. The keys of the returned {@link
     *     AppSearchBatchResult} are the URIs of the input documents. The values are {@code null} if
     *     they were successfully indexed, or a failed {@link AppSearchResult} otherwise.
     */
    @NonNull
    ListenableFuture<AppSearchBatchResult<String, Void>> put(@NonNull PutDocumentsRequest request);

    /**
     * Gets {@link GenericDocument} objects by URIs and namespace from the {@link
     * AppSearchSessionShim} database.
     *
     * @param request a request containing URIs and namespace to get documents for.
     * @return A {@link ListenableFuture} which resolves to an {@link AppSearchBatchResult}. The
     *     keys of the {@link AppSearchBatchResult} represent the input URIs from the {@link
     *     GetByUriRequest} object. The values are either the corresponding {@link GenericDocument}
     *     object for the URI on success, or an {@link AppSearchResult} object on failure. For
     *     example, if a URI is not found, the value for that URI will be set to an {@link
     *     AppSearchResult} object with result code: {@link AppSearchResult#RESULT_NOT_FOUND}.
     */
    @NonNull
    ListenableFuture<AppSearchBatchResult<String, GenericDocument>> getByUri(
            @NonNull GetByUriRequest request);

    /**
     * Retrieves documents from the open {@link AppSearchSessionShim} that match a given query
     * string and type of search provided.
     *
     * <p>Query strings can be empty, contain one term with no operators, or contain multiple terms
     * and operators.
     *
     * <p>For query strings that are empty, all documents that match the {@link SearchSpec} will be
     * returned.
     *
     * <p>For query strings with a single term and no operators, documents that match the provided
     * query string and {@link SearchSpec} will be returned.
     *
     * <p>The following operators are supported:
     *
     * <ul>
     *   <li>AND (implicit)
     *       <p>AND is an operator that matches documents that contain <i>all</i> provided terms.
     *       <p><b>NOTE:</b> A space between terms is treated as an "AND" operator. Explicitly
     *       including "AND" in a query string will treat "AND" as a term, returning documents that
     *       also contain "AND".
     *       <p>Example: "apple AND banana" matches documents that contain the terms "apple", "and",
     *       "banana".
     *       <p>Example: "apple banana" matches documents that contain both "apple" and "banana".
     *       <p>Example: "apple banana cherry" matches documents that contain "apple", "banana", and
     *       "cherry".
     *   <li>OR
     *       <p>OR is an operator that matches documents that contain <i>any</i> provided term.
     *       <p>Example: "apple OR banana" matches documents that contain either "apple" or
     *       "banana".
     *       <p>Example: "apple OR banana OR cherry" matches documents that contain any of "apple",
     *       "banana", or "cherry".
     *   <li>Exclusion (-)
     *       <p>Exclusion (-) is an operator that matches documents that <i>do not</i> contain the
     *       provided term.
     *       <p>Example: "-apple" matches documents that do not contain "apple".
     *   <li>Grouped Terms
     *       <p>For queries that require multiple operators and terms, terms can be grouped into
     *       subqueries. Subqueries are contained within an open "(" and close ")" parenthesis.
     *       <p>Example: "(donut OR bagel) (coffee OR tea)" matches documents that contain either
     *       "donut" or "bagel" and either "coffee" or "tea".
     *   <li>Property Restricts
     *       <p>For queries that require a term to match a specific {@link AppSearchSchema} property
     *       of a document, a ":" must be included between the property name and the term.
     *       <p>Example: "subject:important" matches documents that contain the term "important" in
     *       the "subject" property.
     * </ul>
     *
     * <p>Additional search specifications, such as filtering by {@link AppSearchSchema} type or
     * adding projection, can be set by calling the corresponding {@link SearchSpec.Builder} setter.
     *
     * <p>This method is lightweight. The heavy work will be done in {@link
     * SearchResultsShim#getNextPage()}.
     *
     * @param queryExpression query string to search.
     * @param searchSpec spec for setting document filters, adding projection, setting term match
     *     type, etc.
     * @return a {@link SearchResultsShim} object for retrieved matched documents.
     */
    @NonNull
    SearchResultsShim search(@NonNull String queryExpression, @NonNull SearchSpec searchSpec);

    /**
     * Reports usage of a particular document by URI and namespace.
     *
     * <p>A usage report represents an event in which a user interacted with or viewed a document.
     *
     * <p>For each call to {@link #reportUsage}, AppSearch updates usage count and usage recency
     * metrics for that particular document. These metrics are used for ordering {@link #search}
     * results by the {@link SearchSpec#RANKING_STRATEGY_USAGE_COUNT} and {@link
     * SearchSpec#RANKING_STRATEGY_USAGE_LAST_USED_TIMESTAMP} ranking strategies.
     *
     * <p>Reporting usage of a document is optional.
     *
     * @param request The usage reporting request.
     * @return The pending result of performing this operation which resolves to {@code null} on
     *     success.
     */
    @NonNull
    ListenableFuture<Void> reportUsage(@NonNull ReportUsageRequest request);

    /**
     * Removes {@link GenericDocument} objects by URIs and namespace from the {@link
     * AppSearchSessionShim} database.
     *
     * <p>Removed documents will no longer be surfaced by {@link #search} or {@link #getByUri}
     * calls.
     *
     * <p><b>NOTE:</b>By default, documents are removed via a soft delete operation. Once the
     * document crosses the count threshold or byte usage threshold, the documents will be removed
     * from disk.
     *
     * @param request {@link RemoveByUriRequest} with URIs and namespace to remove from the index.
     * @return a {@link ListenableFuture} which resolves to an {@link AppSearchBatchResult}. The
     *     keys of the {@link AppSearchBatchResult} represent the input URIs from the {@link
     *     RemoveByUriRequest} object. The values are either {@code null} on success, or a failed
     *     {@link AppSearchResult} otherwise. URIs that are not found will return a failed {@link
     *     AppSearchResult} with a result code of {@link AppSearchResult#RESULT_NOT_FOUND}.
     */
    @NonNull
    ListenableFuture<AppSearchBatchResult<String, Void>> remove(
            @NonNull RemoveByUriRequest request);

    /**
     * Removes {@link GenericDocument}s from the index by Query. Documents will be removed if they
     * match the {@code queryExpression} in given namespaces and schemaTypes which is set via {@link
     * SearchSpec.Builder#addFilterNamespaces} and {@link SearchSpec.Builder#addFilterSchemas}.
     *
     * <p>An empty {@code queryExpression} matches all documents.
     *
     * <p>An empty set of namespaces or schemaTypes matches all namespaces or schemaTypes in the
     * current database.
     *
     * @param queryExpression Query String to search.
     * @param searchSpec Spec containing schemaTypes, namespaces and query expression indicates how
     *     document will be removed. All specific about how to scoring, ordering, snippeting and
     *     resulting will be ignored.
     * @return The pending result of performing this operation.
     */
    @NonNull
    ListenableFuture<Void> remove(@NonNull String queryExpression, @NonNull SearchSpec searchSpec);

    /**
     * Flush all schema and document updates, additions, and deletes to disk if possible.
     *
     * @return The pending result of performing this operation. {@link
     *     android.app.appsearch.exceptions.AppSearchException} with {@link
     *     AppSearchResult#RESULT_INTERNAL_ERROR} will be set to the future if we hit error when
     *     save to disk.
     */
    @NonNull
    ListenableFuture<Void> maybeFlush();

    /**
     * Closes the {@link AppSearchSessionShim} to persist all schema and document updates,
     * additions, and deletes to disk.
     */
    @Override
    void close();
}
