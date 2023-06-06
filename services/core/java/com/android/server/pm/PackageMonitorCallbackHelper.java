/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.pm;

import static com.android.server.pm.PackageManagerService.PACKAGE_SCHEME;

import android.annotation.AppIdInt;
import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IRemoteCallback;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;

/** Helper class to handle PackageMonitorCallback and notify the registered client. This is mainly
 * used by PackageMonitor to improve the broadcast latency. */
class PackageMonitorCallbackHelper {
    @NonNull
    private final Object mLock = new Object();
    final IActivityManager mActivityManager = ActivityManager.getService();

    @NonNull
    @GuardedBy("mLock")
    private final RemoteCallbackList<IRemoteCallback> mCallbacks = new RemoteCallbackList<>();

    public void registerPackageMonitorCallback(IRemoteCallback callback, int userId) {
        synchronized (mLock) {
            mCallbacks.register(callback, userId);
        }
    }

    public void unregisterPackageMonitorCallback(IRemoteCallback callback) {
        synchronized (mLock) {
            mCallbacks.unregister(callback);
        }
    }

    public void notifyPackageAddedForNewUsers(String packageName,
            @AppIdInt int appId, @NonNull int[] userIds, @NonNull int[] instantUserIds,
            int dataLoaderType) {
        Bundle extras = new Bundle(2);
        // Set to UID of the first user, EXTRA_UID is automatically updated in sendPackageBroadcast
        final int uid = UserHandle.getUid(
                (ArrayUtils.isEmpty(userIds) ? instantUserIds[0] : userIds[0]), appId);
        extras.putInt(Intent.EXTRA_UID, uid);
        extras.putInt(PackageInstaller.EXTRA_DATA_LOADER_TYPE, dataLoaderType);
        notifyPackageMonitor(Intent.ACTION_PACKAGE_ADDED, packageName, extras ,
                userIds /* userIds */);
    }

    public void notifyResourcesChanged(boolean mediaStatus, boolean replacing,
            @NonNull String[] pkgNames, @NonNull int[] uids) {
        Bundle extras = new Bundle();
        extras.putStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST, pkgNames);
        extras.putIntArray(Intent.EXTRA_CHANGED_UID_LIST, uids);
        if (replacing) {
            extras.putBoolean(Intent.EXTRA_REPLACING, replacing);
        }
        String action = mediaStatus ? Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE
                : Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE;
        notifyPackageMonitor(action, null /* pkg */, extras, null /* userIds */);
    }

    public void notifyPackageChanged(String packageName, boolean dontKillApp,
            ArrayList<String> componentNames, int packageUid, String reason, int[] userIds) {
        Bundle extras = new Bundle(4);
        extras.putString(Intent.EXTRA_CHANGED_COMPONENT_NAME, componentNames.get(0));
        String[] nameList = new String[componentNames.size()];
        componentNames.toArray(nameList);
        extras.putStringArray(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST, nameList);
        extras.putBoolean(Intent.EXTRA_DONT_KILL_APP, dontKillApp);
        extras.putInt(Intent.EXTRA_UID, packageUid);
        if (reason != null) {
            extras.putString(Intent.EXTRA_REASON, reason);
        }
        notifyPackageMonitor(Intent.ACTION_PACKAGE_CHANGED, packageName, extras, userIds);
    }

    public void notifyPackageMonitor(String action, String pkg, Bundle extras,
            int[] userIds) {
        if (!isAllowedCallbackAction(action)) {
            return;
        }
        try {
            final int[] resolvedUserIds;
            if (userIds == null) {
                if (mActivityManager == null) return;
                resolvedUserIds = mActivityManager.getRunningUserIds();
            } else {
                resolvedUserIds = userIds;
            }
            doNotifyCallbacks(action, pkg, extras, resolvedUserIds);
        } catch (RemoteException e) {
            // do nothing
        }
    }

    private static boolean isAllowedCallbackAction(String action) {
        return TextUtils.equals(action, Intent.ACTION_PACKAGE_ADDED)
                || TextUtils.equals(action, Intent.ACTION_PACKAGE_REMOVED)
                || TextUtils.equals(action, Intent.ACTION_PACKAGE_CHANGED)
                || TextUtils.equals(action, Intent.ACTION_UID_REMOVED)
                || TextUtils.equals(action, Intent.ACTION_PACKAGES_SUSPENDED)
                || TextUtils.equals(action, Intent.ACTION_PACKAGES_UNSUSPENDED)
                || TextUtils.equals(action, Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE)
                || TextUtils.equals(action, Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
    }

    private void doNotifyCallbacks(String action, String pkg, Bundle extras, int[] userIds) {
        RemoteCallbackList<IRemoteCallback> callbacks;
        synchronized (mLock) {
            callbacks = mCallbacks;
        }
        for (int userId : userIds) {
            final Intent intent = new Intent(action,
                    pkg != null ? Uri.fromParts(PACKAGE_SCHEME, pkg, null) : null);
            if (extras != null) {
                intent.putExtras(extras);
            }
            int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
            if (uid >= 0 && UserHandle.getUserId(uid) != userId) {
                uid = UserHandle.getUid(userId, UserHandle.getAppId(uid));
                intent.putExtra(Intent.EXTRA_UID, uid);
            }
            intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
            callbacks.broadcast((callback, user) -> {
                int registerUserId = (int) user;
                if ((registerUserId != UserHandle.USER_ALL) && (registerUserId != userId)) {
                    return;
                }
                invokeCallback(callback, intent);
            });
        }
    }

    private void invokeCallback(IRemoteCallback callback, Intent intent) {
        try {
            Bundle bundle = new Bundle();
            bundle.putParcelable(
                    PackageManager.EXTRA_PACKAGE_MONITOR_CALLBACK_RESULT, intent);
            callback.sendResult(bundle);
        } catch (RemoteException e) {
            // do nothing
        }
    }
}
