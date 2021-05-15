/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.server.appsearch;

import static android.app.appsearch.AppSearchResult.throwableToFailedResult;
import static android.os.Process.INVALID_UID;
import static android.os.UserHandle.USER_NULL;

import android.annotation.ElapsedRealtimeLong;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchMigrationHelper;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.GetSchemaResponse;
import android.app.appsearch.PackageIdentifier;
import android.app.appsearch.SearchResultPage;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaResponse;
import android.app.appsearch.StorageInfo;
import android.app.appsearch.aidl.AppSearchBatchResultParcel;
import android.app.appsearch.aidl.AppSearchResultParcel;
import android.app.appsearch.aidl.IAppSearchBatchResultCallback;
import android.app.appsearch.aidl.IAppSearchManager;
import android.app.appsearch.aidl.IAppSearchResultCallback;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageStats;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalManagerRegistry;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.appsearch.external.localstorage.AppSearchImpl;
import com.android.server.appsearch.external.localstorage.stats.CallStats;
import com.android.server.appsearch.stats.LoggerInstanceManager;
import com.android.server.appsearch.stats.PlatformLogger;
import com.android.server.usage.StorageStatsManagerLocal;
import com.android.server.usage.StorageStatsManagerLocal.StorageStatsAugmenter;

import com.google.android.icing.proto.PersistType;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** TODO(b/142567528): add comments when implement this class */
public class AppSearchManagerService extends SystemService {
    private static final String TAG = "AppSearchManagerService";
    private final Context mContext;
    private PackageManager mPackageManager;
    private PackageManagerInternal mPackageManagerInternal;
    private ImplInstanceManager mImplInstanceManager;
    private UserManager mUserManager;
    private LoggerInstanceManager mLoggerInstanceManager;

    // Never call shutdownNow(). It will cancel the futures it's returned. And since
    // Executor#execute won't return anything, we will hang forever waiting for the execution.
    // AppSearch multi-thread execution is guarded by Read & Write Lock in AppSearchImpl, all
    // mutate requests will need to gain write lock and query requests need to gain read lock.
    private static final Executor EXECUTOR = new ThreadPoolExecutor(/*corePoolSize=*/1,
            Runtime.getRuntime().availableProcessors(), /*keepAliveTime*/ 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>());

    // Cache of unlocked user ids so we don't have to query UserManager service each time. The
    // "locked" suffix refers to the fact that access to the field should be locked; unrelated to
    // the unlocked status of user ids.
    @GuardedBy("mUnlockedUserIdsLocked")
    private final Set<Integer> mUnlockedUserIdsLocked = new ArraySet<>();

