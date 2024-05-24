/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.app;

import android.app.AppOpsManager;
import android.app.AsyncNotedAppOp;
import android.app.SyncNotedAppOp;
import android.app.RuntimeAppOpAccessMessage;
import android.content.AttributionSource;
import android.content.AttributionSourceState;
import android.content.pm.ParceledListSlice;
import android.os.Bundle;
import android.os.PackageTagsList;
import android.os.RemoteCallback;
import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsActiveCallback;
import com.android.internal.app.IAppOpsAsyncNotedCallback;
import com.android.internal.app.IAppOpsNotedCallback;
import com.android.internal.app.IAppOpsStartedCallback;
import com.android.internal.app.MessageSamplingConfig;

// AppOpsService AIDL interface.
// PLEASE READ BEFORE MODIFYING THIS FILE.
// Some methods in this interface or their transaction codes are mentioned in
// frameworks/base/boot/hiddenapi/hiddenapi-unsupported.txt, meaning that we cannot change their
// signature or ordering as they may be used by 3p apps.
// Also, some methods are mentioned in native code, meaning that the numbering in
// frameworks/native/libs/permission/include/binder/IAppOpsService.h must match the order here.
// Please be careful to respect both these issues when modifying this file.
interface IAppOpsService {
    // These methods are also called by native code, so please be careful that the number in
    // frameworks/native/libs/permission/include/binder/IAppOpsService.h matches the ordering here.
    int checkOperation(int code, int uid, String packageName);
    SyncNotedAppOp noteOperation(int code, int uid, String packageName, @nullable String attributionTag,
            boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage);
    SyncNotedAppOp startOperation(IBinder clientId, int code, int uid, String packageName,
            @nullable String attributionTag, boolean startIfModeDefault,
            boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage,
            int attributionFlags, int attributionChainId);
    @UnsupportedAppUsage
    void finishOperation(IBinder clientId, int code, int uid, String packageName,
            @nullable String attributionTag);
    void startWatchingMode(int op, String packageName, IAppOpsCallback callback);
    void stopWatchingMode(IAppOpsCallback callback);
    int permissionToOpCode(String permission);
    int checkAudioOperation(int code, int usage, int uid, String packageName);
    boolean shouldCollectNotes(int opCode);
    void setCameraAudioRestriction(int mode);
    void startWatchingModeWithFlags(int op, String packageName, int flags,
            IAppOpsCallback callback);
    // End of methods also called by native code (there may be more blocks like this of native
    // methods later in this file).
    // Deprecated, use noteProxyOperationWithState instead.
    SyncNotedAppOp noteProxyOperation(int code, in AttributionSource attributionSource,
            boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage,
            boolean skipProxyOperation);
    // Deprecated, use startProxyOperationWithState instead.
    SyncNotedAppOp startProxyOperation(IBinder clientId, int code,
            in AttributionSource attributionSource, boolean startIfModeDefault,
            boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage,
            boolean skipProxyOperation, int proxyAttributionFlags, int proxiedAttributionFlags,
            int attributionChainId);
    // Deprecated, use finishProxyOperationWithState instead.
    void finishProxyOperation(IBinder clientId, int code, in AttributionSource attributionSource,
            boolean skipProxyOperation);
    int checkPackage(int uid, String packageName);
    RuntimeAppOpAccessMessage collectRuntimeAppOpAccessMessage();
    MessageSamplingConfig reportRuntimeAppOpAccessMessageAndGetConfig(String packageName,
            in SyncNotedAppOp appOp, String message);
    @UnsupportedAppUsage
    List<AppOpsManager.PackageOps> getPackagesForOps(in int[] ops);
    @UnsupportedAppUsage
    List<AppOpsManager.PackageOps> getOpsForPackage(int uid, String packageName, in int[] ops);
    void getHistoricalOps(int uid, String packageName, String attributionTag, in List<String> ops,
            int historyFlags, int filter, long beginTimeMillis, long endTimeMillis, int flags,
            in RemoteCallback callback);
    void getHistoricalOpsFromDiskRaw(int uid, String packageName, String attributionTag,
            in List<String> ops, int historyFlags, int filter, long beginTimeMillis,
            long endTimeMillis, int flags, in RemoteCallback callback);
    @EnforcePermission("MANAGE_APPOPS")
    void offsetHistory(long duration);
    @EnforcePermission("MANAGE_APPOPS")
    void setHistoryParameters(int mode, long baseSnapshotInterval, int compressionStep);
    @EnforcePermission("MANAGE_APPOPS")
    void addHistoricalOps(in AppOpsManager.HistoricalOps ops);
    @EnforcePermission("MANAGE_APPOPS")
    void resetHistoryParameters();
    @EnforcePermission("MANAGE_APPOPS")
    void resetPackageOpsNoHistory(String packageName);
    @EnforcePermission("MANAGE_APPOPS")
    void clearHistory();
    @EnforcePermission("MANAGE_APPOPS")
    void rebootHistory(long offlineDurationMillis);
    List<AppOpsManager.PackageOps> getUidOps(int uid, in int[] ops);
    void setUidMode(int code, int uid, int mode);
    @UnsupportedAppUsage
    void setMode(int code, int uid, String packageName, int mode);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void resetAllModes(int reqUserId, String reqPackageName);
    void setAudioRestriction(int code, int usage, int uid, int mode, in String[] exceptionPackages);

