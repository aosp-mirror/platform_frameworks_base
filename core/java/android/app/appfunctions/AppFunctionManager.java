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

import static android.app.appfunctions.AppFunctionException.ERROR_SYSTEM_ERROR;
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
import android.os.SystemClock;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Provides access to app functions.
 *
 * <p>An app function is a piece of functionality that apps expose to the system for cross-app
 * orchestration.
 *
 * <p>**Building App Functions:**
 *
 * <p>Most developers should build app functions through the AppFunctions SDK. This SDK library
 * offers a more convenient and type-safe way to build app functions. The SDK provides predefined
 * function schemas for common use cases and associated data classes for function parameters and
 * return values. Apps only have to implement the provided interfaces. Internally, the SDK converts
 * these data classes into {@link ExecuteAppFunctionRequest#getParameters()} and {@link
 * ExecuteAppFunctionResponse#getResultDocument()}.
 *
 * <p>**Discovering App Functions:**
 *
 * <p>When there is a package change or the device starts up, the metadata of available functions is
 * indexed on-device by {@link AppSearchManager}. AppSearch stores the indexed information as an
 * {@code AppFunctionStaticMetadata} document. This document contains the {@code functionIdentifier}
 * and the schema information that the app function implements. This allows other apps and the app
 * itself to discover these functions using the AppSearch search APIs. Visibility to this metadata
 * document is based on the packages that have visibility to the app providing the app functions.
 * AppFunction SDK provides a convenient way to achieve this and is the preferred method.
 *
 * <p>**Executing App Functions:**
 *
 * <p>To execute an app function, the caller app can retrieve the {@code functionIdentifier} from
 * the {@code AppFunctionStaticMetadata} document and use it to build an {@link
 * ExecuteAppFunctionRequest}. Then, invoke {@link #executeAppFunction} with the request to execute
 * the app function. Callers need the {@code android.permission.EXECUTE_APP_FUNCTIONS} or {@code
 * android.permission.EXECUTE_APP_FUNCTIONS_TRUSTED} permission to execute app functions from other
 * apps. An app can always execute its own app functions and doesn't need these permissions.
 * AppFunction SDK provides a convenient way to achieve this and is the preferred method.
 *
 * <p>**Example:**
 *
 * <p>An assistant app is trying to fulfill the user request "Save XYZ into my note". The assistant
 * app should first list all available app functions as {@code AppFunctionStaticMetadata} documents
 * from AppSearch. Then, it should identify an app function that implements the {@code CreateNote}
 * schema. Finally, the assistant app can invoke {@link #executeAppFunction} with the {@code
 * functionIdentifier} of the chosen function.
 */
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
     * @param cancellationSignal the cancellation signal to cancel the execution.
     * @param callback the callback to receive the function execution result or error.
     *     <p>If the calling app does not own the app function or does not have {@code
     *     android.permission.EXECUTE_APP_FUNCTIONS_TRUSTED} or {@code
     *     android.permission.EXECUTE_APP_FUNCTIONS}, the execution result will contain {@code
     *     AppFunctionException.ERROR_DENIED}.
     *     <p>If the caller only has {@code android.permission.EXECUTE_APP_FUNCTIONS} but the
     *     function requires {@code android.permission.EXECUTE_APP_FUNCTIONS_TRUSTED}, the execution
     *     result will contain {@code AppFunctionException.ERROR_DENIED}
     *     <p>If the function requested for execution is disabled, then the execution result will
     *     contain {@code AppFunctionException.ERROR_DISABLED}
     *     <p>If the cancellation signal is issued, the operation is cancelled and no response is
     *     returned to the caller.
     */
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
            @NonNull
                    OutcomeReceiver<ExecuteAppFunctionResponse, AppFunctionException>
                            callback) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        ExecuteAppFunctionAidlRequest aidlRequest =
                new ExecuteAppFunctionAidlRequest(
                        request, mContext.getUser(), mContext.getPackageName(),
                        /* requestTime= */ SystemClock.elapsedRealtime());

        try {
            ICancellationSignal cancellationTransport =
                    mService.executeAppFunction(
                            aidlRequest,
                            new IExecuteAppFunctionCallback.Stub() {
                                @Override
                                public void onSuccess(ExecuteAppFunctionResponse result) {
                                    try {
                                        executor.execute(() -> callback.onResult(result));
                                    } catch (RuntimeException e) {
                                        // Ideally shouldn't happen since errors are wrapped into
                                        // the response, but we catch it here for additional safety.
                                        executor.execute(
                                                () ->
                                                        callback.onError(
                                                                new AppFunctionException(
                                                                        ERROR_SYSTEM_ERROR,
                                                                        e.getMessage())));
                                    }
                                }

                                @Override
                                public void onError(AppFunctionException exception) {
                                    executor.execute(() -> callback.onError(exception));
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
     * <p>This method can only check app functions owned by the caller, or those where the caller
     * has visibility to the owner package and holds either the {@link
     * Manifest.permission#EXECUTE_APP_FUNCTIONS} or {@link
     * Manifest.permission#EXECUTE_APP_FUNCTIONS_TRUSTED} permission.
     *
     * <p>If the operation fails, the callback's {@link OutcomeReceiver#onError} is called with
     * errors:
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
        isAppFunctionEnabledInternal(functionIdentifier, targetPackage, executor, callback);
    }

    /**
     * Returns a boolean through a callback, indicating whether the app function is enabled.
     *
     * <p>This method can only check app functions owned by the caller, unlike {@link
     * #isAppFunctionEnabled(String, String, Executor, OutcomeReceiver)}, which allows specifying a
     * different target package.
     *
     * <p>If the operation fails, the callback's {@link OutcomeReceiver#onError} is called with
     * errors:
     *
     * <ul>
     *   <li>{@link IllegalArgumentException}, if the function is not found or the caller does not
     *       have access to it.
     * </ul>
     *
     * @param functionIdentifier the identifier of the app function to check (unique within the
     *     target package) and in most cases, these are automatically generated by the AppFunctions
     *     SDK
     * @param executor the executor to run the request
     * @param callback the callback to receive the function enabled check result
     */
    public void isAppFunctionEnabled(
            @NonNull String functionIdentifier,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Boolean, Exception> callback) {
        isAppFunctionEnabledInternal(
                functionIdentifier, mContext.getPackageName(), executor, callback);
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

    private void isAppFunctionEnabledInternal(
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
