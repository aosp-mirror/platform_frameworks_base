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

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.content.Context;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;


/**
 * Provides app functions related functionalities.
 *
 * <p>App function is a specific piece of functionality that an app offers to the system. These
 * functionalities can be integrated into various system features.
 *
 * <p>This class wraps {@link android.app.appfunctions.AppFunctionManager} functionalities and
 * exposes it here as a sidecar library (avoiding direct dependency on the platform API).
 */
// TODO(b/357551503): Implement get and set enabled app function APIs.
// TODO(b/367329899): Add sidecar library to Android B builds.
public final class AppFunctionManager {
    private final android.app.appfunctions.AppFunctionManager mManager;
    private final Context mContext;

    /**
     * Creates an instance.
     *
     * @param context A {@link Context}.
     * @throws java.lang.IllegalStateException if the underlying {@link
     *   android.app.appfunctions.AppFunctionManager} is not found.
     */
    public AppFunctionManager(Context context) {
        mContext = Objects.requireNonNull(context);
        mManager = context.getSystemService(android.app.appfunctions.AppFunctionManager.class);
        if (mManager == null) {
            throw new IllegalStateException(
                    "Underlying AppFunctionManager system service not found.");
        }
    }

    /**
     * Executes the app function.
     *
     * <p>Proxies request and response to the underlying {@link
     * android.app.appfunctions.AppFunctionManager#executeAppFunction}, converting the request and
     * response in the appropriate type required by the function.
     */
    public void executeAppFunction(
            @NonNull ExecuteAppFunctionRequest sidecarRequest,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<ExecuteAppFunctionResponse> callback) {
        Objects.requireNonNull(sidecarRequest);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        android.app.appfunctions.ExecuteAppFunctionRequest platformRequest =
                SidecarConverter.getPlatformExecuteAppFunctionRequest(sidecarRequest);
        mManager.executeAppFunction(
                platformRequest, executor, (platformResponse) -> {
                    callback.accept(SidecarConverter.getSidecarExecuteAppFunctionResponse(
                            platformResponse));
                });
    }
}
