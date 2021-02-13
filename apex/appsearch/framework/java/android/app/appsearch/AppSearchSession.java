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

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.os.Bundle;
import android.os.ParcelableException;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Represents a connection to an AppSearch storage system where {@link GenericDocument}s can be
 * placed and queried.
 *
 * This class is thread safe.
 */
public final class AppSearchSession implements Closeable {
    private static final String TAG = "AppSearchSession";
    private final String mPackageName;
    private final String mDatabaseName;
    @UserIdInt
    private final int mUserId;
    private final IAppSearchManager mService;
    private boolean mIsMutated = false;
    private boolean mIsClosed = false;


    /**
     * Creates a search session for the client, defined by the {@code userId} and
     * {@code packageName}.
     */
    static void createSearchSession(
            @NonNull AppSearchManager.SearchContext searchContext,
            @NonNull IAppSearchManager service,
            @UserIdInt int userId,
            @NonNull String packageName,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<AppSearchResult<AppSearchSession>> callback) {
        AppSearchSession searchSession =
                new AppSearchSession(service, userId, packageName, searchContext.mDatabaseName);
        searchSession.initialize(executor, callback);
    }

    // NOTE: No instance of this class should be created or returned except via initialize().
    // Once the callback.accept has been called here, the class is ready to use.
    private void initialize(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<AppSearchResult<AppSearchSession>> callback) {
        try {
            mService.initialize(mUserId, new IAppSearchResultCallback.Stub() {
                public void onResult(AppSearchResult result) {
                    executor.execute(() -> {
                        if (result.isSuccess()) {
                            callback.accept(
                                    AppSearchResult.newSuccessfulResult(AppSearchSession.this));
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

    private AppSearchSession(@NonNull IAppSearchManager service, @UserIdInt int userId,
            @NonNull String packageName, @NonNull String databaseName) {
        mService = service;
        mUserId = userId;
        mPackageName = packageName;
        mDatabaseName = databaseName;
    }

    /**
     * Sets the schema that will be used by documents provided to the {@link #put} method.
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
     * <p>Supplying a schema with such changes will, by default, result in this call returning an
     * {@link AppSearchResult} with a code of {@link AppSearchResult#RESULT_INVALID_SCHEMA} and an
     * error message describing the incompatibility. In this case the previously set schema will
     * remain active.
     *
     * <p>If you need to make non-backwards-compatible changes as described above, you can set the
     * {@link SetSchemaRequest.Builder#setForceOverride} method to {@code true}. In this case,
     * instead of returning an {@link AppSearchResult} with the
     * {@link AppSearchResult#RESULT_INVALID_SCHEMA} error code, all documents which are not
     * compatible with the new schema will be deleted and the incompatible schema will be applied.
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
     * @param request  The schema update request.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive errors resulting from setting the schema. If the
     *                 operation succeeds, the callback will be invoked with {@code null}.
     */
    public void setSchema(
            @NonNull SetSchemaRequest request,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<AppSearchResult<SetSchemaResponse>> callback) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        Preconditions.checkState(!mIsClosed, "AppSearchSession has already been closed");
        List<Bundle> schemaBundles = new ArrayList<>(request.getSchemas().size());
        for (AppSearchSchema schema : request.getSchemas()) {
            schemaBundles.add(schema.getBundle());
        }
        Map<String, List<Bundle>> schemasPackageAccessibleBundles =
                new ArrayMap<>(request.getSchemasVisibleToPackagesInternal().size());
        for (Map.Entry<String, Set<PackageIdentifier>> entry :
                request.getSchemasVisibleToPackagesInternal().entrySet()) {
            List<Bundle> packageIdentifierBundles = new ArrayList<>(entry.getValue().size());
            for (PackageIdentifier packageIdentifier : entry.getValue()) {
                packageIdentifierBundles.add(packageIdentifier.getBundle());
            }
            schemasPackageAccessibleBundles.put(entry.getKey(), packageIdentifierBundles);
        }
        try {
            mService.setSchema(
                    mPackageName,
                    mDatabaseName,
                    schemaBundles,
                    new ArrayList<>(request.getSchemasNotVisibleToSystemUi()),
                    schemasPackageAccessibleBundles,
                    request.isForceOverride(),
                    mUserId,
                    new IAppSearchResultCallback.Stub() {
                        public void onResult(AppSearchResult result) {
                            executor.execute(() -> {
                                if (result.isSuccess()) {
                                    callback.accept(
                                            // TODO(b/151178558) implement Migration in platform.
                                            AppSearchResult.newSuccessfulResult(
                                                    new SetSchemaResponse.Builder().setResultCode(
                                                            result.getResultCode())
                                                            .build()));
                                } else {
                                    callback.accept(result);
                                }
                            });
                        }
                    });
            mIsMutated = true;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves the schema most recently successfully provided to {@link #setSchema}.
     *
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive the pending results of schema.
     */
    public void getSchema(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<AppSearchResult<Set<AppSearchSchema>>> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        Preconditions.checkState(!mIsClosed, "AppSearchSession has already been closed");
        try {
            mService.getSchema(
                    mPackageName,
                    mDatabaseName,
                    mUserId,
                    new IAppSearchResultCallback.Stub() {
                        public void onResult(AppSearchResult result) {
                            executor.execute(() -> {
                                if (result.isSuccess()) {
                                    List<Bundle> schemaBundles =
                                            (List<Bundle>) result.getResultValue();
                                    Set<AppSearchSchema> schemas = new ArraySet<>(
                                            schemaBundles.size());
                                    for (int i = 0; i < schemaBundles.size(); i++) {
                                        schemas.add(new AppSearchSchema(schemaBundles.get(i)));
                                    }
                                    callback.accept(AppSearchResult.newSuccessfulResult(schemas));
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

    /**
     * Indexes documents into AppSearch.
     *
     * <p>Each {@link GenericDocument}'s {@code schemaType} field must be set to the name of a
     * schema type previously registered via the {@link #setSchema} method.
     *
     * @param request  {@link PutDocumentsRequest} containing documents to be indexed
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive pending result of performing this operation. The keys
     *                 of the returned {@link AppSearchBatchResult} are the URIs of the input
     *                 documents. The values are {@code null} if they were successfully indexed,
     *                 or a failed {@link AppSearchResult} otherwise.
     *                 Or {@link BatchResultCallback#onSystemError} will be invoked with a
     *                 {@link Throwable} if an unexpected internal error occurred in AppSearch
     *                 service.
     */
    public void put(
            @NonNull PutDocumentsRequest request,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull BatchResultCallback<String, Void> callback) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        Preconditions.checkState(!mIsClosed, "AppSearchSession has already been closed");
        List<GenericDocument> documents = request.getGenericDocuments();
        List<Bundle> documentBundles = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            documentBundles.add(documents.get(i).getBundle());
        }
        try {
            mService.putDocuments(mPackageName, mDatabaseName, documentBundles, mUserId,
                    new IAppSearchBatchResultCallback.Stub() {
                        public void onResult(AppSearchBatchResult result) {
                            executor.execute(() -> callback.onResult(result));
                        }

                        public void onSystemError(ParcelableException exception) {
                            executor.execute(() -> callback.onSystemError(exception.getCause()));
                        }
                    });
            mIsMutated = true;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves {@link GenericDocument}s by URI.
     *
     * @param request  {@link GetByUriRequest} containing URIs to be retrieved.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive the pending result of performing this operation. The keys
     *                 of the returned {@link AppSearchBatchResult} are the input URIs. The values
     *                 are the returned {@link GenericDocument}s on success, or a failed
     *                 {@link AppSearchResult} otherwise. URIs that are not found will return a
     *                 failed {@link AppSearchResult} with a result code of
     *                 {@link AppSearchResult#RESULT_NOT_FOUND}.
     *                 Or {@link BatchResultCallback#onSystemError} will be invoked with a
     *                 {@link Throwable} if an unexpected internal error occurred in AppSearch
     *                 service.
     */
    public void getByUri(
            @NonNull GetByUriRequest request,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull BatchResultCallback<String, GenericDocument> callback) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        Preconditions.checkState(!mIsClosed, "AppSearchSession has already been closed");
        try {
            mService.getDocuments(
                    mPackageName,
                    mDatabaseName,
                    request.getNamespace(),
                    new ArrayList<>(request.getUris()),
                    request.getProjectionsInternal(),
                    mUserId,
                    new IAppSearchBatchResultCallback.Stub() {
                        public void onResult(AppSearchBatchResult result) {
                            executor.execute(() -> {
                                AppSearchBatchResult.Builder<String, GenericDocument>
                                        documentResultBuilder =
                                        new AppSearchBatchResult.Builder<>();

                                // Translate successful results
                                for (Map.Entry<String, Bundle> bundleEntry :
                                        (Set<Map.Entry<String, Bundle>>)
                                                result.getSuccesses().entrySet()) {
                                    GenericDocument document;
                                    try {
                                        document = new GenericDocument(bundleEntry.getValue());
                                    } catch (Throwable t) {
                                        // These documents went through validation, so how could
                                        // this fail? We must have done something wrong.
                                        documentResultBuilder.setFailure(
                                                bundleEntry.getKey(),
                                                AppSearchResult.RESULT_INTERNAL_ERROR,
                                                t.getMessage());
                                        continue;
                                    }
                                    documentResultBuilder.setSuccess(
                                            bundleEntry.getKey(), document);
                                }

                                // Translate failed results
                                for (Map.Entry<String, AppSearchResult<Bundle>> bundleEntry :
                                        (Set<Map.Entry<String, AppSearchResult<Bundle>>>)
                                                result.getFailures().entrySet()) {
                                    documentResultBuilder.setFailure(
                                            bundleEntry.getKey(),
                                            bundleEntry.getValue().getResultCode(),
                                            bundleEntry.getValue().getErrorMessage());
                                }
                                callback.onResult(documentResultBuilder.build());
                            });
                        }

                        public void onSystemError(ParcelableException exception) {
                            executor.execute(() -> callback.onSystemError(exception.getCause()));
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

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
     * {@link SearchResults#getNextPage}.
     *
     * @param queryExpression Query String to search.
     * @param searchSpec      Spec for setting filters, raw query etc.
     * @param executor        Executor on which to invoke the callback of the following request
     *                        {@link SearchResults#getNextPage}.
     * @return The search result of performing this operation.
     */
    @NonNull
    public SearchResults search(
            @NonNull String queryExpression,
            @NonNull SearchSpec searchSpec,
            @NonNull @CallbackExecutor Executor executor) {
        Objects.requireNonNull(queryExpression);
        Objects.requireNonNull(searchSpec);
        Objects.requireNonNull(executor);
        Preconditions.checkState(!mIsClosed, "AppSearchSession has already been closed");
        return new SearchResults(mService, mPackageName, mDatabaseName, queryExpression,
                searchSpec, mUserId, executor);
    }

    /**
     * Reports usage of a particular document by URI and namespace.
     *
     * <p>A usage report represents an event in which a user interacted with or viewed a document.
     *
     * <p>For each call to {@link #reportUsage}, AppSearch updates usage count and usage recency
     * metrics for that particular document. These metrics are used for ordering {@link #search}
     * results by the {@link SearchSpec#RANKING_STRATEGY_USAGE_COUNT} and
     * {@link SearchSpec#RANKING_STRATEGY_USAGE_LAST_USED_TIMESTAMP} ranking strategies.
     *
     * <p>Reporting usage of a document is optional.
     *
     * @param request The usage reporting request.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive errors. If the operation succeeds, the callback will be
     *                 invoked with {@code null}.
     */
    @NonNull
    public void reportUsage(
            @NonNull ReportUsageRequest request,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<AppSearchResult<Void>> callback) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        Preconditions.checkState(!mIsClosed, "AppSearchSession has already been closed");
        try {
            mService.reportUsage(
                    mPackageName,
                    mDatabaseName,
                    request.getNamespace(),
                    request.getUri(),
                    request.getUsageTimeMillis(),
                    mUserId,
                    new IAppSearchResultCallback.Stub() {
                        public void onResult(AppSearchResult result) {
                            executor.execute(() -> callback.accept(result));
                        }
                    });
            mIsMutated = true;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes {@link GenericDocument}s from the index by URI.
     *
     * @param request  Request containing URIs to be removed.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive the pending result of performing this operation. The keys
     *                 of the returned {@link AppSearchBatchResult} are the input URIs. The values
     *                 are {@code null} on success, or a failed {@link AppSearchResult} otherwise.
     *                 URIs that are not found will return a failed {@link AppSearchResult} with a
     *                 result code of {@link AppSearchResult#RESULT_NOT_FOUND}.
     *                 Or {@link BatchResultCallback#onSystemError} will be invoked with a
     *                 {@link Throwable} if an unexpected internal error occurred in AppSearch
     *                 service.
     */
    public void remove(
            @NonNull RemoveByUriRequest request,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull BatchResultCallback<String, Void> callback) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        Preconditions.checkState(!mIsClosed, "AppSearchSession has already been closed");
        try {
            mService.removeByUri(mPackageName, mDatabaseName, request.getNamespace(),
                    new ArrayList<>(request.getUris()), mUserId,
                    new IAppSearchBatchResultCallback.Stub() {
                        public void onResult(AppSearchBatchResult result) {
                            executor.execute(() -> callback.onResult(result));
                        }

                        public void onSystemError(ParcelableException exception) {
                            executor.execute(() -> callback.onSystemError(exception.getCause()));
                        }
                    });
            mIsMutated = true;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes {@link GenericDocument}s from the index by Query. Documents will be removed if they
     * match the {@code queryExpression} in given namespaces and schemaTypes which is set via
     * {@link SearchSpec.Builder#addFilterNamespaces} and
     * {@link SearchSpec.Builder#addFilterSchemas}.
     *
     * <p> An empty {@code queryExpression} matches all documents.
     *
     * <p> An empty set of namespaces or schemaTypes matches all namespaces or schemaTypes in
     * the current database.
     *
     * @param queryExpression Query String to search.
     * @param searchSpec      Spec containing schemaTypes, namespaces and query expression indicates
     *                        how document will be removed. All specific about how to scoring,
     *                        ordering, snippeting and resulting will be ignored.
     * @param executor        Executor on which to invoke the callback.
     * @param callback        Callback to receive errors resulting from removing the documents. If
     *                        the operation succeeds, the callback will be invoked with
     *                        {@code null}.
     */
    public void remove(
            @NonNull String queryExpression,
            @NonNull SearchSpec searchSpec,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<AppSearchResult<Void>> callback) {
        Objects.requireNonNull(queryExpression);
        Objects.requireNonNull(searchSpec);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        Preconditions.checkState(!mIsClosed, "AppSearchSession has already been closed");
        try {
            mService.removeByQuery(mPackageName, mDatabaseName, queryExpression,
                    searchSpec.getBundle(), mUserId,
                    new IAppSearchResultCallback.Stub() {
                        public void onResult(AppSearchResult result) {
                            executor.execute(() -> callback.accept(result));
                        }
                    });
            mIsMutated = true;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Closes the {@link AppSearchSession} to persist all schema and document updates, additions,
     * and deletes to disk.
     */
    @Override
    public void close() {
        if (mIsMutated && !mIsClosed) {
            try {
                mService.persistToDisk(mUserId);
                mIsClosed = true;
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to close the AppSearchSession", e);
            }
        }
    }
}
