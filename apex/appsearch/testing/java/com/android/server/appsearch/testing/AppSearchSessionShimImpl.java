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

package com.android.server.appsearch.testing;

import android.annotation.NonNull;
import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSession;
import android.app.appsearch.AppSearchSessionShim;
import android.app.appsearch.BatchResultCallback;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.GetByUriRequest;
import android.app.appsearch.GetSchemaResponse;
import android.app.appsearch.PutDocumentsRequest;
import android.app.appsearch.RemoveByUriRequest;
import android.app.appsearch.ReportUsageRequest;
import android.app.appsearch.SearchResults;
import android.app.appsearch.SearchResultsShim;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaRequest;
import android.app.appsearch.SetSchemaResponse;
import android.app.appsearch.StorageInfo;
import android.app.appsearch.exceptions.AppSearchException;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This test class adapts the AppSearch Framework API to ListenableFuture, so it can be tested via
 * a consistent interface.
 * @hide
 */
public class AppSearchSessionShimImpl implements AppSearchSessionShim {
    private final AppSearchSession mAppSearchSession;
    private final ExecutorService mExecutor;

    @NonNull
    public static ListenableFuture<AppSearchSessionShim> createSearchSession(
            @NonNull AppSearchManager.SearchContext searchContext) {
        return createSearchSession(searchContext, Executors.newCachedThreadPool());
    }

    /**  Creates the SearchSession with given ExecutorService. */
    @NonNull
    public static ListenableFuture<AppSearchSessionShim> createSearchSession(
            @NonNull AppSearchManager.SearchContext searchContext,
            @NonNull ExecutorService executor) {
        Context context = ApplicationProvider.getApplicationContext();
        AppSearchManager appSearchManager = context.getSystemService(AppSearchManager.class);
        SettableFuture<AppSearchResult<AppSearchSession>> future = SettableFuture.create();
        appSearchManager.createSearchSession(searchContext, executor, future::set);
        return Futures.transform(
                future,
                instance -> new AppSearchSessionShimImpl(instance.getResultValue(), executor),
                executor);
    }

    private AppSearchSessionShimImpl(
            @NonNull AppSearchSession session, @NonNull ExecutorService executor) {
        mAppSearchSession = Preconditions.checkNotNull(session);
        mExecutor = Preconditions.checkNotNull(executor);
    }

    @Override
    @NonNull
    public ListenableFuture<SetSchemaResponse> setSchema(@NonNull SetSchemaRequest request) {
        SettableFuture<AppSearchResult<SetSchemaResponse>> future = SettableFuture.create();
        mAppSearchSession.setSchema(request, mExecutor, future::set);
        return Futures.transformAsync(future, this::transformResult, mExecutor);
    }

    @Override
    @NonNull
    public ListenableFuture<GetSchemaResponse> getSchema() {
        SettableFuture<AppSearchResult<GetSchemaResponse>> future = SettableFuture.create();
        mAppSearchSession.getSchema(mExecutor, future::set);
        return Futures.transformAsync(future, this::transformResult, mExecutor);
    }

    @NonNull
    @Override
    public ListenableFuture<Set<String>> getNamespaces() {
        SettableFuture<AppSearchResult<Set<String>>> future = SettableFuture.create();
        mAppSearchSession.getNamespaces(mExecutor, future::set);
        return Futures.transformAsync(future, this::transformResult, mExecutor);
    }

    @Override
    @NonNull
    public ListenableFuture<AppSearchBatchResult<String, Void>> put(
            @NonNull PutDocumentsRequest request) {
        SettableFuture<AppSearchBatchResult<String, Void>> future = SettableFuture.create();
        mAppSearchSession.put(
                request, mExecutor, new BatchResultCallbackAdapter<>(future));
        return future;
    }

    @Override
    @NonNull
    public ListenableFuture<AppSearchBatchResult<String, GenericDocument>> getByUri(
            @NonNull GetByUriRequest request) {
        SettableFuture<AppSearchBatchResult<String, GenericDocument>> future =
                SettableFuture.create();
        mAppSearchSession.getByUri(request, mExecutor, new BatchResultCallbackAdapter<>(future));
        return future;
    }

    @Override
    @NonNull
    public SearchResultsShim search(
            @NonNull String queryExpression, @NonNull SearchSpec searchSpec) {
        SearchResults searchResults = mAppSearchSession.search(queryExpression, searchSpec);
        return new SearchResultsShimImpl(searchResults, mExecutor);
    }

    @Override
    @NonNull
    public ListenableFuture<Void> reportUsage(@NonNull ReportUsageRequest request) {
        SettableFuture<AppSearchResult<Void>> future = SettableFuture.create();
        mAppSearchSession.reportUsage(request, mExecutor, future::set);
        return Futures.transformAsync(future, this::transformResult, mExecutor);
    }

    @Override
    @NonNull
    public ListenableFuture<AppSearchBatchResult<String, Void>> remove(
            @NonNull RemoveByUriRequest request) {
        SettableFuture<AppSearchBatchResult<String, Void>> future = SettableFuture.create();
        mAppSearchSession.remove(request, mExecutor, new BatchResultCallbackAdapter<>(future));
        return future;
    }

    @Override
    @NonNull
    public ListenableFuture<Void> remove(
            @NonNull String queryExpression, @NonNull SearchSpec searchSpec) {
        SettableFuture<AppSearchResult<Void>> future = SettableFuture.create();
        mAppSearchSession.remove(queryExpression, searchSpec, mExecutor, future::set);
        return Futures.transformAsync(future, this::transformResult, mExecutor);
    }

    @NonNull
    @Override
    public ListenableFuture<StorageInfo> getStorageInfo() {
        SettableFuture<AppSearchResult<StorageInfo>> future = SettableFuture.create();
        mAppSearchSession.getStorageInfo(mExecutor, future::set);
        return Futures.transformAsync(future, this::transformResult, mExecutor);
    }

    @Override
    public void close() {
        mAppSearchSession.close();
    }

    @Override
    @NonNull
    public ListenableFuture<Void> maybeFlush() {
        SettableFuture<AppSearchResult<Void>> future = SettableFuture.create();
        // The data in platform will be flushed by scheduled task. AppSearchSession won't do
        // anything extra flush.
        future.set(AppSearchResult.newSuccessfulResult(null));
        return Futures.transformAsync(future, this::transformResult, mExecutor);
    }

    private <T> ListenableFuture<T> transformResult(
            @NonNull AppSearchResult<T> result) throws AppSearchException {
        if (!result.isSuccess()) {
            throw new AppSearchException(result.getResultCode(), result.getErrorMessage());
        }
        return Futures.immediateFuture(result.getResultValue());
    }

    private static final class BatchResultCallbackAdapter<K, V>
            implements BatchResultCallback<K, V> {
        private final SettableFuture<AppSearchBatchResult<K, V>> mFuture;

        BatchResultCallbackAdapter(SettableFuture<AppSearchBatchResult<K, V>> future) {
            mFuture = future;
        }

        @Override
        public void onResult(AppSearchBatchResult<K, V> result) {
            mFuture.set(result);
        }

        @Override
        public void onSystemError(Throwable t) {
            mFuture.setException(t);
        }
    }
}
