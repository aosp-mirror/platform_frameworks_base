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
import android.app.appfunctions.AppFunctionException;
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
    private static final String TAG = RunAppFunctionServiceCallback.class.getSimpleName();

    private final ExecuteAppFunctionAidlRequest mRequestInternal;
    private final SafeOneTimeExecuteAppFunctionCallback mSafeExecuteAppFunctionCallback;
    private final ICancellationCallback mCancellationCallback;

    public RunAppFunctionServiceCallback(
            ExecuteAppFunctionAidlRequest requestInternal,
            ICancellationCallback cancellationCallback,
            SafeOneTimeExecuteAppFunctionCallback safeExecuteAppFunctionCallback) {
        this.mRequestInternal = requestInternal;
        this.mSafeExecuteAppFunctionCallback = safeExecuteAppFunctionCallback;
        this.mCancellationCallback = cancellationCallback;
    }

    @Override
    public void onServiceConnected(
            @NonNull IAppFunctionService service,
            @NonNull ServiceUsageCompleteListener serviceUsageCompleteListener) {
        try {
            service.executeAppFunction(
                    mRequestInternal.getClientRequest(),
                    mRequestInternal.getCallingPackage(),
                    mCancellationCallback,
                    new IExecuteAppFunctionCallback.Stub() {
                        @Override
                        public void onSuccess(ExecuteAppFunctionResponse response) {
                            mSafeExecuteAppFunctionCallback.onResult(response);
                            serviceUsageCompleteListener.onCompleted();
                        }

                        @Override
                        public void onError(AppFunctionException error) {
                            mSafeExecuteAppFunctionCallback.onError(error);
                            serviceUsageCompleteListener.onCompleted();
                        }
                    });
        } catch (Exception e) {
            mSafeExecuteAppFunctionCallback.onError(
                    new AppFunctionException(
                            AppFunctionException.ERROR_APP_UNKNOWN_ERROR,
                            e.getMessage()));
            serviceUsageCompleteListener.onCompleted();
        }
    }

    @Override
    public void onFailedToConnect() {
        Slog.e(TAG, "Failed to connect to service");
        mSafeExecuteAppFunctionCallback.onError(
                new AppFunctionException(AppFunctionException.ERROR_APP_UNKNOWN_ERROR,
                        "Failed to connect to AppFunctionService"));
    }

    @Override
    public void onCancelled() {
        mSafeExecuteAppFunctionCallback.disable();
    }
}
