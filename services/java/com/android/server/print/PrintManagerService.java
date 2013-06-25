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

package com.android.server.print;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;
import android.os.UserHandle;
import android.print.IPrintClient;
import android.print.IPrintDocumentAdapter;
import android.print.IPrintManager;
import android.print.PrintAttributes;
import android.print.PrintJobInfo;
import android.provider.Settings;
import android.util.SparseArray;

import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;

import java.util.Iterator;
import java.util.List;
import java.util.Set;

public final class PrintManagerService extends IPrintManager.Stub {

    private static final char COMPONENT_NAME_SEPARATOR = ':';

    private final Object mLock = new Object();

    private final Context mContext;

    private final SparseArray<UserState> mUserStates = new SparseArray<UserState>();

    private int mCurrentUserId = UserHandle.USER_OWNER;

    public PrintManagerService(Context context) {
        mContext = context;
        registerContentObservers();
        registerBoradcastReceivers();
    }

    public void systemRuning() {
        BackgroundThread.getHandler().post(new Runnable() {
            @Override
            public void run() {
                synchronized (mLock) {
                    UserState userState = getCurrentUserStateLocked();
                    userState.updateIfNeededLocked();
                    userState.getSpoolerLocked().notifyClientForActivteJobs();
                }
            }
        });
    }

