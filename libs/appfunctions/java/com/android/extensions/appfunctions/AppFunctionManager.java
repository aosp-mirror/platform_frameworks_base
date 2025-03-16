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

package com.android.extensions.appfunctions;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.UserHandleAware;
import android.content.Context;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Provides app functions related functionalities.
 *
 * <p>App function is a specific piece of functionality that an app offers to the system. These
 * functionalities can be integrated into various system features.
 *
 * <p>This class wraps {@link android.app.appfunctions.AppFunctionManager} functionalities and
 * exposes it here as a sidecar library (avoiding direct dependency on the platform API).
 */
public final class AppFunctionManager {
    /**
     * The default state of the app function. Call {@link #setAppFunctionEnabled} with this to reset
     * enabled state to the default value.
     */
    public static final int APP_FUNCTION_STATE_DEFAULT = 0;

    /**
     * The app function is enabled. To enable an app function, call {@link #setAppFunctionEnabled}
     * with this value.
     */
    public static final int APP_FUNCTION_STATE_ENABLED = 1;

    /**
     * The app function is disabled. To disable an app function, call {@link #setAppFunctionEnabled}
     * with this value.
     */
    public static final int APP_FUNCTION_STATE_DISABLED = 2;

    /**
     * The enabled state of the app function.
     *
     * @hide
     */
    @IntDef(
            prefix = {"APP_FUNCTION_STATE_"},
            value = {
                APP_FUNCTION_STATE_DEFAULT,
                APP_FUNCTION_STATE_ENABLED,
                APP_FUNCTION_STATE_DISABLED
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EnabledState {}

    private final android.app.appfunctions.AppFunctionManager mManager;
    private final Context mContext;

    /**
     * Creates an instance.
     *
     * @param context A {@link Context}.
     * @throws java.lang.IllegalStateException if the underlying {@link
     *     android.app.appfunctions.AppFunctionManager} is not found.
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
     *
     * <p>See {@link android.app.appfunctions.AppFunctionManager#executeAppFunction} for the
     * documented behaviour of this method.
     */
    @RequiresPermission(
            anyOf = {
                Manifest.permission.EXECUTE_APP_FUNCTIONS_TRUSTED,
                Manifest.permission.EXECUTE_APP_FUNCTIONS
            },
            conditional = true)
    public void executeAppFunction(
            @NonNull ExecuteAppFunctionRequest sidecarRequest,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull
                    OutcomeReceiver<ExecuteAppFunctionResponse, AppFunctionException>
                            callback) {
        Objects.requireNonNull(sidecarRequest);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        android.app.appfunctions.ExecuteAppFunctionRequest platformRequest =
                SidecarConverter.getPlatformExecuteAppFunctionRequest(sidecarRequest);
        mManager.executeAppFunction(
                platformRequest,
                executor,
                cancellationSignal,
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(
                            android.app.appfunctions.ExecuteAppFunctionResponse result) {
                        callback.onResult(
                                SidecarConverter.getSidecarExecuteAppFunctionResponse(result));
                    }

                    @Override
                    public void onError(
                            android.app.appfunctions.AppFunctionException exception) {
                        callback.onError(
                                SidecarConverter.getSidecarAppFunctionException(exception));
                    }
                });
    }

    /**
     * Returns a boolean through a callback, indicating whether the app function is enabled.
     *
     * <p>See {@link android.app.appfunctions.AppFunctionManager#isAppFunctionEnabled} for the
     * documented behaviour of this method.
     */
    @RequiresPermission(
            anyOf = {
                Manifest.permission.EXECUTE_APP_FUNCTIONS_TRUSTED,
                Manifest.permission.EXECUTE_APP_FUNCTIONS
            },
            conditional = true)
    public void isAppFunctionEnabled(
            @NonNull String functionIdentifier,
            @NonNull String targetPackage,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Boolean, Exception> callback) {
        mManager.isAppFunctionEnabled(functionIdentifier, targetPackage, executor, callback);
    }

    /**
     * Returns a boolean through a callback, indicating whether the app function is enabled.
     *
     * <p>See {@link android.app.appfunctions.AppFunctionManager#isAppFunctionEnabled} for the
     * documented behaviour of this method.
     */
    public void isAppFunctionEnabled(
            @NonNull String functionIdentifier,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Boolean, Exception> callback) {
        mManager.isAppFunctionEnabled(functionIdentifier, executor, callback);
    }

    /**
     * Sets the enabled state of the app function owned by the calling package.
     *
     * <p>See {@link android.app.appfunctions.AppFunctionManager#setAppFunctionEnabled} for the
     * documented behavoir of this method.
     */
    // Constants in @EnabledState should always mirror those in
    // android.app.appfunctions.AppFunctionManager.
    @SuppressLint("WrongConstant")
    @UserHandleAware
    public void setAppFunctionEnabled(
            @NonNull String functionIdentifier,
            @EnabledState int newEnabledState,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, Exception> callback) {
        mManager.setAppFunctionEnabled(functionIdentifier, newEnabledState, executor, callback);
    }
}
