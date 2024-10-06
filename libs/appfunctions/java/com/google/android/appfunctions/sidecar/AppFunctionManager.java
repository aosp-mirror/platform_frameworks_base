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

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.UserHandleAware;
import android.content.Context;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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
     */
    public void executeAppFunction(
            @NonNull ExecuteAppFunctionRequest sidecarRequest,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull Consumer<ExecuteAppFunctionResponse> callback) {
        Objects.requireNonNull(sidecarRequest);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        android.app.appfunctions.ExecuteAppFunctionRequest platformRequest =
                SidecarConverter.getPlatformExecuteAppFunctionRequest(sidecarRequest);
        mManager.executeAppFunction(
                platformRequest,
                executor,
                cancellationSignal,
                (platformResponse) -> {
                    callback.accept(
                            SidecarConverter.getSidecarExecuteAppFunctionResponse(
                                    platformResponse));
                });
    }

    /**
     * Executes the app function.
     *
     * <p>Proxies request and response to the underlying {@link
     * android.app.appfunctions.AppFunctionManager#executeAppFunction}, converting the request and
     * response in the appropriate type required by the function.
     *
     * @deprecated Use {@link #executeAppFunction(ExecuteAppFunctionRequest, Executor,
     *     CancellationSignal, Consumer)} instead. This method will be removed once usage references
     *     are updated.
     */
    @Deprecated
    public void executeAppFunction(
            @NonNull ExecuteAppFunctionRequest sidecarRequest,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<ExecuteAppFunctionResponse> callback) {
        Objects.requireNonNull(sidecarRequest);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        executeAppFunction(
                sidecarRequest,
                executor,
                new CancellationSignal(),
                callback);
    }

    /**
     * Returns a boolean through a callback, indicating whether the app function is enabled.
     *
     * <p>* This method can only check app functions owned by the caller, or those where the caller
     * has visibility to the owner package and holds either the {@link
     * Manifest.permission#EXECUTE_APP_FUNCTIONS} or {@link
     * Manifest.permission#EXECUTE_APP_FUNCTIONS_TRUSTED} permission.
     *
     * <p>If operation fails, the callback's {@link OutcomeReceiver#onError} is called with errors:
     *
     * <ul>
     *   <li>{@link IllegalArgumentException}, if the function is not found or the caller does not
     *       have access to it.
     * </ul>
     *
     * @param functionIdentifier the identifier of the app function to check (unique within the
     *     target package) and in most cases, these are automatically generated by the AppFunctions
     *     SDK
     * @param targetPackage the package name of the app function's owner
     * @param executor the executor to run the request
     * @param callback the callback to receive the function enabled check result
     */
    public void isAppFunctionEnabled(
            @NonNull String functionIdentifier,
            @NonNull String targetPackage,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Boolean, Exception> callback) {
        mManager.isAppFunctionEnabled(functionIdentifier, targetPackage, executor, callback);
    }

    /**
     * Sets the enabled state of the app function owned by the calling package.
     *
     * <p>If operation fails, the callback's {@link OutcomeReceiver#onError} is called with errors:
     *
     * <ul>
     *   <li>{@link IllegalArgumentException}, if the function is not found or the caller does not
     *       have access to it.
     * </ul>
     *
     * @param functionIdentifier the identifier of the app function to enable (unique within the
     *     calling package). In most cases, identifiers are automatically generated by the
     *     AppFunctions SDK
     * @param newEnabledState the new state of the app function
     * @param executor the executor to run the callback
     * @param callback the callback to receive the result of the function enablement. The call was
     *     successful if no exception was thrown.
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
