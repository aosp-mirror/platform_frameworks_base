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

import android.annotation.ElapsedRealtimeLong;
import android.annotation.NonNull;
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
import android.app.appsearch.exceptions.AppSearchException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
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
import com.android.server.SystemService;
import com.android.server.appsearch.external.localstorage.stats.CallStats;
import com.android.server.appsearch.external.localstorage.stats.OptimizeStats;
import com.android.server.appsearch.external.localstorage.visibilitystore.VisibilityStore;
import com.android.server.appsearch.stats.StatsCollector;
import com.android.server.appsearch.util.PackageUtil;
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

/**
 * The main service implementation which contains AppSearch's platform functionality.
 * @hide
 */
public class AppSearchManagerService extends SystemService {
    private static final String TAG = "AppSearchManagerService";
    private final Context mContext;
    private PackageManager mPackageManager;
    private UserManager mUserManager;
    private AppSearchUserInstanceManager mAppSearchUserInstanceManager;

    // Never call shutdownNow(). It will cancel the futures it's returned. And since
    // Executor#execute won't return anything, we will hang forever waiting for the execution.
    // AppSearch multi-thread execution is guarded by Read & Write Lock in AppSearchImpl, all
    // mutate requests will need to gain write lock and query requests need to gain read lock.
    private static final Executor EXECUTOR = new ThreadPoolExecutor(/*corePoolSize=*/1,
            Runtime.getRuntime().availableProcessors(), /*keepAliveTime*/ 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>());

    // Cache of unlocked users so we don't have to query UserManager service each time. The "locked"
    // suffix refers to the fact that access to the field should be locked; unrelated to the
    // unlocked status of users.
    @GuardedBy("mUnlockedUsersLocked")
    private final Set<UserHandle> mUnlockedUsersLocked = new ArraySet<>();

