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

import static com.android.server.appfunctions.AppFunctionExecutors.THREAD_POOL_EXECUTOR;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.app.appfunctions.AppFunctionStaticMetadataHelper;
import android.app.appfunctions.ExecuteAppFunctionAidlRequest;
import android.app.appfunctions.ExecuteAppFunctionResponse;
import android.app.appfunctions.IAppFunctionManager;
import android.app.appfunctions.IAppFunctionService;
import android.app.appfunctions.IExecuteAppFunctionCallback;
import android.app.appfunctions.SafeOneTimeExecuteAppFunctionCallback;
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.observer.DocumentChangeInfo;
import android.app.appsearch.observer.ObserverCallback;
import android.app.appsearch.observer.ObserverSpec;
import android.app.appsearch.observer.SchemaChangeInfo;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemService.TargetUser;
import com.android.server.appfunctions.RemoteServiceCaller.RunServiceCallCallback;
import com.android.server.appfunctions.RemoteServiceCaller.ServiceUsageCompleteListener;

import java.util.Objects;
import java.util.concurrent.CompletionException;

/** Implementation of the AppFunctionManagerService. */
public class AppFunctionManagerServiceImpl extends IAppFunctionManager.Stub {
    private static final String TAG = AppFunctionManagerServiceImpl.class.getSimpleName();

    private final RemoteServiceCaller<IAppFunctionService> mRemoteServiceCaller;
    private final CallerValidator mCallerValidator;
    private final ServiceHelper mInternalServiceHelper;
    private final ServiceConfig mServiceConfig;
    private final Context mContext;

    public AppFunctionManagerServiceImpl(@NonNull Context context) {
        this(
                context,
                new RemoteServiceCallerImpl<>(
                        context, IAppFunctionService.Stub::asInterface, THREAD_POOL_EXECUTOR),
                new CallerValidatorImpl(context),
                new ServiceHelperImpl(context),
                new ServiceConfigImpl());
    }

    @VisibleForTesting
    AppFunctionManagerServiceImpl(
            Context context,
            RemoteServiceCaller<IAppFunctionService> remoteServiceCaller,
            CallerValidator callerValidator,
            ServiceHelper appFunctionInternalServiceHelper,
            ServiceConfig serviceConfig) {
        mContext = Objects.requireNonNull(context);
        mRemoteServiceCaller = Objects.requireNonNull(remoteServiceCaller);
        mCallerValidator = Objects.requireNonNull(callerValidator);
        mInternalServiceHelper = Objects.requireNonNull(appFunctionInternalServiceHelper);
        mServiceConfig = serviceConfig;
    }

    /** Called when the user is unlocked. */
    public void onUserUnlocked(TargetUser user) {
        Objects.requireNonNull(user);

        registerAppSearchObserver(user);
        trySyncRuntimeMetadata(user);
    }

    /** Called when the user is stopping. */
    public void onUserStopping(@NonNull TargetUser user) {
        Objects.requireNonNull(user);

        MetadataSyncPerUser.removeUserSyncAdapter(user.getUserHandle());
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
        try {
            validatedCallingPackage =
                    mCallerValidator.validateCallingPackage(requestInternal.getCallingPackage());
            mCallerValidator.verifyTargetUserHandle(
                    requestInternal.getUserHandle(), validatedCallingPackage);
        } catch (SecurityException exception) {
            safeExecuteAppFunctionCallback.onResult(
                    ExecuteAppFunctionResponse.newFailure(
                            ExecuteAppFunctionResponse.RESULT_DENIED,
                            exception.getMessage(),
                            /* extras= */ null));
            return;
        }

        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingUid();
        THREAD_POOL_EXECUTOR.execute(
                () -> {
                    try {
                        executeAppFunctionInternal(
                                requestInternal,
                                callingUid,
                                callingPid,
                                safeExecuteAppFunctionCallback);
                    } catch (Exception e) {
                        safeExecuteAppFunctionCallback.onResult(
                                mapExceptionToExecuteAppFunctionResponse(e));
                    }
                });
    }

