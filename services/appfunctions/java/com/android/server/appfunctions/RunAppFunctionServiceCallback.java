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
import android.app.appfunctions.ExecuteAppFunctionAidlRequest;
import android.app.appfunctions.ExecuteAppFunctionResponse;
import android.app.appfunctions.IAppFunctionService;
import android.app.appfunctions.ICancellationCallback;
import android.app.appfunctions.IExecuteAppFunctionCallback;
import android.app.appfunctions.SafeOneTimeExecuteAppFunctionCallback;
import android.util.Slog;

import com.android.server.appfunctions.RemoteServiceCaller.RunServiceCallCallback;
import com.android.server.appfunctions.RemoteServiceCaller.ServiceUsageCompleteListener;


/**
 * A callback to forward a request to the {@link IAppFunctionService} and report back the result.
 */
public class RunAppFunctionServiceCallback implements RunServiceCallCallback<IAppFunctionService> {

    private final ExecuteAppFunctionAidlRequest mRequestInternal;
    private final SafeOneTimeExecuteAppFunctionCallback mSafeExecuteAppFunctionCallback;
    private final ICancellationCallback mCancellationCallback;

    private RunAppFunctionServiceCallback(
            ExecuteAppFunctionAidlRequest requestInternal,
            ICancellationCallback cancellationCallback,
            SafeOneTimeExecuteAppFunctionCallback safeExecuteAppFunctionCallback) {
        this.mRequestInternal = requestInternal;
        this.mSafeExecuteAppFunctionCallback = safeExecuteAppFunctionCallback;
        this.mCancellationCallback = cancellationCallback;
    }

    /**
     * Creates a new instance of {@link RunAppFunctionServiceCallback}.
     *
     * @param requestInternal a request to send to the service.
     * @param cancellationCallback a callback to forward cancellation signal to the service.
     * @param safeExecuteAppFunctionCallback a callback to report back the result of the operation.
     */
    public static RunAppFunctionServiceCallback create(
            ExecuteAppFunctionAidlRequest requestInternal,
            ICancellationCallback cancellationCallback,
            SafeOneTimeExecuteAppFunctionCallback safeExecuteAppFunctionCallback) {
        return new RunAppFunctionServiceCallback(
                requestInternal, cancellationCallback, safeExecuteAppFunctionCallback);
    }

    @Override
    public void onServiceConnected(
            @NonNull IAppFunctionService service,
            @NonNull ServiceUsageCompleteListener serviceUsageCompleteListener) {
        try {
            service.executeAppFunction(
                    mRequestInternal.getClientRequest(),
                    mCancellationCallback,
                    new IExecuteAppFunctionCallback.Stub() {
                        @Override
                        public void onResult(ExecuteAppFunctionResponse response) {
                            mSafeExecuteAppFunctionCallback.onResult(response);
                            serviceUsageCompleteListener.onCompleted();
                        }
                    });
        } catch (Exception e) {
            mSafeExecuteAppFunctionCallback.onResult(
                    ExecuteAppFunctionResponse.newFailure(
                            ExecuteAppFunctionResponse.RESULT_APP_UNKNOWN_ERROR,
                            e.getMessage(),
                            /* extras= */ null));
            serviceUsageCompleteListener.onCompleted();
        }
    }

    @Override
    public void onFailedToConnect() {
        Slog.e("AppFunctionManagerServiceImpl", "Failed to connect to service");
        mSafeExecuteAppFunctionCallback.onResult(
                ExecuteAppFunctionResponse.newFailure(
                        ExecuteAppFunctionResponse.RESULT_APP_UNKNOWN_ERROR,
                        "Failed to connect to AppFunctionService",
                        /* extras= */ null));
    }

    @Override
    public void onCancelled() {
        mSafeExecuteAppFunctionCallback.disable();
    }
}