    public AppSearchManagerService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        publishBinderService(Context.APP_SEARCH_SERVICE, new Stub());
        mPackageManager = getContext().getPackageManager();
        mAppSearchUserInstanceManager = AppSearchUserInstanceManager.getInstance();
        mUserManager = mContext.getSystemService(UserManager.class);
        registerReceivers();
        LocalManagerRegistry.getManager(StorageStatsManagerLocal.class)
                .registerStorageStatsAugmenter(new AppSearchStorageStatsAugmenter(), TAG);
    }

    @Override
    public void onBootPhase(/* @BootPhase */ int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            StatsCollector.getInstance(mContext, EXECUTOR);
        }
    }

    private void registerReceivers() {
        mContext.registerReceiverForAllUsers(
                new UserActionReceiver(),
                new IntentFilter(Intent.ACTION_USER_REMOVED),
                /*broadcastPermission=*/ null,
                /*scheduler=*/ null);

        //TODO(b/145759910) Add a direct callback when user clears the data instead of relying on
        // broadcasts
        IntentFilter packageChangedFilter = new IntentFilter();
        packageChangedFilter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        packageChangedFilter.addAction(Intent.ACTION_PACKAGE_DATA_CLEARED);
        packageChangedFilter.addDataScheme("package");
        packageChangedFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiverForAllUsers(
                new PackageChangedReceiver(),
                packageChangedFilter,
                /*broadcastPermission=*/ null,
                /*scheduler=*/ null);
    }

    private class UserActionReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(@NonNull Context context, @NonNull Intent intent) {
            Objects.requireNonNull(context);
            Objects.requireNonNull(intent);

            switch (intent.getAction()) {
                case Intent.ACTION_USER_REMOVED:
                    UserHandle userHandle = intent.getParcelableExtra(Intent.EXTRA_USER);
                    if (userHandle == null) {
                        Log.e(TAG, "Extra "
                                + Intent.EXTRA_USER + " is missing in the intent: " + intent);
                        return;
                    }
                    handleUserRemoved(userHandle);
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
     * @param userHandle The multi-user handle of the user that need to be removed.
     *
     * @see android.os.Environment#getDataSystemCeDirectory
     */
    private void handleUserRemoved(@NonNull UserHandle userHandle) {
        try {
            mAppSearchUserInstanceManager.closeAndRemoveUserInstance(userHandle);
            Log.i(TAG, "Removed AppSearchImpl instance for: " + userHandle);
        } catch (Throwable t) {
            Log.e(TAG, "Unable to remove data for: " + userHandle, t);
        }
    }

    private class PackageChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(@NonNull Context context, @NonNull Intent intent) {
            Objects.requireNonNull(context);
            Objects.requireNonNull(intent);

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

    private void handlePackageRemoved(@NonNull String packageName, int uid) {
        UserHandle userHandle = UserHandle.getUserHandleForUid(uid);
        try {
            if (isUserLocked(userHandle)) {
                // We cannot access a locked user's directry and remove package data from it.
                // We should remove those uninstalled package data when the user is unlocking.
                return;
            }
            // Only clear the package's data if AppSearch exists for this user.
            if (AppSearchUserInstanceManager.getAppSearchDir(userHandle).exists()) {
                Context userContext = mContext.createContextAsUser(userHandle, /*flags=*/ 0);
                AppSearchUserInstance instance =
                        mAppSearchUserInstanceManager.getOrCreateUserInstance(
                                userContext, userHandle, AppSearchConfig.getInstance(EXECUTOR));
                //TODO(b/145759910) clear visibility setting for package.
                instance.getAppSearchImpl().clearPackageData(packageName);
                instance.getLogger().removeCachedUidForPackage(packageName);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Unable to remove data for package: " + packageName, t);
        }
    }

    @Override
    public void onUserUnlocking(@NonNull TargetUser user) {
        Objects.requireNonNull(user);
        UserHandle userHandle = user.getUserHandle();
        synchronized (mUnlockedUsersLocked) {
            mUnlockedUsersLocked.add(userHandle);
        }
        EXECUTOR.execute(() -> {
            try {
                // Only clear the package's data if AppSearch exists for this user.
                if (AppSearchUserInstanceManager.getAppSearchDir(userHandle).exists()) {
                    Context userContext = mContext.createContextAsUser(userHandle, /*flags=*/ 0);
                    AppSearchUserInstance instance =
                            mAppSearchUserInstanceManager.getOrCreateUserInstance(
                                    userContext, userHandle, AppSearchConfig.getInstance(EXECUTOR));
                    List<PackageInfo> installedPackageInfos = userContext
                            .getPackageManager()
                            .getInstalledPackages(/*flags=*/0);
                    Set<String> packagesToKeep = new ArraySet<>(installedPackageInfos.size());
                    for (int i = 0; i < installedPackageInfos.size(); i++) {
                        packagesToKeep.add(installedPackageInfos.get(i).packageName);
                    }
                    packagesToKeep.add(VisibilityStore.PACKAGE_NAME);
                    //TODO(b/145759910) clear visibility setting for package.
                    instance.getAppSearchImpl().prunePackageData(packagesToKeep);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Unable to prune packages for " + user, t);
            }
        });
    }

    @Override
    public void onUserStopping(@NonNull TargetUser user) {
        Objects.requireNonNull(user);

        synchronized (mUnlockedUsersLocked) {
            UserHandle userHandle = user.getUserHandle();
            mUnlockedUsersLocked.remove(userHandle);
            try {
                mAppSearchUserInstanceManager.closeAndRemoveUserInstance(userHandle);
            } catch (Throwable t) {
                Log.e(TAG, "Error handling user stopping.", t);
            }
        }
    }

    private void verifyUserUnlocked(@NonNull UserHandle callingUser) {
        if (isUserLocked(callingUser)) {
            throw new IllegalStateException(callingUser + " is locked or not running.");
        }
    }

    private boolean isUserLocked(@NonNull UserHandle callingUser) {
        synchronized (mUnlockedUsersLocked) {
            // First, check the local copy.
            if (mUnlockedUsersLocked.contains(callingUser)) {
                return false;
            }
            // If the local copy says the user is locked, check with UM for the actual state,
            // since the user might just have been unlocked.
            return !mUserManager.isUserUnlockingOrUnlocked(callingUser);
        }
    }

    private class Stub extends IAppSearchManager.Stub {
        @Override
        public void setSchema(
                @NonNull String packageName,
                @NonNull String databaseName,
                @NonNull List<Bundle> schemaBundles,
                @NonNull List<String> schemasNotDisplayedBySystem,
                @NonNull Map<String, List<Bundle>> schemasVisibleToPackagesBundles,
                boolean forceOverride,
                int schemaVersion,
                @NonNull UserHandle userHandle,
                @ElapsedRealtimeLong long binderCallStartTimeMillis,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(databaseName);
            Objects.requireNonNull(schemaBundles);
            Objects.requireNonNull(schemasNotDisplayedBySystem);
            Objects.requireNonNull(schemasVisibleToPackagesBundles);
            Objects.requireNonNull(userHandle);
            Objects.requireNonNull(callback);

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            int callingUid = Binder.getCallingUid();
            EXECUTOR.execute(() -> {
                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    verifyCaller(callingUid, packageName);

                    // Obtain the user where the client wants to run the operations in. This should
                    // end up being the same as userHandle, assuming it is not a special user and
                    // the client is allowed to run operations in that user.
                    UserHandle targetUser = handleIncomingUser(userHandle, callingUid);
                    verifyUserUnlocked(targetUser);

                    List<AppSearchSchema> schemas = new ArrayList<>(schemaBundles.size());
                    for (int i = 0; i < schemaBundles.size(); i++) {
                        schemas.add(new AppSearchSchema(schemaBundles.get(i)));
                    }
                    Map<String, List<PackageIdentifier>> schemasVisibleToPackages =
                            new ArrayMap<>(schemasVisibleToPackagesBundles.size());
                    for (Map.Entry<String, List<Bundle>> entry :
                            schemasVisibleToPackagesBundles.entrySet()) {
                        List<PackageIdentifier> packageIdentifiers =
                                new ArrayList<>(entry.getValue().size());
                        for (int i = 0; i < entry.getValue().size(); i++) {
                            packageIdentifiers.add(
                                    new PackageIdentifier(entry.getValue().get(i)));
                        }
                        schemasVisibleToPackages.put(entry.getKey(), packageIdentifiers);
                    }
                    instance = mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    // TODO(b/173532925): Implement logging for statsBuilder
                    SetSchemaResponse setSchemaResponse = instance.getAppSearchImpl().setSchema(
                            packageName,
                            databaseName,
                            schemas,
                            instance.getVisibilityStore(),
                            schemasNotDisplayedBySystem,
                            schemasVisibleToPackages,
                            forceOverride,
                            schemaVersion,
                            /*setSchemaStatsBuilder=*/ null);
                    ++operationSuccessCount;
                    invokeCallbackOnResult(callback,
                            AppSearchResult.newSuccessfulResult(setSchemaResponse.getBundle()));

                    // setSchema will sync the schemas in the request to AppSearch, any existing
                    // schemas which  is not included in the request will be delete if we force
                    // override incompatible schemas. And all documents of these types will be
                    // deleted as well. We should checkForOptimize for these deletion.
                    checkForOptimize(instance);
                } catch (Throwable t) {
                    ++operationFailureCount;
                    statusCode = throwableToFailedResult(t).getResultCode();
                    invokeCallbackOnError(callback, t);
                } finally {
                    if (instance != null) {
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis - binderCallStartTimeMillis);
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setPackageName(packageName)
                                .setDatabase(databaseName)
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                .setCallType(CallStats.CALL_TYPE_SET_SCHEMA)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .build());
                    }
                }
            });
        }

        @Override
        public void getSchema(
                @NonNull String packageName,
                @NonNull String databaseName,
                @NonNull UserHandle userHandle,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(databaseName);
            Objects.requireNonNull(userHandle);
            Objects.requireNonNull(callback);

            int callingUid = Binder.getCallingUid();
            EXECUTOR.execute(() -> {
                try {
                    verifyCaller(callingUid, packageName);

                    // Obtain the user where the client wants to run the operations in. This should
                    // end up being the same as userHandle, assuming it is not a special user and
                    // the client is allowed to run operations in that user.
                    UserHandle targetUser = handleIncomingUser(userHandle, callingUid);
                    verifyUserUnlocked(targetUser);

                    AppSearchUserInstance instance =
                            mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    GetSchemaResponse response =
                            instance.getAppSearchImpl().getSchema(packageName, databaseName);
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
                @NonNull UserHandle userHandle,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(databaseName);
            Objects.requireNonNull(userHandle);
            Objects.requireNonNull(callback);

            int callingUid = Binder.getCallingUid();
            EXECUTOR.execute(() -> {
                try {
                    verifyCaller(callingUid, packageName);

                    // Obtain the user where the client wants to run the operations in. This should
                    // end up being the same as userHandle, assuming it is not a special user and
                    // the client is allowed to run operations in that user.
                    UserHandle targetUser = handleIncomingUser(userHandle, callingUid);
                    verifyUserUnlocked(targetUser);

                    AppSearchUserInstance instance =
                            mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    List<String> namespaces =
                            instance.getAppSearchImpl().getNamespaces(packageName, databaseName);
                    invokeCallbackOnResult(
                            callback, AppSearchResult.newSuccessfulResult(namespaces));
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
                @NonNull UserHandle userHandle,
                @ElapsedRealtimeLong long binderCallStartTimeMillis,
                @NonNull IAppSearchBatchResultCallback callback) {
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(databaseName);
            Objects.requireNonNull(documentBundles);
            Objects.requireNonNull(userHandle);
            Objects.requireNonNull(callback);

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            int callingUid = Binder.getCallingUid();
            EXECUTOR.execute(() -> {
                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    verifyCaller(callingUid, packageName);

                    // Obtain the user where the client wants to run the operations in. This should
                    // end up being the same as userHandle, assuming it is not a special user and
                    // the client is allowed to run operations in that user.
                    UserHandle targetUser = handleIncomingUser(userHandle, callingUid);
                    verifyUserUnlocked(targetUser);

                    AppSearchBatchResult.Builder<String, Void> resultBuilder =
                            new AppSearchBatchResult.Builder<>();
                    instance = mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    for (int i = 0; i < documentBundles.size(); i++) {
                        GenericDocument document = new GenericDocument(documentBundles.get(i));
                        try {
                            instance.getAppSearchImpl().putDocument(
                                    packageName, databaseName, document, instance.getLogger());
                            resultBuilder.setSuccess(document.getId(), /*value=*/ null);
                            ++operationSuccessCount;
                        } catch (Throwable t) {
                            resultBuilder.setResult(document.getId(), throwableToFailedResult(t));
                            AppSearchResult<Void> result = throwableToFailedResult(t);
                            resultBuilder.setResult(document.getId(), result);
                            // Since we can only include one status code in the atom,
                            // for failures, we would just save the one for the last failure
                            statusCode = result.getResultCode();
                            ++operationFailureCount;
                        }
                    }
                    // Now that the batch has been written. Persist the newly written data.
                    instance.getAppSearchImpl().persistToDisk(PersistType.Code.LITE);
                    invokeCallbackOnResult(callback, resultBuilder.build());

                    // The existing documents with same ID will be deleted, so there may be some
                    // resources that could be released after optimize().
                    checkForOptimize(instance, /*mutateBatchSize=*/ documentBundles.size());
                } catch (Throwable t) {
                    ++operationFailureCount;
                    statusCode = throwableToFailedResult(t).getResultCode();
                    invokeCallbackOnError(callback, t);
                } finally {
                    if (instance != null) {
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis - binderCallStartTimeMillis);
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setPackageName(packageName)
                                .setDatabase(databaseName)
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                .setCallType(CallStats.CALL_TYPE_PUT_DOCUMENTS)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .build());
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
                @NonNull UserHandle userHandle,
                @ElapsedRealtimeLong long binderCallStartTimeMillis,
                @NonNull IAppSearchBatchResultCallback callback) {
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(databaseName);
            Objects.requireNonNull(namespace);
            Objects.requireNonNull(ids);
            Objects.requireNonNull(typePropertyPaths);
            Objects.requireNonNull(userHandle);
            Objects.requireNonNull(callback);

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            int callingUid = Binder.getCallingUid();
            EXECUTOR.execute(() -> {
                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    verifyCaller(callingUid, packageName);

                    // Obtain the user where the client wants to run the operations in. This should
                    // end up being the same as userHandle, assuming it is not a special user and
                    // the client is allowed to run operations in that user.
                    UserHandle targetUser = handleIncomingUser(userHandle, callingUid);
                    verifyUserUnlocked(targetUser);

                    AppSearchBatchResult.Builder<String, Bundle> resultBuilder =
                            new AppSearchBatchResult.Builder<>();
                    instance = mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    for (int i = 0; i < ids.size(); i++) {
                        String id = ids.get(i);
                        try {
                            GenericDocument document = instance.getAppSearchImpl().getDocument(
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
                    if (instance != null) {
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis - binderCallStartTimeMillis);
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setPackageName(packageName)
                                .setDatabase(databaseName)
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                .setCallType(CallStats.CALL_TYPE_GET_DOCUMENTS)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .build());
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
                @NonNull UserHandle userHandle,
                @ElapsedRealtimeLong long binderCallStartTimeMillis,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(databaseName);
            Objects.requireNonNull(queryExpression);
            Objects.requireNonNull(searchSpecBundle);
            Objects.requireNonNull(userHandle);
            Objects.requireNonNull(callback);

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            int callingUid = Binder.getCallingUid();
            EXECUTOR.execute(() -> {
                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    verifyCaller(callingUid, packageName);

                    // Obtain the user where the client wants to run the operations in. This should
                    // end up being the same as userHandle, assuming it is not a special user and
                    // the client is allowed to run operations in that user.
                    UserHandle targetUser = handleIncomingUser(userHandle, callingUid);
                    verifyUserUnlocked(targetUser);

                    instance = mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    SearchResultPage searchResultPage = instance.getAppSearchImpl().query(
                            packageName,
                            databaseName,
                            queryExpression,
                            new SearchSpec(searchSpecBundle),
                            instance.getLogger());
                    ++operationSuccessCount;
                    invokeCallbackOnResult(
                            callback,
                            AppSearchResult.newSuccessfulResult(searchResultPage.getBundle()));
                } catch (Throwable t) {
                    ++operationFailureCount;
                    statusCode = throwableToFailedResult(t).getResultCode();
                    invokeCallbackOnError(callback, t);
                } finally {
                    if (instance != null) {
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis - binderCallStartTimeMillis);
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setPackageName(packageName)
                                .setDatabase(databaseName)
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                .setCallType(CallStats.CALL_TYPE_SEARCH)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .build());
                    }
                }
            });
        }

        @Override
        public void globalQuery(
                @NonNull String packageName,
                @NonNull String queryExpression,
                @NonNull Bundle searchSpecBundle,
                @NonNull UserHandle userHandle,
                @ElapsedRealtimeLong long binderCallStartTimeMillis,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(queryExpression);
            Objects.requireNonNull(searchSpecBundle);
            Objects.requireNonNull(userHandle);
            Objects.requireNonNull(callback);

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            int callingUid = Binder.getCallingUid();
            EXECUTOR.execute(() -> {
                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    verifyCaller(callingUid, packageName);

                    // Obtain the user where the client wants to run the operations in. This should
                    // end up being the same as userHandle, assuming it is not a special user and
                    // the client is allowed to run operations in that user.
                    UserHandle targetUser = handleIncomingUser(userHandle, callingUid);
                    verifyUserUnlocked(targetUser);

                    instance = mAppSearchUserInstanceManager.getUserInstance(targetUser);

                    boolean callerHasSystemAccess =
                            instance.getVisibilityStore().doesCallerHaveSystemAccess(packageName);
                    SearchResultPage searchResultPage = instance.getAppSearchImpl().globalQuery(
                            queryExpression,
                            new SearchSpec(searchSpecBundle),
                            packageName,
                            instance.getVisibilityStore(),
                            callingUid,
                            callerHasSystemAccess,
                            instance.getLogger());
                    ++operationSuccessCount;
                    invokeCallbackOnResult(
                            callback,
                            AppSearchResult.newSuccessfulResult(searchResultPage.getBundle()));
                } catch (Throwable t) {
                    ++operationFailureCount;
                    statusCode = throwableToFailedResult(t).getResultCode();
                    invokeCallbackOnError(callback, t);
                } finally {
                    if (instance != null) {
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis - binderCallStartTimeMillis);
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setPackageName(packageName)
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                .setCallType(CallStats.CALL_TYPE_GLOBAL_SEARCH)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .build());
                    }
                }
            });
        }

        @Override
        public void getNextPage(
                @NonNull String packageName,
                long nextPageToken,
                @NonNull UserHandle userHandle,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(userHandle);
            Objects.requireNonNull(callback);

            int callingUid = Binder.getCallingUid();
            EXECUTOR.execute(() -> {
                try {
                    verifyCaller(callingUid, packageName);

                    // Obtain the user where the client wants to run the operations in. This should
                    // end up being the same as userHandle, assuming it is not a special user and
                    // the client is allowed to run operations in that user.
                    UserHandle targetUser = handleIncomingUser(userHandle, callingUid);
                    verifyUserUnlocked(targetUser);

                    AppSearchUserInstance instance =
                            mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    // TODO(b/173532925): Implement logging for statsBuilder
                    SearchResultPage searchResultPage =
                            instance.getAppSearchImpl().getNextPage(
                                    packageName, nextPageToken, /*statsBuilder=*/ null);
                    invokeCallbackOnResult(
                            callback,
                            AppSearchResult.newSuccessfulResult(searchResultPage.getBundle()));
                } catch (Throwable t) {
                    invokeCallbackOnError(callback, t);
                }
            });
        }

        @Override
        public void invalidateNextPageToken(@NonNull String packageName, long nextPageToken,
                @NonNull UserHandle userHandle) {
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(userHandle);

            int callingUid = Binder.getCallingUid();
            EXECUTOR.execute(() -> {
                try {
                    verifyCaller(callingUid, packageName);

                    // Obtain the user where the client wants to run the operations in. This should
                    // end up being the same as userHandle, assuming it is not a special user and
                    // the client is allowed to run operations in that user.
                    UserHandle targetUser = handleIncomingUser(userHandle, callingUid);
                    verifyUserUnlocked(targetUser);

                    AppSearchUserInstance instance =
                            mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    instance.getAppSearchImpl().invalidateNextPageToken(packageName, nextPageToken);
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
                @NonNull UserHandle userHandle,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(databaseName);
            Objects.requireNonNull(fileDescriptor);
            Objects.requireNonNull(queryExpression);
            Objects.requireNonNull(searchSpecBundle);
            Objects.requireNonNull(userHandle);
            Objects.requireNonNull(callback);

            int callingUid = Binder.getCallingUid();
            EXECUTOR.execute(() -> {
                try {
                    verifyCaller(callingUid, packageName);

                    // Obtain the user where the client wants to run the operations in. This should
                    // end up being the same as userHandle, assuming it is not a special user and
                    // the client is allowed to run operations in that user.
                    UserHandle targetUser = handleIncomingUser(userHandle, callingUid);
                    verifyUserUnlocked(targetUser);

                    AppSearchUserInstance instance =
                            mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    // we don't need to append the file. The file is always brand new.
                    try (DataOutputStream outputStream = new DataOutputStream(
                            new FileOutputStream(fileDescriptor.getFileDescriptor()))) {
                        SearchResultPage searchResultPage = instance.getAppSearchImpl().query(
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
                            // TODO(b/173532925): Implement logging for statsBuilder
                            searchResultPage = instance.getAppSearchImpl().getNextPage(
                                    packageName,
                                    searchResultPage.getNextPageToken(),
                                    /*statsBuilder=*/ null);
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
                @NonNull UserHandle userHandle,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(databaseName);
            Objects.requireNonNull(fileDescriptor);
            Objects.requireNonNull(userHandle);
            Objects.requireNonNull(callback);

            int callingUid = Binder.getCallingUid();
            EXECUTOR.execute(() -> {
                try {
                    verifyCaller(callingUid, packageName);

                    // Obtain the user where the client wants to run the operations in. This should
                    // end up being the same as userHandle, assuming it is not a special user and
                    // the client is allowed to run operations in that user.
                    UserHandle targetUser = handleIncomingUser(userHandle, callingUid);
                    verifyUserUnlocked(targetUser);

                    AppSearchUserInstance instance =
                            mAppSearchUserInstanceManager.getUserInstance(targetUser);

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
                                instance.getAppSearchImpl().putDocument(
                                        packageName, databaseName, document, /*logger=*/ null);
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
                    instance.getAppSearchImpl().persistToDisk(PersistType.Code.FULL);
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
                @NonNull UserHandle userHandle,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(databaseName);
            Objects.requireNonNull(namespace);
            Objects.requireNonNull(documentId);
            Objects.requireNonNull(userHandle);
            Objects.requireNonNull(callback);

            int callingUid = Binder.getCallingUid();
            EXECUTOR.execute(() -> {
                try {
                    verifyCaller(callingUid, packageName);

                    // Obtain the user where the client wants to run the operations in. This should
                    // end up being the same as userHandle, assuming it is not a special user and
                    // the client is allowed to run operations in that user.
                    UserHandle targetUser = handleIncomingUser(userHandle, callingUid);
                    verifyUserUnlocked(targetUser);

                    AppSearchUserInstance instance =
                            mAppSearchUserInstanceManager.getUserInstance(targetUser);

                    if (systemUsage
                            && !instance.getVisibilityStore()
                            .doesCallerHaveSystemAccess(packageName)) {
                        throw new AppSearchException(
                                AppSearchResult.RESULT_SECURITY_ERROR,
                                packageName + " does not have access to report system usage");
                    }

                    instance.getAppSearchImpl().reportUsage(
                            packageName, databaseName, namespace, documentId,
                            usageTimeMillis, systemUsage);
                    invokeCallbackOnResult(
                            callback, AppSearchResult.newSuccessfulResult(/*value=*/ null));
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
                @NonNull UserHandle userHandle,
                @ElapsedRealtimeLong long binderCallStartTimeMillis,
                @NonNull IAppSearchBatchResultCallback callback) {
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(databaseName);
            Objects.requireNonNull(namespace);
            Objects.requireNonNull(ids);
            Objects.requireNonNull(userHandle);
            Objects.requireNonNull(callback);

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            int callingUid = Binder.getCallingUid();
            EXECUTOR.execute(() -> {
                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    verifyCaller(callingUid, packageName);

                    // Obtain the user where the client wants to run the operations in. This should
                    // end up being the same as userHandle, assuming it is not a special user and
                    // the client is allowed to run operations in that user.
                    UserHandle targetUser = handleIncomingUser(userHandle, callingUid);
                    verifyUserUnlocked(targetUser);

                    AppSearchBatchResult.Builder<String, Void> resultBuilder =
                            new AppSearchBatchResult.Builder<>();
                    instance = mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    for (int i = 0; i < ids.size(); i++) {
                        String id = ids.get(i);
                        try {
                            instance.getAppSearchImpl().remove(
                                    packageName,
                                    databaseName,
                                    namespace,
                                    id,
                                    /*removeStatsBuilder=*/ null);
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
                    instance.getAppSearchImpl().persistToDisk(PersistType.Code.LITE);
                    invokeCallbackOnResult(callback, resultBuilder.build());

                    checkForOptimize(instance, ids.size());
                } catch (Throwable t) {
                    ++operationFailureCount;
                    statusCode = throwableToFailedResult(t).getResultCode();
                    invokeCallbackOnError(callback, t);
                } finally {
                    if (instance != null) {
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis - binderCallStartTimeMillis);
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setPackageName(packageName)
                                .setDatabase(databaseName)
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                .setCallType(CallStats.CALL_TYPE_REMOVE_DOCUMENTS_BY_ID)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .build());
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
                @NonNull UserHandle userHandle,
                @ElapsedRealtimeLong long binderCallStartTimeMillis,
                @NonNull IAppSearchResultCallback callback) {
            // TODO(b/173532925) log CallStats once we have CALL_TYPE_REMOVE_BY_QUERY added
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(databaseName);
            Objects.requireNonNull(queryExpression);
            Objects.requireNonNull(searchSpecBundle);
            Objects.requireNonNull(userHandle);
            Objects.requireNonNull(callback);

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            int callingUid = Binder.getCallingUid();
            EXECUTOR.execute(() -> {
                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    verifyCaller(callingUid, packageName);

                    // Obtain the user where the client wants to run the operations in. This should
                    // end up being the same as userHandle, assuming it is not a special user and
                    // the client is allowed to run operations in that user.
                    UserHandle targetUser = handleIncomingUser(userHandle, callingUid);
                    verifyUserUnlocked(targetUser);

                    instance = mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    instance.getAppSearchImpl().removeByQuery(
                            packageName,
                            databaseName,
                            queryExpression,
                            new SearchSpec(searchSpecBundle),
                            /*removeStatsBuilder=*/ null);
                    // Now that the batch has been written. Persist the newly written data.
                    instance.getAppSearchImpl().persistToDisk(PersistType.Code.LITE);
                    ++operationSuccessCount;
                    invokeCallbackOnResult(callback, AppSearchResult.newSuccessfulResult(null));

                    checkForOptimize(instance);
                } catch (Throwable t) {
                    ++operationFailureCount;
                    statusCode = throwableToFailedResult(t).getResultCode();
                    invokeCallbackOnError(callback, t);
                } finally {
                    if (instance != null) {
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis - binderCallStartTimeMillis);
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setPackageName(packageName)
                                .setDatabase(databaseName)
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                .setCallType(CallStats.CALL_TYPE_REMOVE_DOCUMENTS_BY_SEARCH)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .build());
                    }
                }
            });
        }

        @Override
        public void getStorageInfo(
                @NonNull String packageName,
                @NonNull String databaseName,
                @NonNull UserHandle userHandle,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(databaseName);
            Objects.requireNonNull(userHandle);
            Objects.requireNonNull(callback);

            int callingUid = Binder.getCallingUid();
            EXECUTOR.execute(() -> {
                try {
                    verifyCaller(callingUid, packageName);

                    // Obtain the user where the client wants to run the operations in. This should
                    // end up being the same as userHandle, assuming it is not a special user and
                    // the client is allowed to run operations in that user.
                    UserHandle targetUser = handleIncomingUser(userHandle, callingUid);
                    verifyUserUnlocked(targetUser);

                    AppSearchUserInstance instance =
                            mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    StorageInfo storageInfo = instance.getAppSearchImpl()
                            .getStorageInfoForDatabase(packageName, databaseName);
                    Bundle storageInfoBundle = storageInfo.getBundle();
                    invokeCallbackOnResult(
                            callback, AppSearchResult.newSuccessfulResult(storageInfoBundle));
                } catch (Throwable t) {
                    invokeCallbackOnError(callback, t);
                }
            });
        }

        @Override
        public void persistToDisk(
                @NonNull String packageName,
                @NonNull UserHandle userHandle,
                @ElapsedRealtimeLong long binderCallStartTimeMillis) {
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(userHandle);

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            int callingUid = Binder.getCallingUid();
            EXECUTOR.execute(() -> {
                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    verifyCaller(callingUid, packageName);

                    // Obtain the user where the client wants to run the operations in. This should
                    // end up being the same as userHandle, assuming it is not a special user and
                    // the client is allowed to run operations in that user.
                    UserHandle targetUser = handleIncomingUser(userHandle, callingUid);
                    verifyUserUnlocked(targetUser);

                    instance = mAppSearchUserInstanceManager.getUserInstance(targetUser);
                    instance.getAppSearchImpl().persistToDisk(PersistType.Code.FULL);
                    ++operationSuccessCount;
                } catch (Throwable t) {
                    ++operationFailureCount;
                    statusCode = throwableToFailedResult(t).getResultCode();
                    Log.e(TAG, "Unable to persist the data to disk", t);
                } finally {
                    if (instance != null) {
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis - binderCallStartTimeMillis);
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                .setCallType(CallStats.CALL_TYPE_FLUSH)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .build());
                    }
                }
            });
        }

        @Override
        public void initialize(
                @NonNull String packageName,
                @NonNull UserHandle userHandle,
                @ElapsedRealtimeLong long binderCallStartTimeMillis,
                @NonNull IAppSearchResultCallback callback) {
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(userHandle);
            Objects.requireNonNull(callback);

            long totalLatencyStartTimeMillis = SystemClock.elapsedRealtime();
            int callingUid = Binder.getCallingUid();

            EXECUTOR.execute(() -> {
                @AppSearchResult.ResultCode int statusCode = AppSearchResult.RESULT_OK;
                AppSearchUserInstance instance = null;
                int operationSuccessCount = 0;
                int operationFailureCount = 0;
                try {
                    verifyCaller(callingUid, packageName);

                    // Obtain the user where the client wants to run the operations in. This should
                    // end up being the same as userHandle, assuming it is not a special user and
                    // the client is allowed to run operations in that user.
                    UserHandle targetUser = handleIncomingUser(userHandle, callingUid);
                    verifyUserUnlocked(targetUser);

                    Context targetUserContext = mContext.createContextAsUser(targetUser,
                            /*flags=*/ 0);
                    instance = mAppSearchUserInstanceManager.getOrCreateUserInstance(
                            targetUserContext, targetUser, AppSearchConfig.getInstance(EXECUTOR));
                    ++operationSuccessCount;
                    invokeCallbackOnResult(callback, AppSearchResult.newSuccessfulResult(null));
                } catch (Throwable t) {
                    ++operationFailureCount;
                    statusCode = throwableToFailedResult(t).getResultCode();
                    invokeCallbackOnError(callback, t);
                } finally {
                    if (instance != null) {
                        int estimatedBinderLatencyMillis =
                                2 * (int) (totalLatencyStartTimeMillis - binderCallStartTimeMillis);
                        int totalLatencyMillis =
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartTimeMillis);
                        instance.getLogger().logStats(new CallStats.Builder()
                                .setStatusCode(statusCode)
                                .setTotalLatencyMillis(totalLatencyMillis)
                                .setCallType(CallStats.CALL_TYPE_INITIALIZE)
                                // TODO(b/173532925) check the existing binder call latency chart
                                // is good enough for us:
                                // http://dashboards/view/_72c98f9a_91d9_41d4_ab9a_bc14f79742b4
                                .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                                .setNumOperationsSucceeded(operationSuccessCount)
                                .setNumOperationsFailed(operationFailureCount)
                                .build());
                    }
                }
            });
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

    /**
     * Helper for dealing with incoming user arguments to system service calls.
     *
     * @param targetUserHandle The user which the caller is requesting to execute as.
     * @param callingUid The actual uid of the caller as determined by Binder.
     * @return the user handle that the call should run as. Will always be a concrete user.
     */
    @NonNull
    private UserHandle handleIncomingUser(@NonNull UserHandle targetUserHandle, int callingUid) {
        UserHandle callingUserHandle = UserHandle.getUserHandleForUid(callingUid);
        if (callingUserHandle.equals(targetUserHandle)) {
            return targetUserHandle;
        }

        // Duplicates UserController#ensureNotSpecialUser
        if (targetUserHandle.getIdentifier() < 0) {
            throw new IllegalArgumentException(
                    "Call does not support special user " + targetUserHandle);
        }

        throw new SecurityException(
                "Requested user, " + targetUserHandle + ", is not the same as the calling user, "
                        + callingUserHandle + ".");
    }

    /**
     * Verify various aspects of the calling user.
     *
     * @param callingUid Uid of the caller, usually retrieved from Binder for authenticity.
     * @param claimedCallingPackage Package name the caller claims to be.
     */
    private void verifyCaller(int callingUid, @NonNull String claimedCallingPackage) {
        // Obtain the user where the client is running in. Note that this could be different from
        // the userHandle where the client wants to run the AppSearch operation in.
        UserHandle callingUserHandle = UserHandle.getUserHandleForUid(callingUid);
        Context callingUserContext = mContext.createContextAsUser(callingUserHandle,
                /*flags=*/ 0);

        verifyCallingPackage(callingUserContext, callingUid, claimedCallingPackage);
        verifyNotInstantApp(callingUserContext, claimedCallingPackage);
    }

    /**
     * Check that the caller's supposed package name matches the uid making the call.
     *
     * @throws SecurityException if the package name and uid don't match.
     */
    private void verifyCallingPackage(
            @NonNull Context actualCallingUserContext,
            int actualCallingUid,
            @NonNull String claimedCallingPackage) {
        int claimedCallingUid = PackageUtil.getPackageUid(
                actualCallingUserContext, claimedCallingPackage);
        if (claimedCallingUid == INVALID_UID) {
            throw new SecurityException(
                    "Specified calling package [" + claimedCallingPackage + "] not found");
        }
        if (claimedCallingUid != actualCallingUid) {
            throw new SecurityException(
                    "Specified calling package ["
                            + claimedCallingPackage
                            + "] does not match the calling uid "
                            + actualCallingUid);
        }
    }

    /**
     * Ensure instant apps can't make calls to AppSearch.
     *
     * @throws SecurityException if the caller is an instant app.
     */
    private void verifyNotInstantApp(@NonNull Context userContext, @NonNull String packageName) {
        PackageManager callingPackageManager = userContext.getPackageManager();
        if (callingPackageManager.isInstantApp(packageName)) {
            throw new SecurityException("Caller not allowed to create AppSearch session"
                    + "; userHandle=" + userContext.getUser() + ", callingPackage=" + packageName);
        }
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

            try {
                verifyUserUnlocked(userHandle);
                Context userContext = mContext.createContextAsUser(userHandle, /*flags=*/ 0);
                AppSearchUserInstance instance =
                        mAppSearchUserInstanceManager.getOrCreateUserInstance(
                                userContext, userHandle, AppSearchConfig.getInstance(EXECUTOR));
                stats.dataSize += instance.getAppSearchImpl()
                        .getStorageInfoForPackage(packageName).getSizeBytes();
            } catch (Throwable t) {
                Log.e(
                        TAG,
                        "Unable to augment storage stats for "
                                + userHandle
                                + " packageName "
                                + packageName,
                        t);
            }
        }

        @Override
        public void augmentStatsForUid(
                @NonNull PackageStats stats, int uid, boolean canCallerAccessAllStats) {
            Objects.requireNonNull(stats);

            UserHandle userHandle = UserHandle.getUserHandleForUid(uid);
            try {
                verifyUserUnlocked(userHandle);
                String[] packagesForUid = mPackageManager.getPackagesForUid(uid);
                if (packagesForUid == null) {
                    return;
                }
                Context userContext = mContext.createContextAsUser(userHandle, /*flags=*/ 0);
                AppSearchUserInstance instance =
                        mAppSearchUserInstanceManager.getOrCreateUserInstance(
                                userContext, userHandle, AppSearchConfig.getInstance(EXECUTOR));
                for (int i = 0; i < packagesForUid.length; i++) {
                    stats.dataSize += instance.getAppSearchImpl()
                            .getStorageInfoForPackage(packagesForUid[i]).getSizeBytes();
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

            try {
                verifyUserUnlocked(userHandle);
                List<PackageInfo> packagesForUser = mPackageManager.getInstalledPackagesAsUser(
                        /*flags=*/0, userHandle.getIdentifier());
                if (packagesForUser == null) {
                    return;
                }
                Context userContext = mContext.createContextAsUser(userHandle, /*flags=*/ 0);
                AppSearchUserInstance instance =
                        mAppSearchUserInstanceManager.getOrCreateUserInstance(
                                userContext, userHandle, AppSearchConfig.getInstance(EXECUTOR));
                for (int i = 0; i < packagesForUser.size(); i++) {
                    String packageName = packagesForUser.get(i).packageName;
                    stats.dataSize += instance.getAppSearchImpl()
                            .getStorageInfoForPackage(packageName).getSizeBytes();
                }
            } catch (Throwable t) {
                Log.e(TAG, "Unable to augment storage stats for " + userHandle, t);
            }
        }
    }

    private void checkForOptimize(AppSearchUserInstance instance, int mutateBatchSize) {
        EXECUTOR.execute(() -> {
            long totalLatencyStartMillis = SystemClock.elapsedRealtime();
            OptimizeStats.Builder builder = new OptimizeStats.Builder();
            try {
                instance.getAppSearchImpl().checkForOptimize(mutateBatchSize, builder);
            } catch (AppSearchException e) {
                Log.w(TAG, "Error occurred when check for optimize", e);
            } finally {
                OptimizeStats oStats = builder
                        .setTotalLatencyMillis(
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartMillis))
                        .build();
                if (oStats.getOriginalDocumentCount() > 0) {
                    // see if optimize has been run by checking originalDocumentCount
                    instance.getLogger().logStats(oStats);
                }
            }
        });
    }

    private void checkForOptimize(AppSearchUserInstance instance) {
        EXECUTOR.execute(() -> {
            long totalLatencyStartMillis = SystemClock.elapsedRealtime();
            OptimizeStats.Builder builder = new OptimizeStats.Builder();
            try {
                instance.getAppSearchImpl().checkForOptimize(builder);
            } catch (AppSearchException e) {
                Log.w(TAG, "Error occurred when check for optimize", e);
            } finally {
                OptimizeStats oStats = builder
                        .setTotalLatencyMillis(
                                (int) (SystemClock.elapsedRealtime() - totalLatencyStartMillis))
                        .build();
                if (oStats.getOriginalDocumentCount() > 0) {
                    // see if optimize has been run by checking originalDocumentCount
                    instance.getLogger().logStats(oStats);
                }
            }
        });
    }
}
