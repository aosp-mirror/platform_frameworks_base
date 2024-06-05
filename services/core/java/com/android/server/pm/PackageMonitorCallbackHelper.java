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
import android.os.Handler;
import android.os.IRemoteCallback;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;
import java.util.function.BiFunction;

/** Helper class to handle PackageMonitorCallback and notify the registered client. This is mainly
 * used by PackageMonitor to improve the broadcast latency. */
class PackageMonitorCallbackHelper {

    private static final boolean DEBUG = false;
    private static final String TAG = "PackageMonitorCallbackHelper";

    @NonNull
    private final Object mLock = new Object();
    IActivityManager mActivityManager;

    @NonNull
    @GuardedBy("mLock")
    private final RemoteCallbackList<IRemoteCallback> mCallbacks = new RemoteCallbackList<>();

    public void registerPackageMonitorCallback(IRemoteCallback callback, int userId, int uid) {
        synchronized (mLock) {
            mCallbacks.register(callback, new RegisterUser(userId, uid));
        }
    }

    public void unregisterPackageMonitorCallback(IRemoteCallback callback) {
        synchronized (mLock) {
            mCallbacks.unregister(callback);
        }
    }

    public void onUserRemoved(int userId) {
        ArrayList<IRemoteCallback> targetUnRegisteredCallbacks = null;
        synchronized (mLock) {
            int registerCount = mCallbacks.getRegisteredCallbackCount();
            for (int i = 0; i < registerCount; i++) {
                RegisterUser registerUser =
                        (RegisterUser) mCallbacks.getRegisteredCallbackCookie(i);
                if (registerUser.getUserId() == userId) {
                    IRemoteCallback callback = mCallbacks.getRegisteredCallbackItem(i);
                    if (targetUnRegisteredCallbacks == null) {
                        targetUnRegisteredCallbacks = new ArrayList<>();
                    }
                    targetUnRegisteredCallbacks.add(callback);
                }
            }
        }
        if (targetUnRegisteredCallbacks != null && targetUnRegisteredCallbacks.size() > 0) {
            int count = targetUnRegisteredCallbacks.size();
            for (int i = 0; i < count; i++) {
                unregisterPackageMonitorCallback(targetUnRegisteredCallbacks.get(i));
            }
        }
    }

    public void notifyPackageAddedForNewUsers(String packageName,
            @AppIdInt int appId, @NonNull int[] userIds, @NonNull int[] instantUserIds,
            boolean isArchived, int dataLoaderType, SparseArray<int[]> broadcastAllowList,
            @NonNull Handler handler) {
        Bundle extras = new Bundle(2);
        // Set to UID of the first user, EXTRA_UID is automatically updated in sendPackageBroadcast
        final int uid = UserHandle.getUid(
                (ArrayUtils.isEmpty(userIds) ? instantUserIds[0] : userIds[0]), appId);
        extras.putInt(Intent.EXTRA_UID, uid);
        if (isArchived) {
            extras.putBoolean(Intent.EXTRA_ARCHIVAL, true);
        }
        extras.putInt(PackageInstaller.EXTRA_DATA_LOADER_TYPE, dataLoaderType);
        notifyPackageMonitor(Intent.ACTION_PACKAGE_ADDED, packageName, extras,
                userIds /* userIds */, instantUserIds, broadcastAllowList, handler,
                null /* filterExtras */);
    }

