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
 * <p>This class is thread safe.
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
     * Sets the schema that represents the organizational structure of data within the AppSearch
     * database.
     *
     * <p>Upon creating an {@link AppSearchSession}, {@link #setSchema} should be called. If the
     * schema needs to be updated, or it has not been previously set, then the provided schema will
     * be saved and persisted to disk. Otherwise, {@link #setSchema} is handled efficiently as a
     * no-op call.
     *
     * @param request the schema to set or update the AppSearch database to.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive errors resulting from setting the schema. If the
     *                 operation succeeds, the callback will be invoked with {@code null}.
     */
    // TODO(b/169883602): Change @code references to @link when setPlatformSurfaceable APIs are
    //  exposed.
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
                    new ArrayList<>(request.getSchemasNotDisplayedBySystem()),
                    schemasPackageAccessibleBundles,
                    request.isForceOverride(),
                    mUserId,
                    request.getVersion(),
                    new IAppSearchResultCallback.Stub() {
                        public void onResult(AppSearchResult result) {
                            executor.execute(() -> {
                                if (result.isSuccess()) {
                                    callback.accept(
                                            // TODO(b/177266929) implement Migration in platform.
                                            AppSearchResult.newSuccessfulResult(
                                                    new SetSchemaResponse.Builder().build()));
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
            @NonNull Consumer<AppSearchResult<GetSchemaResponse>> callback) {
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
                                    Bundle responseBundle = (Bundle) result.getResultValue();
                                    GetSchemaResponse response =
                                            new GetSchemaResponse(responseBundle);
                                    callback.accept(AppSearchResult.newSuccessfulResult(response));
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
     * Retrieves the set of all namespaces in the current database with at least one document.
     *
     * @param executor        Executor on which to invoke the callback.
     * @param callback        Callback to receive the namespaces.
     */
    public void getNamespaces(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<AppSearchResult<Set<String>>> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        Preconditions.checkState(!mIsClosed, "AppSearchSession has already been closed");
        try {
            mService.getNamespaces(
                    mPackageName,
                    mDatabaseName,
                    mUserId,
                    new IAppSearchResultCallback.Stub() {
                        public void onResult(AppSearchResult result) {
                            executor.execute(() -> {
                                if (result.isSuccess()) {
                                    Set<String> namespaces =
                                            new ArraySet<>((List<String>) result.getResultValue());
                                    callback.accept(
                                            AppSearchResult.newSuccessfulResult(namespaces));
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
     * Indexes documents into the {@link AppSearchSession} database.
     *
     * <p>Each {@link GenericDocument} object must have a {@code schemaType} field set to an {@link
     * AppSearchSchema} type that has been previously registered by calling the {@link #setSchema}
     * method.
     *
     * @param request containing documents to be indexed.
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
            // TODO(b/173532925) a timestamp needs to be sent here to calculate binder latency
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
     * Gets {@link GenericDocument} objects by URIs and namespace from the {@link AppSearchSession}
     * database.
     *
     * @param request a request containing URIs and namespace to get documents for.
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
     * Retrieves documents from the open {@link AppSearchSession} that match a given query string
     * and type of search provided.
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
     * SearchResults#getNextPage}.
     *
     * @param queryExpression query string to search.
     * @param searchSpec spec for setting document filters, adding projection, setting term match
     *     type, etc.
     * @return a {@link SearchResults} object for retrieved matched documents.
     */
    @NonNull
    public SearchResults search(@NonNull String queryExpression, @NonNull SearchSpec searchSpec) {
        Objects.requireNonNull(queryExpression);
        Objects.requireNonNull(searchSpec);
        Preconditions.checkState(!mIsClosed, "AppSearchSession has already been closed");
        return new SearchResults(mService, mPackageName, mDatabaseName, queryExpression,
                searchSpec, mUserId);
    }

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
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive errors. If the operation succeeds, the callback will be
     *                 invoked with {@code null}.
     */
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
                    /*systemUsage=*/ false,
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
     * Removes {@link GenericDocument} objects by URIs and namespace from the {@link
     * AppSearchSession} database.
     *
     * <p>Removed documents will no longer be surfaced by {@link #search} or {@link #getByUri}
     * calls.
     *
     * <p><b>NOTE:</b>By default, documents are removed via a soft delete operation. Once the
     * document crosses the count threshold or byte usage threshold, the documents will be removed
     * from disk.
     *
     * @param request {@link RemoveByUriRequest} with URIs and namespace to remove from the index.
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
     * Gets the storage info for this {@link AppSearchSession} database.
     *
     * <p>This may take time proportional to the number of documents and may be inefficient to
     * call repeatedly.
     *
     * @param executor        Executor on which to invoke the callback.
     * @param callback        Callback to receive the storage info.
     */
    public void getStorageInfo(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<AppSearchResult<StorageInfo>> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        Preconditions.checkState(!mIsClosed, "AppSearchSession has already been closed");
        // TODO(b/182909475): Implement getStorageInfo
        throw new UnsupportedOperationException();
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
