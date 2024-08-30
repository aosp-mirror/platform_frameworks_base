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
import android.app.appfunctions.IAppFunctionManager;
import android.app.appfunctions.IAppFunctionService;
import android.app.appfunctions.IExecuteAppFunctionCallback;
import android.app.appfunctions.SafeOneTimeExecuteAppFunctionCallback;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appfunctions.RemoteServiceCaller.RunServiceCallCallback;
import com.android.server.appfunctions.RemoteServiceCaller.ServiceUsageCompleteListener;

import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of the AppFunctionManagerService.
 */
public class AppFunctionManagerServiceImpl extends IAppFunctionManager.Stub {
    private static final String TAG = AppFunctionManagerServiceImpl.class.getSimpleName();

    private final RemoteServiceCaller<IAppFunctionService> mRemoteServiceCaller;
    private final CallerValidator mCallerValidator;
    private final ServiceHelper mInternalServiceHelper;
    private final ServiceConfig mServiceConfig;


    public AppFunctionManagerServiceImpl(@NonNull Context context) {
        this(new RemoteServiceCallerImpl<>(
                        context,
                        IAppFunctionService.Stub::asInterface, new ThreadPoolExecutor(
                        /*corePoolSize=*/ Runtime.getRuntime().availableProcessors(),
                        /*maxConcurrency=*/ Runtime.getRuntime().availableProcessors(),
                        /*keepAliveTime=*/ 0L,
                        /*unit=*/ TimeUnit.SECONDS,
                        /*workQueue=*/ new LinkedBlockingQueue<>())),
                new CallerValidatorImpl(context),
                new ServiceHelperImpl(context),
                new ServiceConfigImpl());
    }

    @VisibleForTesting
    AppFunctionManagerServiceImpl(RemoteServiceCaller<IAppFunctionService> remoteServiceCaller,
                                  CallerValidator callerValidator,
                                  ServiceHelper appFunctionInternalServiceHelper,
                                  ServiceConfig serviceConfig) {
        mRemoteServiceCaller = Objects.requireNonNull(remoteServiceCaller);
        mCallerValidator = Objects.requireNonNull(callerValidator);
        mInternalServiceHelper =
                Objects.requireNonNull(appFunctionInternalServiceHelper);
        mServiceConfig = serviceConfig;
    }