    public void notifyResourcesChanged(boolean mediaStatus, boolean replacing,
            @NonNull String[] pkgNames, @NonNull int[] uids, @NonNull Handler handler) {
        Bundle extras = new Bundle();
        extras.putStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST, pkgNames);
        extras.putIntArray(Intent.EXTRA_CHANGED_UID_LIST, uids);
        if (replacing) {
            extras.putBoolean(Intent.EXTRA_REPLACING, replacing);
        }
        String action = mediaStatus ? Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE
                : Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE;
        notifyPackageMonitor(action, null /* pkg */, extras, null /* userIds */,
                null /* instantUserIds */, null /* broadcastAllowList */, handler,
                null /* filterExtras */);
    }

    public void notifyPackageChanged(String packageName, boolean dontKillApp,
            ArrayList<String> componentNames, int packageUid, String reason, int[] userIds,
            int[] instantUserIds, SparseArray<int[]> broadcastAllowList, Handler handler) {
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
        notifyPackageMonitor(Intent.ACTION_PACKAGE_CHANGED, packageName, extras, userIds,
                instantUserIds, broadcastAllowList, handler, null /* filterExtras */);
    }

    public void notifyPackageMonitor(String action, String pkg, Bundle extras,
            int[] userIds, int[] instantUserIds, SparseArray<int[]> broadcastAllowList,
            Handler handler, BiFunction<Integer, Bundle, Bundle> filterExtras) {
        if (!isAllowedCallbackAction(action)) {
            return;
        }
        try {
            final int[] resolvedUserIds;
            if (userIds == null) {
                if (mActivityManager == null) {
                    mActivityManager = ActivityManager.getService();
                }
                if (mActivityManager == null) return;
                resolvedUserIds = mActivityManager.getRunningUserIds();
            } else {
                resolvedUserIds = userIds;
            }

            if (ArrayUtils.isEmpty(instantUserIds)) {
                doNotifyCallbacksByAction(
                        action, pkg, extras, resolvedUserIds, broadcastAllowList, handler,
                        filterExtras);
            } else {
                doNotifyCallbacksByAction(action, pkg, extras, instantUserIds, broadcastAllowList,
                        handler, filterExtras);
            }
        } catch (RemoteException e) {
            // do nothing
        }
    }

    void notifyPackageMonitorWithIntent(Intent intent,
            int userId, int[] broadcastAllowList, Handler handler) {
        if (!isAllowedCallbackAction(intent.getAction())) {
            return;
        }
        doNotifyCallbacksByIntent(intent, userId, broadcastAllowList, handler);
    }

    private static boolean isAllowedCallbackAction(String action) {
        return TextUtils.equals(action, Intent.ACTION_PACKAGE_ADDED)
                || TextUtils.equals(action, Intent.ACTION_PACKAGE_REMOVED)
                || TextUtils.equals(action, Intent.ACTION_PACKAGE_CHANGED)
                || TextUtils.equals(action, Intent.ACTION_UID_REMOVED)
                || TextUtils.equals(action, Intent.ACTION_PACKAGES_SUSPENDED)
                || TextUtils.equals(action, Intent.ACTION_PACKAGES_UNSUSPENDED)
                || TextUtils.equals(action, Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE)
                || TextUtils.equals(action, Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)
                || TextUtils.equals(action, Intent.ACTION_PACKAGE_DATA_CLEARED)
                || TextUtils.equals(action, Intent.ACTION_PACKAGE_RESTARTED)
                || TextUtils.equals(action, Intent.ACTION_PACKAGE_UNSTOPPED);

    }

    private void doNotifyCallbacksByIntent(Intent intent, int userId,
            int[] broadcastAllowList, Handler handler) {
        RemoteCallbackList<IRemoteCallback> callbacks;
        synchronized (mLock) {
            callbacks = mCallbacks;
        }
        doNotifyCallbacks(callbacks, intent, userId, broadcastAllowList, handler,
                null /* filterExtrasFunction */);
    }

    private void doNotifyCallbacksByAction(String action, String pkg, Bundle extras, int[] userIds,
            SparseArray<int[]> broadcastAllowList, Handler handler,
            BiFunction<Integer, Bundle, Bundle> filterExtrasFunction) {
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

            final int[] allowUids =
                    broadcastAllowList != null ? broadcastAllowList.get(userId) : null;
            doNotifyCallbacks(callbacks, intent, userId, allowUids, handler, filterExtrasFunction);
        }
    }

    private void doNotifyCallbacks(RemoteCallbackList<IRemoteCallback> callbacks,
            Intent intent, int userId, int[] allowUids, Handler handler,
            BiFunction<Integer, Bundle, Bundle> filterExtrasFunction) {
        handler.post(() -> callbacks.broadcast((callback, user) -> {
            RegisterUser registerUser = (RegisterUser) user;
            if ((registerUser.getUserId() != UserHandle.USER_ALL) && (registerUser.getUserId()
                    != userId)) {
                return;
            }
            int registerUid = registerUser.getUid();
            if (allowUids != null && registerUid != Process.SYSTEM_UID
                    && !ArrayUtils.contains(allowUids, registerUid)) {
                if (DEBUG) {
                    Slog.w(TAG, "Skip invoke PackageMonitorCallback for " + intent.getAction()
                            + ", uid " + registerUid);
                }
                return;
            }
            Intent newIntent = intent;
            if (filterExtrasFunction != null) {
                final Bundle extras = intent.getExtras();
                if (extras != null) {
                    final Bundle filteredExtras = filterExtrasFunction.apply(registerUid, extras);
                    if (filteredExtras == null) {
                        // caller is unable to access this intent
                        if (DEBUG) {
                            Slog.w(TAG,
                                    "Skip invoke PackageMonitorCallback for " + intent.getAction()
                                            + " because null filteredExtras");
                        }
                        return;
                    }
                    newIntent = new Intent(newIntent);
                    newIntent.replaceExtras(filteredExtras);
                }
            }
            invokeCallback(callback, newIntent);
        }));
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

    private final class RegisterUser {
        int mUserId;
        int mUid;

        RegisterUser(int userId, int uid) {
            mUid = uid;
            mUserId = userId;
        }

        public int getUid() {
            return mUid;
        }

        public int getUserId() {
            return mUserId;
        }
    }
}