    @WorkerThread
    private void executeAppFunctionInternal(
            ExecuteAppFunctionAidlRequest requestInternal,
            int callingUid,
            int callingPid,
            SafeOneTimeExecuteAppFunctionCallback safeExecuteAppFunctionCallback) {
        UserHandle targetUser = requestInternal.getUserHandle();
        // TODO(b/354956319): Add and honor the new enterprise policies.
        if (mCallerValidator.isUserOrganizationManaged(targetUser)) {
            safeExecuteAppFunctionCallback.onResult(
                    ExecuteAppFunctionResponse.newFailure(
                            ExecuteAppFunctionResponse.RESULT_INTERNAL_ERROR,
                            "Cannot run on a device with a device owner or from the managed"
                                    + " profile.",
                            /* extras= */ null));
            return;
        }

        String targetPackageName = requestInternal.getClientRequest().getTargetPackageName();
        if (TextUtils.isEmpty(targetPackageName)) {
            safeExecuteAppFunctionCallback.onResult(
                    ExecuteAppFunctionResponse.newFailure(
                            ExecuteAppFunctionResponse.RESULT_INVALID_ARGUMENT,
                            "Target package name cannot be empty.",
                            /* extras= */ null));
            return;
        }

        var unused =
                mCallerValidator
                        .verifyCallerCanExecuteAppFunction(
                                callingUid,
                                callingPid,
                                requestInternal.getCallingPackage(),
                                targetPackageName,
                                requestInternal.getClientRequest().getFunctionIdentifier())
                        .thenAccept(
                                canExecute -> {
                                    if (!canExecute) {
                                        safeExecuteAppFunctionCallback.onResult(
                                                ExecuteAppFunctionResponse.newFailure(
                                                        ExecuteAppFunctionResponse.RESULT_DENIED,
                                                        "Caller does not have permission to execute"
                                                                + " the appfunction",
                                                        /* extras= */ null));
                                        return;
                                    }
                                    Intent serviceIntent =
                                            mInternalServiceHelper.resolveAppFunctionService(
                                                    targetPackageName, targetUser);
                                    if (serviceIntent == null) {
                                        safeExecuteAppFunctionCallback.onResult(
                                                ExecuteAppFunctionResponse.newFailure(
                                                        ExecuteAppFunctionResponse
                                                                .RESULT_INTERNAL_ERROR,
                                                        "Cannot find the target service.",
                                                        /* extras= */ null));
                                        return;
                                    }
                                    bindAppFunctionServiceUnchecked(
                                            requestInternal,
                                            serviceIntent,
                                            targetUser,
                                            safeExecuteAppFunctionCallback,
                                            /* bindFlags= */ Context.BIND_AUTO_CREATE);
                                })
                        .exceptionally(
                                ex -> {
                                    safeExecuteAppFunctionCallback.onResult(
                                            mapExceptionToExecuteAppFunctionResponse(ex));
                                    return null;
                                });
    }

    private void bindAppFunctionServiceUnchecked(
            @NonNull ExecuteAppFunctionAidlRequest requestInternal,
            @NonNull Intent serviceIntent,
            @NonNull UserHandle targetUser,
            @NonNull SafeOneTimeExecuteAppFunctionCallback safeExecuteAppFunctionCallback,
            int bindFlags) {
        boolean bindServiceResult =
                mRemoteServiceCaller.runServiceCall(
                        serviceIntent,
                        bindFlags,
                        targetUser,
                        new RunServiceCallCallback<IAppFunctionService>() {
                            @Override
                            public void onServiceConnected(
                                    @NonNull IAppFunctionService service,
                                    @NonNull
                                            ServiceUsageCompleteListener
                                                    serviceUsageCompleteListener) {
                                try {
                                    service.executeAppFunction(
                                            requestInternal.getClientRequest(),
                                            new IExecuteAppFunctionCallback.Stub() {
                                                @Override
                                                public void onResult(
                                                        ExecuteAppFunctionResponse response) {
                                                    safeExecuteAppFunctionCallback.onResult(
                                                            response);
                                                    serviceUsageCompleteListener.onCompleted();
                                                }
                                            });
                                } catch (Exception e) {
                                    safeExecuteAppFunctionCallback.onResult(
                                            ExecuteAppFunctionResponse.newFailure(
                                                    ExecuteAppFunctionResponse
                                                            .RESULT_APP_UNKNOWN_ERROR,
                                                    e.getMessage(),
                                                    /* extras= */ null));
                                    serviceUsageCompleteListener.onCompleted();
                                }
                            }

                            @Override
                            public void onFailedToConnect() {
                                Slog.e(TAG, "Failed to connect to service");
                                safeExecuteAppFunctionCallback.onResult(
                                        ExecuteAppFunctionResponse.newFailure(
                                                ExecuteAppFunctionResponse.RESULT_APP_UNKNOWN_ERROR,
                                                "Failed to connect to AppFunctionService",
                                                /* extras= */ null));
                            }
                        });

        if (!bindServiceResult) {
            Slog.e(TAG, "Failed to bind to the AppFunctionService");
            safeExecuteAppFunctionCallback.onResult(
                    ExecuteAppFunctionResponse.newFailure(
                            ExecuteAppFunctionResponse.RESULT_TIMED_OUT,
                            "Failed to bind the AppFunctionService.",
                            /* extras= */ null));
        }
    }

    private ExecuteAppFunctionResponse mapExceptionToExecuteAppFunctionResponse(Throwable e) {
        if (e instanceof CompletionException) {
            e = e.getCause();
        }

        if (e instanceof AppSearchException) {
            AppSearchException appSearchException = (AppSearchException) e;
            return ExecuteAppFunctionResponse.newFailure(
                    mapAppSearchResultFailureCodeToExecuteAppFunctionResponse(
                            appSearchException.getResultCode()),
                    appSearchException.getMessage(),
                    /* extras= */ null);
        }

        return ExecuteAppFunctionResponse.newFailure(
                ExecuteAppFunctionResponse.RESULT_INTERNAL_ERROR,
                e.getMessage(),
                /* extras= */ null);
    }