    @Override
    public void executeAppFunction(
            @NonNull ExecuteAppFunctionAidlRequest requestInternal,
            @NonNull IExecuteAppFunctionCallback executeAppFunctionCallback) {
        Objects.requireNonNull(requestInternal);
        Objects.requireNonNull(executeAppFunctionCallback);

        final SafeOneTimeExecuteAppFunctionCallback safeExecuteAppFunctionCallback =
                new SafeOneTimeExecuteAppFunctionCallback(executeAppFunctionCallback);

        String validatedCallingPackage;
        UserHandle targetUser;
        try {
            validatedCallingPackage = mCallerValidator
                    .validateCallingPackage(requestInternal.getCallingPackage());
            targetUser = mCallerValidator.verifyTargetUserHandle(
                    requestInternal.getUserHandle(), validatedCallingPackage);
        } catch (SecurityException exception) {
            safeExecuteAppFunctionCallback.onResult(new ExecuteAppFunctionResponse
                    .Builder(ExecuteAppFunctionResponse.RESULT_DENIED,
                    getExceptionMessage(exception)).build());
            return;
        }

        // TODO(b/354956319): Add and honor the new enterprise policies.
        if (mCallerValidator.isUserOrganizationManaged(targetUser)) {
            safeExecuteAppFunctionCallback.onResult(new ExecuteAppFunctionResponse.Builder(
                    ExecuteAppFunctionResponse.RESULT_INTERNAL_ERROR,
                    "Cannot run on a device with a device owner or from the managed profile."
            ).build());
            return;
        }

        String targetPackageName = requestInternal.getClientRequest().getTargetPackageName();
        if (TextUtils.isEmpty(targetPackageName)) {
            safeExecuteAppFunctionCallback.onResult(new ExecuteAppFunctionResponse.Builder(
                    ExecuteAppFunctionResponse.RESULT_INVALID_ARGUMENT,
                    "Target package name cannot be empty."
            ).build());
            return;
        }

        if (!mCallerValidator.verifyCallerCanExecuteAppFunction(
                validatedCallingPackage, targetPackageName)) {
            safeExecuteAppFunctionCallback.onResult(new ExecuteAppFunctionResponse
                    .Builder(ExecuteAppFunctionResponse.RESULT_DENIED,
                    "Caller does not have permission to execute the appfunction")
                    .build());
            return;
        }

        Intent serviceIntent = mInternalServiceHelper.resolveAppFunctionService(
                targetPackageName,
                targetUser);
        if (serviceIntent == null) {
            safeExecuteAppFunctionCallback.onResult(new ExecuteAppFunctionResponse.Builder(
                    ExecuteAppFunctionResponse.RESULT_INTERNAL_ERROR,
                    "Cannot find the target service."
            ).build());
            return;
        }

        final long token = Binder.clearCallingIdentity();
        try {
            bindAppFunctionServiceUnchecked(requestInternal, serviceIntent, targetUser,
                safeExecuteAppFunctionCallback,
                /*bindFlags=*/ Context.BIND_AUTO_CREATE,
                /*timeoutInMillis=*/ mServiceConfig.getExecuteAppFunctionTimeoutMillis());
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void bindAppFunctionServiceUnchecked(
            @NonNull ExecuteAppFunctionAidlRequest requestInternal,
            @NonNull Intent serviceIntent, @NonNull UserHandle targetUser,
            @NonNull SafeOneTimeExecuteAppFunctionCallback
                    safeExecuteAppFunctionCallback,
            int bindFlags, long timeoutInMillis) {
        boolean bindServiceResult = mRemoteServiceCaller.runServiceCall(
                serviceIntent,
                bindFlags,
                timeoutInMillis,
                targetUser,
                new RunServiceCallCallback<IAppFunctionService>() {
                    @Override
                    public void onServiceConnected(@NonNull IAppFunctionService service,
                                                   @NonNull ServiceUsageCompleteListener
                                                           serviceUsageCompleteListener) {
                        try {
                            service.executeAppFunction(
                                    requestInternal.getClientRequest(),
                                    new IExecuteAppFunctionCallback.Stub() {
                                        @Override
                                        public void onResult(ExecuteAppFunctionResponse response) {
                                            safeExecuteAppFunctionCallback.onResult(response);
                                            serviceUsageCompleteListener.onCompleted();
                                        }
                                    }
                            );
                        } catch (Exception e) {
                            safeExecuteAppFunctionCallback.onResult(new ExecuteAppFunctionResponse
                                    .Builder(ExecuteAppFunctionResponse.RESULT_APP_UNKNOWN_ERROR,
                                    getExceptionMessage(e)).build());
                            serviceUsageCompleteListener.onCompleted();
                        }
                    }

                    @Override
                    public void onFailedToConnect() {
                        Slog.e(TAG, "Failed to connect to service");
                        safeExecuteAppFunctionCallback.onResult(new ExecuteAppFunctionResponse
                                .Builder(ExecuteAppFunctionResponse.RESULT_APP_UNKNOWN_ERROR,
                                "Failed to connect to AppFunctionService").build());
                    }

                    @Override
                    public void onTimedOut() {
                        Slog.e(TAG, "Timed out");
                        safeExecuteAppFunctionCallback.onResult(
                                new ExecuteAppFunctionResponse.Builder(
                                        ExecuteAppFunctionResponse.RESULT_TIMED_OUT,
                                        "Binding to AppFunctionService timed out."
                                ).build());
                    }
                }
        );

        if (!bindServiceResult) {
            Slog.e(TAG, "Failed to bind to the AppFunctionService");
            safeExecuteAppFunctionCallback.onResult(new ExecuteAppFunctionResponse.Builder(
                    ExecuteAppFunctionResponse.RESULT_TIMED_OUT,
                    "Failed to bind the AppFunctionService."
            ).build());
        }
    }

    private String getExceptionMessage(Exception exception) {
        return exception.getMessage() == null ? "" : exception.getMessage();
    }
}
