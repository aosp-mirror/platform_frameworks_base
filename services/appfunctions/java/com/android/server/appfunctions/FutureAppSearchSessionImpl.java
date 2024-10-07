/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.appfunctions;

import static com.android.server.appfunctions.FutureAppSearchSession.failedResultToException;

import android.annotation.NonNull;
import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchManager.SearchContext;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSession;
import android.app.appsearch.BatchResultCallback;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.GetByDocumentIdRequest;
import android.app.appsearch.GetSchemaResponse;
import android.app.appsearch.PutDocumentsRequest;
import android.app.appsearch.RemoveByDocumentIdRequest;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchResults;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaRequest;
import android.app.appsearch.SetSchemaResponse;

import com.android.internal.infra.AndroidFuture;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/** Implementation of {@link FutureAppSearchSession} */
public class FutureAppSearchSessionImpl implements FutureAppSearchSession {

    private static final String TAG = FutureAppSearchSession.class.getSimpleName();
    private final Executor mExecutor;
    private final AndroidFuture<AppSearchResult<AppSearchSession>> mSettableSessionFuture;

    public FutureAppSearchSessionImpl(
            @NonNull AppSearchManager appSearchManager,
            @NonNull Executor executor,
            @NonNull SearchContext appSearchContext) {
        Objects.requireNonNull(appSearchManager);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(appSearchContext);

        mExecutor = executor;
        mSettableSessionFuture = new AndroidFuture<>();
        appSearchManager.createSearchSession(
                appSearchContext, mExecutor, mSettableSessionFuture::complete);
    }

    private AndroidFuture<AppSearchSession> getSessionAsync() {
        return mSettableSessionFuture.thenApply(
                result -> {
                    if (result.isSuccess()) {
                        return result.getResultValue();
                    } else {
                        throw new RuntimeException(failedResultToException(result));
                    }
                });
    }

    @Override
    public AndroidFuture<SetSchemaResponse> setSchema(@NonNull SetSchemaRequest setSchemaRequest) {
        Objects.requireNonNull(setSchemaRequest);

        return getSessionAsync()
                .thenCompose(
                        session -> {
                            AndroidFuture<AppSearchResult<SetSchemaResponse>>
                                    settableSchemaResponse = new AndroidFuture<>();
                            session.setSchema(
                                    setSchemaRequest,
                                    mExecutor,
                                    mExecutor,
                                    settableSchemaResponse::complete);
                            return settableSchemaResponse.thenApply(
                                    result -> {
                                        if (result.isSuccess()) {
                                            return result.getResultValue();
                                        } else {
                                            throw new RuntimeException(
                                                    failedResultToException(result));
                                        }
                                    });
                        });
    }

    @Override
    public AndroidFuture<GetSchemaResponse> getSchema() {
        return getSessionAsync()
                .thenCompose(
                        session -> {
                            AndroidFuture<AppSearchResult<GetSchemaResponse>>
                                    settableSchemaResponse = new AndroidFuture<>();
                            session.getSchema(mExecutor, settableSchemaResponse::complete);
                            return settableSchemaResponse.thenApply(
                                    result -> {
                                        if (result.isSuccess()) {
                                            return result.getResultValue();
                                        } else {
                                            throw new RuntimeException(
                                                    failedResultToException(result));
                                        }
                                    });
                        });
    }

    @Override
    public AndroidFuture<AppSearchBatchResult<String, Void>> put(
            @NonNull PutDocumentsRequest putDocumentsRequest) {
        Objects.requireNonNull(putDocumentsRequest);

        return getSessionAsync()
                .thenCompose(
                        session -> {
                            AndroidFuture<AppSearchBatchResult<String, Void>> batchResultFuture =
                                    new AndroidFuture<>();

                            session.put(
                                    putDocumentsRequest, mExecutor, batchResultFuture::complete);
                            return batchResultFuture;
                        });
    }

    @Override
    public AndroidFuture<AppSearchBatchResult<String, Void>> remove(
            @NonNull RemoveByDocumentIdRequest removeRequest) {
        Objects.requireNonNull(removeRequest);

        return getSessionAsync()
                .thenCompose(
                        session -> {
                            AndroidFuture<AppSearchBatchResult<String, Void>>
                                    settableBatchResultFuture = new AndroidFuture<>();
                            session.remove(
                                    removeRequest,
                                    mExecutor,
                                    new BatchResultCallbackAdapter<>(settableBatchResultFuture));
                            return settableBatchResultFuture;
                        });
    }

    @Override
    public AndroidFuture<AppSearchBatchResult<String, GenericDocument>> getByDocumentId(
            @NonNull GetByDocumentIdRequest getRequest) {
        Objects.requireNonNull(getRequest);

        return getSessionAsync()
                .thenCompose(
                        session -> {
                            AndroidFuture<AppSearchBatchResult<String, GenericDocument>>
                                    batchResultFuture = new AndroidFuture<>();
                            session.getByDocumentId(
                                    getRequest,
                                    mExecutor,
                                    new BatchResultCallbackAdapter<>(batchResultFuture));
                            return batchResultFuture;
                        });
    }

    @Override
    public AndroidFuture<FutureSearchResults> search(
            @NonNull String queryExpression, @NonNull SearchSpec searchSpec) {
        return getSessionAsync()
                .thenApply(session -> session.search(queryExpression, searchSpec))
                .thenApply(result -> new FutureSearchResultsImpl(result, mExecutor));
    }

    @Override
    public void close() {
        getSessionAsync()
                .whenComplete(
                        (appSearchSession, throwable) -> {
                            if (appSearchSession != null) {
                                appSearchSession.close();
                            }
                        });
    }

    private static final class FutureSearchResultsImpl implements FutureSearchResults {
        private final SearchResults mSearchResults;
        private final Executor mExecutor;

        private FutureSearchResultsImpl(
                @NonNull SearchResults searchResults, @NonNull Executor executor) {
            this.mSearchResults = searchResults;
            this.mExecutor = executor;
        }

        @Override
        public AndroidFuture<List<SearchResult>> getNextPage() {
            AndroidFuture<AppSearchResult<List<SearchResult>>> nextPageFuture =
                    new AndroidFuture<>();

            mSearchResults.getNextPage(mExecutor, nextPageFuture::complete);
            return nextPageFuture.thenApply(
                    result -> {
                        if (result.isSuccess()) {
                            return result.getResultValue();
                        } else {
                            throw new RuntimeException(failedResultToException(result));
                        }
                    });
        }
    }

    private static final class BatchResultCallbackAdapter<K, V>
            implements BatchResultCallback<K, V> {
        private final AndroidFuture<AppSearchBatchResult<K, V>> mFuture;

        BatchResultCallbackAdapter(AndroidFuture<AppSearchBatchResult<K, V>> future) {
            mFuture = future;
        }

        @Override
        public void onResult(@NonNull AppSearchBatchResult<K, V> result) {
            mFuture.complete(result);
        }

        @Override
        public void onSystemError(Throwable t) {
            mFuture.completeExceptionally(t);
        }
    }
}
