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

import android.annotation.NonNull;
import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchManager.SearchContext;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSession;
import android.app.appsearch.GetSchemaResponse;
import android.app.appsearch.PutDocumentsRequest;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchResults;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaRequest;
import android.app.appsearch.SetSchemaResponse;
import android.util.Slog;

import com.android.internal.infra.AndroidFuture;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/** A future API wrapper of {@link AppSearchSession} APIs. */
public class FutureAppSearchSession implements Closeable {
    private static final String TAG = FutureAppSearchSession.class.getSimpleName();
    private final Executor mExecutor;
    private final AndroidFuture<AppSearchResult<AppSearchSession>> mSettableSessionFuture;

    public FutureAppSearchSession(
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

    /** Converts a failed app search result codes into an exception. */
    @NonNull
    public static Exception failedResultToException(@NonNull AppSearchResult<?> appSearchResult) {
        return switch (appSearchResult.getResultCode()) {
            case AppSearchResult.RESULT_INVALID_ARGUMENT ->
                    new IllegalArgumentException(appSearchResult.getErrorMessage());
            case AppSearchResult.RESULT_IO_ERROR ->
                    new IOException(appSearchResult.getErrorMessage());
            case AppSearchResult.RESULT_SECURITY_ERROR ->
                    new SecurityException(appSearchResult.getErrorMessage());
            default -> new IllegalStateException(appSearchResult.getErrorMessage());
        };
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

    /** Gets the schema for a given app search session. */
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

    /** Sets the schema for a given app search session. */
    public AndroidFuture<SetSchemaResponse> setSchema(@NonNull SetSchemaRequest setSchemaRequest) {
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

    /** Indexes documents into the AppSearchSession database. */
    public AndroidFuture<AppSearchBatchResult<String, Void>> put(
            @NonNull PutDocumentsRequest putDocumentsRequest) {
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

    /**
     * Retrieves documents from the open AppSearchSession that match a given query string and type
     * of search provided.
     */
    public AndroidFuture<FutureSearchResults> search(
            @NonNull String queryExpression, @NonNull SearchSpec searchSpec) {
        return getSessionAsync()
                .thenApply(session -> session.search(queryExpression, searchSpec))
                .thenApply(result -> new FutureSearchResults(result, mExecutor));
    }

    @Override
    public void close() throws IOException {
        try {
            getSessionAsync().get().close();
        } catch (Exception ex) {
            Slog.e(TAG, "Failed to close app search session", ex);
        }
    }

    /** A future API wrapper of {@link android.app.appsearch.SearchResults}. */
    public static class FutureSearchResults {
        private final SearchResults mSearchResults;
        private final Executor mExecutor;

        public FutureSearchResults(
                @NonNull SearchResults searchResults, @NonNull Executor executor) {
            mSearchResults = Objects.requireNonNull(searchResults);
            mExecutor = Objects.requireNonNull(executor);
        }

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
}
