/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.internal.infra;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.IInterface;
import android.util.Slog;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class representing a remote service that can queue multiple pending requests while not
 * bound.
 *
 * @param <S> the concrete remote service class
 * @param <I> the interface of the binder service
 *
 * @deprecated Use {@link ServiceConnector} to manage remote service connections
 */
@Deprecated
public abstract class AbstractMultiplePendingRequestsRemoteService<S
        extends AbstractMultiplePendingRequestsRemoteService<S, I>, I extends IInterface>
        extends AbstractRemoteService<S, I> {

    private final int mInitialCapacity;

    protected @NonNull List<BasePendingRequest<S, I>> mPendingRequests;

    public AbstractMultiplePendingRequestsRemoteService(@NonNull Context context,
            @NonNull String serviceInterface, @NonNull ComponentName componentName, int userId,
            @NonNull VultureCallback<S> callback, @NonNull Handler handler,
            int bindingFlags, boolean verbose, int initialCapacity) {
        super(context, serviceInterface, componentName, userId, callback, handler, bindingFlags,
                verbose);
        mInitialCapacity = initialCapacity;
        mPendingRequests = new ArrayList<>(mInitialCapacity);
    }

    @Override // from AbstractRemoteService
    void handlePendingRequests() {
        synchronized (mPendingRequests) {
            final int size = mPendingRequests.size();
            if (mVerbose) Slog.v(mTag, "Sending " + size + " pending requests");
            for (int i = 0; i < size; i++) {
                mPendingRequests.get(i).run();
            }
            mPendingRequests.clear();
        }
    }

    @Override // from AbstractRemoteService
    protected void handleOnDestroy() {
        synchronized (mPendingRequests) {
            final int size = mPendingRequests.size();
            if (mVerbose) Slog.v(mTag, "Canceling " + size + " pending requests");
            for (int i = 0; i < size; i++) {
                mPendingRequests.get(i).cancel();
            }
            mPendingRequests.clear();
        }
    }

    @Override // from AbstractRemoteService
    final void handleBindFailure() {
        synchronized (mPendingRequests) {
            final int size = mPendingRequests.size();
            if (mVerbose) Slog.v(mTag, "Sending failure to " + size + " pending requests");
            for (int i = 0; i < size; i++) {
                final BasePendingRequest<S, I> request = mPendingRequests.get(i);
                request.onFailed();
                request.finish();
            }
            mPendingRequests.clear();
        }
    }

    @Override // from AbstractRemoteService
    public void dump(@NonNull String prefix, @NonNull PrintWriter pw) {
        super.dump(prefix, pw);

        pw.append(prefix).append("initialCapacity=").append(String.valueOf(mInitialCapacity))
                .println();
        int size;
        synchronized (mPendingRequests) {
            size = mPendingRequests.size();
        }
        pw.append(prefix).append("pendingRequests=").append(String.valueOf(size)).println();
    }

    @Override // from AbstractRemoteService
    void handlePendingRequestWhileUnBound(@NonNull BasePendingRequest<S, I> pendingRequest) {
        synchronized (mPendingRequests) {
            mPendingRequests.add(pendingRequest);
            if (mVerbose) {
                Slog.v(mTag,
                        "queued " + mPendingRequests.size() + " requests; last=" + pendingRequest);
            }
        }
    }
}
