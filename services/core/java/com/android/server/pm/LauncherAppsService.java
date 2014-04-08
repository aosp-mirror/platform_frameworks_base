/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ILauncherApps;
import android.content.pm.IOnAppsChangedListener;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.IInterface;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;

import com.android.internal.content.PackageMonitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Service that manages requests and callbacks for launchers that support
 * managed profiles. 
 */
public class LauncherAppsService extends ILauncherApps.Stub {

    private static final String TAG = "LauncherAppsService";
    private final Context mContext;
    private final PackageManager mPm;
    private final UserManager mUm;
    private final PackageCallbackList<IOnAppsChangedListener> mListeners
            = new PackageCallbackList<IOnAppsChangedListener>();

    private MyPackageMonitor mPackageMonitor = new MyPackageMonitor();

    public LauncherAppsService(Context context) {
        mContext = context;
        mPm = mContext.getPackageManager();
        mUm = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
    }

    /*
     * @see android.content.pm.ILauncherApps#addOnAppsChangedListener(
     *          android.content.pm.IOnAppsChangedListener)
     */
    @Override
    public void addOnAppsChangedListener(IOnAppsChangedListener listener) throws RemoteException {
        synchronized (mListeners) {
            if (mListeners.getRegisteredCallbackCount() == 0) {
                startWatchingPackageBroadcasts();
            }
            mListeners.unregister(listener);
            mListeners.register(listener);
        }
    }

    /*
     * @see android.content.pm.ILauncherApps#removeOnAppsChangedListener(
     *          android.content.pm.IOnAppsChangedListener)
     */
    @Override
    public void removeOnAppsChangedListener(IOnAppsChangedListener listener)
            throws RemoteException {
        synchronized (mListeners) {
            mListeners.unregister(listener);
            if (mListeners.getRegisteredCallbackCount() == 0) {
                stopWatchingPackageBroadcasts();
            }
        }
    }

    /**
     * Register a receiver to watch for package broadcasts
     */
    private void startWatchingPackageBroadcasts() {
        mPackageMonitor.register(mContext, null, UserHandle.ALL, true);
    }

    /**
     * Unregister package broadcast receiver
     */
    private void stopWatchingPackageBroadcasts() {
        mPackageMonitor.unregister();
    }

    void checkCallbackCount() {
        synchronized (LauncherAppsService.this) {
            if (mListeners.getRegisteredCallbackCount() == 0) {
                stopWatchingPackageBroadcasts();
            }
        }
    }

    /**
     * Checks if the caller is in the same group as the userToCheck.
     */
    private void ensureInUserProfiles(UserHandle userToCheck, String message) {
        final int callingUserId = UserHandle.getCallingUserId();
        final int targetUserId = userToCheck.getIdentifier();

        if (targetUserId == callingUserId) return;

        long ident = Binder.clearCallingIdentity();
        try {
            UserInfo callingUserInfo = mUm.getUserInfo(callingUserId);
            UserInfo targetUserInfo = mUm.getUserInfo(targetUserId);
            if (targetUserInfo == null
                    || targetUserInfo.profileGroupId == UserInfo.NO_PROFILE_GROUP_ID
                    || targetUserInfo.profileGroupId != callingUserInfo.profileGroupId) {
                throw new SecurityException(message);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public List<ResolveInfo> getLauncherActivities(String packageName, UserHandle user)
            throws RemoteException {
        ensureInUserProfiles(user, "Cannot retrieve activities for unrelated profile " + user);

        final Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        long ident = Binder.clearCallingIdentity();
        try {
            List<ResolveInfo> apps = mPm.queryIntentActivitiesAsUser(mainIntent, 0,
                    user.getIdentifier());
            return apps;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public ResolveInfo resolveActivity(Intent intent, UserHandle user)
            throws RemoteException {
        ensureInUserProfiles(user, "Cannot resolve activity for unrelated profile " + user);

        long ident = Binder.clearCallingIdentity();
        try {
            ResolveInfo app = mPm.resolveActivityAsUser(intent, 0, user.getIdentifier());
            return app;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void startActivityAsUser(ComponentName component, Rect sourceBounds,
            Bundle opts, UserHandle user) throws RemoteException {
        ensureInUserProfiles(user, "Cannot start activity for unrelated profile " + user);

        Intent launchIntent = new Intent(Intent.ACTION_MAIN);
        launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        launchIntent.setComponent(component);
        launchIntent.setSourceBounds(sourceBounds);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        final int callingUserId = UserHandle.getCallingUserId();
        long ident = Binder.clearCallingIdentity();
        try {
            mContext.startActivityAsUser(launchIntent, opts, user);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private class MyPackageMonitor extends PackageMonitor {

        @Override
        public void onPackageAdded(String packageName, int uid) {
            UserHandle user = new UserHandle(getChangingUserId());
            // TODO: if (!isProfile(user)) return;
            final int n = mListeners.beginBroadcast();
            for (int i = 0; i < n; i++) {
                IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                try {
                    listener.onPackageAdded(user, packageName);
                } catch (RemoteException re) {
                    Slog.d(TAG, "Callback failed ", re);
                }
            }
            mListeners.finishBroadcast();

            super.onPackageAdded(packageName, uid);
        }

        @Override
        public void onPackageRemoved(String packageName, int uid) {
            UserHandle user = new UserHandle(getChangingUserId());
            // TODO: if (!isCurrentProfile(user)) return;
            final int n = mListeners.beginBroadcast();
            for (int i = 0; i < n; i++) {
                IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                try {
                    listener.onPackageRemoved(user, packageName);
                } catch (RemoteException re) {
                    Slog.d(TAG, "Callback failed ", re);
                }
            }
            mListeners.finishBroadcast();

            super.onPackageRemoved(packageName, uid);
        }

        @Override
        public void onPackageModified(String packageName) {
            UserHandle user = new UserHandle(getChangingUserId());
            // TODO: if (!isProfile(user)) return;
            final int n = mListeners.beginBroadcast();
            for (int i = 0; i < n; i++) {
                IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                try {
                    listener.onPackageChanged(user, packageName);
                } catch (RemoteException re) {
                    Slog.d(TAG, "Callback failed ", re);
                }
            }
            mListeners.finishBroadcast();

            super.onPackageModified(packageName);
        }

        @Override
        public void onPackagesAvailable(String[] packages) {
            UserHandle user = new UserHandle(getChangingUserId());
            // TODO: if (!isProfile(user)) return;
            final int n = mListeners.beginBroadcast();
            for (int i = 0; i < n; i++) {
                IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                try {
                    listener.onPackagesAvailable(user, packages, isReplacing());
                } catch (RemoteException re) {
                    Slog.d(TAG, "Callback failed ", re);
                }
            }
            mListeners.finishBroadcast();

            super.onPackagesAvailable(packages);
        }

        @Override
        public void onPackagesUnavailable(String[] packages) {
            UserHandle user = new UserHandle(getChangingUserId());
            // TODO: if (!isProfile(user)) return;
            final int n = mListeners.beginBroadcast();
            for (int i = 0; i < n; i++) {
                IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                try {
                    listener.onPackagesUnavailable(user, packages, isReplacing());
                } catch (RemoteException re) {
                    Slog.d(TAG, "Callback failed ", re);
                }
            }
            mListeners.finishBroadcast();

            super.onPackagesUnavailable(packages);
        }

    }

    class PackageCallbackList<T extends IInterface> extends RemoteCallbackList<T> {

        @Override
        public void onCallbackDied(T callback, Object cookie) {
            checkCallbackCount();
        }
    }
}
