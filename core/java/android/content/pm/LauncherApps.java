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

package android.content.pm;

import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ILauncherApps;
import android.content.pm.IOnAppsChangedListener;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Class for retrieving a list of launchable activities for the current user and any associated
 * managed profiles. This is mainly for use by launchers. Apps can be queried for each user profile.
 * Since the PackageManager will not deliver package broadcasts for other profiles, you can register
 * for package changes here.
 * <p>
 * To watch for managed profiles being added or removed, register for the following broadcasts:
 * {@link Intent#ACTION_MANAGED_PROFILE_ADDED} and {@link Intent#ACTION_MANAGED_PROFILE_REMOVED}.
 * <p>
 * You can retrieve the list of profiles associated with this user with
 * {@link UserManager#getUserProfiles()}.
 */
public class LauncherApps {

    static final String TAG = "LauncherApps";
    static final boolean DEBUG = false;

    private Context mContext;
    private ILauncherApps mService;
    private PackageManager mPm;

    private List<CallbackMessageHandler> mCallbacks
            = new ArrayList<CallbackMessageHandler>();

    /**
     * Callbacks for package changes to this and related managed profiles.
     */
    public static abstract class Callback {
        /**
         * Indicates that a package was removed from the specified profile.
         *
         * If a package is removed while being updated onPackageChanged will be
         * called instead.
         *
         * @param packageName The name of the package that was removed.
         * @param user The UserHandle of the profile that generated the change.
         */
        abstract public void onPackageRemoved(String packageName, UserHandle user);

        /**
         * Indicates that a package was added to the specified profile.
         *
         * If a package is added while being updated then onPackageChanged will be
         * called instead.
         *
         * @param packageName The name of the package that was added.
         * @param user The UserHandle of the profile that generated the change.
         */
        abstract public void onPackageAdded(String packageName, UserHandle user);

        /**
         * Indicates that a package was modified in the specified profile.
         * This can happen, for example, when the package is updated or when
         * one or more components are enabled or disabled.
         *
         * @param packageName The name of the package that has changed.
         * @param user The UserHandle of the profile that generated the change.
         */
        abstract public void onPackageChanged(String packageName, UserHandle user);

        /**
         * Indicates that one or more packages have become available. For
         * example, this can happen when a removable storage card has
         * reappeared.
         *
         * @param packageNames The names of the packages that have become
         *            available.
         * @param user The UserHandle of the profile that generated the change.
         * @param replacing Indicates whether these packages are replacing
         *            existing ones.
         */
        abstract public void onPackagesAvailable(String[] packageNames, UserHandle user,
                boolean replacing);

        /**
         * Indicates that one or more packages have become unavailable. For
         * example, this can happen when a removable storage card has been
         * removed.
         *
         * @param packageNames The names of the packages that have become
         *            unavailable.
         * @param user The UserHandle of the profile that generated the change.
         * @param replacing Indicates whether the packages are about to be
         *            replaced with new versions.
         */
        abstract public void onPackagesUnavailable(String[] packageNames, UserHandle user,
                boolean replacing);
    }

    /** @hide */
    public LauncherApps(Context context, ILauncherApps service) {
        mContext = context;
        mService = service;
        mPm = context.getPackageManager();
    }

    /**
     * Retrieves a list of launchable activities that match {@link Intent#ACTION_MAIN} and
     * {@link Intent#CATEGORY_LAUNCHER}, for a specified user.
     *
     * @param packageName The specific package to query. If null, it checks all installed packages
     *            in the profile.
     * @param user The UserHandle of the profile.
     * @return List of launchable activities. Can be an empty list but will not be null.
     */
    public List<LauncherActivityInfo> getActivityList(String packageName, UserHandle user) {
        List<ResolveInfo> activities = null;
        try {
            activities = mService.getLauncherActivities(packageName, user);
        } catch (RemoteException re) {
        }
        if (activities == null) {
            return Collections.EMPTY_LIST;
        }
        ArrayList<LauncherActivityInfo> lais = new ArrayList<LauncherActivityInfo>();
        final int count = activities.size();
        for (int i = 0; i < count; i++) {
            ResolveInfo ri = activities.get(i);
            long firstInstallTime = 0;
            try {
                firstInstallTime = mPm.getPackageInfo(ri.activityInfo.packageName,
                    PackageManager.GET_UNINSTALLED_PACKAGES).firstInstallTime;
            } catch (NameNotFoundException nnfe) {
                // Sorry, can't find package
            }
            LauncherActivityInfo lai = new LauncherActivityInfo(mContext, ri, user,
                    firstInstallTime);
            if (DEBUG) {
                Log.v(TAG, "Returning activity for profile " + user + " : "
                        + lai.getComponentName());
            }
            lais.add(lai);
        }
        return lais;
    }

    static ComponentName getComponentName(ResolveInfo ri) {
        return new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
    }

    /**
     * Returns the activity info for a given intent and user handle, if it resolves. Otherwise it
     * returns null.
     *
     * @param intent The intent to find a match for.
     * @param user The profile to look in for a match.
     * @return An activity info object if there is a match.
     */
    public LauncherActivityInfo resolveActivity(Intent intent, UserHandle user) {
        try {
            ResolveInfo ri = mService.resolveActivity(intent, user);
            if (ri != null) {
                long firstInstallTime = 0;
                try {
                    firstInstallTime = mPm.getPackageInfo(ri.activityInfo.packageName,
                            PackageManager.GET_UNINSTALLED_PACKAGES).firstInstallTime;
                } catch (NameNotFoundException nnfe) {
                    // Sorry, can't find package
                }
                LauncherActivityInfo info = new LauncherActivityInfo(mContext, ri, user,
                        firstInstallTime);
                return info;
            }
        } catch (RemoteException re) {
            throw new RuntimeException("Failed to call LauncherAppsService");
        }
        return null;
    }

    /**
     * Starts a Main activity in the specified profile.
     *
     * @param component The ComponentName of the activity to launch
     * @param user The UserHandle of the profile
     * @param sourceBounds The Rect containing the source bounds of the clicked icon
     * @param opts Options to pass to startActivity
     */
    public void startMainActivity(ComponentName component, UserHandle user, Rect sourceBounds,
            Bundle opts) {
        if (DEBUG) {
            Log.i(TAG, "StartMainActivity " + component + " " + user.getIdentifier());
        }
        try {
            mService.startActivityAsUser(component, sourceBounds, opts, user);
        } catch (RemoteException re) {
            // Oops!
        }
    }

    /**
     * Starts the settings activity to show the application details for a
     * package in the specified profile.
     *
     * @param component The ComponentName of the package to launch settings for.
     * @param user The UserHandle of the profile
     * @param sourceBounds The Rect containing the source bounds of the clicked icon
     * @param opts Options to pass to startActivity
     */
    public void startAppDetailsActivity(ComponentName component, UserHandle user,
            Rect sourceBounds, Bundle opts) {
        try {
            mService.showAppDetailsAsUser(component, sourceBounds, opts, user);
        } catch (RemoteException re) {
            // Oops!
        }
    }

    /**
     * Checks if the package is installed and enabled for a profile.
     *
     * @param packageName The package to check.
     * @param user The UserHandle of the profile.
     *
     * @return true if the package exists and is enabled.
     */
    public boolean isPackageEnabled(String packageName, UserHandle user) {
        try {
            return mService.isPackageEnabled(packageName, user);
        } catch (RemoteException re) {
            throw new RuntimeException("Failed to call LauncherAppsService");
        }
    }

    /**
     * Checks if the activity exists and it enabled for a profile.
     *
     * @param component The activity to check.
     * @param user The UserHandle of the profile.
     *
     * @return true if the activity exists and is enabled.
     */
    public boolean isActivityEnabled(ComponentName component, UserHandle user) {
        try {
            return mService.isActivityEnabled(component, user);
        } catch (RemoteException re) {
            throw new RuntimeException("Failed to call LauncherAppsService");
        }
    }


    /**
     * Adds a callback for changes to packages in current and managed profiles.
     *
     * @param callback The callback to add.
     */
    public void addCallback(Callback callback) {
        addCallback(callback, null);
    }

    /**
     * Adds a callback for changes to packages in current and managed profiles.
     *
     * @param callback The callback to add.
     * @param handler that should be used to post callbacks on, may be null.
     */
    public void addCallback(Callback callback, Handler handler) {
        synchronized (this) {
            if (callback != null && !mCallbacks.contains(callback)) {
                boolean addedFirstCallback = mCallbacks.size() == 0;
                addCallbackLocked(callback, handler);
                if (addedFirstCallback) {
                    try {
                        mService.addOnAppsChangedListener(mAppsChangedListener);
                    } catch (RemoteException re) {
                    }
                }
            }
        }
    }

    /**
     * Removes a callback that was previously added.
     *
     * @param callback The callback to remove.
     * @see #addCallback(Callback)
     */
    public void removeCallback(Callback callback) {
        synchronized (this) {
            removeCallbackLocked(callback);
            if (mCallbacks.size() == 0) {
                try {
                    mService.removeOnAppsChangedListener(mAppsChangedListener);
                } catch (RemoteException re) {
                }
            }
        }
    }

    private void removeCallbackLocked(Callback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("Callback cannot be null");
        }
        final int size = mCallbacks.size();
        for (int i = 0; i < size; ++i) {
            if (mCallbacks.get(i).mCallback == callback) {
                mCallbacks.remove(i);
                return;
            }
        }
    }

    private void addCallbackLocked(Callback callback, Handler handler) {
        // Remove if already present.
        removeCallbackLocked(callback);
        if (handler == null) {
            handler = new Handler();
        }
        CallbackMessageHandler toAdd = new CallbackMessageHandler(handler.getLooper(), callback);
        mCallbacks.add(toAdd);
    }

    private IOnAppsChangedListener.Stub mAppsChangedListener = new IOnAppsChangedListener.Stub() {

        @Override
        public void onPackageRemoved(UserHandle user, String packageName)
                throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "onPackageRemoved " + user.getIdentifier() + "," + packageName);
            }
            synchronized (LauncherApps.this) {
                for (CallbackMessageHandler callback : mCallbacks) {
                    callback.postOnPackageRemoved(packageName, user);
                }
            }
        }

        @Override
        public void onPackageChanged(UserHandle user, String packageName) throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "onPackageChanged " + user.getIdentifier() + "," + packageName);
            }
            synchronized (LauncherApps.this) {
                for (CallbackMessageHandler callback : mCallbacks) {
                    callback.postOnPackageChanged(packageName, user);
                }
            }
        }

        @Override
        public void onPackageAdded(UserHandle user, String packageName) throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "onPackageAdded " + user.getIdentifier() + "," + packageName);
            }
            synchronized (LauncherApps.this) {
                for (CallbackMessageHandler callback : mCallbacks) {
                    callback.postOnPackageAdded(packageName, user);
                }
            }
        }

        @Override
        public void onPackagesAvailable(UserHandle user, String[] packageNames, boolean replacing)
                throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "onPackagesAvailable " + user.getIdentifier() + "," + packageNames);
            }
            synchronized (LauncherApps.this) {
                for (CallbackMessageHandler callback : mCallbacks) {
                    callback.postOnPackagesAvailable(packageNames, user, replacing);
                }
            }
        }

        @Override
        public void onPackagesUnavailable(UserHandle user, String[] packageNames, boolean replacing)
                throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "onPackagesUnavailable " + user.getIdentifier() + "," + packageNames);
            }
            synchronized (LauncherApps.this) {
                for (CallbackMessageHandler callback : mCallbacks) {
                    callback.postOnPackagesUnavailable(packageNames, user, replacing);
                }
           }
        }
    };

    private static class CallbackMessageHandler extends Handler {
        private static final int MSG_ADDED = 1;
        private static final int MSG_REMOVED = 2;
        private static final int MSG_CHANGED = 3;
        private static final int MSG_AVAILABLE = 4;
        private static final int MSG_UNAVAILABLE = 5;

        private LauncherApps.Callback mCallback;

        private static class CallbackInfo {
            String[] packageNames;
            String packageName;
            boolean replacing;
            UserHandle user;
        }

        public CallbackMessageHandler(Looper looper, LauncherApps.Callback callback) {
            super(looper, null, true);
            mCallback = callback;
        }

        @Override
        public void handleMessage(Message msg) {
            if (mCallback == null || !(msg.obj instanceof CallbackInfo)) {
                return;
            }
            CallbackInfo info = (CallbackInfo) msg.obj;
            switch (msg.what) {
                case MSG_ADDED:
                    mCallback.onPackageAdded(info.packageName, info.user);
                    break;
                case MSG_REMOVED:
                    mCallback.onPackageRemoved(info.packageName, info.user);
                    break;
                case MSG_CHANGED:
                    mCallback.onPackageChanged(info.packageName, info.user);
                    break;
                case MSG_AVAILABLE:
                    mCallback.onPackagesAvailable(info.packageNames, info.user, info.replacing);
                    break;
                case MSG_UNAVAILABLE:
                    mCallback.onPackagesUnavailable(info.packageNames, info.user, info.replacing);
                    break;
            }
        }

        public void postOnPackageAdded(String packageName, UserHandle user) {
            CallbackInfo info = new CallbackInfo();
            info.packageName = packageName;
            info.user = user;
            obtainMessage(MSG_ADDED, info).sendToTarget();
        }

        public void postOnPackageRemoved(String packageName, UserHandle user) {
            CallbackInfo info = new CallbackInfo();
            info.packageName = packageName;
            info.user = user;
            obtainMessage(MSG_REMOVED, info).sendToTarget();
        }

        public void postOnPackageChanged(String packageName, UserHandle user) {
            CallbackInfo info = new CallbackInfo();
            info.packageName = packageName;
            info.user = user;
            obtainMessage(MSG_CHANGED, info).sendToTarget();
        }

        public void postOnPackagesAvailable(String[] packageNames, UserHandle user,
                boolean replacing) {
            CallbackInfo info = new CallbackInfo();
            info.packageNames = packageNames;
            info.replacing = replacing;
            info.user = user;
            obtainMessage(MSG_AVAILABLE, info).sendToTarget();
        }

        public void postOnPackagesUnavailable(String[] packageNames, UserHandle user,
                boolean replacing) {
            CallbackInfo info = new CallbackInfo();
            info.packageNames = packageNames;
            info.replacing = replacing;
            info.user = user;
            obtainMessage(MSG_UNAVAILABLE, info).sendToTarget();
        }
    }

    /** Remove after unbundled apps have migrated STOP SHIP */
    public static abstract class OnAppsChangedCallback extends Callback {
    }

    /** Remove after unbundled apps have migrated STOP SHIP */
    public void addOnAppsChangedCallback(OnAppsChangedCallback callback) {
        addCallback(callback, null);
    }

    /** Remove after unbundled apps have migrated STOP SHIP */
    public void addOnAppsChangedCallback(OnAppsChangedCallback callback, Handler handler) {
        addCallback(callback, handler);
    }

    /** Remove after unbundled apps have migrated STOP SHIP */
    public void removeOnAppsChangedCallback(OnAppsChangedCallback callback) {
        removeCallback(callback);
    }

    /** Remove after unbundled apps have migrated STOP SHIP */
    public void startActivityForProfile(ComponentName component, UserHandle user, Rect sourceBounds,
            Bundle opts) {
        startMainActivity(component, user, sourceBounds, opts);
    }

    /** Remove after unbundled apps have migrated STOP SHIP */
    public void showAppDetailsForProfile(ComponentName component, UserHandle user,
            Rect sourceBounds, Bundle opts) {
        startAppDetailsActivity(component, user, sourceBounds, opts);
    }

    /** Remove after unbundled apps have migrated STOP SHIP */
    public boolean isPackageEnabledForProfile(String packageName, UserHandle user) {
        return isPackageEnabled(packageName, user);
    }

    /** Remove after unbundled apps have migrated STOP SHIP */
    public boolean isActivityEnabledForProfile(ComponentName component, UserHandle user) {
        return isActivityEnabled(component, user);
    }
}
