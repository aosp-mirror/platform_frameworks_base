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

import static android.app.appfunctions.flags.Flags.FLAG_ENABLE_APP_FUNCTION_MANAGER;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.WorkerThread;
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchManager.SearchContext;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSession;
import android.app.appsearch.GetSchemaResponse;
import android.app.appsearch.SetSchemaRequest;
import android.app.appsearch.SetSchemaResponse;
import android.util.Slog;

import com.android.internal.infra.AndroidFuture;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Helper class for interacting with a system server local appsearch session asynchronously.
 *
 * <p>Converts the AppSearch Callback API to {@link AndroidFuture}.
 */
@FlaggedApi(FLAG_ENABLE_APP_FUNCTION_MANAGER)
public class SyncAppSearchCallHelper implements Closeable {
    private static final String TAG = SyncAppSearchCallHelper.class.getSimpleName();
    private final Executor mExecutor;
    private final AppSearchManager mAppSearchManager;
    private final AndroidFuture<AppSearchResult<AppSearchSession>> mSettableSessionFuture;

    public SyncAppSearchCallHelper(
            @NonNull AppSearchManager appSearchManager,
            @NonNull Executor executor,
            @NonNull SearchContext appSearchContext) {
        Objects.requireNonNull(appSearchManager);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(appSearchContext);

        mExecutor = executor;
        mAppSearchManager = appSearchManager;
        mSettableSessionFuture = new AndroidFuture<>();
        mAppSearchManager.createSearchSession(
                appSearchContext, mExecutor, mSettableSessionFuture::complete);
    }

    /** Converts a failed app search result codes into an exception. */
    @NonNull
    private static Exception failedResultToException(@NonNull AppSearchResult appSearchResult) {
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
                .thenComposeAsync(
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
                        },
                        mExecutor);
    }

    /** Sets the schema for a given app search session. */
    public AndroidFuture<SetSchemaResponse> setSchema(@NonNull SetSchemaRequest setSchemaRequest) {
        return getSessionAsync()
                .thenComposeAsync(
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
                        },
                        mExecutor);
    }

    @Override
    public void close() throws IOException {
        try {
            getSessionAsync().get().close();
        } catch (Exception ex) {
            Slog.e(TAG, "Failed to close app search session", ex);
        }
    }
}
