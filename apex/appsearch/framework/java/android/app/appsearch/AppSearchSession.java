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
import android.app.appsearch.aidl.AppSearchBatchResultParcel;
import android.app.appsearch.aidl.AppSearchResultParcel;
import android.app.appsearch.aidl.IAppSearchBatchResultCallback;
import android.app.appsearch.aidl.IAppSearchManager;
import android.app.appsearch.aidl.IAppSearchResultCallback;
import android.app.appsearch.exceptions.AppSearchException;
import android.app.appsearch.util.SchemaMigrationUtil;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Provides a connection to a single AppSearch database.
 *
 * <p>An {@link AppSearchSession} instance provides access to database operations such as
 * setting a schema, adding documents, and searching.
 *
 * <p>This class is thread safe.
 *
 * @see GlobalSearchSession
 */
public final class AppSearchSession implements Closeable {
    private static final String TAG = "AppSearchSession";

    private final String mPackageName;
    private final String mDatabaseName;
    private final UserHandle mUserHandle;
    private final IAppSearchManager mService;

    private boolean mIsMutated = false;
    private boolean mIsClosed = false;

    /**
     * Creates a search session for the client, defined by the {@code userHandle} and
     * {@code packageName}.
     */
    static void createSearchSession(
            @NonNull AppSearchManager.SearchContext searchContext,
            @NonNull IAppSearchManager service,
            @NonNull UserHandle userHandle,
            @NonNull String packageName,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<AppSearchResult<AppSearchSession>> callback) {
        AppSearchSession searchSession =
                new AppSearchSession(service, userHandle, packageName, searchContext.mDatabaseName);
        searchSession.initialize(executor, callback);
    }

