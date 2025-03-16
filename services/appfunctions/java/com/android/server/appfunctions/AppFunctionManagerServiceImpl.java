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

import static android.app.appfunctions.AppFunctionRuntimeMetadata.APP_FUNCTION_RUNTIME_METADATA_DB;
import static android.app.appfunctions.AppFunctionRuntimeMetadata.APP_FUNCTION_RUNTIME_NAMESPACE;

import static com.android.server.appfunctions.AppFunctionExecutors.THREAD_POOL_EXECUTOR;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.app.appfunctions.AppFunctionException;
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
import android.os.IBinder;
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
import com.android.internal.util.DumpUtils;
import com.android.server.SystemService.TargetUser;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
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
    private final Map<String, Object> mLocks = new WeakHashMap<>();
    private final AppFunctionsLoggerWrapper mLoggerWrapper;

    public AppFunctionManagerServiceImpl(@NonNull Context context) {
        this(
                context,
                new RemoteServiceCallerImpl<>(
                        context, IAppFunctionService.Stub::asInterface, THREAD_POOL_EXECUTOR),
                new CallerValidatorImpl(context),
                new ServiceHelperImpl(context),
                new ServiceConfigImpl(),
                new AppFunctionsLoggerWrapper(context));
    }

    @VisibleForTesting
    AppFunctionManagerServiceImpl(
            Context context,
            RemoteServiceCaller<IAppFunctionService> remoteServiceCaller,
            CallerValidator callerValidator,
            ServiceHelper appFunctionInternalServiceHelper,
            ServiceConfig serviceConfig,
            AppFunctionsLoggerWrapper loggerWrapper) {
        mContext = Objects.requireNonNull(context);
        mRemoteServiceCaller = Objects.requireNonNull(remoteServiceCaller);
        mCallerValidator = Objects.requireNonNull(callerValidator);
        mInternalServiceHelper = Objects.requireNonNull(appFunctionInternalServiceHelper);
        mServiceConfig = serviceConfig;
        mLoggerWrapper = loggerWrapper;
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
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) {
            return;
        }

        final long token = Binder.clearCallingIdentity();
        try {
            AppFunctionDumpHelper.dumpAppFunctionsState(mContext, pw);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public ICancellationSignal executeAppFunction(
            @NonNull ExecuteAppFunctionAidlRequest requestInternal,
            @NonNull IExecuteAppFunctionCallback executeAppFunctionCallback) {
        Objects.requireNonNull(requestInternal);
        Objects.requireNonNull(executeAppFunctionCallback);

        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();

        final SafeOneTimeExecuteAppFunctionCallback safeExecuteAppFunctionCallback =
                new SafeOneTimeExecuteAppFunctionCallback(executeAppFunctionCallback,
                        new SafeOneTimeExecuteAppFunctionCallback.CompletionCallback() {
                            @Override
                            public void finalizeOnSuccess(
                                    @NonNull ExecuteAppFunctionResponse result) {
                                mLoggerWrapper.logAppFunctionSuccess(requestInternal, result,
                                        callingUid);
                            }

                            @Override
                            public void finalizeOnError(@NonNull AppFunctionException error) {
                                mLoggerWrapper.logAppFunctionError(requestInternal,
                                        error.getErrorCode(), callingUid);
                            }
                        });

        String validatedCallingPackage;
        try {
            validatedCallingPackage =
                    mCallerValidator.validateCallingPackage(requestInternal.getCallingPackage());
            mCallerValidator.verifyTargetUserHandle(
                    requestInternal.getUserHandle(), validatedCallingPackage);
        } catch (SecurityException exception) {
            safeExecuteAppFunctionCallback.onError(
                    new AppFunctionException(
                            AppFunctionException.ERROR_DENIED, exception.getMessage()));
            return null;
        }

        ICancellationSignal localCancelTransport = CancellationSignal.createTransport();

        THREAD_POOL_EXECUTOR.execute(
                () -> {
                    try {
                        executeAppFunctionInternal(
                                requestInternal,
                                callingUid,
                                callingPid,
                                localCancelTransport,
                                safeExecuteAppFunctionCallback,
                                executeAppFunctionCallback.asBinder());
                    } catch (Exception e) {
                        safeExecuteAppFunctionCallback.onError(
                                mapExceptionToExecuteAppFunctionResponse(e));
                    }
                });
        return localCancelTransport;
    }

    @WorkerThread
    private void executeAppFunctionInternal(
            @NonNull ExecuteAppFunctionAidlRequest requestInternal,
            int callingUid,
            int callingPid,
            @NonNull ICancellationSignal localCancelTransport,
            @NonNull SafeOneTimeExecuteAppFunctionCallback safeExecuteAppFunctionCallback,
            @NonNull IBinder callerBinder) {
        UserHandle targetUser = requestInternal.getUserHandle();
        UserHandle callingUser = UserHandle.getUserHandleForUid(callingUid);
        if (!mCallerValidator.verifyEnterprisePolicyIsAllowed(callingUser, targetUser)) {
            safeExecuteAppFunctionCallback.onError(
                    new AppFunctionException(
                            AppFunctionException.ERROR_ENTERPRISE_POLICY_DISALLOWED,
                            "Cannot run on a user with a restricted enterprise policy"));
            return;
        }

        String targetPackageName = requestInternal.getClientRequest().getTargetPackageName();
        if (TextUtils.isEmpty(targetPackageName)) {
            safeExecuteAppFunctionCallback.onError(
                    new AppFunctionException(
                            AppFunctionException.ERROR_INVALID_ARGUMENT,
                            "Target package name cannot be empty."));
            return;
        }

        mCallerValidator
                .verifyCallerCanExecuteAppFunction(
                        callingUid,
                        callingPid,
                        targetUser,
                        requestInternal.getCallingPackage(),
                        targetPackageName,
                        requestInternal.getClientRequest().getFunctionIdentifier())
                .thenAccept(
                        canExecute -> {
                            if (!canExecute) {
                                throw new SecurityException(
                                        "Caller does not have permission to execute the"
                                                + " appfunction");
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
                                safeExecuteAppFunctionCallback.onError(
                                        new AppFunctionException(
                                                AppFunctionException.ERROR_SYSTEM_ERROR,
                                                "Cannot find the target service."));
                                return;
                            }
                            bindAppFunctionServiceUnchecked(
                                    requestInternal,
                                    serviceIntent,
                                    targetUser,
                                    localCancelTransport,
                                    safeExecuteAppFunctionCallback,
                                    /* bindFlags= */ Context.BIND_AUTO_CREATE
                                            | Context.BIND_FOREGROUND_SERVICE,
                                    callerBinder);
                        })
                .exceptionally(
                        ex -> {
                            safeExecuteAppFunctionCallback.onError(
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
                        synchronized (getLockForPackage(callingPackage)) {
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
     *
     * <p>Required to hold a lock to call this function to avoid document changes during the
     * process.
     */
    @WorkerThread
    @GuardedBy("getLockForPackage(callingPackage)")
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
            AppFunctionRuntimeMetadata newMetadata =
                    new AppFunctionRuntimeMetadata.Builder(existingMetadata)
                            .setEnabled(enabledState)
                            .build();
            AppSearchBatchResult<String, Void> putDocumentBatchResult =
                    runtimeMetadataSearchSession
                            .put(
                                    new PutDocumentsRequest.Builder()
                                            .addGenericDocuments(newMetadata)
                                            .build())
                            .get();
            if (!putDocumentBatchResult.isSuccess()) {
                throw new IllegalStateException(
                        "Failed writing updated doc to AppSearch due to " + putDocumentBatchResult);
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
            int bindFlags,
            @NonNull IBinder callerBinder) {
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
                        mServiceConfig.getExecuteAppFunctionCancellationTimeoutMillis(),
                        cancellationSignal,
                        new RunAppFunctionServiceCallback(
                                requestInternal,
                                cancellationCallback,
                                safeExecuteAppFunctionCallback),
                        callerBinder);

        if (!bindServiceResult) {
            Slog.e(TAG, "Failed to bind to the AppFunctionService");
            safeExecuteAppFunctionCallback.onError(
                    new AppFunctionException(
                            AppFunctionException.ERROR_SYSTEM_ERROR,
                            "Failed to bind the AppFunctionService."));
        }
    }

    private AppSearchManager getAppSearchManagerAsUser(@NonNull UserHandle userHandle) {
        return mContext.createContextAsUser(userHandle, /* flags= */ 0)
                .getSystemService(AppSearchManager.class);
    }

    private AppFunctionException mapExceptionToExecuteAppFunctionResponse(Throwable e) {
        if (e instanceof CompletionException) {
            e = e.getCause();
        }
        int resultCode = AppFunctionException.ERROR_SYSTEM_ERROR;
        if (e instanceof AppSearchException appSearchException) {
            resultCode =
                    mapAppSearchResultFailureCodeToExecuteAppFunctionResponse(
                            appSearchException.getResultCode());
        } else if (e instanceof SecurityException) {
            resultCode = AppFunctionException.ERROR_DENIED;
        } else if (e instanceof DisabledAppFunctionException) {
            resultCode = AppFunctionException.ERROR_DISABLED;
        }
        return new AppFunctionException(resultCode, e.getMessage());
    }

    private int mapAppSearchResultFailureCodeToExecuteAppFunctionResponse(int resultCode) {
        if (resultCode == AppSearchResult.RESULT_OK) {
            throw new IllegalArgumentException(
                    "This method can only be used to convert failure result codes.");
        }

        switch (resultCode) {
            case AppSearchResult.RESULT_NOT_FOUND:
                return AppFunctionException.ERROR_FUNCTION_NOT_FOUND;
            case AppSearchResult.RESULT_INVALID_ARGUMENT:
            case AppSearchResult.RESULT_INTERNAL_ERROR:
            case AppSearchResult.RESULT_SECURITY_ERROR:
                // fall-through
        }
        return AppFunctionException.ERROR_SYSTEM_ERROR;
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
                new FutureGlobalSearchSession(perUserAppSearchManager, THREAD_POOL_EXECUTOR);
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

    /**
     * Retrieves the lock object associated with the given package name.
     *
     * <p>This method returns the lock object from the {@code mLocks} map if it exists. If no lock
     * is found for the given package name, a new lock object is created, stored in the map, and
     * returned.
     */
    @VisibleForTesting
    @NonNull
    Object getLockForPackage(String callingPackage) {
        // Synchronized the access to mLocks to prevent race condition.
        synchronized (mLocks) {
            // By using a WeakHashMap, we allow the garbage collector to reclaim memory by removing
            // entries associated with unused callingPackage keys. Therefore, we remove the null
            // values before getting/computing a new value. The goal is to not let the size of this
            // map grow without an upper bound.
            mLocks.values().removeAll(Collections.singleton(null)); // Remove null values
            return mLocks.computeIfAbsent(callingPackage, k -> new Object());
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
