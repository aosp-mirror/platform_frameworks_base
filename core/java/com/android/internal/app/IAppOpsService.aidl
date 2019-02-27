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
import android.app.AppOpsManager;
import android.content.pm.ParceledListSlice;
import android.os.Bundle;
import android.os.RemoteCallback;
import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsActiveCallback;
import com.android.internal.app.IAppOpsNotedCallback;

interface IAppOpsService {
    // These first methods are also called by native code, so must
    // be kept in sync with frameworks/native/libs/binder/include/binder/IAppOpsService.h
    int checkOperation(int code, int uid, String packageName);
    int noteOperation(int code, int uid, String packageName);
    int startOperation(IBinder token, int code, int uid, String packageName,
            boolean startIfModeDefault);
    @UnsupportedAppUsage
    void finishOperation(IBinder token, int code, int uid, String packageName);
    void startWatchingMode(int op, String packageName, IAppOpsCallback callback);
    void stopWatchingMode(IAppOpsCallback callback);
    IBinder getToken(IBinder clientToken);
    int permissionToOpCode(String permission);
    int noteProxyOperation(int code, int proxyUid, String proxyPackageName,
                int callingUid, String callingPackageName);

    // Remaining methods are only used in Java.
    int checkPackage(int uid, String packageName);
    @UnsupportedAppUsage
    List<AppOpsManager.PackageOps> getPackagesForOps(in int[] ops);
    @UnsupportedAppUsage
    List<AppOpsManager.PackageOps> getOpsForPackage(int uid, String packageName, in int[] ops);
    void getHistoricalOps(int uid, String packageName, in List<String> ops, long beginTimeMillis,
            long endTimeMillis, in RemoteCallback callback);
    void getHistoricalOpsFromDiskRaw(int uid, String packageName, in List<String> ops,
            long beginTimeMillis, long endTimeMillis, in RemoteCallback callback);
    void offsetHistory(long duration);
    void setHistoryParameters(int mode, long baseSnapshotInterval, int compressionStep);
    void addHistoricalOps(in AppOpsManager.HistoricalOps ops);
    void resetHistoryParameters();
    void clearHistory();
    List<AppOpsManager.PackageOps> getUidOps(int uid, in int[] ops);
    void setUidMode(int code, int uid, int mode);
    @UnsupportedAppUsage
    void setMode(int code, int uid, String packageName, int mode);
    @UnsupportedAppUsage
    void resetAllModes(int reqUserId, String reqPackageName);
    int checkAudioOperation(int code, int usage, int uid, String packageName);
    void setAudioRestriction(int code, int usage, int uid, int mode, in String[] exceptionPackages);

    void setUserRestrictions(in Bundle restrictions, IBinder token, int userHandle);
    void setUserRestriction(int code, boolean restricted, IBinder token, int userHandle, in String[] exceptionPackages);
    void removeUser(int userHandle);

    void startWatchingActive(in int[] ops, IAppOpsActiveCallback callback);
    void stopWatchingActive(IAppOpsActiveCallback callback);
    boolean isOperationActive(int code, int uid, String packageName);

    void startWatchingModeWithFlags(int op, String packageName, int flags, IAppOpsCallback callback);

    void startWatchingNoted(in int[] ops, IAppOpsNotedCallback callback);
    void stopWatchingNoted(IAppOpsNotedCallback callback);

    int checkOperationRaw(int code, int uid, String packageName);
}
