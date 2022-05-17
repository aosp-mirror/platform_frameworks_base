/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.app;


import android.annotation.Nullable;
import android.os.IInterface;

import com.android.internal.infra.AndroidFuture;
import com.android.internal.infra.ServiceConnector;

import java.util.concurrent.CompletableFuture;

/**
 * Fake implementation of {@link ServiceConnector<T>} used for tests.
 *
 * Tests provide a service instance via {@link #FakeServiceConnector(IInterface)} that will be
 * connected to and used to fulfill service jobs.
 */
final class FakeServiceConnector<T extends IInterface> implements ServiceConnector<T> {
    private final T mService;
    @Nullable
    private ServiceLifecycleCallbacks mServiceLifecycleCallbacks;
    private boolean mIsConnected;
    private int mConnectCount = 0;

    FakeServiceConnector(T service) {
        mService = service;
    }

    @Override
    public boolean run(VoidJob<T> job) {
        AndroidFuture<Void> unusedFuture = post(job);
        return true;
    }

    @Override
    public AndroidFuture<Void> post(VoidJob<T> job) {
        markPossibleConnection();

        return postForResult(job);
    }

    @Override
    public <R> AndroidFuture<R> postForResult(Job<T, R> job) {
        markPossibleConnection();

        AndroidFuture<R> androidFuture = new AndroidFuture();
        try {
            androidFuture.complete(job.run(mService));
        } catch (Exception ex) {
            androidFuture.completeExceptionally(ex);
        }
        return androidFuture;
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public <R> AndroidFuture<R> postAsync(Job<T, CompletableFuture<R>> job) {
        markPossibleConnection();
        AndroidFuture<R> androidFuture = new AndroidFuture();

        try {
            CompletableFuture<R> future = job.run(mService);
            future.whenComplete((result, exception) -> {
                if (exception != null) {
                    androidFuture.completeExceptionally(exception);
                } else {
                    androidFuture.complete(result);
                }
            });
        } catch (Exception ex) {
            androidFuture.completeExceptionally(ex);
        }

        return androidFuture;
    }

    @Override
    public AndroidFuture<T> connect() {
        markPossibleConnection();
        return AndroidFuture.completedFuture(mService);
    }

    @Override
    public void unbind() {
        if (mServiceLifecycleCallbacks != null) {
            mServiceLifecycleCallbacks.onDisconnected(mService);
        }
        mIsConnected = false;
    }

    @Override
    public void setServiceLifecycleCallbacks(@Nullable ServiceLifecycleCallbacks<T> callbacks) {
        mServiceLifecycleCallbacks = callbacks;
    }

    private void markPossibleConnection() {
        if (mIsConnected) {
            return;
        }

        mConnectCount += 1;
        mIsConnected = true;

        if (mServiceLifecycleCallbacks != null) {
            mServiceLifecycleCallbacks.onConnected(mService);
        }
    }

    public void killServiceProcess() {
        if (!mIsConnected) {
            return;
        }
        mIsConnected = false;

        if (mServiceLifecycleCallbacks != null) {
            mServiceLifecycleCallbacks.onBinderDied();
        }
    }

    /**
     * Returns {@code true} if the underlying service is connected.
     */
    public boolean getIsConnected() {
        return mIsConnected;
    }

    /**
     * Returns the number of times a connection was established with the underlying service.
     */
    public int getConnectCount() {
        return mConnectCount;
    }
}
