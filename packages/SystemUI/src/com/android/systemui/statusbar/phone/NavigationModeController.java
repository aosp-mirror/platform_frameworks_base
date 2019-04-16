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
import static android.os.UserHandle.USER_CURRENT;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON_OVERLAY;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY;

import android.content.BroadcastReceiver;
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
    private static final boolean DEBUG = true;

    public interface ModeChangedListener {
        void onNavigationModeChanged(int mode);
    }

    private final Context mContext;
    private final IOverlayManager mOverlayManager;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final UiOffloadThread mUiOffloadThread;

    private SparseBooleanArray mRestoreGesturalNavBarMode = new SparseBooleanArray();

    private int mMode = NAV_BAR_MODE_3BUTTON;
    private ArrayList<ModeChangedListener> mListeners = new ArrayList<>();

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(ACTION_OVERLAY_CHANGED)) {
                if (DEBUG) {
                    Log.d(TAG, "ACTION_OVERLAY_CHANGED");
                }
                updateCurrentInteractionMode(true /* notify */);
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

    @Inject
    public NavigationModeController(Context context,
            DeviceProvisionedController deviceProvisionedController,
            UiOffloadThread uiOffloadThread) {
        mContext = context;
        mOverlayManager = IOverlayManager.Stub.asInterface(
                ServiceManager.getService(Context.OVERLAY_SERVICE));
        mUiOffloadThread = uiOffloadThread;
        mDeviceProvisionedController = deviceProvisionedController;
        mDeviceProvisionedController.addCallback(mDeviceProvisionedCallback);

        IntentFilter overlayFilter = new IntentFilter(ACTION_OVERLAY_CHANGED);
        overlayFilter.addDataScheme("package");
        overlayFilter.addDataSchemeSpecificPart("android", PatternMatcher.PATTERN_LITERAL);
        mContext.registerReceiverAsUser(mReceiver, UserHandle.ALL, overlayFilter, null, null);

        updateCurrentInteractionMode(false /* notify */);

        // Check if we need to defer enabling gestural nav
        deferGesturalNavOverlayIfNecessary();
    }

    public void updateCurrentInteractionMode(boolean notify) {
        Context context = getCurrentUserContext();
        int mode = getCurrentInteractionMode(context);
        mMode = mode;
        if (DEBUG) {
            Log.e(TAG, "updateCurrentInteractionMode: mode=" + mMode
                    + " contextUser=" + context.getUserId());
            dumpAssetPaths(context);
        }

        if (notify) {
            for (int i = 0; i < mListeners.size(); i++) {
                mListeners.get(i).onNavigationModeChanged(mode);
            }
        }
    }

    public int addListener(ModeChangedListener listener) {
        mListeners.add(listener);
        return getCurrentInteractionMode(mContext);
    }

    public void removeListener(ModeChangedListener listener) {
        mListeners.remove(listener);
    }

    private int getCurrentInteractionMode(Context context) {
        int mode = context.getResources().getInteger(
                com.android.internal.R.integer.config_navBarInteractionMode);
        return mode;
    }

    private Context getCurrentUserContext() {
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
        dumpAssetPaths(getCurrentUserContext());
    }

    private void dumpAssetPaths(Context context) {
        Log.d(TAG, "assetPaths=");
        ApkAssets[] assets = context.getResources().getAssets().getApkAssets();
        for (ApkAssets a : assets) {
            Log.d(TAG, "    " + a.getAssetPath());
        }
    }
}
