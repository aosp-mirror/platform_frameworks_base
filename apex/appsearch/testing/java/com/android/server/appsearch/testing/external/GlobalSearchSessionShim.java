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

import android.annotation.NonNull;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.Closeable;

/**
 * This class provides global access to the centralized AppSearch index maintained by the system.
 *
 * <p>Apps can retrieve indexed documents through the {@link #search} API.
 */
public interface GlobalSearchSessionShim extends Closeable {
    /**
     * Retrieves documents from all AppSearch databases that the querying application has access to.
     *
     * <p>Applications can be granted access to documents by specifying {@link
     * SetSchemaRequest.Builder#setSchemaTypeVisibilityForPackage}, or {@link
     * SetSchemaRequest.Builder#setDocumentClassVisibilityForPackage} when building a schema.
     *
     * <p>Document access can also be granted to system UIs by specifying {@link
     * SetSchemaRequest.Builder#setSchemaTypeDisplayedBySystem}, or {@link
     * SetSchemaRequest.Builder#setDocumentClassDisplayedBySystem} when building a schema.
     *
     * <p>See {@link AppSearchSessionShim#search} for a detailed explanation on forming a query
     * string.
     *
     * <p>This method is lightweight. The heavy work will be done in {@link
     * SearchResultsShim#getNextPage}.
     *
     * @param queryExpression query string to search.
     * @param searchSpec spec for setting document filters, adding projection, setting term match
     *     type, etc.
     * @return a {@link SearchResultsShim} object for retrieved matched documents.
     */
    @NonNull
    SearchResultsShim search(@NonNull String queryExpression, @NonNull SearchSpec searchSpec);

    /**
     * Reports that a particular document has been used from a system surface.
     *
     * <p>See {@link AppSearchSessionShim#reportUsage} for a general description of document usage,
     * as well as an API that can be used by the app itself.
     *
     * <p>Usage reported via this method is accounted separately from usage reported via {@link
     * AppSearchSessionShim#reportUsage} and may be accessed using the constants {@link
     * SearchSpec#RANKING_STRATEGY_SYSTEM_USAGE_COUNT} and {@link
     * SearchSpec#RANKING_STRATEGY_SYSTEM_USAGE_LAST_USED_TIMESTAMP}.
     *
     * @return The pending result of performing this operation which resolves to {@code null} on
     *     success. The pending result will be completed with an {@link
     *     android.app.appsearch.exceptions.AppSearchException} with a code of {@link
     *     AppSearchResult#RESULT_SECURITY_ERROR} if this API is invoked by an app which is not part
     *     of the system.
     */
    @NonNull
    ListenableFuture<Void> reportSystemUsage(@NonNull ReportSystemUsageRequest request);

    /** Closes the {@link GlobalSearchSessionShim}. */
    @Override
    void close();
}
