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

import static com.android.server.appfunctions.AppFunctionExecutors.LOGGING_THREAD_EXECUTOR;

import android.annotation.NonNull;
import android.app.appfunctions.ExecuteAppFunctionAidlRequest;
import android.app.appfunctions.ExecuteAppFunctionResponse;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.Slog;

import java.util.Objects;

/** Wraps AppFunctionsStatsLog. */
public class AppFunctionsLoggerWrapper {
    private static final String TAG = AppFunctionsLoggerWrapper.class.getSimpleName();

    private static final int SUCCESS_RESPONSE_CODE = -1;

    private final Context mContext;

    public AppFunctionsLoggerWrapper(@NonNull Context context) {
        mContext = Objects.requireNonNull(context);
    }

    void logAppFunctionSuccess(ExecuteAppFunctionAidlRequest request,
            ExecuteAppFunctionResponse response, int callingUid) {
        logAppFunctionsRequestReported(request, SUCCESS_RESPONSE_CODE,
                response.getResponseDataSize(), callingUid);
    }

    void logAppFunctionError(ExecuteAppFunctionAidlRequest request, int errorCode, int callingUid) {
        logAppFunctionsRequestReported(request, errorCode, /* responseSizeBytes = */ 0, callingUid);
    }

    private void logAppFunctionsRequestReported(ExecuteAppFunctionAidlRequest request,
            int errorCode, int responseSizeBytes, int callingUid) {
        final long latency = SystemClock.elapsedRealtime() - request.getRequestTime();
        LOGGING_THREAD_EXECUTOR.execute(() -> AppFunctionsStatsLog.write(
                AppFunctionsStatsLog.APP_FUNCTIONS_REQUEST_REPORTED,
                callingUid,
                getPackageUid(request.getClientRequest().getTargetPackageName()),
                errorCode,
                request.getClientRequest().getRequestDataSize(), responseSizeBytes,
                latency)
        );
    }

    private int getPackageUid(String packageName) {
        try {
            return mContext.getPackageManager().getPackageUid(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Package uid not found for " + packageName);
        }
        return 0;
    }
}
