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
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSession;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchResults;

import com.android.internal.infra.AndroidFuture;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;

public class FutureSearchResultsImpl implements FutureSearchResults {
    private final SearchResults mSearchResults;
    private final Executor mExecutor;

    public FutureSearchResultsImpl(
            @NonNull SearchResults searchResults, @NonNull Executor executor) {
        this.mSearchResults = searchResults;
        this.mExecutor = executor;
    }

    @Override
    public AndroidFuture<List<SearchResult>> getNextPage() {
        AndroidFuture<AppSearchResult<List<SearchResult>>> nextPageFuture = new AndroidFuture<>();

        mSearchResults.getNextPage(mExecutor, nextPageFuture::complete);
        return nextPageFuture
                .thenApply(
                        result -> {
                            if (result.isSuccess()) {
                                return result.getResultValue();
                            } else {
                                throw new RuntimeException(
                                        FutureSearchResults.failedResultToException(result));
                            }
                        });
    }

    @Override
    public void close() {
        mSearchResults.close();
    }
}