    // NOTE: No instance of this class should be created or returned except via initialize().
    // Once the callback.accept has been called here, the class is ready to use.
    private void initialize(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<AppSearchResult<AppSearchSession>> callback) {
        try {
            mService.initialize(
                    mUserHandle,
                    /*binderCallStartTimeMillis=*/ SystemClock.elapsedRealtime(),
                    new IAppSearchResultCallback.Stub() {
                        @Override
                        public void onResult(AppSearchResultParcel resultParcel) {
                            executor.execute(() -> {
                                AppSearchResult<Void> result = resultParcel.getResult();
                                if (result.isSuccess()) {
                                    callback.accept(
                                            AppSearchResult.newSuccessfulResult(
                                                    AppSearchSession.this));
                                } else {
                                    callback.accept(AppSearchResult.newFailedResult(result));
                                }
                    });
                }
            });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private AppSearchSession(@NonNull IAppSearchManager service, @NonNull UserHandle userHandle,
            @NonNull String packageName, @NonNull String databaseName) {
        mService = service;
        mUserHandle = userHandle;
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
     * @param workExecutor Executor on which to schedule heavy client-side background work such as
     *                     transforming documents.
     * @param callbackExecutor Executor on which to invoke the callback.
     * @param callback Callback to receive errors resulting from setting the schema. If the
     *                 operation succeeds, the callback will be invoked with {@code null}.
     */
    // TODO(b/169883602): Change @code references to @link when setPlatformSurfaceable APIs are
    //  exposed.
    public void setSchema(
            @NonNull SetSchemaRequest request,
            @NonNull Executor workExecutor,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull Consumer<AppSearchResult<SetSchemaResponse>> callback) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(workExecutor);
        Objects.requireNonNull(callbackExecutor);
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

        // No need to trigger migration if user never set migrator
        if (request.getMigrators().isEmpty()) {
            setSchemaNoMigrations(
                    request,
                    schemaBundles,
                    schemasPackageAccessibleBundles,
                    callbackExecutor,
                    callback);
        } else {
            setSchemaWithMigrations(
                    request,
                    schemaBundles,
                    schemasPackageAccessibleBundles,
                    workExecutor,
                    callbackExecutor,
                    callback);
        }
        mIsMutated = true;
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
                    mUserHandle,
                    new IAppSearchResultCallback.Stub() {
                        @Override
                        public void onResult(AppSearchResultParcel resultParcel) {
                            executor.execute(() -> {
                                AppSearchResult<Bundle> result = resultParcel.getResult();
                                if (result.isSuccess()) {
                                    GetSchemaResponse response =
                                            new GetSchemaResponse(result.getResultValue());
                                    callback.accept(AppSearchResult.newSuccessfulResult(response));
                                } else {
                                    callback.accept(AppSearchResult.newFailedResult(result));
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
                    mUserHandle,
                    new IAppSearchResultCallback.Stub() {
                        @Override
                        public void onResult(AppSearchResultParcel resultParcel) {
                            executor.execute(() -> {
                                AppSearchResult<List<String>> result = resultParcel.getResult();
                                if (result.isSuccess()) {
                                    Set<String> namespaces =
                                            new ArraySet<>(result.getResultValue());
                                    callback.accept(
                                            AppSearchResult.newSuccessfulResult(namespaces));
                                } else {
                                    callback.accept(AppSearchResult.newFailedResult(result));
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
     *                 of the returned {@link AppSearchBatchResult} are the IDs of the input
     *                 documents. The values are {@code null} if they were successfully indexed,
     *                 or a failed {@link AppSearchResult} otherwise. If an unexpected internal
     *                 error occurs in the AppSearch service,
     *                 {@link BatchResultCallback#onSystemError} will be invoked with a
     *                 {@link Throwable}.
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
            mService.putDocuments(mPackageName, mDatabaseName, documentBundles, mUserHandle,
                    /*binderCallStartTimeMillis=*/ SystemClock.elapsedRealtime(),
                    new IAppSearchBatchResultCallback.Stub() {
                        @Override
                        public void onResult(AppSearchBatchResultParcel resultParcel) {
                            executor.execute(() -> callback.onResult(resultParcel.getResult()));
                        }

                        @Override
                        public void onSystemError(AppSearchResultParcel resultParcel) {
                            executor.execute(() -> sendSystemErrorToCallback(
                                    resultParcel.getResult(), callback));
                        }
                    });
            mIsMutated = true;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @deprecated TODO(b/181887768): Exists for dogfood transition; must be removed.
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public void getByUri(
            @NonNull GetByUriRequest request,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull BatchResultCallback<String, GenericDocument> callback) {
        getByDocumentId(request.toGetByDocumentIdRequest(), executor, callback);
    }

    /**
     * Gets {@link GenericDocument} objects by document IDs in a namespace from the {@link
     * AppSearchSession} database.
     *
     * @param request a request containing a namespace and IDs to get documents for.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive the pending result of performing this operation. The keys
     *                 of the returned {@link AppSearchBatchResult} are the input IDs. The values
     *                 are the returned {@link GenericDocument}s on success, or a failed
     *                 {@link AppSearchResult} otherwise. IDs that are not found will return a
     *                 failed {@link AppSearchResult} with a result code of
     *                 {@link AppSearchResult#RESULT_NOT_FOUND}. If an unexpected internal error
     *                 occurs in the AppSearch service, {@link BatchResultCallback#onSystemError}
     *                 will be invoked with a {@link Throwable}.
     */
    public void getByDocumentId(
            @NonNull GetByDocumentIdRequest request,
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
                    new ArrayList<>(request.getIds()),
                    request.getProjectionsInternal(),
                    mUserHandle,
                    /*binderCallStartTimeMillis=*/ SystemClock.elapsedRealtime(),
                    new IAppSearchBatchResultCallback.Stub() {
                        @Override
                        public void onResult(AppSearchBatchResultParcel resultParcel) {
                            executor.execute(() -> {
                                AppSearchBatchResult<String, Bundle> result =
                                        resultParcel.getResult();
                                AppSearchBatchResult.Builder<String, GenericDocument>
                                        documentResultBuilder =
                                        new AppSearchBatchResult.Builder<>();

                                // Translate successful results
                                for (Map.Entry<String, Bundle> bundleEntry :
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
                                        ((Map<String, AppSearchResult<Bundle>>)
                                                result.getFailures()).entrySet()) {
                                    documentResultBuilder.setFailure(
                                            bundleEntry.getKey(),
                                            bundleEntry.getValue().getResultCode(),
                                            bundleEntry.getValue().getErrorMessage());
                                }
                                callback.onResult(documentResultBuilder.build());
                            });
                        }

                        @Override
                        public void onSystemError(AppSearchResultParcel result) {
                            executor.execute(
                                    () -> sendSystemErrorToCallback(result.getResult(), callback));
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves documents from the open {@link AppSearchSession} that match a given query
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
                searchSpec, mUserHandle);
    }

    /**
     * Reports usage of a particular document by namespace and ID.
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
                    request.getDocumentId(),
                    request.getUsageTimestampMillis(),
                    /*systemUsage=*/ false,
                    mUserHandle,
                    new IAppSearchResultCallback.Stub() {
                        @Override
                        public void onResult(AppSearchResultParcel resultParcel) {
                            executor.execute(() -> callback.accept(resultParcel.getResult()));
                        }
                    });
            mIsMutated = true;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @deprecated TODO(b/181887768): Exists for dogfood transition; must be removed.
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public void remove(
            @NonNull RemoveByUriRequest request,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull BatchResultCallback<String, Void> callback) {
        remove(request.toRemoveByDocumentIdRequest(), executor, callback);
    }

    /**
     * Removes {@link GenericDocument} objects by document IDs in a namespace from the {@link
     * AppSearchSession} database.
     *
     * <p>Removed documents will no longer be surfaced by {@link #search} or {@link
     * #getByDocumentId} calls.
     *
     * <p>Once the database crosses the document count or byte usage threshold, removed documents
     * will be deleted from disk.
     *
     * @param request {@link RemoveByDocumentIdRequest} with IDs in a namespace to remove from the
     *     index.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive the pending result of performing this operation. The keys
     *                 of the returned {@link AppSearchBatchResult} are the input document IDs. The
     *                 values are {@code null} on success, or a failed {@link AppSearchResult}
     *                 otherwise. IDs that are not found will return a failed
     *                 {@link AppSearchResult} with a result code of
     *                 {@link AppSearchResult#RESULT_NOT_FOUND}. If an unexpected internal error
     *                 occurs in the AppSearch service, {@link BatchResultCallback#onSystemError}
     *                 will be invoked with a {@link Throwable}.
     */
    public void remove(
            @NonNull RemoveByDocumentIdRequest request,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull BatchResultCallback<String, Void> callback) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        Preconditions.checkState(!mIsClosed, "AppSearchSession has already been closed");
        try {
            mService.removeByDocumentId(
                    mPackageName,
                    mDatabaseName,
                    request.getNamespace(),
                    new ArrayList<>(request.getIds()),
                    mUserHandle,
                    /*binderCallStartTimeMillis=*/ SystemClock.elapsedRealtime(),
                    new IAppSearchBatchResultCallback.Stub() {
                        @Override
                        public void onResult(AppSearchBatchResultParcel resultParcel) {
                            executor.execute(() -> callback.onResult(resultParcel.getResult()));
                        }

                        @Override
                        public void onSystemError(AppSearchResultParcel resultParcel) {
                            executor.execute(() -> sendSystemErrorToCallback(
                                    resultParcel.getResult(), callback));
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
            mService.removeByQuery(
                    mPackageName,
                    mDatabaseName,
                    queryExpression,
                    searchSpec.getBundle(),
                    mUserHandle,
                    /*binderCallStartTimeMillis=*/ SystemClock.elapsedRealtime(),
                    new IAppSearchResultCallback.Stub() {
                        @Override
                        public void onResult(AppSearchResultParcel resultParcel) {
                            executor.execute(() -> callback.accept(resultParcel.getResult()));
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
     * <p>This may take time proportional to the number of documents and may be inefficient to call
     * repeatedly.
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
        try {
            mService.getStorageInfo(
                    mPackageName,
                    mDatabaseName,
                    mUserHandle,
                    new IAppSearchResultCallback.Stub() {
                        @Override
                        public void onResult(AppSearchResultParcel resultParcel) {
                            executor.execute(() -> {
                                AppSearchResult<Bundle> result = resultParcel.getResult();
                                if (result.isSuccess()) {
                                    StorageInfo response = new StorageInfo(result.getResultValue());
                                    callback.accept(AppSearchResult.newSuccessfulResult(response));
                                } else {
                                    callback.accept(AppSearchResult.newFailedResult(result));
                                }
                            });
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Closes the {@link AppSearchSession} to persist all schema and document updates,
     * additions, and deletes to disk.
     */
    @Override
    public void close() {
        if (mIsMutated && !mIsClosed) {
            try {
                mService.persistToDisk(
                        mUserHandle, /*binderCallStartTimeMillis=*/ SystemClock.elapsedRealtime());
                mIsClosed = true;
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to close the AppSearchSession", e);
            }
        }
    }

    /**
     * Set schema to Icing for no-migration scenario.
     *
     * <p>We only need one time {@link #setSchema} call for no-migration scenario by using the
     * forceoverride in the request.
     */
    private void setSchemaNoMigrations(
            @NonNull SetSchemaRequest request,
            @NonNull List<Bundle> schemaBundles,
            @NonNull Map<String, List<Bundle>> schemasPackageAccessibleBundles,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<AppSearchResult<SetSchemaResponse>> callback) {
        try {
            mService.setSchema(
                    mPackageName,
                    mDatabaseName,
                    schemaBundles,
                    new ArrayList<>(request.getSchemasNotDisplayedBySystem()),
                    schemasPackageAccessibleBundles,
                    request.isForceOverride(),
                    request.getVersion(),
                    mUserHandle,
                    /*binderCallStartTimeMillis=*/ SystemClock.elapsedRealtime(),
                    new IAppSearchResultCallback.Stub() {
                        @Override
                        public void onResult(AppSearchResultParcel resultParcel) {
                            executor.execute(() -> {
                                AppSearchResult<Bundle> result = resultParcel.getResult();
                                if (result.isSuccess()) {
                                    try {
                                        SetSchemaResponse setSchemaResponse =
                                                new SetSchemaResponse(result.getResultValue());
                                        if (!request.isForceOverride()) {
                                            // Throw exception if there is any deleted types or
                                            // incompatible types. That's the only case we swallowed
                                            // in the AppSearchImpl#setSchema().
                                            SchemaMigrationUtil.checkDeletedAndIncompatible(
                                                    setSchemaResponse.getDeletedTypes(),
                                                    setSchemaResponse.getIncompatibleTypes());
                                        }
                                        callback.accept(AppSearchResult
                                                .newSuccessfulResult(setSchemaResponse));
                                    } catch (Throwable t) {
                                        callback.accept(AppSearchResult.throwableToFailedResult(t));
                                    }
                                } else {
                                    callback.accept(AppSearchResult.newFailedResult(result));
                                }
                            });
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set schema to Icing for migration scenario.
     *
     * <p>First time {@link #setSchema} call with forceOverride is false gives us all incompatible
     * changes. After trigger migrations, the second time call {@link #setSchema} will actually
     * apply the changes.
     */
    private void setSchemaWithMigrations(
            @NonNull SetSchemaRequest request,
            @NonNull List<Bundle> schemaBundles,
            @NonNull Map<String, List<Bundle>> schemasPackageAccessibleBundles,
            @NonNull Executor workExecutor,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull Consumer<AppSearchResult<SetSchemaResponse>> callback) {
        workExecutor.execute(() -> {
            try {
                // Migration process
                // 1. Validate and retrieve all active migrators.
                CompletableFuture<AppSearchResult<GetSchemaResponse>> getSchemaFuture =
                        new CompletableFuture<>();
                getSchema(callbackExecutor, getSchemaFuture::complete);
                AppSearchResult<GetSchemaResponse> getSchemaResult = getSchemaFuture.get();
                if (!getSchemaResult.isSuccess()) {
                    callbackExecutor.execute(() ->
                            callback.accept(AppSearchResult.newFailedResult(getSchemaResult)));
                    return;
                }
                GetSchemaResponse getSchemaResponse = getSchemaResult.getResultValue();
                int currentVersion = getSchemaResponse.getVersion();
                int finalVersion = request.getVersion();
                Map<String, Migrator> activeMigrators = SchemaMigrationUtil.getActiveMigrators(
                        getSchemaResponse.getSchemas(), request.getMigrators(), currentVersion,
                        finalVersion);

                // No need to trigger migration if no migrator is active.
                if (activeMigrators.isEmpty()) {
                    setSchemaNoMigrations(request, schemaBundles, schemasPackageAccessibleBundles,
                            callbackExecutor, callback);
                    return;
                }

                // 2. SetSchema with forceOverride=false, to retrieve the list of
                // incompatible/deleted types.
                CompletableFuture<AppSearchResult<Bundle>> setSchemaFuture =
                        new CompletableFuture<>();
                mService.setSchema(
                        mPackageName,
                        mDatabaseName,
                        schemaBundles,
                        new ArrayList<>(request.getSchemasNotDisplayedBySystem()),
                        schemasPackageAccessibleBundles,
                        /*forceOverride=*/ false,
                        request.getVersion(),
                        mUserHandle,
                        /*binderCallStartTimeMillis=*/ SystemClock.elapsedRealtime(),
                        new IAppSearchResultCallback.Stub() {
                            @Override
                            public void onResult(AppSearchResultParcel resultParcel) {
                                setSchemaFuture.complete(resultParcel.getResult());
                            }
                        });
                AppSearchResult<Bundle> setSchemaResult = setSchemaFuture.get();
                if (!setSchemaResult.isSuccess()) {
                    callbackExecutor.execute(() ->
                            callback.accept(AppSearchResult.newFailedResult(setSchemaResult)));
                    return;
                }
                SetSchemaResponse setSchemaResponse =
                        new SetSchemaResponse(setSchemaResult.getResultValue());

                // 3. If forceOverride is false, check that all incompatible types will be migrated.
                // If some aren't we must throw an error, rather than proceeding and deleting those
                // types.
                if (!request.isForceOverride()) {
                    SchemaMigrationUtil.checkDeletedAndIncompatibleAfterMigration(setSchemaResponse,
                            activeMigrators.keySet());
                }

                try (AppSearchMigrationHelper migrationHelper = new AppSearchMigrationHelper(
                        mService, mUserHandle, mPackageName, mDatabaseName, request.getSchemas())) {

                    // 4. Trigger migration for all migrators.
                    // TODO(b/177266929) trigger migration for all types together rather than
                    //  separately.
                    for (Map.Entry<String, Migrator> entry : activeMigrators.entrySet()) {
                        migrationHelper.queryAndTransform(/*schemaType=*/ entry.getKey(),
                                /*migrator=*/ entry.getValue(), currentVersion,
                                finalVersion);
                    }

                    // 5. SetSchema a second time with forceOverride=true if the first attempted
                    // failed.
                    if (!setSchemaResponse.getIncompatibleTypes().isEmpty()
                            || !setSchemaResponse.getDeletedTypes().isEmpty()) {
                        CompletableFuture<AppSearchResult<Bundle>> setSchema2Future =
                                new CompletableFuture<>();
                        // only trigger second setSchema() call if the first one is fail.
                        mService.setSchema(
                                mPackageName,
                                mDatabaseName,
                                schemaBundles,
                                new ArrayList<>(request.getSchemasNotDisplayedBySystem()),
                                schemasPackageAccessibleBundles,
                                /*forceOverride=*/ true,
                                request.getVersion(),
                                mUserHandle,
                                /*binderCallStartTimeMillis=*/ SystemClock.elapsedRealtime(),
                                new IAppSearchResultCallback.Stub() {
                                    @Override
                                    public void onResult(AppSearchResultParcel resultParcel) {
                                        setSchema2Future.complete(resultParcel.getResult());
                                    }
                                });
                        AppSearchResult<Bundle> setSchema2Result = setSchema2Future.get();
                        if (!setSchema2Result.isSuccess()) {
                            // we failed to set the schema in second time with forceOverride = true,
                            // which is an impossible case. Since we only swallow the incompatible
                            // error in the first setSchema call, all other errors will be thrown at
                            // the first time.
                            callbackExecutor.execute(() -> callback.accept(
                                    AppSearchResult.newFailedResult(setSchema2Result)));
                            return;
                        }
                    }

                    SetSchemaResponse.Builder responseBuilder = setSchemaResponse.toBuilder()
                            .addMigratedTypes(activeMigrators.keySet());

                    // 6. Put all the migrated documents into the index, now that the new schema is
                    // set.
                    AppSearchResult<SetSchemaResponse> putResult =
                            migrationHelper.putMigratedDocuments(responseBuilder);
                    callbackExecutor.execute(() -> callback.accept(putResult));
                }
            } catch (Throwable t) {
                callbackExecutor.execute(() -> callback.accept(
                        AppSearchResult.throwableToFailedResult(t)));
            }
        });
    }

    /**
     * Calls {@link BatchResultCallback#onSystemError} with a throwable derived from the given
     * failed {@link AppSearchResult}.
     *
     * <p>The {@link AppSearchResult} generally comes from
     * {@link IAppSearchBatchResultCallback#onSystemError}.
     *
     * <p>This method should be called from the callback executor thread.
     */
    private void sendSystemErrorToCallback(
            @NonNull AppSearchResult<?> failedResult, @NonNull BatchResultCallback<?, ?> callback) {
        Preconditions.checkArgument(!failedResult.isSuccess());
        Throwable throwable = new AppSearchException(
                failedResult.getResultCode(), failedResult.getErrorMessage());
        callback.onSystemError(throwable);
    }
}