    public AppSearchManagerService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        publishBinderService(Context.APP_SEARCH_SERVICE, new Stub());
        mPackageManager = getContext().getPackageManager();
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        mImplInstanceManager = ImplInstanceManager.getInstance(mContext);
        mUserManager = mContext.getSystemService(UserManager.class);
        mLoggerInstanceManager = LoggerInstanceManager.getInstance();
        registerReceivers();
        LocalManagerRegistry.getManager(StorageStatsManagerLocal.class)
                .registerStorageStatsAugmenter(new AppSearchStorageStatsAugmenter(), TAG);
    }

    private void registerReceivers() {
        mContext.registerReceiverAsUser(new UserActionReceiver(), UserHandle.ALL,
                new IntentFilter(Intent.ACTION_USER_REMOVED), /*broadcastPermission=*/ null,
                /*scheduler=*/ null);

        //TODO(b/145759910) Add a direct callback when user clears the data instead of relying on
        // broadcasts
        IntentFilter packageChangedFilter = new IntentFilter();
        packageChangedFilter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        packageChangedFilter.addAction(Intent.ACTION_PACKAGE_DATA_CLEARED);
        packageChangedFilter.addDataScheme("package");
        packageChangedFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiverAsUser(new PackageChangedReceiver(), UserHandle.ALL,
                packageChangedFilter, /*broadcastPermission=*/ null,
                /*scheduler=*/ null);
    }

    private class UserActionReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(@NonNull Context context, @NonNull Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_USER_REMOVED:
                    int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, USER_NULL);
                    if (userId == USER_NULL) {
                        Log.e(TAG, "userId is missing in the intent: " + intent);
                        return;
                    }
                    handleUserRemoved(userId);
                    break;
                default:
                    Log.e(TAG, "Received unknown intent: " + intent);
            }
        }
    }

    /**
     * Handles user removed action.
     *
     * <p>Only need to clear the AppSearchImpl instance. The data of AppSearch is saved in the
     * "credential encrypted" system directory of each user. That directory will be auto-deleted
     * when a user is removed.
     *
     * @param userId The multi-user userId of the user that need to be removed.
     *
     * @see android.os.Environment#getDataSystemCeDirectory
     */
    private void handleUserRemoved(@UserIdInt int userId) {
        try {
            mImplInstanceManager.removeAppSearchImplForUser(userId);
            mLoggerInstanceManager.removePlatformLoggerForUser(userId);
            Log.i(TAG, "Removed AppSearchImpl instance for user: " + userId);
        } catch (Throwable t) {
            Log.e(TAG, "Unable to remove data for user: " + userId, t);
        }
    }

    private class PackageChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(@NonNull Context context, @NonNull Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_PACKAGE_FULLY_REMOVED:
                case Intent.ACTION_PACKAGE_DATA_CLEARED:
                    String packageName = intent.getData().getSchemeSpecificPart();
                    if (packageName == null) {
                        Log.e(TAG, "Package name is missing in the intent: " + intent);
                        return;
                    }
                    int uid = intent.getIntExtra(Intent.EXTRA_UID, INVALID_UID);
                    if (uid == INVALID_UID) {
                        Log.e(TAG, "uid is missing in the intent: " + intent);
                        return;
                    }
                    handlePackageRemoved(packageName, uid);
                    break;
                default:
                    Log.e(TAG, "Received unknown intent: " + intent);
            }
        }
    }

    private void handlePackageRemoved(String packageName, int uid) {
        int userId = UserHandle.getUserId(uid);
        try {
            if (isUserLocked(userId)) {
                //TODO(b/186151459) clear the uninstalled package data when user is unlocked.
                return;
            }
            if (ImplInstanceManager.getAppSearchDir(userId).exists()) {
                // Only clear the package's data if AppSearch exists for this user.
                PlatformLogger logger = mLoggerInstanceManager.getOrCreatePlatformLogger(mContext,
                        userId);
                AppSearchImpl impl = mImplInstanceManager.getOrCreateAppSearchImpl(mContext,
                        userId, logger);
                //TODO(b/145759910) clear visibility setting for package.
                impl.clearPackageData(packageName);
                logger.removeCachedUidForPackage(packageName);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Unable to remove data for package: " + packageName, t);
        }
    }

    @Override
    public void onUserUnlocking(@NonNull TargetUser user) {
        synchronized (mUnlockedUserIdsLocked) {
            mUnlockedUserIdsLocked.add(user.getUserIdentifier());
        }
    }

    @Override
    public void onUserStopping(@NonNull TargetUser user) {
        synchronized (mUnlockedUserIdsLocked) {
            mUnlockedUserIdsLocked.remove(user.getUserIdentifier());
            try {
                mImplInstanceManager.closeAndRemoveAppSearchImplForUser(user.getUserIdentifier());
            } catch (Throwable t) {
                Log.e(TAG, "Error handling user stopping.", t);
            }
        }
    }

    private void verifyUserUnlocked(int callingUserId) {
        if (isUserLocked(callingUserId)) {
            throw new IllegalStateException("User " + callingUserId + " is locked or not running.");
        }
    }

    private boolean isUserLocked(int callingUserId) {
        synchronized (mUnlockedUserIdsLocked) {
            // First, check the local copy.
            if (mUnlockedUserIdsLocked.contains(callingUserId)) {
                return false;
            }
            // If the local copy says the user is locked, check with UM for the actual state,
            // since the user might just have been unlocked.
            return !mUserManager.isUserUnlockingOrUnlocked(UserHandle.of(callingUserId));
        }
    }

    private class Stub extends IAppSearchManager.Stub {
        @Override
        public void setSchema(
                @NonNull String packageName,
                @NonNull String databaseName,
                @NonNull List<Bundle> schemaBundles,
                @NonNull List<String> schemasNotDisplayedBySystem,
                @NonNull Map<String, List<Bundle>> schemasPackageAccessibleBundles,
                boolean forceOverride,
                int schemaVersion,
                @UserIdInt int userId,
                @ElapsedRealtimeLong long binderCallStartTimeMillis,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(databaseName);
            Objects.requireNonNull(schemaBundles);
            Objects.requireNonNull(callback);
            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                PlatformLogger logger = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    verifyUserUnlocked(callingUserId);
                    verifyCallingPackage(callingUid, packageName);
                    List<AppSearchSchema> schemas = new ArrayList<>(schemaBundles.size());
                    for (int i = 0; i < schemaBundles.size(); i++) {
                        schemas.add(new AppSearchSchema(schemaBundles.get(i)));
                    }
                    Map<String, List<PackageIdentifier>> schemasPackageAccessible =
                            new ArrayMap<>(schemasPackageAccessibleBundles.size());
                    for (Map.Entry<String, List<Bundle>> entry :
                            schemasPackageAccessibleBundles.entrySet()) {
                        List<PackageIdentifier> packageIdentifiers =
                                new ArrayList<>(entry.getValue().size());
                        for (int i = 0; i < entry.getValue().size(); i++) {
                            packageIdentifiers.add(
                                    new PackageIdentifier(entry.getValue().get(i)));
                        }
                        schemasPackageAccessible.put(entry.getKey(), packageIdentifiers);
                    }
                    AppSearchImpl impl = mImplInstanceManager.getAppSearchImpl(callingUserId);
                    logger = mLoggerInstanceManager.getPlatformLogger(callingUserId);
                    SetSchemaResponse setSchemaResponse = impl.setSchema(
                            packageName,
                            databaseName,
                            schemas,
                            schemasNotDisplayedBySystem,
                            schemasPackageAccessible,
                            forceOverride,
                            schemaVersion);
                    ++operationSuccessCount;
                    invokeCallbackOnResult(callback,
                            AppSearchResult.newSuccessfulResult(setSchemaResponse.getBundle()));
                } catch (Throwable t) {
                    ++operationFailureCount;
                    statusCode = throwableToFailedResult(t).getResultCode();
                    invokeCallbackOnError(callback, t);
                } finally {
                    if (logger != null) {
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis - binderCallStartTimeMillis);
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        CallStats.Builder cBuilder = new CallStats.Builder(packageName,
                                databaseName)
                                .setCallType(CallStats.CALL_TYPE_SET_SCHEMA)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount);
                        cBuilder.getGeneralStatsBuilder()
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis);
                        logger.logStats(cBuilder.build());
                    }
                }
            });
        }

        @Override
        public void getSchema(
                @NonNull String packageName,
                @NonNull String databaseName,
                @UserIdInt int userId,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(databaseName);
            Objects.requireNonNull(callback);
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                try {
                    verifyUserUnlocked(callingUserId);
                    verifyCallingPackage(callingUid, packageName);
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    GetSchemaResponse response = impl.getSchema(packageName, databaseName);
                    invokeCallbackOnResult(
                            callback,
                            AppSearchResult.newSuccessfulResult(response.getBundle()));
                } catch (Throwable t) {
                    invokeCallbackOnError(callback, t);
                }
            });
        }

        @Override
        public void getNamespaces(
                @NonNull String packageName,
                @NonNull String databaseName,
                @UserIdInt int userId,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(databaseName);
            Objects.requireNonNull(callback);
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                try {
                    verifyUserUnlocked(callingUserId);
                    verifyCallingPackage(callingUid, packageName);
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    List<String> namespaces = impl.getNamespaces(packageName, databaseName);
                    invokeCallbackOnResult(callback,
                            AppSearchResult.newSuccessfulResult(namespaces));
                } catch (Throwable t) {
                    invokeCallbackOnError(callback, t);
                }
            });
        }

        @Override
        public void putDocuments(
                @NonNull String packageName,
                @NonNull String databaseName,
                @NonNull List<Bundle> documentBundles,
                @UserIdInt int userId,
                @ElapsedRealtimeLong long binderCallStartTimeMillis,
                @NonNull IAppSearchBatchResultCallback callback) {
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(databaseName);
            Objects.requireNonNull(documentBundles);
            Objects.requireNonNull(callback);
            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                PlatformLogger logger = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    verifyUserUnlocked(callingUserId);
                    verifyCallingPackage(callingUid, packageName);
                    AppSearchBatchResult.Builder<String, Void> resultBuilder =
                            new AppSearchBatchResult.Builder<>();
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    logger = mLoggerInstanceManager.getPlatformLogger(callingUserId);
                    for (int i = 0; i < documentBundles.size(); i++) {
                        GenericDocument document = new GenericDocument(documentBundles.get(i));
                        try {
                            impl.putDocument(packageName, databaseName, document, logger);
                            resultBuilder.setSuccess(document.getId(), /*result=*/ null);
                            ++operationSuccessCount;
                        } catch (Throwable t) {
                            resultBuilder.setResult(document.getId(),
                                    throwableToFailedResult(t));
                            AppSearchResult<Void> result = throwableToFailedResult(t);
                            resultBuilder.setResult(document.getId(), result);
                            // Since we can only include one status code in the atom,
                            // for failures, we would just save the one for the last failure
                            statusCode = result.getResultCode();
                            ++operationFailureCount;
                        }
                    }
                    // Now that the batch has been written. Persist the newly written data.
                    impl.persistToDisk(PersistType.Code.LITE);
                    invokeCallbackOnResult(callback, resultBuilder.build());
                } catch (Throwable t) {
                    ++operationFailureCount;
                    statusCode = throwableToFailedResult(t).getResultCode();
                    invokeCallbackOnError(callback, t);
                } finally {
                    if (logger != null) {
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis - binderCallStartTimeMillis);
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        CallStats.Builder cBuilder = new CallStats.Builder(packageName,
                                databaseName)
                                .setCallType(CallStats.CALL_TYPE_PUT_DOCUMENTS)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount);
                        cBuilder.getGeneralStatsBuilder()
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis);
                        logger.logStats(cBuilder.build());
                    }
                }
            });
        }

        @Override
        public void getDocuments(
                @NonNull String packageName,
                @NonNull String databaseName,
                @NonNull String namespace,
                @NonNull List<String> ids,
                @NonNull Map<String, List<String>> typePropertyPaths,
                @UserIdInt int userId,
                @ElapsedRealtimeLong long binderCallStartTimeMillis,
                @NonNull IAppSearchBatchResultCallback callback) {
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(databaseName);
            Objects.requireNonNull(namespace);
            Objects.requireNonNull(ids);
            Objects.requireNonNull(callback);
            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                PlatformLogger logger = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    verifyUserUnlocked(callingUserId);
                    verifyCallingPackage(callingUid, packageName);
                    AppSearchBatchResult.Builder<String, Bundle> resultBuilder =
                            new AppSearchBatchResult.Builder<>();
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    logger = mLoggerInstanceManager.getPlatformLogger(callingUserId);
                    for (int i = 0; i < ids.size(); i++) {
                        String id = ids.get(i);
                        try {
                            GenericDocument document =
                                    impl.getDocument(
                                            packageName,
                                            databaseName,
                                            namespace,
                                            id,
                                            typePropertyPaths);
                            ++operationSuccessCount;
                            resultBuilder.setSuccess(id, document.getBundle());
                        } catch (Throwable t) {
                            // Since we can only include one status code in the atom,
                            // for failures, we would just save the one for the last failure
                            AppSearchResult<Bundle> result = throwableToFailedResult(t);
                            resultBuilder.setResult(id, result);
                            statusCode = result.getResultCode();
                            ++operationFailureCount;
                        }
                    }
                    invokeCallbackOnResult(callback, resultBuilder.build());
                } catch (Throwable t) {
                    ++operationFailureCount;
                    statusCode = throwableToFailedResult(t).getResultCode();
                    invokeCallbackOnError(callback, t);
                } finally {
                    if (logger != null) {
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis - binderCallStartTimeMillis);
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        CallStats.Builder cBuilder = new CallStats.Builder(packageName,
                                databaseName)
                                .setCallType(CallStats.CALL_TYPE_GET_DOCUMENTS)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount);
                        cBuilder.getGeneralStatsBuilder()
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis);
                        logger.logStats(cBuilder.build());
                    }
                }
            });
        }

        @Override
        public void query(
                @NonNull String packageName,
                @NonNull String databaseName,
                @NonNull String queryExpression,
                @NonNull Bundle searchSpecBundle,
                @UserIdInt int userId,
                @ElapsedRealtimeLong long binderCallStartTimeMillis,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(databaseName);
            Objects.requireNonNull(queryExpression);
            Objects.requireNonNull(searchSpecBundle);
            Objects.requireNonNull(callback);
            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                PlatformLogger logger = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    verifyUserUnlocked(callingUserId);
                    verifyCallingPackage(callingUid, packageName);
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    logger = mLoggerInstanceManager.getPlatformLogger(callingUserId);
                    SearchResultPage searchResultPage =
                            impl.query(
                                    packageName,
                                    databaseName,
                                    queryExpression,
                                    new SearchSpec(searchSpecBundle),
                                    logger);
                    ++operationSuccessCount;
                    invokeCallbackOnResult(
                            callback,
                            AppSearchResult.newSuccessfulResult(searchResultPage.getBundle()));
                } catch (Throwable t) {
                    ++operationFailureCount;
                    statusCode = throwableToFailedResult(t).getResultCode();
                    invokeCallbackOnError(callback, t);
                } finally {
                    if (logger != null) {
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis - binderCallStartTimeMillis);
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        CallStats.Builder cBuilder = new CallStats.Builder(packageName,
                                databaseName)
                                .setCallType(CallStats.CALL_TYPE_SEARCH)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount);
                        cBuilder.getGeneralStatsBuilder()
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis);
                        logger.logStats(cBuilder.build());
                    }
                }
            });
        }

        @Override
        public void globalQuery(
                @NonNull String packageName,
                @NonNull String queryExpression,
                @NonNull Bundle searchSpecBundle,
                @UserIdInt int userId,
                @ElapsedRealtimeLong long binderCallStartTimeMillis,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(queryExpression);
            Objects.requireNonNull(searchSpecBundle);
            Objects.requireNonNull(callback);
            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                PlatformLogger logger = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    verifyUserUnlocked(callingUserId);
                    verifyCallingPackage(callingUid, packageName);
                    logger = mLoggerInstanceManager.getPlatformLogger(callingUserId);
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    SearchResultPage searchResultPage =
                            impl.globalQuery(
                                    queryExpression,
                                    new SearchSpec(searchSpecBundle),
                                    packageName,
                                    callingUid,
                                    logger);
                    ++operationSuccessCount;
                    invokeCallbackOnResult(
                            callback,
                            AppSearchResult.newSuccessfulResult(searchResultPage.getBundle()));
                } catch (Throwable t) {
                    ++operationFailureCount;
                    statusCode = throwableToFailedResult(t).getResultCode();
                    invokeCallbackOnError(callback, t);
                } finally {
                    if (logger != null) {
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis - binderCallStartTimeMillis);
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        // TODO(b/173532925) database would be nulluable once we remove generalStats
                        CallStats.Builder cBuilder = new CallStats.Builder(packageName,
                                /*database=*/ "")
                                .setCallType(CallStats.CALL_TYPE_GLOBAL_SEARCH)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount);
                        cBuilder.getGeneralStatsBuilder()
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis);
                        logger.logStats(cBuilder.build());
                    }
                }
            });
        }

        @Override
        public void getNextPage(
                long nextPageToken,
                @UserIdInt int userId,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(callback);
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            // TODO(b/162450968) check nextPageToken is being advanced by the same uid as originally
            // opened it
            EXECUTOR.execute(() -> {
                try {
                    verifyUserUnlocked(callingUserId);
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    SearchResultPage searchResultPage = impl.getNextPage(nextPageToken);
                    invokeCallbackOnResult(
                            callback,
                            AppSearchResult.newSuccessfulResult(searchResultPage.getBundle()));
                } catch (Throwable t) {
                    invokeCallbackOnError(callback, t);
                }
            });
        }

        @Override
        public void invalidateNextPageToken(long nextPageToken, @UserIdInt int userId) {
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                try {
                    verifyUserUnlocked(callingUserId);
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    impl.invalidateNextPageToken(nextPageToken);
                } catch (Throwable t) {
                    Log.e(TAG, "Unable to invalidate the query page token", t);
                }
            });
        }

        @Override
        public void writeQueryResultsToFile(
                @NonNull String packageName,
                @NonNull String databaseName,
                @NonNull ParcelFileDescriptor fileDescriptor,
                @NonNull String queryExpression,
                @NonNull Bundle searchSpecBundle,
                @UserIdInt int userId,
                @NonNull IAppSearchResultCallback callback) {
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                try {
                    verifyCallingPackage(callingUid, packageName);
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    // we don't need to append the file. The file is always brand new.
                    try (DataOutputStream outputStream = new DataOutputStream(
                            new FileOutputStream(fileDescriptor.getFileDescriptor()))) {
                        SearchResultPage searchResultPage = impl.query(
                                packageName,
                                databaseName,
                                queryExpression,
                                new SearchSpec(searchSpecBundle),
                                /*logger=*/ null);
                        while (!searchResultPage.getResults().isEmpty()) {
                            for (int i = 0; i < searchResultPage.getResults().size(); i++) {
                                AppSearchMigrationHelper.writeBundleToOutputStream(
                                        outputStream, searchResultPage.getResults().get(i)
                                                .getGenericDocument().getBundle());
                            }
                            searchResultPage = impl.getNextPage(
                                    searchResultPage.getNextPageToken());
                        }
                    }
                    invokeCallbackOnResult(callback, AppSearchResult.newSuccessfulResult(null));
                } catch (Throwable t) {
                    invokeCallbackOnError(callback, t);
                }
            });
        }

        @Override
        public void putDocumentsFromFile(
                @NonNull String packageName,
                @NonNull String databaseName,
                @NonNull ParcelFileDescriptor fileDescriptor,
                @UserIdInt int userId,
                @NonNull IAppSearchResultCallback callback) {
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                try {
                    verifyCallingPackage(callingUid, packageName);
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);

                    GenericDocument document;
                    ArrayList<Bundle> migrationFailureBundles = new ArrayList<>();
                    try (DataInputStream inputStream = new DataInputStream(
                            new FileInputStream(fileDescriptor.getFileDescriptor()))) {
                        while (true) {
                            try {
                                document = AppSearchMigrationHelper
                                        .readDocumentFromInputStream(inputStream);
                            } catch (EOFException e) {
                                // nothing wrong, we just finish the reading.
                                break;
                            }
                            try {
                                impl.putDocument(packageName, databaseName, document,
                                        /*logger=*/ null);
                            } catch (Throwable t) {
                                migrationFailureBundles.add(new SetSchemaResponse.MigrationFailure(
                                        document.getNamespace(),
                                        document.getId(),
                                        document.getSchemaType(),
                                        AppSearchResult.throwableToFailedResult(t))
                                        .getBundle());
                            }
                        }
                    }
                    impl.persistToDisk(PersistType.Code.FULL);
                    invokeCallbackOnResult(callback,
                            AppSearchResult.newSuccessfulResult(migrationFailureBundles));
                } catch (Throwable t) {
                    invokeCallbackOnError(callback, t);
                }
            });
        }

        @Override
        public void reportUsage(
                @NonNull String packageName,
                @NonNull String databaseName,
                @NonNull String namespace,
                @NonNull String documentId,
                long usageTimeMillis,
                boolean systemUsage,
                @UserIdInt int userId,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(databaseName);
            Objects.requireNonNull(namespace);
            Objects.requireNonNull(documentId);
            Objects.requireNonNull(callback);
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                try {
                    verifyUserUnlocked(callingUserId);

                    if (systemUsage) {
                        // TODO(b/183031844): Validate that the call comes from the system
                    }

                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    impl.reportUsage(
                            packageName, databaseName, namespace, documentId,
                            usageTimeMillis, systemUsage);
                    invokeCallbackOnResult(
                            callback, AppSearchResult.newSuccessfulResult(/*result=*/ null));
                } catch (Throwable t) {
                    invokeCallbackOnError(callback, t);
                }
            });
        }

        @Override
        public void removeByDocumentId(
                @NonNull String packageName,
                @NonNull String databaseName,
                @NonNull String namespace,
                @NonNull List<String> ids,
                @UserIdInt int userId,
                @ElapsedRealtimeLong long binderCallStartTimeMillis,
                @NonNull IAppSearchBatchResultCallback callback) {
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(databaseName);
            Objects.requireNonNull(ids);
            Objects.requireNonNull(callback);
            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                PlatformLogger logger = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    verifyUserUnlocked(callingUserId);
                    verifyCallingPackage(callingUid, packageName);
                    AppSearchBatchResult.Builder<String, Void> resultBuilder =
                            new AppSearchBatchResult.Builder<>();
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    logger = mLoggerInstanceManager.getPlatformLogger(callingUserId);
                    for (int i = 0; i < ids.size(); i++) {
                        String id = ids.get(i);
                        try {
                            impl.remove(packageName, databaseName, namespace, id);
                            ++operationSuccessCount;
                            resultBuilder.setSuccess(id, /*result= */ null);
                        } catch (Throwable t) {
                            AppSearchResult<Void> result = throwableToFailedResult(t);
                            resultBuilder.setResult(id, result);
                            // Since we can only include one status code in the atom,
                            // for failures, we would just save the one for the last failure
                            statusCode = result.getResultCode();
                            ++operationFailureCount;
                        }
                    }
                    // Now that the batch has been written. Persist the newly written data.
                    impl.persistToDisk(PersistType.Code.LITE);
                    invokeCallbackOnResult(callback, resultBuilder.build());
                } catch (Throwable t) {
                    ++operationFailureCount;
                    statusCode = throwableToFailedResult(t).getResultCode();
                    invokeCallbackOnError(callback, t);
                } finally {
                    if (logger != null) {
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis - binderCallStartTimeMillis);
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        CallStats.Builder cBuilder = new CallStats.Builder(packageName,
                                databaseName)
                                .setCallType(CallStats.CALL_TYPE_REMOVE_DOCUMENTS_BY_ID)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount);
                        cBuilder.getGeneralStatsBuilder()
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis);
                        logger.logStats(cBuilder.build());
                    }
                }
            });
        }

        @Override
        public void removeByQuery(
                @NonNull String packageName,
                @NonNull String databaseName,
                @NonNull String queryExpression,
                @NonNull Bundle searchSpecBundle,
                @UserIdInt int userId,
                @ElapsedRealtimeLong long binderCallStartTimeMillis,
                @NonNull IAppSearchResultCallback callback) {
            // TODO(b/173532925) log CallStats once we have CALL_TYPE_REMOVE_BY_QUERY added
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(databaseName);
            Objects.requireNonNull(queryExpression);
            Objects.requireNonNull(searchSpecBundle);
            Objects.requireNonNull(callback);
            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                PlatformLogger logger = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    verifyUserUnlocked(callingUserId);
                    verifyCallingPackage(callingUid, packageName);
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    logger = mLoggerInstanceManager.getPlatformLogger(callingUserId);
                    impl.removeByQuery(
                            packageName,
                            databaseName,
                            queryExpression,
                            new SearchSpec(searchSpecBundle));
                    // Now that the batch has been written. Persist the newly written data.
                    impl.persistToDisk(PersistType.Code.LITE);
                    ++operationSuccessCount;
                    invokeCallbackOnResult(callback, AppSearchResult.newSuccessfulResult(null));
                } catch (Throwable t) {
                    ++operationFailureCount;
                    statusCode = throwableToFailedResult(t).getResultCode();
                    invokeCallbackOnError(callback, t);
                } finally {
                    if (logger != null) {
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis - binderCallStartTimeMillis);
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        CallStats.Builder cBuilder = new CallStats.Builder(packageName,
                                databaseName)
                                .setCallType(CallStats.CALL_TYPE_REMOVE_DOCUMENTS_BY_SEARCH)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount);
                        cBuilder.getGeneralStatsBuilder()
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis);
                        logger.logStats(cBuilder.build());
                    }
                }
            });
        }

        @Override
        public void getStorageInfo(
                @NonNull String packageName,
                @NonNull String databaseName,
                @UserIdInt int userId,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(databaseName);
            Objects.requireNonNull(callback);
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                try {
                    verifyUserUnlocked(callingUserId);
                    verifyCallingPackage(callingUid, packageName);
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    StorageInfo storageInfo = impl.getStorageInfoForDatabase(packageName,
                            databaseName);
                    Bundle storageInfoBundle = storageInfo.getBundle();
                    invokeCallbackOnResult(
                            callback, AppSearchResult.newSuccessfulResult(storageInfoBundle));
                } catch (Throwable t) {
                    invokeCallbackOnError(callback, t);
                }
            });
        }

        @Override
        public void persistToDisk(@UserIdInt int userId,
                @ElapsedRealtimeLong long binderCallStartTimeMillis) {
            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                PlatformLogger logger = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    verifyUserUnlocked(callingUserId);
                    AppSearchImpl impl =
                            mImplInstanceManager.getAppSearchImpl(callingUserId);
                    logger = mLoggerInstanceManager.getPlatformLogger(callingUserId);
                    impl.persistToDisk(PersistType.Code.FULL);
                    ++operationSuccessCount;
                } catch (Throwable t) {
                    ++operationFailureCount;
                    statusCode = throwableToFailedResult(t).getResultCode();
                    Log.e(TAG, "Unable to persist the data to disk", t);
                } finally {
                    if (logger != null) {
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis - binderCallStartTimeMillis);
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        CallStats.Builder cBuilder = new CallStats.Builder(/*packageName=*/ "",
                                /*databaseName=*/ "")
                                .setCallType(CallStats.CALL_TYPE_FLUSH)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount);
                        cBuilder.getGeneralStatsBuilder()
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis);
                        logger.logStats(cBuilder.build());
                    }
                }
            });
        }

        @Override
        public void initialize(@UserIdInt int userId,
                @ElapsedRealtimeLong long binderCallStartTimeMillis,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(callback);
            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            int callingUid = Binder.getCallingUid();
            int callingUserId = handleIncomingUser(userId, callingUid);
            EXECUTOR.execute(() -> {
                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                PlatformLogger logger = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    verifyUserUnlocked(callingUserId);
                    logger = mLoggerInstanceManager.getOrCreatePlatformLogger(mContext,
                            callingUserId);
                    mImplInstanceManager.getOrCreateAppSearchImpl(mContext, callingUserId, logger);
                    ++operationSuccessCount;
                    invokeCallbackOnResult(callback, AppSearchResult.newSuccessfulResult(null));
                } catch (Throwable t) {
                    ++operationFailureCount;
                    statusCode = throwableToFailedResult(t).getResultCode();
                    invokeCallbackOnError(callback, t);
                } finally {
                    if (logger != null) {
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis - binderCallStartTimeMillis);
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        // TODO(b/173532925) make packageName and database nullable after
                        //  removing generalStats
                        CallStats.Builder cBuilder = new CallStats.Builder(/*packageName=*/"",
                                /*database=*/ "")
                                .setCallType(CallStats.CALL_TYPE_INITIALIZE)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount);
                        cBuilder.getGeneralStatsBuilder()
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis);
                        logger.logStats(cBuilder.build());
                    }
                }
            });
        }

        private void verifyCallingPackage(int callingUid, @NonNull String callingPackage) {
            Objects.requireNonNull(callingPackage);
            if (mPackageManagerInternal.getPackageUid(
                    callingPackage, /*flags=*/ 0, UserHandle.getUserId(callingUid))
                    != callingUid) {
                throw new SecurityException(
                        "Specified calling package ["
                                + callingPackage
                                + "] does not match the calling uid "
                                + callingUid);
            }
        }

        /** Invokes the {@link IAppSearchResultCallback} with the result. */
        private void invokeCallbackOnResult(
                IAppSearchResultCallback callback, AppSearchResult<?> result) {
            try {
                callback.onResult(new AppSearchResultParcel<>(result));
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to send result to the callback", e);
            }
        }

        /** Invokes the {@link IAppSearchBatchResultCallback} with the result. */
        private void invokeCallbackOnResult(
                IAppSearchBatchResultCallback callback, AppSearchBatchResult<String, ?> result) {
            try {
                callback.onResult(new AppSearchBatchResultParcel<>(result));
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to send result to the callback", e);
            }
        }

        /**
         * Invokes the {@link IAppSearchResultCallback} with an throwable.
         *
         * <p>The throwable is convert to a {@link AppSearchResult};
         */
        private void invokeCallbackOnError(IAppSearchResultCallback callback, Throwable throwable) {
            AppSearchResult<?> result = throwableToFailedResult(throwable);
            try {
                callback.onResult(new AppSearchResultParcel<>(result));
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to send result to the callback", e);
            }
        }

        /**
         * Invokes the {@link IAppSearchBatchResultCallback} with an unexpected internal throwable.
         *
         * <p>The throwable is converted to {@link AppSearchResult}.
         */
        private void invokeCallbackOnError(
                @NonNull IAppSearchBatchResultCallback callback, @NonNull Throwable throwable) {
            AppSearchResult<?> result = throwableToFailedResult(throwable);
            try {
                callback.onSystemError(new AppSearchResultParcel<>(result));
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to send error to the callback", e);
            }
        }
    }

    // TODO(b/173553485) verifying that the caller has permission to access target user's data
    // TODO(b/173553485) Handle ACTION_USER_REMOVED broadcast
    // TODO(b/173553485) Implement SystemService.onUserStopping()
    private static int handleIncomingUser(@UserIdInt int userId, int callingUid) {
        int callingPid = Binder.getCallingPid();
        return ActivityManager.handleIncomingUser(
                callingPid,
                callingUid,
                userId,
                /*allowAll=*/ false,
                /*requireFull=*/ false,
                /*name=*/ null,
                /*callerPackage=*/ null);
    }

    // TODO(b/179160886): Cache the previous storage stats.
    private class AppSearchStorageStatsAugmenter implements StorageStatsAugmenter {
        @Override
        public void augmentStatsForPackageForUser(
                @NonNull PackageStats stats,
                @NonNull String packageName,
                @NonNull UserHandle userHandle,
                boolean canCallerAccessAllStats) {
            Objects.requireNonNull(stats);
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(userHandle);
            int userId = userHandle.getIdentifier();
            try {
                verifyUserUnlocked(userId);
                PlatformLogger logger = mLoggerInstanceManager.getOrCreatePlatformLogger(mContext,
                        userId);
                AppSearchImpl impl = mImplInstanceManager.getOrCreateAppSearchImpl(mContext,
                        userId, logger);
                stats.dataSize += impl.getStorageInfoForPackage(packageName).getSizeBytes();
            } catch (Throwable t) {
                Log.e(
                        TAG,
                        "Unable to augment storage stats for userId "
                                + userId
                                + " packageName "
                                + packageName,
                        t);
            }
        }

        @Override
        public void augmentStatsForUid(
                @NonNull PackageStats stats, int uid, boolean canCallerAccessAllStats) {
            Objects.requireNonNull(stats);
            int userId = UserHandle.getUserId(uid);
            try {
                verifyUserUnlocked(userId);
                String[] packagesForUid = mPackageManager.getPackagesForUid(uid);
                if (packagesForUid == null) {
                    return;
                }
                PlatformLogger logger = mLoggerInstanceManager.getOrCreatePlatformLogger(mContext,
                        userId);
                AppSearchImpl impl = mImplInstanceManager.getOrCreateAppSearchImpl(mContext,
                        userId, logger);
                for (int i = 0; i < packagesForUid.length; i++) {
                    stats.dataSize +=
                            impl.getStorageInfoForPackage(packagesForUid[i]).getSizeBytes();
                }
            } catch (Throwable t) {
                Log.e(TAG, "Unable to augment storage stats for uid " + uid, t);
            }
        }

        @Override
        public void augmentStatsForUser(
                @NonNull PackageStats stats, @NonNull UserHandle userHandle) {
            // TODO(b/179160886): this implementation could incur many jni calls and a lot of
            //  in-memory processing from getStorageInfoForPackage. Instead, we can just compute the
            //  size of the icing dir (or use the overall StorageInfo without interpolating it).
            Objects.requireNonNull(stats);
            Objects.requireNonNull(userHandle);
            int userId = userHandle.getIdentifier();
            try {
                verifyUserUnlocked(userId);
                List<PackageInfo> packagesForUser =
                        mPackageManager.getInstalledPackagesAsUser(/*flags=*/0, userId);
                if (packagesForUser == null) {
                    return;
                }
                PlatformLogger logger = mLoggerInstanceManager.getOrCreatePlatformLogger(mContext,
                        userId);
                AppSearchImpl impl =
                        mImplInstanceManager.getOrCreateAppSearchImpl(mContext, userId, logger);
                for (int i = 0; i < packagesForUser.size(); i++) {
                    String packageName = packagesForUser.get(i).packageName;
                    stats.dataSize += impl.getStorageInfoForPackage(packageName).getSizeBytes();
                }
            } catch (Throwable t) {
                Log.e(TAG, "Unable to augment storage stats for user " + userId, t);
            }
        }
    }
}
