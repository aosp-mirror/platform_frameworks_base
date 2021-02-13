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
     *       applied.
     *   <li>Add a {@link android.app.appsearch.AppSearchSchema.Migrator} for each incompatible type
     *       and make no deletion. The migrator will migrate documents from it's old schema version
     *       to the new version. See the migration section below.
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
     * @return The pending {@link SetSchemaResponse} of performing this operation. Success if the
     *     the schema has been set and any migrations has been done. Otherwise, the failure {@link
     *     android.app.appsearch.SetSchemaResponse.MigrationFailure} indicates which document is
     *     fail to be migrated.
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
     * Retrieves {@link GenericDocument}s by URI.
     *
     * @param request {@link GetByUriRequest} containing URIs to be retrieved.
     * @return The pending result of performing this operation. The keys of the returned {@link
     *     AppSearchBatchResult} are the input URIs. The values are the returned {@link
     *     GenericDocument}s on success, or a failed {@link AppSearchResult} otherwise. URIs that
     *     are not found will return a failed {@link AppSearchResult} with a result code of {@link
     *     AppSearchResult#RESULT_NOT_FOUND}.
     */
    @NonNull
    ListenableFuture<AppSearchBatchResult<String, GenericDocument>> getByUri(
            @NonNull GetByUriRequest request);

    /**
     * Searches for documents based on a given query string.
     *
     * <p>Currently we support following features in the raw query format:
     *
     * <ul>
     *   <li>AND
     *       <p>AND joins (e.g. “match documents that have both the terms ‘dog’ and ‘cat’”).
     *       Example: hello world matches documents that have both ‘hello’ and ‘world’
     *   <li>OR
     *       <p>OR joins (e.g. “match documents that have either the term ‘dog’ or ‘cat’”). Example:
     *       dog OR puppy
     *   <li>Exclusion
     *       <p>Exclude a term (e.g. “match documents that do not have the term ‘dog’”). Example:
     *       -dog excludes the term ‘dog’
     *   <li>Grouping terms
     *       <p>Allow for conceptual grouping of subqueries to enable hierarchical structures (e.g.
     *       “match documents that have either ‘dog’ or ‘puppy’, and either ‘cat’ or ‘kitten’”).
     *       Example: (dog puppy) (cat kitten) two one group containing two terms.
     *   <li>Property restricts
     *       <p>Specifies which properties of a document to specifically match terms in (e.g. “match
     *       documents where the ‘subject’ property contains ‘important’”). Example:
     *       subject:important matches documents with the term ‘important’ in the ‘subject’ property
     *   <li>Schema type restricts
     *       <p>This is similar to property restricts, but allows for restricts on top-level
     *       document fields, such as schema_type. Clients should be able to limit their query to
     *       documents of a certain schema_type (e.g. “match documents that are of the ‘Email’
     *       schema_type”). Example: { schema_type_filters: “Email”, “Video”,query: “dog” } will
     *       match documents that contain the query term ‘dog’ and are of either the ‘Email’ schema
     *       type or the ‘Video’ schema type.
     * </ul>
     *
     * <p>This method is lightweight. The heavy work will be done in {@link
     * SearchResultsShim#getNextPage()}.
     *
     * @param queryExpression Query String to search.
     * @param searchSpec Spec for setting filters, raw query etc.
     * @return The search result of performing this operation.
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
     * Removes {@link GenericDocument}s from the index by URI.
     *
     * @param request Request containing URIs to be removed.
     * @return The pending result of performing this operation. The keys of the returned {@link
     *     AppSearchBatchResult} are the input URIs. The values are {@code null} on success, or a
     *     failed {@link AppSearchResult} otherwise. URIs that are not found will return a failed
     *     {@link AppSearchResult} with a result code of {@link AppSearchResult#RESULT_NOT_FOUND}.
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
