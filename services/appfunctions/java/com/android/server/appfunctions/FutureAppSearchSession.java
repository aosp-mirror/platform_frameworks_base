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
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSession;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.GetByDocumentIdRequest;
import android.app.appsearch.GetSchemaResponse;
import android.app.appsearch.PutDocumentsRequest;
import android.app.appsearch.RemoveByDocumentIdRequest;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaRequest;
import android.app.appsearch.SetSchemaResponse;

import com.android.internal.infra.AndroidFuture;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/** A future API wrapper of {@link AppSearchSession} APIs. */
public interface FutureAppSearchSession extends Closeable {

    /** Converts a failed app search result codes into an exception. */
    @NonNull
    static Exception failedResultToException(@NonNull AppSearchResult<?> appSearchResult) {
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
     * Sets the schema that represents the organizational structure of data within the AppSearch
     * database.
     */
    AndroidFuture<SetSchemaResponse> setSchema(@NonNull SetSchemaRequest setSchemaRequest);

    /** Retrieves the schema most recently successfully provided to {@code setSchema}. */
    AndroidFuture<GetSchemaResponse> getSchema();

    /** Indexes documents into the {@link AppSearchSession} database. */
    AndroidFuture<AppSearchBatchResult<String, Void>> put(
            @NonNull PutDocumentsRequest putDocumentsRequest);

    /** Removes {@link GenericDocument} from the index by Query. */
    AndroidFuture<AppSearchBatchResult<String, Void>> remove(
            @NonNull RemoveByDocumentIdRequest removeRequest);

    /**
     * Gets {@link GenericDocument} objects by document IDs in a namespace from the {@link
     * AppSearchSession} database.
     */
    AndroidFuture<AppSearchBatchResult<String, GenericDocument>> getByDocumentId(
            @NonNull GetByDocumentIdRequest getRequest);

    /**
     * Retrieves documents from the open {@link AppSearchSession} that match a given query string
     * and type of search provided.
     */
    AndroidFuture<FutureSearchResults> search(
            @NonNull String queryExpression, @NonNull SearchSpec searchSpec);

    @Override
    void close();

    /** A future API wrapper of {@link android.app.appsearch.SearchResults}. */
    interface FutureSearchResults {

        /**
         * Retrieves the next page of {@link SearchResult} objects from the {@link AppSearchSession}
         * database.
         *
         * <p>Continue calling this method to access results until it returns an empty list,
         * signifying there are no more results.
         */
        AndroidFuture<List<SearchResult>> getNextPage();
    }
}
