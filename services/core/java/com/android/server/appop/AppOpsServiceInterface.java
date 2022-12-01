/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.appop;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.AttributionSource;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PackageTagsList;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.app.IAppOpsActiveCallback;
import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsNotedCallback;
import com.android.internal.app.IAppOpsStartedCallback;

import dalvik.annotation.optimization.NeverCompile;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

/**
 *
 */
public interface AppOpsServiceInterface extends PersistenceScheduler {

    /**
     *
     */
    void systemReady();

    /**
     *
     */
    void shutdown();

    /**
     *
     * @param uid
     * @param packageName
     */
    void verifyPackage(int uid, String packageName);

    /**
     *
     * @param op
     * @param packageName
     * @param flags
     * @param callback
     */
    void startWatchingModeWithFlags(int op, String packageName, int flags,
            IAppOpsCallback callback);

    /**
     *
     * @param callback
     */
    void stopWatchingMode(IAppOpsCallback callback);

    /**
     *
     * @param ops
     * @param callback
     */
    void startWatchingActive(int[] ops, IAppOpsActiveCallback callback);

    /**
     *
     * @param callback
     */
    void stopWatchingActive(IAppOpsActiveCallback callback);

    /**
     *
     * @param ops
     * @param callback
     */
    void startWatchingStarted(int[] ops, @NonNull IAppOpsStartedCallback callback);

    /**
     *
     * @param callback
     */
    void stopWatchingStarted(IAppOpsStartedCallback callback);

    /**
     *
     * @param ops
     * @param callback
     */
    void startWatchingNoted(@NonNull int[] ops, @NonNull IAppOpsNotedCallback callback);

    /**
     *
     * @param callback
     */
    void stopWatchingNoted(IAppOpsNotedCallback callback);

    /**
     * @param clientId
     * @param code
     * @param uid
     * @param packageName
     * @param attributionTag
     * @param startIfModeDefault
     * @param message
     * @param attributionFlags
     * @param attributionChainId
     * @return
     */
    int startOperation(@NonNull IBinder clientId, int code, int uid,
            @Nullable String packageName, @Nullable String attributionTag,
            boolean startIfModeDefault, @NonNull String message,
            @AppOpsManager.AttributionFlags int attributionFlags,
            int attributionChainId);


    int startOperationUnchecked(IBinder clientId, int code, int uid, @NonNull String packageName,
            @Nullable String attributionTag, int proxyUid, String proxyPackageName,
            @Nullable String proxyAttributionTag, @AppOpsManager.OpFlags int flags,
            boolean startIfModeDefault, @AppOpsManager.AttributionFlags int attributionFlags,
            int attributionChainId, boolean dryRun);

    /**
     *
     * @param clientId
     * @param code
     * @param uid
     * @param packageName
     * @param attributionTag
     */
    void finishOperation(IBinder clientId, int code, int uid, String packageName,
            String attributionTag);

    /**
     *
     * @param clientId
     * @param code
     * @param uid
     * @param packageName
     * @param attributionTag
     */
    void finishOperationUnchecked(IBinder clientId, int code, int uid, String packageName,
            String attributionTag);

    /**
     *
     * @param uidPackageNames
     * @param visible
     */
    void updateAppWidgetVisibility(SparseArray<String> uidPackageNames, boolean visible);

    /**
     *
     */
    void readState();

    /**
     *
     */
    void writeState();

    /**
     *
     * @param uid
     * @param packageName
     */
    void packageRemoved(int uid, String packageName);

    /**
     *
     * @param uid
     */
    void uidRemoved(int uid);

    /**
     *
     * @param uid
     * @param procState
     * @param capability
     */
    void updateUidProcState(int uid, int procState,
            @ActivityManager.ProcessCapability int capability);

    /**
     *
     * @param ops
     * @return
     */
    List<AppOpsManager.PackageOps> getPackagesForOps(int[] ops);

    /**
     *
     * @param uid
     * @param packageName
     * @param ops
     * @return
     */
    List<AppOpsManager.PackageOps> getOpsForPackage(int uid, String packageName,
            int[] ops);

    /**
     *
     * @param uid
     * @param packageName
     * @param attributionTag
     * @param opNames
     * @param dataType
     * @param filter
     * @param beginTimeMillis
     * @param endTimeMillis
     * @param flags
     * @param callback
     */
    void getHistoricalOps(int uid, String packageName, String attributionTag,
            List<String> opNames, int dataType, int filter, long beginTimeMillis,
            long endTimeMillis, int flags, RemoteCallback callback);

    /**
     *
     * @param uid
     * @param packageName
     * @param attributionTag
     * @param opNames
     * @param dataType
     * @param filter
     * @param beginTimeMillis
     * @param endTimeMillis
     * @param flags
     * @param callback
     */
    void getHistoricalOpsFromDiskRaw(int uid, String packageName, String attributionTag,
            List<String> opNames, int dataType, int filter, long beginTimeMillis,
            long endTimeMillis, int flags, RemoteCallback callback);

    /**
     *
     */
    void reloadNonHistoricalState();

