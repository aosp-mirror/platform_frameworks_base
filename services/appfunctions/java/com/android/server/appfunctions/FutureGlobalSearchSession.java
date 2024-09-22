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
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.GlobalSearchSession;
import android.app.appsearch.exceptions.AppSearchException;
import android.app.appsearch.observer.ObserverCallback;
import android.app.appsearch.observer.ObserverSpec;

import com.android.internal.infra.AndroidFuture;

import java.io.Closeable;
import java.util.concurrent.Executor;

/** A wrapper around {@link GlobalSearchSession} that provides a future-based API. */
public class FutureGlobalSearchSession implements Closeable {
    private static final String TAG = FutureGlobalSearchSession.class.getSimpleName();
    private final Executor mExecutor;
    private final AndroidFuture<AppSearchResult<GlobalSearchSession>> mSettableSessionFuture;

    public FutureGlobalSearchSession(
            @NonNull AppSearchManager appSearchManager, @NonNull Executor executor) {
        this.mExecutor = executor;
        mSettableSessionFuture = new AndroidFuture<>();
        appSearchManager.createGlobalSearchSession(mExecutor, mSettableSessionFuture::complete);
    }

    private AndroidFuture<GlobalSearchSession> getSessionAsync() {
        return mSettableSessionFuture.thenApply(
                result -> {
                    if (result.isSuccess()) {
                        return result.getResultValue();
                    } else {
                        throw new RuntimeException(
                                FutureAppSearchSession.failedResultToException(result));
                    }
                });
    }

    /**
     * Registers an observer callback for the given target package name.
     *
     * @param targetPackageName The package name of the target app.
     * @param spec The observer spec.
     * @param executor The executor to run the observer callback on.
     * @param observer The observer callback to register.
     * @return A future that completes once the observer is registered.
     */
    public AndroidFuture<Void> registerObserverCallbackAsync(
            String targetPackageName,
            ObserverSpec spec,
            Executor executor,
            ObserverCallback observer) {
        return getSessionAsync()
                .thenCompose(
                        session -> {
                            try {
                                session.registerObserverCallback(
                                        targetPackageName, spec, executor, observer);
                                return AndroidFuture.completedFuture(null);
                            } catch (AppSearchException e) {
                                throw new RuntimeException(e);
                            }
                        });
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
}
