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
import android.os.IInterface;
import android.util.Slog;

import java.io.PrintWriter;

/**
 * Base class representing a remote service that can have only one pending requests while not bound.
 *
 * <p>If another request is received while not bound, the previous one will be canceled.
 *
 * @param <S> the concrete remote service class
 * @param <I> the interface of the binder service
 *
 * @hide
 */
public abstract class AbstractSinglePendingRequestRemoteService<S
        extends AbstractSinglePendingRequestRemoteService<S, I>, I extends IInterface>
        extends AbstractRemoteService<S, I> {

    protected BasePendingRequest<S, I> mPendingRequest;

    public AbstractSinglePendingRequestRemoteService(@NonNull Context context,
            @NonNull String serviceInterface, @NonNull ComponentName componentName, int userId,
            @NonNull VultureCallback<S> callback, boolean bindInstantServiceAllowed,
            boolean verbose) {
        super(context, serviceInterface, componentName, userId, callback, bindInstantServiceAllowed,
                verbose);
    }

    @Override // from AbstractRemoteService
    void handlePendingRequests() {
        if (mPendingRequest != null) {
            final BasePendingRequest<S, I> pendingRequest = mPendingRequest;
            mPendingRequest = null;
            handlePendingRequest(pendingRequest);
        }
    }

    @Override // from AbstractRemoteService
    protected void handleOnDestroy() {
        if (mPendingRequest != null) {
            mPendingRequest.cancel();
            mPendingRequest = null;
        }
    }

    @Override // from AbstractRemoteService
    public void dump(@NonNull String prefix, @NonNull PrintWriter pw) {
        super.dump(prefix, pw);
        pw.append(prefix).append("hasPendingRequest=")
                .append(String.valueOf(mPendingRequest != null)).println();
    }

    @Override // from AbstractRemoteService
    void handlePendingRequestWhileUnBound(@NonNull BasePendingRequest<S, I> pendingRequest) {
        if (mPendingRequest != null) {
            if (mVerbose) {
                Slog.v(mTag, "handlePendingRequestWhileUnBound(): cancelling " + mPendingRequest
                        + " to handle " + pendingRequest);
            }
            mPendingRequest.cancel();
        }
        mPendingRequest = pendingRequest;
    }
}
