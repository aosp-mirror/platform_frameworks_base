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

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/** A future API wrapper of {@link android.app.appsearch.SearchResults}. */
public interface FutureSearchResults extends Closeable {

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

    /**
     * Retrieves the next page of {@link SearchResult} objects from the {@link AppSearchSession}
     * database.
     *
     * <p>Continue calling this method to access results until it returns an empty list, signifying
     * there are no more results.
     */
    AndroidFuture<List<SearchResult>> getNextPage();

    @Override
    void close();
}
