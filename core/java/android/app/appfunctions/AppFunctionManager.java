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
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.annotation.UserHandleAware;
import android.content.Context;
import android.os.RemoteException;

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
    private final IAppFunctionManager mService;
    private final Context mContext;

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
     */
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
            @NonNull Consumer<ExecuteAppFunctionResponse> callback) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        ExecuteAppFunctionAidlRequest aidlRequest =
                new ExecuteAppFunctionAidlRequest(
                        request, mContext.getUser(), mContext.getPackageName());
        try {
            mService.executeAppFunction(
                    aidlRequest,
                    new IExecuteAppFunctionCallback.Stub() {
                        @Override
                        public void onResult(ExecuteAppFunctionResponse result) {
                            try {
                                executor.execute(() -> callback.accept(result));
                            } catch (RuntimeException e) {
                                // Ideally shouldn't happen since errors are wrapped into the
                                // response, but we catch it here for additional safety.
                                callback.accept(
                                        ExecuteAppFunctionResponse.newFailure(
                                                getResultCode(e),
                                                e.getMessage(),
                                                /* extras= */ null));
                            }
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
