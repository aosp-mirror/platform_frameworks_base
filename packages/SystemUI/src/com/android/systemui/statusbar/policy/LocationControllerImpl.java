/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import static android.app.AppOpsManager.OP_COARSE_LOCATION;
import static android.app.AppOpsManager.OP_FINE_LOCATION;
import static android.app.AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION;

import static com.android.settingslib.Utils.updateLocationEnabled;

import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.PermissionChecker;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.BootCompleteCache;
import com.android.systemui.appops.AppOpItem;
import com.android.systemui.appops.AppOpsController;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.DeviceConfigProxy;
import com.android.systemui.util.Utils;
import com.android.systemui.util.settings.SecureSettings;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * A controller to manage changes of location related states and update the views accordingly.
 */
@SysUISingleton
public class LocationControllerImpl extends BroadcastReceiver implements LocationController,
        AppOpsController.Callback {

    private final Context mContext;
    private final AppOpsController mAppOpsController;
    private final DeviceConfigProxy mDeviceConfigProxy;
    private final BootCompleteCache mBootCompleteCache;
    private final UserTracker mUserTracker;
    private final UiEventLogger mUiEventLogger;
    private final H mHandler;
    private final Handler mBackgroundHandler;
    private final PackageManager mPackageManager;
    private final ContentObserver mContentObserver;
    private final SecureSettings mSecureSettings;

    private boolean mAreActiveLocationRequests;
    private boolean mShouldDisplayAllAccesses;
    private boolean mShowSystemAccessesFlag;
    private boolean mShowSystemAccessesSetting;
    private boolean mExperimentStarted;

    @Inject
    public LocationControllerImpl(Context context, AppOpsController appOpsController,
            DeviceConfigProxy deviceConfigProxy,
            @Main Looper mainLooper, @Background Handler backgroundHandler,
            BroadcastDispatcher broadcastDispatcher, BootCompleteCache bootCompleteCache,
            UserTracker userTracker, PackageManager packageManager, UiEventLogger uiEventLogger,
            SecureSettings secureSettings) {
        mContext = context;
        mAppOpsController = appOpsController;
        mDeviceConfigProxy = deviceConfigProxy;
        mBootCompleteCache = bootCompleteCache;
        mHandler = new H(mainLooper);
        mUserTracker = userTracker;
        mUiEventLogger = uiEventLogger;
        mSecureSettings = secureSettings;
        mBackgroundHandler = backgroundHandler;
        mPackageManager = packageManager;
        mShouldDisplayAllAccesses = getAllAccessesSetting();
        mShowSystemAccessesFlag = getShowSystemFlag();
        mShowSystemAccessesSetting = getShowSystemSetting();
        mExperimentStarted = getExperimentStarted();
        toggleSystemSettingIfExperimentJustStarted();

        mContentObserver = new ContentObserver(mBackgroundHandler) {
            @Override
            public void onChange(boolean selfChange) {
                mShowSystemAccessesSetting = getShowSystemSetting();
            }
        };

        // Register to listen for changes in Settings.Secure settings.
        mSecureSettings.registerContentObserver(
                Settings.Secure.LOCATION_SHOW_SYSTEM_OPS, mContentObserver);

        // Register to listen for changes in DeviceConfig settings.
        mDeviceConfigProxy.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_PRIVACY,
                backgroundHandler::post,
                properties -> {
                    // Update the Device Config flag which controls the experiment to display
                    // location accesses.
                    mShouldDisplayAllAccesses = getAllAccessesSetting();
                    // Update the Device Config flag which controls the experiment to display
                    // system location accesses.
                    mShowSystemAccessesFlag = getShowSystemFlag();
                    // Update the local flag for the system accesses experiment and potentially
                    // update the behavior based on the flag value.
                    toggleSystemSettingIfExperimentJustStarted();
                    updateActiveLocationRequests();
                });

        // Register to listen for changes in location settings.
        IntentFilter filter = new IntentFilter();
        filter.addAction(LocationManager.MODE_CHANGED_ACTION);
        broadcastDispatcher.registerReceiverWithHandler(this, filter, mHandler, UserHandle.ALL);

        // Listen to all accesses and filter the ones interested in based on flags.
        mAppOpsController.addCallback(
                new int[]{OP_COARSE_LOCATION, OP_FINE_LOCATION, OP_MONITOR_HIGH_POWER_LOCATION},
                this);

        // Examine the current location state and initialize the status view.
        backgroundHandler.post(this::updateActiveLocationRequests);
    }

    /**
     * Add a callback to listen for changes in location settings.
     */
    @Override
    public void addCallback(@NonNull LocationChangeCallback cb) {
        mHandler.obtainMessage(H.MSG_ADD_CALLBACK, cb).sendToTarget();
        mHandler.sendEmptyMessage(H.MSG_LOCATION_SETTINGS_CHANGED);
    }

    @Override
    public void removeCallback(@NonNull LocationChangeCallback cb) {
        mHandler.obtainMessage(H.MSG_REMOVE_CALLBACK, cb).sendToTarget();
    }

    /**
     * Enable or disable location in settings.
     *
     * <p>This will attempt to enable/disable every type of location setting
     * (e.g. high and balanced power).
     *
     * <p>If enabling, a user consent dialog will pop up prompting the user to accept.
     * If the user doesn't accept, network location won't be enabled.
     *
     * @return true if attempt to change setting was successful.
     */
    public boolean setLocationEnabled(boolean enabled) {
        // QuickSettings always runs as the owner, so specifically set the settings
        // for the current foreground user.
        int currentUserId = mUserTracker.getUserId();
        if (isUserLocationRestricted(currentUserId)) {
            return false;
        }
        // When enabling location, a user consent dialog will pop up, and the
        // setting won't be fully enabled until the user accepts the agreement.
        updateLocationEnabled(mContext, enabled, currentUserId,
                Settings.Secure.LOCATION_CHANGER_QUICK_SETTINGS);
        return true;
    }

    /**
     * Returns true if location is enabled in settings. Will return false if
     * {@link LocationManager} service has not been completely initialized
     */
    public boolean isLocationEnabled() {
        // QuickSettings always runs as the owner, so specifically retrieve the settings
        // for the current foreground user.
        LocationManager locationManager =
                (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        return mBootCompleteCache.isBootComplete() && locationManager.isLocationEnabledForUser(
                mUserTracker.getUserHandle());
    }

    @Override
    public boolean isLocationActive() {
        return mAreActiveLocationRequests;
    }

    /**
     * Returns true if the current user is restricted from using location.
     */
    private boolean isUserLocationRestricted(int userId) {
        final UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        return um.hasUserRestriction(UserManager.DISALLOW_SHARE_LOCATION,
                UserHandle.of(userId));
    }

    private boolean getAllAccessesSetting() {
        return mDeviceConfigProxy.getBoolean(DeviceConfig.NAMESPACE_PRIVACY,
                SystemUiDeviceConfigFlags.PROPERTY_LOCATION_INDICATORS_SMALL_ENABLED, false);
    }

    private boolean getShowSystemFlag() {
        return mDeviceConfigProxy.getBoolean(DeviceConfig.NAMESPACE_PRIVACY,
                SystemUiDeviceConfigFlags.PROPERTY_LOCATION_INDICATORS_SHOW_SYSTEM, false);
    }

    private boolean getShowSystemSetting() {
        return mSecureSettings.getInt(Settings.Secure.LOCATION_SHOW_SYSTEM_OPS, 0) == 1;
    }

    private boolean getExperimentStarted() {
        return mSecureSettings
                .getInt(Settings.Secure.LOCATION_INDICATOR_EXPERIMENT_STARTED, 0) == 1;
    }

    private void toggleSystemSettingIfExperimentJustStarted() {
        // mShowSystemAccessesFlag indicates whether the Device Config flag is flipped
        // by an experiment. mExperimentStarted is the local device value which indicates the last
        // value the device has seen for the Device Config flag.
        // The local device value is needed to determine that the Device Config flag was just
        // flipped, as the experiment behavior should only happen once after the experiment is
        // enabled.
        if (mShowSystemAccessesFlag && !mExperimentStarted) {
            // If the Device Config flag is enabled, but the local device setting is not then the
            // experiment just started. Update the local flag to match and enable the experiment
            // behavior by flipping the show system setting value.
            mSecureSettings.putInt(Settings.Secure.LOCATION_INDICATOR_EXPERIMENT_STARTED, 1);
            mSecureSettings.putInt(Settings.Secure.LOCATION_SHOW_SYSTEM_OPS, 1);
            mExperimentStarted = true;
        } else if (!mShowSystemAccessesFlag && mExperimentStarted) {
            // If the Device Config flag is disabled, but the local device flag is enabled then
            // update the local device flag to match.
            mSecureSettings.putInt(Settings.Secure.LOCATION_INDICATOR_EXPERIMENT_STARTED, 0);
            mExperimentStarted = false;
        }
    }

    /**
     * Returns true if there currently exist active high power location requests.
     */
    @VisibleForTesting
    protected boolean areActiveHighPowerLocationRequests() {
        List<AppOpItem> appOpsItems = mAppOpsController.getActiveAppOps();

        final int numItems = appOpsItems.size();
        for (int i = 0; i < numItems; i++) {
            if (appOpsItems.get(i).getCode() == OP_MONITOR_HIGH_POWER_LOCATION) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns true if there currently exist active location requests.
     */
    @VisibleForTesting
    protected void areActiveLocationRequests() {
        if (!mShouldDisplayAllAccesses) {
            return;
        }
        boolean hadActiveLocationRequests = mAreActiveLocationRequests;
        boolean shouldDisplay = false;
        boolean systemAppOp = false;
        boolean nonSystemAppOp = false;
        boolean isSystemApp;

        List<AppOpItem> appOpsItems = mAppOpsController.getActiveAppOps();
        final List<UserInfo> profiles = mUserTracker.getUserProfiles();
        final int numItems = appOpsItems.size();
        for (int i = 0; i < numItems; i++) {
            if (appOpsItems.get(i).getCode() == OP_FINE_LOCATION
                    || appOpsItems.get(i).getCode() == OP_COARSE_LOCATION) {
                isSystemApp = isSystemApp(profiles, appOpsItems.get(i));
                if (isSystemApp) {
                    systemAppOp = true;
                } else {
                    nonSystemAppOp = true;
                }

                shouldDisplay = mShowSystemAccessesSetting || shouldDisplay || !isSystemApp;
            }
        }

        boolean highPowerOp = areActiveHighPowerLocationRequests();
        mAreActiveLocationRequests = shouldDisplay;
        if (mAreActiveLocationRequests != hadActiveLocationRequests) {
            mHandler.sendEmptyMessage(H.MSG_LOCATION_ACTIVE_CHANGED);
        }

        // Log each of the types of location access that would cause the location indicator to be
        // shown, regardless of device's setting state. This is used to understand how often a
        // user would see the location indicator based on any settings state the device could be in.
        if (!hadActiveLocationRequests && (highPowerOp || systemAppOp || nonSystemAppOp)) {
            if (highPowerOp) {
                mUiEventLogger.log(
                        LocationIndicatorEvent.LOCATION_INDICATOR_MONITOR_HIGH_POWER);
            }
            if (systemAppOp) {
                mUiEventLogger.log(LocationIndicatorEvent.LOCATION_INDICATOR_SYSTEM_APP);
            }
            if (nonSystemAppOp) {
                mUiEventLogger.log(LocationIndicatorEvent.LOCATION_INDICATOR_NON_SYSTEM_APP);
            }
        }
    }

    private boolean isSystemApp(List<UserInfo> profiles, AppOpItem item) {
        final String permission = AppOpsManager.opToPermission(item.getCode());
        UserHandle user = UserHandle.getUserHandleForUid(item.getUid());

        // Don't show apps belonging to background users except managed users.
        boolean foundUser = false;
        final int numProfiles = profiles.size();
        for (int i = 0; i < numProfiles; i++) {
            if (profiles.get(i).getUserHandle().equals(user)) {
                foundUser = true;
            }
        }
        if (!foundUser) {
            return true;
        }

        final int permissionFlags = mPackageManager.getPermissionFlags(
                permission, item.getPackageName(), user);
        if (PermissionChecker.checkPermissionForPreflight(mContext, permission,
                PermissionChecker.PID_UNKNOWN, item.getUid(), item.getPackageName())
                == PermissionChecker.PERMISSION_GRANTED) {
            return (permissionFlags
                    & PackageManager.FLAG_PERMISSION_USER_SENSITIVE_WHEN_GRANTED)
                    == 0;
        } else {
            return (permissionFlags
                    & PackageManager.FLAG_PERMISSION_USER_SENSITIVE_WHEN_DENIED) == 0;
        }
    }

    // Reads the active location requests from either OP_MONITOR_HIGH_POWER_LOCATION,
    // OP_FINE_LOCATION, or OP_COARSE_LOCATION and updates the status view if necessary.
    private void updateActiveLocationRequests() {
        if (mShouldDisplayAllAccesses) {
            mBackgroundHandler.post(this::areActiveLocationRequests);
        } else {
            boolean hadActiveLocationRequests = mAreActiveLocationRequests;
            mAreActiveLocationRequests = areActiveHighPowerLocationRequests();
            if (mAreActiveLocationRequests != hadActiveLocationRequests) {
                mHandler.sendEmptyMessage(H.MSG_LOCATION_ACTIVE_CHANGED);
                if (mAreActiveLocationRequests) {
                    // Log that the indicator was shown for a high power op.
                    mUiEventLogger.log(
                            LocationIndicatorEvent.LOCATION_INDICATOR_MONITOR_HIGH_POWER);
                }
            }
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (LocationManager.MODE_CHANGED_ACTION.equals(intent.getAction())) {
            mHandler.locationSettingsChanged();
        }
    }

    @Override
    public void onActiveStateChanged(int code, int uid, String packageName, boolean active) {
        updateActiveLocationRequests();
    }

    private final class H extends Handler {
        private static final int MSG_LOCATION_SETTINGS_CHANGED = 1;
        private static final int MSG_LOCATION_ACTIVE_CHANGED = 2;
        private static final int MSG_ADD_CALLBACK = 3;
        private static final int MSG_REMOVE_CALLBACK = 4;

        private ArrayList<LocationChangeCallback> mSettingsChangeCallbacks = new ArrayList<>();

        H(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_LOCATION_SETTINGS_CHANGED:
                    locationSettingsChanged();
                    break;
                case MSG_LOCATION_ACTIVE_CHANGED:
                    locationActiveChanged();
                    break;
                case MSG_ADD_CALLBACK:
                    mSettingsChangeCallbacks.add((LocationChangeCallback) msg.obj);
                    break;
                case MSG_REMOVE_CALLBACK:
                    mSettingsChangeCallbacks.remove((LocationChangeCallback) msg.obj);
                    break;

            }
        }

        private void locationActiveChanged() {
            Utils.safeForeach(mSettingsChangeCallbacks,
                    cb -> cb.onLocationActiveChanged(mAreActiveLocationRequests));
        }

        private void locationSettingsChanged() {
            boolean isEnabled = isLocationEnabled();
            Utils.safeForeach(mSettingsChangeCallbacks,
                    cb -> cb.onLocationSettingsChanged(isEnabled));
        }
    }

    /**
     * Enum for events which prompt the location indicator to appear.
     */
    enum LocationIndicatorEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "Location indicator shown for high power access")
        LOCATION_INDICATOR_MONITOR_HIGH_POWER(935),
        @UiEvent(doc = "Location indicator shown for system app access")
        LOCATION_INDICATOR_SYSTEM_APP(936),
        @UiEvent(doc = "Location indicator shown for non system app access")
        LOCATION_INDICATOR_NON_SYSTEM_APP(937);

        private final int mId;
        LocationIndicatorEvent(int id) {
            mId = id;
        }
        @Override public int getId() {
            return mId;
        }
    }
}