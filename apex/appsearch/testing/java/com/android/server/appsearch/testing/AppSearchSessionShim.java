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
import android.app.appsearch.BatchResultCallback;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.GetByUriRequest;
import android.app.appsearch.PutDocumentsRequest;
import android.app.appsearch.RemoveByUriRequest;
import android.app.appsearch.SearchResults;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaRequest;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This test class adapts the AppSearch Framework API to ListenableFuture, so it can be tested via
 * a consistent interface.
 * @hide
 */
public class AppSearchSessionShim {
    private final AppSearchSession mAppSearchSession;
    private final ExecutorService mExecutor;

    @NonNull
    public static ListenableFuture<AppSearchResult<AppSearchSessionShim>> createSearchSession(
            @NonNull AppSearchManager.SearchContext searchContext) {
        Context context = ApplicationProvider.getApplicationContext();
        AppSearchManager appSearchManager = context.getSystemService(AppSearchManager.class);
        SettableFuture<AppSearchResult<AppSearchSession>> future = SettableFuture.create();
        ExecutorService executor = Executors.newCachedThreadPool();
        appSearchManager.createSearchSession(searchContext, executor, future::set);
        return Futures.transform(future, (instance) -> {
            if (!instance.isSuccess()) {
                return AppSearchResult.newFailedResult(
                        instance.getResultCode(), instance.getErrorMessage());
            }
            AppSearchSession searchSession = instance.getResultValue();
            AppSearchSessionShim shim = new AppSearchSessionShim(searchSession, executor);
            return AppSearchResult.newSuccessfulResult(shim);
        }, executor);
    }

    private AppSearchSessionShim(
            @NonNull AppSearchSession session, @NonNull ExecutorService executor) {
        mAppSearchSession = Preconditions.checkNotNull(session);
        mExecutor = Preconditions.checkNotNull(executor);
    }

    @NonNull
    public ListenableFuture<AppSearchResult<Void>> setSchema(@NonNull SetSchemaRequest request) {
        SettableFuture<AppSearchResult<Void>> future = SettableFuture.create();
        mAppSearchSession.setSchema(request, mExecutor, future::set);
        return future;
    }

    @NonNull
    public ListenableFuture<AppSearchBatchResult<String, Void>> putDocuments(
            @NonNull PutDocumentsRequest request) {
        SettableFuture<AppSearchBatchResult<String, Void>> future = SettableFuture.create();
        mAppSearchSession.putDocuments(
                request, mExecutor, new BatchResultCallbackAdapter<>(future));
        return future;
    }

    @NonNull
    public ListenableFuture<AppSearchBatchResult<String, GenericDocument>> getByUri(
            @NonNull GetByUriRequest request) {
        SettableFuture<AppSearchBatchResult<String, GenericDocument>> future =
                SettableFuture.create();
        mAppSearchSession.getByUri(request, mExecutor, new BatchResultCallbackAdapter<>(future));
        return future;
    }

    @NonNull
    public SearchResultsShim query(
            @NonNull String queryExpression, @NonNull SearchSpec searchSpec) {
        SearchResults searchResults =
                mAppSearchSession.query(queryExpression, searchSpec, mExecutor);
        return new SearchResultsShim(searchResults);
    }

    @NonNull
    public ListenableFuture<AppSearchBatchResult<String, Void>> removeByUri(
            @NonNull RemoveByUriRequest request) {
        SettableFuture<AppSearchBatchResult<String, Void>> future = SettableFuture.create();
        mAppSearchSession.removeByUri(request, mExecutor, new BatchResultCallbackAdapter<>(future));
        return future;
    }

    @NonNull
    public ListenableFuture<AppSearchResult<Void>> removeByQuery(
            @NonNull String queryExpression, @NonNull SearchSpec searchSpec) {
        SettableFuture<AppSearchResult<Void>> future = SettableFuture.create();
        mAppSearchSession.removeByQuery(queryExpression, searchSpec, mExecutor, future::set);
        return future;
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
