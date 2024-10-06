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

package android.app.appfunctions;

import static android.app.appfunctions.ExecuteAppFunctionResponse.getResultCode;
import static android.app.appfunctions.flags.Flags.FLAG_ENABLE_APP_FUNCTION_MANAGER;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.annotation.UserHandleAware;
import android.app.appsearch.AppSearchManager;
import android.content.Context;
import android.os.CancellationSignal;
import android.os.ICancellationSignal;
import android.os.OutcomeReceiver;
import android.os.ParcelableException;
import android.os.RemoteException;

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
 */
// TODO(b/357551503): Implement get and set enabled app function APIs.
@FlaggedApi(FLAG_ENABLE_APP_FUNCTION_MANAGER)
@SystemService(Context.APP_FUNCTION_SERVICE)
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

    private final IAppFunctionManager mService;
    private final Context mContext;

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

    /**
     * Creates an instance.
     *
     * @param service An interface to the backing service.
     * @param context A {@link Context}.
     * @hide
     */
    public AppFunctionManager(IAppFunctionManager service, Context context) {
        mService = service;
        mContext = context;
    }

    /**
     * Executes the app function.
     *
     * <p>Note: Applications can execute functions they define. To execute functions defined in
     * another component, apps would need to have {@code
     * android.permission.EXECUTE_APP_FUNCTIONS_TRUSTED} or {@code
     * android.permission.EXECUTE_APP_FUNCTIONS}.
     *
     * @param request the request to execute the app function
     * @param executor the executor to run the callback
     * @param callback the callback to receive the function execution result. if the calling app
     *     does not own the app function or does not have {@code
     *     android.permission.EXECUTE_APP_FUNCTIONS_TRUSTED} or {@code
     *     android.permission.EXECUTE_APP_FUNCTIONS}, the execution result will contain {@code
     *     ExecuteAppFunctionResponse.RESULT_DENIED}.
     * @deprecated Use {@link #executeAppFunction(ExecuteAppFunctionRequest, Executor,
     *     CancellationSignal, Consumer)} instead. This method will be removed once usage references
     *     are updated.
     */
    @RequiresPermission(
            anyOf = {
                Manifest.permission.EXECUTE_APP_FUNCTIONS_TRUSTED,
                Manifest.permission.EXECUTE_APP_FUNCTIONS
            },
            conditional = true)
    @UserHandleAware
    @Deprecated
    public void executeAppFunction(
            @NonNull ExecuteAppFunctionRequest request,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<ExecuteAppFunctionResponse> callback) {
        executeAppFunction(request, executor, new CancellationSignal(), callback);
    }

    /**
     * Executes the app function.
     *
     * <p>Note: Applications can execute functions they define. To execute functions defined in
     * another component, apps would need to have {@code
     * android.permission.EXECUTE_APP_FUNCTIONS_TRUSTED} or {@code
     * android.permission.EXECUTE_APP_FUNCTIONS}.
     *
     * @param request the request to execute the app function
     * @param executor the executor to run the callback
     * @param cancellationSignal the cancellation signal to cancel the execution.
     * @param callback the callback to receive the function execution result. if the calling app
     *     does not own the app function or does not have {@code
     *     android.permission.EXECUTE_APP_FUNCTIONS_TRUSTED} or {@code
     *     android.permission.EXECUTE_APP_FUNCTIONS}, the execution result will contain {@code
     *     ExecuteAppFunctionResponse.RESULT_DENIED}.
     */
    // TODO(b/357551503): Document the behavior when the cancellation signal is issued.
    // TODO(b/360864791): Document that apps can opt-out from being executed by callers with
    //   EXECUTE_APP_FUNCTIONS and how a caller knows whether a function is opted out.
    // TODO(b/357551503): Update documentation when get / set APIs are implemented that this will
    //   also return RESULT_DENIED if the app function is disabled.
    @RequiresPermission(
            anyOf = {
                Manifest.permission.EXECUTE_APP_FUNCTIONS_TRUSTED,
                Manifest.permission.EXECUTE_APP_FUNCTIONS
            },
            conditional = true)
    @UserHandleAware
    public void executeAppFunction(
            @NonNull ExecuteAppFunctionRequest request,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull Consumer<ExecuteAppFunctionResponse> callback) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        ExecuteAppFunctionAidlRequest aidlRequest =
                new ExecuteAppFunctionAidlRequest(
                        request, mContext.getUser(), mContext.getPackageName());

        try {
            ICancellationSignal cancellationTransport =
                    mService.executeAppFunction(
                            aidlRequest,
                            new IExecuteAppFunctionCallback.Stub() {
                                @Override
                                public void onResult(ExecuteAppFunctionResponse result) {
                                    try {
                                        executor.execute(() -> callback.accept(result));
                                    } catch (RuntimeException e) {
                                        // Ideally shouldn't happen since errors are wrapped into
                                        // the
                                        // response, but we catch it here for additional safety.
                                        callback.accept(
                                                ExecuteAppFunctionResponse.newFailure(
                                                        getResultCode(e),
                                                        e.getMessage(),
                                                        /* extras= */ null));
                                    }
                                }
                            });
            if (cancellationTransport != null) {
                cancellationSignal.setRemote(cancellationTransport);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
        Objects.requireNonNull(functionIdentifier);
        Objects.requireNonNull(targetPackage);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        AppSearchManager appSearchManager = mContext.getSystemService(AppSearchManager.class);
        if (appSearchManager == null) {
            callback.onError(new IllegalStateException("Failed to get AppSearchManager."));
            return;
        }

        AppFunctionManagerHelper.isAppFunctionEnabled(
                functionIdentifier, targetPackage, appSearchManager, executor, callback);
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
    @UserHandleAware
    public void setAppFunctionEnabled(
            @NonNull String functionIdentifier,
            @EnabledState int newEnabledState,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Void, Exception> callback) {
        Objects.requireNonNull(functionIdentifier);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        CallbackWrapper callbackWrapper = new CallbackWrapper(executor, callback);
        try {
            mService.setAppFunctionEnabled(
                    mContext.getPackageName(),
                    functionIdentifier,
                    mContext.getUser(),
                    newEnabledState,
                    callbackWrapper);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static class CallbackWrapper extends IAppFunctionEnabledCallback.Stub {

        private final OutcomeReceiver<Void, Exception> mCallback;
        private final Executor mExecutor;

        CallbackWrapper(
                @NonNull Executor callbackExecutor,
                @NonNull OutcomeReceiver<Void, Exception> callback) {
            mCallback = callback;
            mExecutor = callbackExecutor;
        }

        @Override
        public void onSuccess() {
            mExecutor.execute(() -> mCallback.onResult(null));
        }

        @Override
        public void onError(@NonNull ParcelableException exception) {
            mExecutor.execute(
                    () -> {
                        if (IllegalArgumentException.class.isAssignableFrom(
                                exception.getCause().getClass())) {
                            mCallback.onError((IllegalArgumentException) exception.getCause());
                        } else if (SecurityException.class.isAssignableFrom(
                                exception.getCause().getClass())) {
                            mCallback.onError((SecurityException) exception.getCause());
                        } else {
                            mCallback.onError(exception);
                        }
                    });
        }
    }
}
