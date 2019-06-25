/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static android.content.Intent.ACTION_OVERLAY_CHANGED;
import static android.content.Intent.ACTION_PREFERRED_ACTIVITY_CHANGED;
import static android.os.UserHandle.USER_CURRENT;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON_OVERLAY;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.om.IOverlayManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ApkAssets;
import android.os.PatternMatcher;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseBooleanArray;

import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.UiOffloadThread;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.util.NotificationChannels;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Controller for tracking the current navigation bar mode.
 */
@Singleton
public class NavigationModeController implements Dumpable {

    private static final String TAG = NavigationModeController.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final int SYSTEM_APP_MASK =
            ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
    static final String SHARED_PREFERENCES_NAME = "navigation_mode_controller_preferences";
    static final String PREFS_SWITCHED_FROM_GESTURE_NAV_KEY = "switched_from_gesture_nav";

    public interface ModeChangedListener {
        void onNavigationModeChanged(int mode);
    }

    private final Context mContext;
    private Context mCurrentUserContext;
    private final IOverlayManager mOverlayManager;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final UiOffloadThread mUiOffloadThread;

    private SparseBooleanArray mRestoreGesturalNavBarMode = new SparseBooleanArray();

    private int mMode = NAV_BAR_MODE_3BUTTON;
    private ArrayList<ModeChangedListener> mListeners = new ArrayList<>();

