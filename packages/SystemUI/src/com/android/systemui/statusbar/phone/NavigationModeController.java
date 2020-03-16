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
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON_OVERLAY;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.content.pm.PackageManager;
import android.content.res.ApkAssets;
import android.os.PatternMatcher;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.util.Log;
import android.util.SparseBooleanArray;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Executor;

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
    private Context mCurrentUserContext;
    private final IOverlayManager mOverlayManager;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final Executor mUiBgExecutor;

    private SparseBooleanArray mRestoreGesturalNavBarMode = new SparseBooleanArray();

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

    @Inject
    public NavigationModeController(Context context,
            DeviceProvisionedController deviceProvisionedController,
            @UiBackground Executor uiBgExecutor) {
        mContext = context;
        mCurrentUserContext = context;
        mOverlayManager = IOverlayManager.Stub.asInterface(
                ServiceManager.getService(Context.OVERLAY_SERVICE));
        mUiBgExecutor = uiBgExecutor;
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

    private boolean setGestureModeOverlayForMainLauncher() {
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

    public void updateCurrentInteractionMode(boolean notify) {
        mCurrentUserContext = getCurrentUserContext();
        int mode = getCurrentInteractionMode(mCurrentUserContext);
        if (mode == NAV_BAR_MODE_GESTURAL) {
            switchToDefaultGestureNavOverlayIfNecessary();
        }
        mUiBgExecutor.execute(() -> {
            Settings.Secure.putString(mCurrentUserContext.getContentResolver(),
                    Secure.NAVIGATION_MODE, String.valueOf(mode));
        });
        if (DEBUG) {
            Log.e(TAG, "updateCurrentInteractionMode: mode=" + mode);
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
            Log.d(TAG, "getCurrentInteractionMode: mode=" + mode
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
            Log.e(TAG, "Failed to create package context", e);
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
            setGestureModeOverlayForMainLauncher();
            mRestoreGesturalNavBarMode.put(userId, false);
        }
    }

    private void switchToDefaultGestureNavOverlayIfNecessary() {
        final int userId = mCurrentUserContext.getUserId();
        try {
            final IOverlayManager om = mOverlayManager;
            final OverlayInfo info = om.getOverlayInfo(NAV_BAR_MODE_GESTURAL_OVERLAY, userId);
            if (info != null && !info.isEnabled()) {
                // Enable the default gesture nav overlay, and move the back gesture inset scale to
                // Settings.Secure for left and right sensitivity.
                final int curInset = mCurrentUserContext.getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.config_backGestureInset);
                om.setEnabledExclusiveInCategory(NAV_BAR_MODE_GESTURAL_OVERLAY, userId);
                final int defInset = mCurrentUserContext.getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.config_backGestureInset);

                final float scale = defInset == 0 ? 1.0f : ((float) curInset) / defInset;
                Settings.Secure.putFloat(mCurrentUserContext.getContentResolver(),
                        Secure.BACK_GESTURE_INSET_SCALE_LEFT, scale);
                Settings.Secure.putFloat(mCurrentUserContext.getContentResolver(),
                        Secure.BACK_GESTURE_INSET_SCALE_RIGHT, scale);
                if (DEBUG) {
                    Log.v(TAG, "Moved back sensitivity for user " + userId + " to scale " + scale);
                }
            }
        } catch (SecurityException | IllegalStateException | RemoteException e) {
            Log.e(TAG, "Failed to switch to default gesture nav overlay for user " + userId);
        }
    }

    public void setModeOverlay(String overlayPkg, int userId) {
        mUiBgExecutor.execute(() -> {
            try {
                mOverlayManager.setEnabledExclusiveInCategory(overlayPkg, userId);
                if (DEBUG) {
                    Log.d(TAG, "setModeOverlay: overlayPackage=" + overlayPkg
                            + " userId=" + userId);
                }
            } catch (SecurityException | IllegalStateException | RemoteException e) {
                Log.e(TAG, "Failed to enable overlay " + overlayPkg + " for user " + userId);
            }
        });
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NavigationModeController:");
        pw.println("  mode=" + getCurrentInteractionMode(mCurrentUserContext));
        String defaultOverlays = "";
        try {
            defaultOverlays = String.join(", ", mOverlayManager.getDefaultOverlayPackages());
        } catch (RemoteException e) {
            defaultOverlays = "failed_to_fetch";
        }
        pw.println("  defaultOverlays=" + defaultOverlays);
        pw.println("  restoreGesturalNavMode:");
        for (int i = 0; i < mRestoreGesturalNavBarMode.size(); i++) {
            pw.println("    userId=" + mRestoreGesturalNavBarMode.keyAt(i)
                    + " shouldRestore=" + mRestoreGesturalNavBarMode.valueAt(i));
        }
        dumpAssetPaths(mCurrentUserContext);
    }

    private void dumpAssetPaths(Context context) {
        Log.d(TAG, "  contextUser=" + mCurrentUserContext.getUserId());
        Log.d(TAG, "  assetPaths=");
        ApkAssets[] assets = context.getResources().getAssets().getApkAssets();
        for (ApkAssets a : assets) {
            Log.d(TAG, "    " + a.getAssetPath());
        }
    }
}
