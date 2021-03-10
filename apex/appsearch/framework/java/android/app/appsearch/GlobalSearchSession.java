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
import android.os.RemoteException;

import com.android.internal.util.Preconditions;

import java.io.Closeable;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * This class provides global access to the centralized AppSearch index maintained by the system.
 *
 * <p>Apps can retrieve indexed documents through the {@link #search} API.
 */
public class GlobalSearchSession implements Closeable {

    private final IAppSearchManager mService;

    @UserIdInt
    private final int mUserId;
    private boolean mIsClosed = false;

    private final String mPackageName;

    /**
     * Creates a search session for the client, defined by the {@code userId} and
     * {@code packageName}.
     */
    static void createGlobalSearchSession(
            @NonNull IAppSearchManager service,
            @UserIdInt int userId,
            @NonNull String packageName,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<AppSearchResult<GlobalSearchSession>> callback) {
        GlobalSearchSession globalSearchSession = new GlobalSearchSession(service, userId,
                packageName);
        globalSearchSession.initialize(executor, callback);
    }

    // NOTE: No instance of this class should be created or returned except via initialize().
    // Once the callback.accept has been called here, the class is ready to use.
    private void initialize(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<AppSearchResult<GlobalSearchSession>> callback) {
        try {
            mService.initialize(mUserId, new IAppSearchResultCallback.Stub() {
                public void onResult(AppSearchResult result) {
                    executor.execute(() -> {
                        if (result.isSuccess()) {
                            callback.accept(
                                    AppSearchResult.newSuccessfulResult(GlobalSearchSession.this));
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

    private GlobalSearchSession(@NonNull IAppSearchManager service, @UserIdInt int userId,
            @NonNull String packageName) {
        mService = service;
        mUserId = userId;
        mPackageName = packageName;
    }

    /**
     * Retrieves documents from all AppSearch databases that the querying application has access to.
     *
     * <p>Applications can be granted access to documents by specifying {@link
     * SetSchemaRequest.Builder#setSchemaTypeVisibilityForPackage} when building a schema.
     *
     * <p>Document access can also be granted to system UIs by specifying {@link
     * SetSchemaRequest.Builder#setSchemaTypeDisplayedBySystem} when building a schema.
     *
     * <p>See {@link AppSearchSession#search} for a detailed explanation on forming a query string.
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
        Preconditions.checkState(!mIsClosed, "GlobalSearchSession has already been closed");
        return new SearchResults(mService, mPackageName, /*databaseName=*/null, queryExpression,
                searchSpec, mUserId);
    }

    /** Closes the {@link GlobalSearchSession}. */
    @Override
    public void close() {
        mIsClosed = true;
    }
}