    void setUserRestrictions(in Bundle restrictions, IBinder token, int userHandle);
    void setUserRestriction(int code, boolean restricted, IBinder token, int userHandle, in PackageTagsList excludedPackageTags);

    void removeUser(int userHandle);

    void startWatchingActive(in int[] ops, IAppOpsActiveCallback callback);
    void stopWatchingActive(IAppOpsActiveCallback callback);
    boolean isOperationActive(int code, int uid, String packageName);
    boolean isProxying(int op, String proxyPackageName, String proxyAttributionTag, int proxiedUid,
            String proxiedPackageName);

    void startWatchingStarted(in int[] ops, IAppOpsStartedCallback callback);
    void stopWatchingStarted(IAppOpsStartedCallback callback);

    void startWatchingNoted(in int[] ops, IAppOpsNotedCallback callback);
    void stopWatchingNoted(IAppOpsNotedCallback callback);

    void startWatchingAsyncNoted(String packageName, IAppOpsAsyncNotedCallback callback);
    void stopWatchingAsyncNoted(String packageName, IAppOpsAsyncNotedCallback callback);
    List<AsyncNotedAppOp> extractAsyncOps(String packageName);

    int checkOperationRaw(int code, int uid, String packageName, @nullable String attributionTag);
    void reloadNonHistoricalState();

    void collectNoteOpCallsForValidation(String stackTrace, int op, String packageName, long version);

    SyncNotedAppOp noteProxyOperationWithState(int code,
            in AttributionSourceState attributionSourceStateState,
            boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage,
            boolean skipProxyOperation);
    SyncNotedAppOp startProxyOperationWithState(IBinder clientId, int code,
            in AttributionSourceState attributionSourceStateState, boolean startIfModeDefault,
            boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage,
            boolean skipProxyOperation, int proxyAttributionFlags, int proxiedAttributionFlags,
            int attributionChainId);
    void finishProxyOperationWithState(IBinder clientId, int code,
            in AttributionSourceState attributionSourceStateState, boolean skipProxyOperation);
    int checkOperationRawForDevice(int code, int uid, String packageName,
            @nullable String attributionTag, int virtualDeviceId);
    int checkOperationForDevice(int code, int uid, String packageName, int virtualDeviceId);
    SyncNotedAppOp noteOperationForDevice(int code, int uid, String packageName,
            @nullable String attributionTag, int virtualDeviceId,
            boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage);
    SyncNotedAppOp startOperationForDevice(IBinder clientId, int code, int uid, String packageName,
            @nullable String attributionTag,  int virtualDeviceId, boolean startIfModeDefault,
            boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage,
            int attributionFlags, int attributionChainId);
    void finishOperationForDevice(IBinder clientId, int code, int uid, String packageName,
            @nullable String attributionTag, int virtualDeviceId);
   List<AppOpsManager.PackageOps> getPackagesForOpsForDevice(in int[] ops, String persistentDeviceId);
}
