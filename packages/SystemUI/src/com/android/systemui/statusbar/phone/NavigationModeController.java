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

import static android.app.Activity.RESULT_CANCELED;
import static android.app.Activity.RESULT_OK;
import static android.app.admin.DevicePolicyManager.STATE_USER_UNMANAGED;
import static android.content.Intent.ACTION_OVERLAY_CHANGED;
import static android.content.Intent.ACTION_PREFERRED_ACTIVITY_CHANGED;
import static android.content.pm.PackageManager.FEATURE_DEVICE_ADMIN;
import static android.os.UserHandle.USER_CURRENT;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON_OVERLAY;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY;

import static com.android.systemui.shared.system.QuickStepContract.ACTION_ENABLE_GESTURE_NAV;
import static com.android.systemui.shared.system.QuickStepContract.ACTION_ENABLE_GESTURE_NAV_RESULT;
import static com.android.systemui.shared.system.QuickStepContract.EXTRA_RESULT_INTENT;

import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.om.IOverlayManager;
import android.content.pm.PackageManager;
import android.content.res.ApkAssets;
import android.os.PatternMatcher;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.util.Log;
import android.util.SparseBooleanArray;

import com.android.systemui.Dumpable;
import com.android.systemui.UiOffloadThread;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;

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

                    // When switching users, defer enabling the gestural nav overlay until the user
                    // is all set up
                    deferGesturalNavOverlayIfNecessary();
                }
            };

    private BroadcastReceiver mEnableGestureNavReceiver;

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

        updateCurrentInteractionMode(false /* notify */);

        // Check if we need to defer enabling gestural nav
        deferGesturalNavOverlayIfNecessary();
    }

    private void removeEnableGestureNavListener() {
        if (mEnableGestureNavReceiver != null) {
            if (DEBUG) {
                Log.d(TAG, "mEnableGestureNavReceiver unregistered");
            }
            mContext.unregisterReceiver(mEnableGestureNavReceiver);
            mEnableGestureNavReceiver = null;
        }
    }

    private boolean setGestureModeOverlayForMainLauncher() {
        removeEnableGestureNavListener();
        if (getCurrentInteractionMode(mCurrentUserContext) == NAV_BAR_MODE_GESTURAL) {
            // Already in gesture mode
            return true;
        }

        Log.d(TAG, "Switching system navigation to full-gesture mode:"
                + " contextUser="
                + mCurrentUserContext.getUserId());

        setModeOverlay(NAV_BAR_MODE_GESTURAL_OVERLAY, USER_CURRENT);
        return true;
    }

    private boolean enableGestureNav(Intent intent) {
        if (!(intent.getParcelableExtra(EXTRA_RESULT_INTENT) instanceof PendingIntent)) {
            Log.e(TAG, "No callback pending intent was attached");
            return false;
        }

        PendingIntent callback = intent.getParcelableExtra(EXTRA_RESULT_INTENT);
        Intent callbackIntent = callback.getIntent();
        if (callbackIntent == null
                || !ACTION_ENABLE_GESTURE_NAV_RESULT.equals(callbackIntent.getAction())) {
            Log.e(TAG, "Invalid callback intent");
            return false;
        }
        String callerPackage = callback.getCreatorPackage();
        UserHandle callerUser = callback.getCreatorUserHandle();

        DevicePolicyManager dpm = mCurrentUserContext.getSystemService(DevicePolicyManager.class);
        ComponentName ownerComponent = dpm.getDeviceOwnerComponentOnCallingUser();

        if (ownerComponent != null) {
            // Verify that the caller is the owner component
            if (!ownerComponent.getPackageName().equals(callerPackage)
                    || !mCurrentUserContext.getUser().equals(callerUser)) {
                Log.e(TAG, "Callback must be from the device owner");
                return false;
            }
        } else {
            UserHandle callerParent = mCurrentUserContext.getSystemService(UserManager.class)
                    .getProfileParent(callerUser);
            if (callerParent == null || !callerParent.equals(mCurrentUserContext.getUser())) {
                Log.e(TAG, "Callback must be from a managed user");
                return false;
            }
            ComponentName profileOwner = dpm.getProfileOwnerAsUser(callerUser);
            if (profileOwner == null || !profileOwner.getPackageName().equals(callerPackage)) {
                Log.e(TAG, "Callback must be from the profile owner");
                return false;
            }
        }

        return setGestureModeOverlayForMainLauncher();
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

    private boolean supportsDeviceAdmin() {
        return mContext.getPackageManager().hasSystemFeature(FEATURE_DEVICE_ADMIN);
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
            removeEnableGestureNavListener();
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
            removeEnableGestureNavListener();
            return;
        }

        // If the default is gestural, force-enable three button mode until the device is
        // provisioned
        setModeOverlay(NAV_BAR_MODE_3BUTTON_OVERLAY, USER_CURRENT);
        mRestoreGesturalNavBarMode.put(userId, true);

        if (supportsDeviceAdmin() && mEnableGestureNavReceiver == null) {
            mEnableGestureNavReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (DEBUG) {
                        Log.d(TAG, "ACTION_ENABLE_GESTURE_NAV");
                    }
                    setResultCode(enableGestureNav(intent) ? RESULT_OK : RESULT_CANCELED);
                }
            };
            // Register for all users so that we can get managed users as well
            mContext.registerReceiverAsUser(mEnableGestureNavReceiver, UserHandle.ALL,
                    new IntentFilter(ACTION_ENABLE_GESTURE_NAV), null, null);
            if (DEBUG) {
                Log.d(TAG, "mEnableGestureNavReceiver registered");
            }
        }
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
            if (!supportsDeviceAdmin()
                    || mCurrentUserContext.getSystemService(DevicePolicyManager.class)
                    .getUserProvisioningState() == STATE_USER_UNMANAGED) {
                setGestureModeOverlayForMainLauncher();
            } else {
                if (DEBUG) {
                    Log.d(TAG, "Not restoring to gesture nav for managed user");
                }
            }
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
    }

    private void dumpAssetPaths(Context context) {
        Log.d(TAG, "assetPaths=");
        ApkAssets[] assets = context.getResources().getAssets().getApkAssets();
        for (ApkAssets a : assets) {
            Log.d(TAG, "    " + a.getAssetPath());
        }
    }
}
