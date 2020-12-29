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

import android.annotation.NonNull;
import android.annotation.SuppressLint;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.Set;

/**
 * Represents a connection to an AppSearch storage system where {@link GenericDocument}s can be
 * placed and queried.
 *
 * All implementations of this interface must be thread safe.
 */
public interface AppSearchSessionShim {

    /**
     * Sets the schema that will be used by documents provided to the {@link #putDocuments} method.
     *
     * <p>The schema provided here is compared to the stored copy of the schema previously supplied
     * to {@link #setSchema}, if any, to determine how to treat existing documents. The following
     * types of schema modifications are always safe and are made without deleting any existing
     * documents:
     * <ul>
     *     <li>Addition of new types
     *     <li>Addition of new
     *         {@link AppSearchSchema.PropertyConfig#CARDINALITY_OPTIONAL OPTIONAL} or
     *         {@link AppSearchSchema.PropertyConfig#CARDINALITY_REPEATED REPEATED} properties to a
     *         type
     *     <li>Changing the cardinality of a data type to be less restrictive (e.g. changing an
     *         {@link AppSearchSchema.PropertyConfig#CARDINALITY_OPTIONAL OPTIONAL} property into a
     *         {@link AppSearchSchema.PropertyConfig#CARDINALITY_REPEATED REPEATED} property.
     * </ul>
     *
     * <p>The following types of schema changes are not backwards-compatible:
     * <ul>
     *     <li>Removal of an existing type
     *     <li>Removal of a property from a type
     *     <li>Changing the data type ({@code boolean}, {@code long}, etc.) of an existing property
     *     <li>For properties of {@code Document} type, changing the schema type of
     *         {@code Document}s of that property
     *     <li>Changing the cardinality of a data type to be more restrictive (e.g. changing an
     *         {@link AppSearchSchema.PropertyConfig#CARDINALITY_OPTIONAL OPTIONAL} property into a
     *         {@link AppSearchSchema.PropertyConfig#CARDINALITY_REQUIRED REQUIRED} property).
     *     <li>Adding a
     *         {@link AppSearchSchema.PropertyConfig#CARDINALITY_REQUIRED REQUIRED} property.
     * </ul>
     * <p>Supplying a schema with such changes will, by default, result in this call completing its
     * future with an {@link androidx.appsearch.exceptions.AppSearchException} with a code of
     * {@link AppSearchResult#RESULT_INVALID_SCHEMA} and a message describing the incompatibility.
     * In this case the previously set schema will remain active.
     *
     * <p>If you need to make non-backwards-compatible changes as described above, you can set the
     * {@link SetSchemaRequest.Builder#setForceOverride} method to {@code true}. In this case,
     * instead of completing its future with an
     * {@link androidx.appsearch.exceptions.AppSearchException} with the
     * {@link AppSearchResult#RESULT_INVALID_SCHEMA} error code, all documents which are not
     * compatible with the new schema will be deleted and the incompatible schema will be applied.
     *
     * <p>It is a no-op to set the same schema as has been previously set; this is handled
     * efficiently.
     *
     * <p>By default, documents are visible on platform surfaces. To opt out, call {@code
     * SetSchemaRequest.Builder#setPlatformSurfaceable} with {@code surfaceable} as false. Any
     * visibility settings apply only to the schemas that are included in the {@code request}.
     * Visibility settings for a schema type do not apply or persist across
     * {@link SetSchemaRequest}s.
     *
     * @param request The schema update request.
     * @return The pending result of performing this operation.
     */
    // TODO(b/169883602): Change @code references to @link when setPlatformSurfaceable APIs are
    //  exposed.
    @NonNull
    ListenableFuture<Void> setSchema(@NonNull SetSchemaRequest request);

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
     * @return The pending result of performing this operation. The keys of the returned
     * {@link AppSearchBatchResult} are the URIs of the input documents. The values are
     * {@code null} if they were successfully indexed, or a failed {@link AppSearchResult}
     * otherwise.
     */
    @NonNull
    ListenableFuture<AppSearchBatchResult<String, Void>> putDocuments(
            @NonNull PutDocumentsRequest request);

    /**
     * Retrieves {@link GenericDocument}s by URI.
     *
     * @param request {@link GetByUriRequest} containing URIs to be retrieved.
     * @return The pending result of performing this operation. The keys of the returned
     * {@link AppSearchBatchResult} are the input URIs. The values are the returned
     * {@link GenericDocument}s on success, or a failed {@link AppSearchResult} otherwise.
     * URIs that are not found will return a failed {@link AppSearchResult} with a result code
     * of {@link AppSearchResult#RESULT_NOT_FOUND}.
     */
    @NonNull
    ListenableFuture<AppSearchBatchResult<String, GenericDocument>> getByUri(
            @NonNull GetByUriRequest request);

    /**
     * Searches a document based on a given query string.
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
     * {@link SearchResults#getNextPage()}.
     *
     * @param queryExpression Query String to search.
     * @param searchSpec      Spec for setting filters, raw query etc.
     * @return The search result of performing this operation.
     */
    @NonNull
    SearchResultsShim query(@NonNull String queryExpression, @NonNull SearchSpec searchSpec);

    /**
     * Removes {@link GenericDocument}s from the index by URI.
     *
     * @param request Request containing URIs to be removed.
     * @return The pending result of performing this operation. The keys of the returned
     * {@link AppSearchBatchResult} are the input URIs. The values are {@code null} on success,
     * or a failed {@link AppSearchResult} otherwise. URIs that are not found will return a
     * failed {@link AppSearchResult} with a result code of
     * {@link AppSearchResult#RESULT_NOT_FOUND}.
     */
    @NonNull
    ListenableFuture<AppSearchBatchResult<String, Void>> removeByUri(
            @NonNull RemoveByUriRequest request);

    /**
     * Removes {@link GenericDocument}s from the index by Query. Documents will be removed if they
     * match the {@code queryExpression} in given namespaces and schemaTypes which is set via
     * {@link SearchSpec.Builder#addNamespace} and {@link SearchSpec.Builder#addSchemaType}.
     *
     * <p> An empty {@code queryExpression} matches all documents.
     *
     * <p> An empty set of namespaces or schemaTypes matches all namespaces or schemaTypes in
     * the current database.
     *
     * @param queryExpression Query String to search.
     * @param searchSpec      Spec containing schemaTypes, namespaces and query expression
     *                        indicates how document will be removed. All specific about how to
     *                        scoring, ordering, snippeting and resulting will be ignored.
     * @return The pending result of performing this operation.
     */
    @NonNull
    ListenableFuture<Void> removeByQuery(
            @NonNull String queryExpression, @NonNull SearchSpec searchSpec);
}
