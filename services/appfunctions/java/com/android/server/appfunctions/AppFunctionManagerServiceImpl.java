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

import static android.app.appfunctions.AppFunctionManager.APP_FUNCTION_STATE_DISABLED;
import static android.app.appfunctions.AppFunctionManager.APP_FUNCTION_STATE_ENABLED;
import static android.app.appfunctions.AppFunctionRuntimeMetadata.APP_FUNCTION_RUNTIME_METADATA_DB;
import static android.app.appfunctions.AppFunctionRuntimeMetadata.APP_FUNCTION_RUNTIME_NAMESPACE;

import static com.android.server.appfunctions.AppFunctionExecutors.THREAD_POOL_EXECUTOR;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.app.appfunctions.AppFunctionManager;
import android.app.appfunctions.AppFunctionManagerHelper;
import android.app.appfunctions.AppFunctionRuntimeMetadata;
import android.app.appfunctions.AppFunctionStaticMetadataHelper;
import android.app.appfunctions.ExecuteAppFunctionAidlRequest;
import android.app.appfunctions.ExecuteAppFunctionResponse;
import android.app.appfunctions.IAppFunctionEnabledCallback;
import android.app.appfunctions.IAppFunctionManager;
import android.app.appfunctions.IAppFunctionService;
import android.app.appfunctions.ICancellationCallback;
import android.app.appfunctions.IExecuteAppFunctionCallback;
import android.app.appfunctions.SafeOneTimeExecuteAppFunctionCallback;
import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchManager.SearchContext;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.GetByDocumentIdRequest;
import android.app.appsearch.PutDocumentsRequest;
import android.app.appsearch.observer.DocumentChangeInfo;
import android.app.appsearch.observer.ObserverCallback;
import android.app.appsearch.observer.ObserverSpec;
import android.app.appsearch.observer.SchemaChangeInfo;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.ICancellationSignal;
import android.os.OutcomeReceiver;
import android.os.ParcelableException;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.infra.AndroidFuture;
import com.android.server.SystemService.TargetUser;
import com.android.server.appfunctions.RemoteServiceCaller.RunServiceCallCallback;
import com.android.server.appfunctions.RemoteServiceCaller.ServiceUsageCompleteListener;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/** Implementation of the AppFunctionManagerService. */
public class AppFunctionManagerServiceImpl extends IAppFunctionManager.Stub {
    private static final String TAG = AppFunctionManagerServiceImpl.class.getSimpleName();

    private final RemoteServiceCaller<IAppFunctionService> mRemoteServiceCaller;
    private final CallerValidator mCallerValidator;
    private final ServiceHelper mInternalServiceHelper;
    private final ServiceConfig mServiceConfig;
    private final Context mContext;
    private final Object mLock = new Object();

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
    public ICancellationSignal executeAppFunction(
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
            return null;
        }

        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();

        ICancellationSignal localCancelTransport = CancellationSignal.createTransport();