    @Override
    public PrintJobInfo print(String printJobName, IPrintClient client,
            IPrintDocumentAdapter documentAdapter, PrintAttributes attributes, int appId,
            int userId) {
        final int resolvedAppId = resolveCallingAppEnforcingPermissions(appId);
        final int resolvedUserId = resolveCallingUserEnforcingPermissions(userId);
        final UserState userState;
        final RemotePrintSpooler spooler;
        synchronized (mLock) {
            userState = getOrCreateUserStateLocked(resolvedUserId);
            spooler = userState.getSpoolerLocked();
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            return spooler.createPrintJob(printJobName, client, documentAdapter,
                    attributes, resolvedAppId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public List<PrintJobInfo> getPrintJobInfos(int appId, int userId) {
        final int resolvedAppId = resolveCallingAppEnforcingPermissions(appId);
        final int resolvedUserId = resolveCallingUserEnforcingPermissions(userId);
        final UserState userState;
        final RemotePrintSpooler spooler;
        synchronized (mLock) {
            userState = getOrCreateUserStateLocked(resolvedUserId);
            spooler = userState.getSpoolerLocked();
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            return spooler.getPrintJobInfos(null, PrintJobInfo.STATE_ANY,
                    resolvedAppId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public PrintJobInfo getPrintJobInfo(int printJobId, int appId, int userId) {
        final int resolvedAppId = resolveCallingAppEnforcingPermissions(appId);
        final int resolvedUserId = resolveCallingUserEnforcingPermissions(userId);
        final UserState userState;
        final RemotePrintSpooler spooler;
        synchronized (mLock) {
            userState = getOrCreateUserStateLocked(resolvedUserId);
            spooler = userState.getSpoolerLocked();
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            return spooler.getPrintJobInfo(printJobId, resolvedAppId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void cancelPrintJob(int printJobId, int appId, int userId) {
        final int resolvedAppId = resolveCallingAppEnforcingPermissions(appId);
        final int resolvedUserId = resolveCallingUserEnforcingPermissions(userId);
        final UserState userState;
        final RemotePrintSpooler spooler;
        synchronized (mLock) {
            userState = getOrCreateUserStateLocked(resolvedUserId);
            spooler = userState.getSpoolerLocked();
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            if (spooler.cancelPrintJob(printJobId, resolvedAppId)) {
                return;
            }
            PrintJobInfo printJobInfo = getPrintJobInfo(printJobId, resolvedAppId, resolvedUserId);
            if (printJobInfo == null) {
                return;
            }
            ComponentName printServiceName = printJobInfo.getPrinterId().getService();
            RemotePrintService printService = null;
            synchronized (mLock) {
                printService = userState.getActiveServices().get(printServiceName);
            }
            if (printService == null) {
                return;
            }
            printService.onRequestCancelPrintJob(printJobInfo);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void registerContentObservers() {
        final Uri enabledPrintServicesUri = Settings.Secure.getUriFor(
                Settings.Secure.ENABLED_PRINT_SERVICES);

        ContentObserver observer = new ContentObserver(BackgroundThread.getHandler()) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (enabledPrintServicesUri.equals(uri)) {
                    synchronized (mLock) {
                        UserState userState = getCurrentUserStateLocked();
                        userState.updateIfNeededLocked();
                    }
                }
            }
        };

        mContext.getContentResolver().registerContentObserver(enabledPrintServicesUri,
                false, observer, UserHandle.USER_ALL);
    }

    private void registerBoradcastReceivers() {
        PackageMonitor monitor = new PackageMonitor() {
            @Override
            public boolean onPackageChanged(String packageName, int uid, String[] components) {
                synchronized (mLock) {
                    UserState userState = getOrCreateUserStateLocked(getChangingUserId());
                    Iterator<ComponentName> iterator = userState.getEnabledServices().iterator();
                    while (iterator.hasNext()) {
                        ComponentName componentName = iterator.next();
                        if (packageName.equals(componentName.getPackageName())) {
                            userState.updateIfNeededLocked();
                            return true;
                        }
                    }
                }
                return false;
            }

            @Override
            public void onPackageRemoved(String packageName, int uid) {
                synchronized (mLock) {
                    UserState userState = getOrCreateUserStateLocked(getChangingUserId());
                    Iterator<ComponentName> iterator = userState.getEnabledServices().iterator();
                    while (iterator.hasNext()) {
                        ComponentName componentName = iterator.next();
                        if (packageName.equals(componentName.getPackageName())) {
                            iterator.remove();
                            persistComponentNamesToSettingLocked(
                                    Settings.Secure.ENABLED_PRINT_SERVICES,
                                    userState.getEnabledServices(), getChangingUserId());
                            userState.updateIfNeededLocked();
                            return;
                        }
                    }
                }
            }

            @Override
            public boolean onHandleForceStop(Intent intent, String[] stoppedPackages,
                    int uid, boolean doit) {
                synchronized (mLock) {
                    UserState userState = getOrCreateUserStateLocked(getChangingUserId());
                    boolean stoppedSomePackages = false;
                    Iterator<ComponentName> iterator = userState.getEnabledServices().iterator();
                    while (iterator.hasNext()) {
                        ComponentName componentName = iterator.next();
                        String componentPackage = componentName.getPackageName();
                        for (String stoppedPackage : stoppedPackages) {
                            if (componentPackage.equals(stoppedPackage)) {
                                if (!doit) {
                                    return true;
                                }
                                stoppedSomePackages = true;
                                break;
                            }
                        }
                    }
                    if (stoppedSomePackages) {
                        userState.updateIfNeededLocked();
                    }
                    return false;
                }
            }

            private void persistComponentNamesToSettingLocked(String settingName,
                    Set<ComponentName> componentNames, int userId) {
                StringBuilder builder = new StringBuilder();
                for (ComponentName componentName : componentNames) {
                    if (builder.length() > 0) {
                        builder.append(COMPONENT_NAME_SEPARATOR);
                    }
                    builder.append(componentName.flattenToShortString());
                }
                Settings.Secure.putStringForUser(mContext.getContentResolver(),
                        settingName, builder.toString(), userId);
            }
        };

        // package changes
        monitor.register(mContext, BackgroundThread.getHandler().getLooper(),
                UserHandle.ALL, true);

        // user changes
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_SWITCHED);

        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                    switchUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
                } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                    removeUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
                }
            }
        }, UserHandle.ALL, intentFilter, null, BackgroundThread.getHandler());
    }

    private UserState getCurrentUserStateLocked() {
        return getOrCreateUserStateLocked(mCurrentUserId);
    }

    private UserState getOrCreateUserStateLocked(int userId) {
        UserState userState = mUserStates.get(userId);
        if (userState == null) {
            userState = new UserState(mContext, userId, mLock);
            mUserStates.put(userId, userState);
        }
        return userState;
    }

    private void switchUser(int newUserId) {
        synchronized (mLock) {
            if (newUserId == mCurrentUserId) {
                return;
            }
            mCurrentUserId = newUserId;
            UserState userState = getCurrentUserStateLocked();
            userState.updateIfNeededLocked();
            userState.getSpoolerLocked().notifyClientForActivteJobs();
        }
    }

    private void removeUser(int removedUserId) {
        synchronized (mLock) {
            UserState userState = mUserStates.get(removedUserId);
            if (userState != null) {
                userState.destroyLocked();
                mUserStates.remove(removedUserId);
            }
        }
    }

    private int resolveCallingAppEnforcingPermissions(int appId) {
        final int callingUid = Binder.getCallingUid();
        if (callingUid == 0 || callingUid == Process.SYSTEM_UID
                || callingUid == Process.SHELL_UID) {
            return appId;
        }
        final int callingAppId = UserHandle.getAppId(callingUid);
        if (appId == callingAppId) {
            return appId;
        }
        if (mContext.checkCallingPermission(Manifest.permission.ACCESS_ALL_PRINT_JOBS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Call from app " + callingAppId + " as app "
                    + appId + " without permission ACCESS_ALL_PRINT_JOBS");
        }
        return appId;
    }

    private int resolveCallingUserEnforcingPermissions(int userId) {
        final int callingUid = Binder.getCallingUid();
        if (callingUid == 0 || callingUid == Process.SYSTEM_UID
                || callingUid == Process.SHELL_UID) {
            return userId;
        }
        final int callingUserId = UserHandle.getUserId(callingUid);
        if (callingUserId == userId) {
            return userId;
        }
        if (mContext.checkCallingPermission(Manifest.permission.INTERACT_ACROSS_USERS_FULL)
                != PackageManager.PERMISSION_GRANTED
            ||  mContext.checkCallingPermission(Manifest.permission.INTERACT_ACROSS_USERS)
                != PackageManager.PERMISSION_GRANTED) {
            if (userId == UserHandle.USER_CURRENT_OR_SELF) {
                return callingUserId;
            }
            throw new SecurityException("Call from user " + callingUserId + " as user "
                + userId + " without permission INTERACT_ACROSS_USERS or "
                + "INTERACT_ACROSS_USERS_FULL not allowed.");
        }
        if (userId == UserHandle.USER_CURRENT || userId == UserHandle.USER_CURRENT_OR_SELF) {
            return mCurrentUserId;
        }
        throw new IllegalArgumentException("Calling user can be changed to only "
                + "UserHandle.USER_CURRENT or UserHandle.USER_CURRENT_OR_SELF.");
    }
}