    private String mLastDefaultLauncher;

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case ACTION_OVERLAY_CHANGED:
                    if (DEBUG) {
                        Log.d(TAG, "ACTION_OVERLAY_CHANGED");
                    }
                    updateCurrentInteractionMode(true /* notify */);
                    break;
                case ACTION_PREFERRED_ACTIVITY_CHANGED:
                    if (DEBUG) {
                        Log.d(TAG, "ACTION_PREFERRED_ACTIVITY_CHANGED");
                    }
                    final String launcher = getDefaultLauncherPackageName(mCurrentUserContext);
                    // Check if it is a default launcher change
                    if (!TextUtils.equals(mLastDefaultLauncher, launcher)) {
                        switchFromGestureNavModeIfNotSupportedByDefaultLauncher();
                        showNotificationIfDefaultLauncherSupportsGestureNav();
                        mLastDefaultLauncher = launcher;
                    }
                    break;
            }
        }
    };

    private final DeviceProvisionedController.DeviceProvisionedListener mDeviceProvisionedCallback =
            new DeviceProvisionedController.DeviceProvisionedListener() {
                @Override
                public void onDeviceProvisionedChanged() {
                    if (DEBUG) {
                        Log.d(TAG, "onDeviceProvisionedChanged: "
                                + mDeviceProvisionedController.isDeviceProvisioned());
                    }
                    // Once the device has been provisioned, check if we can restore gestural nav
                    restoreGesturalNavOverlayIfNecessary();
                }

                @Override
                public void onUserSetupChanged() {
                    if (DEBUG) {
                        Log.d(TAG, "onUserSetupChanged: "
                                + mDeviceProvisionedController.isCurrentUserSetup());
                    }
                    // Once the user has been setup, check if we can restore gestural nav
                    restoreGesturalNavOverlayIfNecessary();
                }

                @Override
                public void onUserSwitched() {
                    if (DEBUG) {
                        Log.d(TAG, "onUserSwitched: "
                                + ActivityManagerWrapper.getInstance().getCurrentUserId());
                    }

                    // Update the nav mode for the current user
                    updateCurrentInteractionMode(true /* notify */);
                    switchFromGestureNavModeIfNotSupportedByDefaultLauncher();

                    // When switching users, defer enabling the gestural nav overlay until the user
                    // is all set up
                    deferGesturalNavOverlayIfNecessary();
                }
            };

    @Inject
    public NavigationModeController(Context context,
            DeviceProvisionedController deviceProvisionedController,
            UiOffloadThread uiOffloadThread) {
        mContext = context;
        mCurrentUserContext = context;
        mOverlayManager = IOverlayManager.Stub.asInterface(
                ServiceManager.getService(Context.OVERLAY_SERVICE));
        mUiOffloadThread = uiOffloadThread;
        mDeviceProvisionedController = deviceProvisionedController;
        mDeviceProvisionedController.addCallback(mDeviceProvisionedCallback);

        IntentFilter overlayFilter = new IntentFilter(ACTION_OVERLAY_CHANGED);
        overlayFilter.addDataScheme("package");
        overlayFilter.addDataSchemeSpecificPart("android", PatternMatcher.PATTERN_LITERAL);
        mContext.registerReceiverAsUser(mReceiver, UserHandle.ALL, overlayFilter, null, null);

        IntentFilter preferredActivityFilter = new IntentFilter(ACTION_PREFERRED_ACTIVITY_CHANGED);
        mContext.registerReceiverAsUser(mReceiver, UserHandle.ALL, preferredActivityFilter, null,
                null);
        // We are only interested in launcher changes, so keeping track of the current default.
        mLastDefaultLauncher = getDefaultLauncherPackageName(mContext);

        updateCurrentInteractionMode(false /* notify */);
        switchFromGestureNavModeIfNotSupportedByDefaultLauncher();

        // Check if we need to defer enabling gestural nav
        deferGesturalNavOverlayIfNecessary();
    }

    public void updateCurrentInteractionMode(boolean notify) {
        mCurrentUserContext = getCurrentUserContext();
        int mode = getCurrentInteractionMode(mCurrentUserContext);
        mMode = mode;
        mUiOffloadThread.submit(() -> {
            Settings.Secure.putString(mCurrentUserContext.getContentResolver(),
                    Secure.NAVIGATION_MODE, String.valueOf(mode));
        });
        if (DEBUG) {
            Log.e(TAG, "updateCurrentInteractionMode: mode=" + mMode
                    + " contextUser=" + mCurrentUserContext.getUserId());
            dumpAssetPaths(mCurrentUserContext);
        }

        if (notify) {
            for (int i = 0; i < mListeners.size(); i++) {
                mListeners.get(i).onNavigationModeChanged(mode);
            }
        }
    }

    public int addListener(ModeChangedListener listener) {
        mListeners.add(listener);
        return getCurrentInteractionMode(mCurrentUserContext);
    }

    public void removeListener(ModeChangedListener listener) {
        mListeners.remove(listener);
    }

    private int getCurrentInteractionMode(Context context) {
        int mode = context.getResources().getInteger(
                com.android.internal.R.integer.config_navBarInteractionMode);
        if (DEBUG) {
            Log.d(TAG, "getCurrentInteractionMode: mode=" + mMode
                    + " contextUser=" + context.getUserId());
        }
        return mode;
    }

    public Context getCurrentUserContext() {
        int userId = ActivityManagerWrapper.getInstance().getCurrentUserId();
        if (DEBUG) {
            Log.d(TAG, "getCurrentUserContext: contextUser=" + mContext.getUserId()
                    + " currentUser=" + userId);
        }
        if (mContext.getUserId() == userId) {
            return mContext;
        }
        try {
            return mContext.createPackageContextAsUser(mContext.getPackageName(),
                    0 /* flags */, UserHandle.of(userId));
        } catch (PackageManager.NameNotFoundException e) {
            // Never happens for the sysui package
            return null;
        }
    }

    private void deferGesturalNavOverlayIfNecessary() {
        final int userId = mDeviceProvisionedController.getCurrentUser();
        mRestoreGesturalNavBarMode.put(userId, false);
        if (mDeviceProvisionedController.isDeviceProvisioned()
                && mDeviceProvisionedController.isCurrentUserSetup()) {
            // User is already setup and device is provisioned, nothing to do
            if (DEBUG) {
                Log.d(TAG, "deferGesturalNavOverlayIfNecessary: device is provisioned and user is "
                        + "setup");
            }
            return;
        }

        ArrayList<String> defaultOverlays = new ArrayList<>();
        try {
            defaultOverlays.addAll(Arrays.asList(mOverlayManager.getDefaultOverlayPackages()));
        } catch (RemoteException e) {
            Log.e(TAG, "deferGesturalNavOverlayIfNecessary: failed to fetch default overlays");
        }
        if (!defaultOverlays.contains(NAV_BAR_MODE_GESTURAL_OVERLAY)) {
            // No default gesture nav overlay
            if (DEBUG) {
                Log.d(TAG, "deferGesturalNavOverlayIfNecessary: no default gestural overlay, "
                        + "default=" + defaultOverlays);
            }
            return;
        }

        // If the default is gestural, force-enable three button mode until the device is
        // provisioned
        setModeOverlay(NAV_BAR_MODE_3BUTTON_OVERLAY, USER_CURRENT);
        mRestoreGesturalNavBarMode.put(userId, true);
        if (DEBUG) {
            Log.d(TAG, "deferGesturalNavOverlayIfNecessary: setting to 3 button mode");
        }
    }

    private void restoreGesturalNavOverlayIfNecessary() {
        if (DEBUG) {
            Log.d(TAG, "restoreGesturalNavOverlayIfNecessary: needs restore="
                    + mRestoreGesturalNavBarMode);
        }
        final int userId = mDeviceProvisionedController.getCurrentUser();
        if (mRestoreGesturalNavBarMode.get(userId)) {
            // Restore the gestural state if necessary
            setModeOverlay(NAV_BAR_MODE_GESTURAL_OVERLAY, USER_CURRENT);
            mRestoreGesturalNavBarMode.put(userId, false);
        }
    }

    public void setModeOverlay(String overlayPkg, int userId) {
        mUiOffloadThread.submit(() -> {
            try {
                mOverlayManager.setEnabledExclusiveInCategory(overlayPkg, userId);
                if (DEBUG) {
                    Log.d(TAG, "setModeOverlay: overlayPackage=" + overlayPkg
                            + " userId=" + userId);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to enable overlay " + overlayPkg + " for user " + userId);
            }
        });
    }

    private void switchFromGestureNavModeIfNotSupportedByDefaultLauncher() {
        if (getCurrentInteractionMode(mCurrentUserContext) != NAV_BAR_MODE_GESTURAL) {
            return;
        }
        final Boolean supported = isGestureNavSupportedByDefaultLauncher(mCurrentUserContext);
        if (supported == null || supported) {
            return;
        }

        Log.d(TAG, "Switching system navigation to 3-button mode:"
                + " defaultLauncher=" + getDefaultLauncherPackageName(mCurrentUserContext)
                + " contextUser=" + mCurrentUserContext.getUserId());

        setModeOverlay(NAV_BAR_MODE_3BUTTON_OVERLAY, USER_CURRENT);
        showNotification(mCurrentUserContext, R.string.notification_content_system_nav_changed);
        mCurrentUserContext.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(PREFS_SWITCHED_FROM_GESTURE_NAV_KEY, true).apply();
    }

    private void showNotificationIfDefaultLauncherSupportsGestureNav() {
        boolean previouslySwitchedFromGestureNav = mCurrentUserContext
                .getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREFS_SWITCHED_FROM_GESTURE_NAV_KEY, false);
        if (!previouslySwitchedFromGestureNav) {
            return;
        }
        if (getCurrentInteractionMode(mCurrentUserContext) == NAV_BAR_MODE_GESTURAL) {
            return;
        }
        final Boolean supported = isGestureNavSupportedByDefaultLauncher(mCurrentUserContext);
        if (supported == null || !supported) {
            return;
        }

        showNotification(mCurrentUserContext, R.string.notification_content_gesture_nav_available);
        mCurrentUserContext.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(PREFS_SWITCHED_FROM_GESTURE_NAV_KEY, false).apply();
    }

    /**
     * Returns null if there is no default launcher set for the current user. Returns true if the
     * current default launcher supports Gesture Navigation. Returns false otherwise.
     */
    private Boolean isGestureNavSupportedByDefaultLauncher(Context context) {
        final String defaultLauncherPackageName = getDefaultLauncherPackageName(context);
        if (DEBUG) {
            Log.d(TAG, "isGestureNavSupportedByDefaultLauncher:"
                    + " defaultLauncher=" + defaultLauncherPackageName
                    + " contextUser=" + context.getUserId());
        }
        if (defaultLauncherPackageName == null) {
            return null;
        }
        if (isSystemApp(context, defaultLauncherPackageName)) {
            return true;
        }
        return false;
    }

    private String getDefaultLauncherPackageName(Context context) {
        final ComponentName cn = context.getPackageManager().getHomeActivities(new ArrayList<>());
        if (cn == null) {
            return null;
        }
        return cn.getPackageName();
    }

    /** Returns true if the app for the given package name is a system app for this device */
    private boolean isSystemApp(Context context, String packageName) {
        try {
            ApplicationInfo ai = context.getPackageManager().getApplicationInfo(packageName,
                    PackageManager.GET_META_DATA);
            return ai != null && ((ai.flags & SYSTEM_APP_MASK) != 0);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void showNotification(Context context, int resId) {
        final CharSequence message = context.getResources().getString(resId);
        if (DEBUG) {
            Log.d(TAG, "showNotification: message=" + message);
        }

        final Notification.Builder builder =
                new Notification.Builder(mContext, NotificationChannels.ALERTS)
                        .setContentText(message)
                        .setStyle(new Notification.BigTextStyle())
                        .setSmallIcon(R.drawable.ic_info)
                        .setAutoCancel(true)
                        .setContentIntent(PendingIntent.getActivity(context, 0, new Intent(), 0));
        context.getSystemService(NotificationManager.class).notify(TAG, 0, builder.build());
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NavigationModeController:");
        pw.println("  mode=" + mMode);
        String defaultOverlays = "";
        try {
            defaultOverlays = String.join(", ", mOverlayManager.getDefaultOverlayPackages());
        } catch (RemoteException e) {
            defaultOverlays = "failed_to_fetch";
        }
        pw.println("  defaultOverlays=" + defaultOverlays);
        dumpAssetPaths(mCurrentUserContext);

        pw.println("  defaultLauncher=" + mLastDefaultLauncher);
        boolean previouslySwitchedFromGestureNav = mCurrentUserContext
                .getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREFS_SWITCHED_FROM_GESTURE_NAV_KEY, false);
        pw.println("  previouslySwitchedFromGestureNav=" + previouslySwitchedFromGestureNav);
    }

    private void dumpAssetPaths(Context context) {
        Log.d(TAG, "assetPaths=");
        ApkAssets[] assets = context.getResources().getAssets().getApkAssets();
        for (ApkAssets a : assets) {
            Log.d(TAG, "    " + a.getAssetPath());
        }
    }
}