        THREAD_POOL_EXECUTOR.execute(
                () -> {
                    try {
                        executeAppFunctionInternal(
                                requestInternal,
                                callingUid,
                                callingPid,
                                localCancelTransport,
                                safeExecuteAppFunctionCallback);
                    } catch (Exception e) {
                        safeExecuteAppFunctionCallback.onResult(
                                mapExceptionToExecuteAppFunctionResponse(e));
                    }
                });
        return localCancelTransport;
    }

    @WorkerThread
    private void executeAppFunctionInternal(
            ExecuteAppFunctionAidlRequest requestInternal,
            int callingUid,
            int callingPid,
            ICancellationSignal localCancelTransport,
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
                                                "Caller does not have permission to execute the"
                                                        + " appfunction",
                                                /* extras= */ null));
                            }
                        })
                .thenCompose(
                        isEnabled ->
                                isAppFunctionEnabled(
                                        requestInternal.getClientRequest().getFunctionIdentifier(),
                                        requestInternal.getClientRequest().getTargetPackageName(),
                                        getAppSearchManagerAsUser(requestInternal.getUserHandle()),
                                        THREAD_POOL_EXECUTOR))
                .thenAccept(
                        isEnabled -> {
                            if (!isEnabled) {
                                throw new DisabledAppFunctionException(
                                        "The app function is disabled");
                            }
                        })
                .thenAccept(
                        unused -> {
                            Intent serviceIntent =
                                    mInternalServiceHelper.resolveAppFunctionService(
                                            targetPackageName, targetUser);
                            if (serviceIntent == null) {
                                safeExecuteAppFunctionCallback.onResult(
                                        ExecuteAppFunctionResponse.newFailure(
                                                ExecuteAppFunctionResponse.RESULT_INTERNAL_ERROR,
                                                "Cannot find the target service.",
                                                /* extras= */ null));
                                return;
                            }
                            bindAppFunctionServiceUnchecked(
                                    requestInternal,
                                    serviceIntent,
                                    targetUser,
                                    localCancelTransport,
                                    safeExecuteAppFunctionCallback,
                                    /* bindFlags= */ Context.BIND_AUTO_CREATE
                                            | Context.BIND_FOREGROUND_SERVICE);
                        })
                .exceptionally(
                        ex -> {
                            safeExecuteAppFunctionCallback.onResult(
                                    mapExceptionToExecuteAppFunctionResponse(ex));
                            return null;
                        });
    }

    private static AndroidFuture<Boolean> isAppFunctionEnabled(
            @NonNull String functionIdentifier,
            @NonNull String targetPackage,
            @NonNull AppSearchManager appSearchManager,
            @NonNull Executor executor) {
        AndroidFuture<Boolean> future = new AndroidFuture<>();
        AppFunctionManagerHelper.isAppFunctionEnabled(
                functionIdentifier,
                targetPackage,
                appSearchManager,
                executor,
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(@NonNull Boolean result) {
                        future.complete(result);
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        future.completeExceptionally(error);
                    }
                });
        return future;
    }

    @Override
    public void setAppFunctionEnabled(
            @NonNull String callingPackage,
            @NonNull String functionIdentifier,
            @NonNull UserHandle userHandle,
            @AppFunctionManager.EnabledState int enabledState,
            @NonNull IAppFunctionEnabledCallback callback) {
        try {
            mCallerValidator.validateCallingPackage(callingPackage);
        } catch (SecurityException e) {
            reportException(callback, e);
            return;
        }
        THREAD_POOL_EXECUTOR.execute(
                () -> {
                    try {
                        // TODO(357551503): Instead of holding a global lock, hold a per-package
                        //  lock.
                        synchronized (mLock) {
                            setAppFunctionEnabledInternalLocked(
                                    callingPackage, functionIdentifier, userHandle, enabledState);
                        }
                        callback.onSuccess();
                    } catch (Exception e) {
                        Slog.e(TAG, "Error in setAppFunctionEnabled: ", e);
                        reportException(callback, e);
                    }
                });
    }

    private static void reportException(
            @NonNull IAppFunctionEnabledCallback callback, @NonNull Exception exception) {
        try {
            callback.onError(new ParcelableException(exception));
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to report the exception", e);
        }
    }

    /**
     * Sets the enabled status of a specified app function.
     * <p>
     * Required to hold a lock to call this function to avoid document changes during the process.
     */
    @WorkerThread
    @GuardedBy("mLock")
    private void setAppFunctionEnabledInternalLocked(
            @NonNull String callingPackage,
            @NonNull String functionIdentifier,
            @NonNull UserHandle userHandle,
            @AppFunctionManager.EnabledState int enabledState)
            throws Exception {
        AppSearchManager perUserAppSearchManager = getAppSearchManagerAsUser(userHandle);

        if (perUserAppSearchManager == null) {
            throw new IllegalStateException(
                    "AppSearchManager not found for user:" + userHandle.getIdentifier());
        }
        SearchContext runtimeMetadataSearchContext =
                new SearchContext.Builder(APP_FUNCTION_RUNTIME_METADATA_DB).build();

        try (FutureAppSearchSession runtimeMetadataSearchSession =
                new FutureAppSearchSessionImpl(
                        perUserAppSearchManager,
                        THREAD_POOL_EXECUTOR,
                        runtimeMetadataSearchContext)) {
            AppFunctionRuntimeMetadata existingMetadata =
                    new AppFunctionRuntimeMetadata(
                            getRuntimeMetadataGenericDocument(
                                    callingPackage,
                                    functionIdentifier,
                                    runtimeMetadataSearchSession));
            AppFunctionRuntimeMetadata.Builder newMetadata =
                    new AppFunctionRuntimeMetadata.Builder(existingMetadata);
            switch (enabledState) {
                case AppFunctionManager.APP_FUNCTION_STATE_DEFAULT -> {
                    newMetadata.setEnabled(null);
                }
                case APP_FUNCTION_STATE_ENABLED -> {
                    newMetadata.setEnabled(true);
                }
                case APP_FUNCTION_STATE_DISABLED -> {
                    newMetadata.setEnabled(false);
                }
                default ->
                        throw new IllegalArgumentException(
                                "Value of EnabledState is unsupported.");
            }
            AppSearchBatchResult<String, Void> putDocumentBatchResult =
                    runtimeMetadataSearchSession
                            .put(
                                    new PutDocumentsRequest.Builder()
                                            .addGenericDocuments(newMetadata.build())
                                            .build())
                            .get();
            if (!putDocumentBatchResult.isSuccess()) {
                throw new IllegalStateException("Failed writing updated doc to AppSearch due to "
                        + putDocumentBatchResult);
            }
        }
    }

    @WorkerThread
    @NonNull
    private AppFunctionRuntimeMetadata getRuntimeMetadataGenericDocument(
            @NonNull String packageName,
            @NonNull String functionId,
            @NonNull FutureAppSearchSession runtimeMetadataSearchSession)
            throws Exception {
        String documentId =
                AppFunctionRuntimeMetadata.getDocumentIdForAppFunction(packageName, functionId);
        GetByDocumentIdRequest request =
                new GetByDocumentIdRequest.Builder(APP_FUNCTION_RUNTIME_NAMESPACE)
                        .addIds(documentId)
                        .build();
        AppSearchBatchResult<String, GenericDocument> result =
                runtimeMetadataSearchSession.getByDocumentId(request).get();
        if (result.isSuccess()) {
            return new AppFunctionRuntimeMetadata((result.getSuccesses().get(documentId)));
        }
        throw new IllegalArgumentException("Function " + functionId + " does not exist");
    }

    private void bindAppFunctionServiceUnchecked(
            @NonNull ExecuteAppFunctionAidlRequest requestInternal,
            @NonNull Intent serviceIntent,
            @NonNull UserHandle targetUser,
            @NonNull ICancellationSignal cancellationSignalTransport,
            @NonNull SafeOneTimeExecuteAppFunctionCallback safeExecuteAppFunctionCallback,
            int bindFlags) {
        CancellationSignal cancellationSignal =
                CancellationSignal.fromTransport(cancellationSignalTransport);
        ICancellationCallback cancellationCallback =
                new ICancellationCallback.Stub() {
                    @Override
                    public void sendCancellationTransport(
                            @NonNull ICancellationSignal cancellationTransport) {
                        cancellationSignal.setRemote(cancellationTransport);
                    }
                };
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
                                            cancellationCallback,
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

    private AppSearchManager getAppSearchManagerAsUser(@NonNull UserHandle userHandle) {
        return mContext.createContextAsUser(userHandle, /* flags= */ 0)
                .getSystemService(AppSearchManager.class);
    }

    private ExecuteAppFunctionResponse mapExceptionToExecuteAppFunctionResponse(Throwable e) {
        if (e instanceof CompletionException) {
            e = e.getCause();
        }
        int resultCode = ExecuteAppFunctionResponse.RESULT_INTERNAL_ERROR;
        if (e instanceof AppSearchException appSearchException) {
            resultCode =
                    mapAppSearchResultFailureCodeToExecuteAppFunctionResponse(
                            appSearchException.getResultCode());
        } else if (e instanceof SecurityException) {
            resultCode = ExecuteAppFunctionResponse.RESULT_DENIED;
        } else if (e instanceof DisabledAppFunctionException) {
            resultCode = ExecuteAppFunctionResponse.RESULT_DISABLED;
        }
        return ExecuteAppFunctionResponse.newFailure(
                resultCode, e.getMessage(), /* extras= */ null);
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

    /** Throws when executing a disabled app function. */
    private static class DisabledAppFunctionException extends RuntimeException {
        private DisabledAppFunctionException(@NonNull String errorMessage) {
            super(errorMessage);
        }
    }
}