    private int mapAppSearchResultFailureCodeToExecuteAppFunctionResponse(int resultCode) {
        if (resultCode == AppSearchResult.RESULT_OK) {
            throw new IllegalArgumentException(
                    "This method can only be used to convert failure result codes.");
        }

        switch (resultCode) {
            case AppSearchResult.RESULT_NOT_FOUND:
                return ExecuteAppFunctionResponse.RESULT_INVALID_ARGUMENT;
            case AppSearchResult.RESULT_INVALID_ARGUMENT:
            case AppSearchResult.RESULT_INTERNAL_ERROR:
            case AppSearchResult.RESULT_SECURITY_ERROR:
                // fall-through
        }
        return ExecuteAppFunctionResponse.RESULT_INTERNAL_ERROR;
    }

    private void registerAppSearchObserver(@NonNull TargetUser user) {
        AppSearchManager perUserAppSearchManager =
                mContext.createContextAsUser(user.getUserHandle(), /* flags= */ 0)
                        .getSystemService(AppSearchManager.class);
        if (perUserAppSearchManager == null) {
            Slog.d(TAG, "AppSearch Manager not found for user: " + user.getUserIdentifier());
            return;
        }
        FutureGlobalSearchSession futureGlobalSearchSession =
                new FutureGlobalSearchSession(
                        perUserAppSearchManager, AppFunctionExecutors.THREAD_POOL_EXECUTOR);
        AppFunctionMetadataObserver appFunctionMetadataObserver =
                new AppFunctionMetadataObserver(
                        user.getUserHandle(),
                        mContext.createContextAsUser(user.getUserHandle(), /* flags= */ 0));
        var unused =
                futureGlobalSearchSession
                        .registerObserverCallbackAsync(
                                "android",
                                new ObserverSpec.Builder().build(),
                                THREAD_POOL_EXECUTOR,
                                appFunctionMetadataObserver)
                        .whenComplete(
                                (voidResult, ex) -> {
                                    if (ex != null) {
                                        Slog.e(TAG, "Failed to register observer: ", ex);
                                    }
                                    futureGlobalSearchSession.close();
                                });
    }

    private void trySyncRuntimeMetadata(@NonNull TargetUser user) {
        MetadataSyncAdapter metadataSyncAdapter =
                MetadataSyncPerUser.getPerUserMetadataSyncAdapter(
                        user.getUserHandle(),
                        mContext.createContextAsUser(user.getUserHandle(), /* flags= */ 0));
        if (metadataSyncAdapter != null) {
            var unused =
                    metadataSyncAdapter
                            .submitSyncRequest()
                            .whenComplete(
                                    (isSuccess, ex) -> {
                                        if (ex != null || !isSuccess) {
                                            Slog.e(TAG, "Sync was not successful");
                                        }
                                    });
        }
    }

    private static class AppFunctionMetadataObserver implements ObserverCallback {
        @Nullable private final MetadataSyncAdapter mPerUserMetadataSyncAdapter;

        AppFunctionMetadataObserver(@NonNull UserHandle userHandle, @NonNull Context userContext) {
            mPerUserMetadataSyncAdapter =
                    MetadataSyncPerUser.getPerUserMetadataSyncAdapter(userHandle, userContext);
        }

        @Override
        public void onDocumentChanged(@NonNull DocumentChangeInfo documentChangeInfo) {
            if (mPerUserMetadataSyncAdapter == null) {
                return;
            }
            if (documentChangeInfo
                            .getDatabaseName()
                            .equals(AppFunctionStaticMetadataHelper.APP_FUNCTION_STATIC_METADATA_DB)
                    && documentChangeInfo
                            .getNamespace()
                            .equals(
                                    AppFunctionStaticMetadataHelper
                                            .APP_FUNCTION_STATIC_NAMESPACE)) {
                var unused = mPerUserMetadataSyncAdapter.submitSyncRequest();
            }
        }

        @Override
        public void onSchemaChanged(@NonNull SchemaChangeInfo schemaChangeInfo) {
            if (mPerUserMetadataSyncAdapter == null) {
                return;
            }
            if (schemaChangeInfo
                    .getDatabaseName()
                    .equals(AppFunctionStaticMetadataHelper.APP_FUNCTION_STATIC_METADATA_DB)) {
                boolean shouldInitiateSync = false;
                for (String schemaName : schemaChangeInfo.getChangedSchemaNames()) {
                    if (schemaName.startsWith(AppFunctionStaticMetadataHelper.STATIC_SCHEMA_TYPE)) {
                        shouldInitiateSync = true;
                        break;
                    }
                }
                if (shouldInitiateSync) {
                    var unused = mPerUserMetadataSyncAdapter.submitSyncRequest();
                }
            }
        }
    }
}
