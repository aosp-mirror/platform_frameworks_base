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

package com.google.android.appfunctions.sidecar;

import android.annotation.NonNull;

/**
 * Utility class containing methods to convert Sidecar objects of AppFunctions API into the
 * underlying platform classes.
 *
 * @hide
 */
public final class SidecarConverter {
    private SidecarConverter() {}

    /**
     * Converts sidecar's {@link com.google.android.appfunctions.sidecar.ExecuteAppFunctionRequest}
     * into platform's {@link android.app.appfunctions.ExecuteAppFunctionRequest}
     *
     * @hide
     */
    @NonNull
    public static android.app.appfunctions.ExecuteAppFunctionRequest
            getPlatformExecuteAppFunctionRequest(@NonNull ExecuteAppFunctionRequest request) {
        return new
                android.app.appfunctions.ExecuteAppFunctionRequest.Builder(
                request.getTargetPackageName(),
                request.getFunctionIdentifier())
                .setExtras(request.getExtras())
                .setParameters(request.getParameters())
                .build();
    }

    /**
     * Converts sidecar's {@link com.google.android.appfunctions.sidecar.ExecuteAppFunctionResponse}
     * into platform's {@link android.app.appfunctions.ExecuteAppFunctionResponse}
     *
     * @hide
     */
    @NonNull
    public static android.app.appfunctions.ExecuteAppFunctionResponse
            getPlatformExecuteAppFunctionResponse(@NonNull ExecuteAppFunctionResponse response) {
        if (response.isSuccess()) {
            return android.app.appfunctions.ExecuteAppFunctionResponse.newSuccess(
                    response.getResultDocument(), response.getExtras());
        } else {
            return android.app.appfunctions.ExecuteAppFunctionResponse.newFailure(
                    response.getResultCode(),
                    response.getErrorMessage(),
                    response.getExtras());
        }
    }

    /**
     * Converts platform's {@link android.app.appfunctions.ExecuteAppFunctionRequest}
     * into sidecar's {@link com.google.android.appfunctions.sidecar.ExecuteAppFunctionRequest}
     *
     * @hide
     */
    @NonNull
    public static ExecuteAppFunctionRequest getSidecarExecuteAppFunctionRequest(
            @NonNull android.app.appfunctions.ExecuteAppFunctionRequest request) {
        return new ExecuteAppFunctionRequest.Builder(
                request.getTargetPackageName(),
                request.getFunctionIdentifier())
                .setExtras(request.getExtras())
                .setParameters(request.getParameters())
                .build();
    }

    /**
     * Converts platform's {@link android.app.appfunctions.ExecuteAppFunctionResponse}
     * into sidecar's {@link com.google.android.appfunctions.sidecar.ExecuteAppFunctionResponse}
     *
     * @hide
     */
    @NonNull
    public static ExecuteAppFunctionResponse getSidecarExecuteAppFunctionResponse(
            @NonNull android.app.appfunctions.ExecuteAppFunctionResponse response) {
        if (response.isSuccess()) {
            return ExecuteAppFunctionResponse.newSuccess(
                    response.getResultDocument(), response.getExtras());
        } else {
            return ExecuteAppFunctionResponse.newFailure(
                    response.getResultCode(),
                    response.getErrorMessage(),
                    response.getExtras());
        }
    }
}
