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
import android.content.pm.ParceledListSlice;
import android.os.Bundle;
import android.os.RemoteCallback;
import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsActiveCallback;
import com.android.internal.app.IAppOpsAsyncNotedCallback;
import com.android.internal.app.IAppOpsNotedCallback;
import com.android.internal.app.IAppOpsStartedCallback;
import com.android.internal.app.MessageSamplingConfig;

interface IAppOpsService {
    // These methods are also called by native code, so must
    // be kept in sync with frameworks/native/libs/binder/include/binder/IAppOpsService.h
    // and not be reordered
    int checkOperation(int code, int uid, String packageName);
    SyncNotedAppOp noteOperation(int code, int uid, String packageName, @nullable String attributionTag,
            boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage);
    SyncNotedAppOp startOperation(IBinder clientId, int code, int uid, String packageName,
            @nullable String attributionTag, boolean startIfModeDefault,
            boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage);
    @UnsupportedAppUsage
    void finishOperation(IBinder clientId, int code, int uid, String packageName,
            @nullable String attributionTag);
    void startWatchingMode(int op, String packageName, IAppOpsCallback callback);
    void stopWatchingMode(IAppOpsCallback callback);
    int permissionToOpCode(String permission);
    int checkAudioOperation(int code, int usage, int uid, String packageName);
    boolean shouldCollectNotes(int opCode);
    void setCameraAudioRestriction(int mode);
    // End of methods also called by native code.
    // Any new method exposed to native must be added after the last one, do not reorder

    SyncNotedAppOp noteProxyOperation(int code, in AttributionSource attributionSource,
            boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage,
            boolean skipProxyOperation);
    SyncNotedAppOp startProxyOperation(IBinder clientId, int code, in AttributionSource attributionSource,
            boolean startIfModeDefault, boolean shouldCollectAsyncNotedOp, String message,
            boolean shouldCollectMessage, boolean skipProxyOperation);
    void finishProxyOperation(IBinder clientId, int code, in AttributionSource attributionSource);

    // Remaining methods are only used in Java.
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
    void offsetHistory(long duration);
    void setHistoryParameters(int mode, long baseSnapshotInterval, int compressionStep);
    void addHistoricalOps(in AppOpsManager.HistoricalOps ops);
    void resetHistoryParameters();
    void resetPackageOpsNoHistory(String packageName);
    void clearHistory();
    void rebootHistory(long offlineDurationMillis);
    List<AppOpsManager.PackageOps> getUidOps(int uid, in int[] ops);
    void setUidMode(int code, int uid, int mode);
    @UnsupportedAppUsage
    void setMode(int code, int uid, String packageName, int mode);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void resetAllModes(int reqUserId, String reqPackageName);
    void setAudioRestriction(int code, int usage, int uid, int mode, in String[] exceptionPackages);

    void setUserRestrictions(in Bundle restrictions, IBinder token, int userHandle);
    void setUserRestriction(int code, boolean restricted, IBinder token, int userHandle, in String[] exceptionPackages);
    void removeUser(int userHandle);

    void startWatchingActive(in int[] ops, IAppOpsActiveCallback callback);
    void stopWatchingActive(IAppOpsActiveCallback callback);
    boolean isOperationActive(int code, int uid, String packageName);
    boolean isProxying(int op, String proxyPackageName, String proxyAttributionTag, int proxiedUid,
            String proxiedPackageName);

    void startWatchingStarted(in int[] ops, IAppOpsStartedCallback callback);
    void stopWatchingStarted(IAppOpsStartedCallback callback);

    void startWatchingModeWithFlags(int op, String packageName, int flags, IAppOpsCallback callback);

    void startWatchingNoted(in int[] ops, IAppOpsNotedCallback callback);
    void stopWatchingNoted(IAppOpsNotedCallback callback);

    void startWatchingAsyncNoted(String packageName, IAppOpsAsyncNotedCallback callback);
    void stopWatchingAsyncNoted(String packageName, IAppOpsAsyncNotedCallback callback);
    List<AsyncNotedAppOp> extractAsyncOps(String packageName);

    int checkOperationRaw(int code, int uid, String packageName);

    void reloadNonHistoricalState();

    void collectNoteOpCallsForValidation(String stackTrace, int op, String packageName, long version);
}