    /**
     *
     * @param uid
     * @param ops
     * @return
     */
    List<AppOpsManager.PackageOps> getUidOps(int uid, int[] ops);

    /**
     *
     * @param owners
     */
    void setDeviceAndProfileOwners(SparseIntArray owners);

    // used in audio restriction calls, might just copy the logic to avoid having this call.
    /**
     *
     * @param callingPid
     * @param callingUid
     * @param targetUid
     */
    void enforceManageAppOpsModes(int callingPid, int callingUid, int targetUid);

    /**
     *
     * @param code
     * @param uid
     * @param mode
     * @param permissionPolicyCallback
     */
    void setUidMode(int code, int uid, int mode,
            @Nullable IAppOpsCallback permissionPolicyCallback);

    /**
     *
     * @param code
     * @param uid
     * @param packageName
     * @param mode
     * @param permissionPolicyCallback
     */
    void setMode(int code, int uid, @NonNull String packageName, int mode,
            @Nullable IAppOpsCallback permissionPolicyCallback);

    /**
     *
     * @param reqUserId
     * @param reqPackageName
     */
    void resetAllModes(int reqUserId, String reqPackageName);

    /**
     *
     * @param code
     * @param uid
     * @param packageName
     * @param attributionTag
     * @param raw
     * @return
     */
    int checkOperation(int code, int uid, String packageName,
            @Nullable String attributionTag, boolean raw);

    /**
     *
     * @param uid
     * @param packageName
     * @return
     */
    int checkPackage(int uid, String packageName);

    /**
     *
     * @param code
     * @param uid
     * @param packageName
     * @param attributionTag
     * @param message
     * @return
     */
    int noteOperation(int code, int uid, @Nullable String packageName,
            @Nullable String attributionTag, @Nullable String message);

    /**
     *
     * @param code
     * @param uid
     * @param packageName
     * @param attributionTag
     * @param proxyUid
     * @param proxyPackageName
     * @param proxyAttributionTag
     * @param flags
     * @return
     */
    @AppOpsManager.Mode
    int noteOperationUnchecked(int code, int uid, @NonNull String packageName,
            @Nullable String attributionTag, int proxyUid, String proxyPackageName,
            @Nullable String proxyAttributionTag, @AppOpsManager.OpFlags int flags);

    boolean isAttributionTagValid(int uid, @NonNull String packageName,
            @Nullable String attributionTag, @Nullable String proxyPackageName);

    /**
     *
     * @param fd
     * @param pw
     * @param args
     */
    @NeverCompile
        // Avoid size overhead of debugging code.
    void dump(FileDescriptor fd, PrintWriter pw, String[] args);

    /**
     *
     * @param restrictions
     * @param token
     * @param userHandle
     */
    void setUserRestrictions(Bundle restrictions, IBinder token, int userHandle);

    /**
     *
     * @param code
     * @param restricted
     * @param token
     * @param userHandle
     * @param excludedPackageTags
     */
    void setUserRestriction(int code, boolean restricted, IBinder token, int userHandle,
            PackageTagsList excludedPackageTags);

    /**
     *
     * @param code
     * @param restricted
     * @param token
     */
    void setGlobalRestriction(int code, boolean restricted, IBinder token);

    /**
     *
     * @param code
     * @param user
     * @param pkg
     * @param attributionTag
     * @return
     */
    int getOpRestrictionCount(int code, UserHandle user, String pkg,
            String attributionTag);

    /**
     *
     * @param code
     * @param uid
     */
    // added to interface for audio restriction stuff
    void notifyWatchersOfChange(int code, int uid);

    /**
     *
     * @param userHandle
     * @throws RemoteException
     */
    void removeUser(int userHandle) throws RemoteException;

    /**
     *
     * @param code
     * @param uid
     * @param packageName
     * @return
     */
    boolean isOperationActive(int code, int uid, String packageName);

    /**
     *
     * @param op
     * @param proxyPackageName
     * @param proxyAttributionTag
     * @param proxiedUid
     * @param proxiedPackageName
     * @return
     */
    // TODO this one might not need to be in the interface
    boolean isProxying(int op, @NonNull String proxyPackageName,
            @NonNull String proxyAttributionTag, int proxiedUid,
            @NonNull String proxiedPackageName);

    /**
     *
     * @param packageName
     */
    void resetPackageOpsNoHistory(@NonNull String packageName);

    /**
     *
     * @param mode
     * @param baseSnapshotInterval
     * @param compressionStep
     */
    void setHistoryParameters(@AppOpsManager.HistoricalMode int mode,
            long baseSnapshotInterval, int compressionStep);

    /**
     *
     * @param offsetMillis
     */
    void offsetHistory(long offsetMillis);

    /**
     *
     * @param ops
     */
    void addHistoricalOps(AppOpsManager.HistoricalOps ops);

    /**
     *
     */
    void resetHistoryParameters();

    /**
     *
     */
    void clearHistory();

    /**
     *
     * @param offlineDurationMillis
     */
    void rebootHistory(long offlineDurationMillis);
}
