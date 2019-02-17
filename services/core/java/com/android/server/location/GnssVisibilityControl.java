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

package com.android.server.location;

import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.StatsLog;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles GNSS non-framework location access user visibility and control.
 *
 * The state of the GnssVisibilityControl object must be accessed/modified through the Handler
 * thread only.
 */
class GnssVisibilityControl {
    private static final String TAG = "GnssVisibilityControl";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // Constants related to non-framework (NFW) location access permission proxy apps.
    private static final String NFW_PROXY_APP_PKG_ACTIVITY_NAME_SUFFIX =
            ".NonFrameworkLocationAccessActivity";
    private static final String NFW_INTENT_ACTION_NFW_LOCATION_ACCESS_SUFFIX =
            ".intent.action.NON_FRAMEWORK_LOCATION_ACCESS";
    private static final String NFW_INTENT_TYPE = "text/plain";

    private static final String LOCATION_PERMISSION_NAME =
            "android.permission.ACCESS_FINE_LOCATION";

    private static final String[] NO_LOCATION_ENABLED_PROXY_APPS = new String[0];

    // Wakelocks
    private static final String WAKELOCK_KEY = TAG;
    private static final long WAKELOCK_TIMEOUT_MILLIS = 60 * 1000;
    private final PowerManager.WakeLock mWakeLock;

    private final AppOpsManager mAppOps;
    private final PackageManager mPackageManager;

    private final Handler mHandler;
    private final Context mContext;

    private boolean mIsDeviceLocationSettingsEnabled;

    // Number of non-framework location access proxy apps is expected to be small (< 5).
    private static final int HASH_MAP_INITIAL_CAPACITY_PROXY_APP_TO_LOCATION_PERMISSIONS = 7;
    private HashMap<String, Boolean> mProxyAppToLocationPermissions = new HashMap<>(
            HASH_MAP_INITIAL_CAPACITY_PROXY_APP_TO_LOCATION_PERMISSIONS);

    private PackageManager.OnPermissionsChangedListener mOnPermissionsChangedListener =
            uid -> runOnHandler(() -> handlePermissionsChanged(uid));

    GnssVisibilityControl(Context context, Looper looper) {
        mContext = context;
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_KEY);
        mHandler = new Handler(looper);
        mAppOps = mContext.getSystemService(AppOpsManager.class);
        mPackageManager = mContext.getPackageManager();

        // Complete initialization as the first event to run in mHandler thread. After that,
        // all object state read/update events run in the mHandler thread.
        runOnHandler(this::handleInitialize);
    }

    void updateProxyApps(List<String> nfwLocationAccessProxyApps) {
        // NOTE: This class doesn't explicitly register and listen for SIM_STATE_CHANGED event
        //       but rather piggy backs on the GnssLocationProvider SIM_STATE_CHANGED handling
        //       so that the order of processing is preserved. GnssLocationProvider should
        //       first load the new config parameters for the new SIM and then call this method.
        runOnHandler(() -> handleUpdateProxyApps(nfwLocationAccessProxyApps));
    }

    void reportNfwNotification(String proxyAppPackageName, byte protocolStack,
            String otherProtocolStackName, byte requestor, String requestorId, byte responseType,
            boolean inEmergencyMode, boolean isCachedLocation) {
        runOnHandler(() -> handleNfwNotification(
                new NfwNotification(proxyAppPackageName, protocolStack, otherProtocolStackName,
                        requestor, requestorId, responseType, inEmergencyMode, isCachedLocation)));
    }

    private void handleInitialize() {
        disableNfwLocationAccess(); // Disable until config properties are loaded.
        listenForProxyAppsPackageUpdates();
        listenForDeviceLocationSettingsUpdate();
        mIsDeviceLocationSettingsEnabled = getDeviceLocationSettings();
    }

    private boolean getDeviceLocationSettings() {
        return mContext.getSystemService(LocationManager.class).isLocationEnabled();
    }

    private void listenForProxyAppsPackageUpdates() {
        // Listen for proxy apps package installation, removal events.
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        intentFilter.addDataScheme("package");
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) {
                    return;
                }

                switch (action) {
                    case Intent.ACTION_PACKAGE_ADDED:
                    case Intent.ACTION_PACKAGE_REMOVED:
                    case Intent.ACTION_PACKAGE_REPLACED:
                        String pkgName = intent.getData().getEncodedSchemeSpecificPart();
                        handleProxyAppPackageUpdate(pkgName, action);
                        break;
                }
            }
        }, UserHandle.ALL, intentFilter, null, mHandler);
    }

    private void listenForDeviceLocationSettingsUpdate() {
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCATION_MODE),
                true,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        handleDeviceLocationSettingsUpdated();
                    }
                }, UserHandle.USER_ALL);
    }

    private void handleProxyAppPackageUpdate(String pkgName, String action) {
        final Boolean locationPermission = mProxyAppToLocationPermissions.get(pkgName);
        if (locationPermission == null) {
            return; // ignore, pkgName is not one of the proxy apps in our list.
        }

        Log.i(TAG, "Proxy app " + pkgName + " package changed: " + action);
        final boolean updatedLocationPermission = hasLocationPermission(pkgName);
        if (locationPermission != updatedLocationPermission) {
            // Permission changed. So, update the GNSS HAL with the updated list.
            mProxyAppToLocationPermissions.put(pkgName, updatedLocationPermission);
            updateNfwLocationAccessProxyAppsInGnssHal();
        }
    }

    private void handleUpdateProxyApps(List<String> nfwLocationAccessProxyApps) {
        if (!isProxyAppListUpdated(nfwLocationAccessProxyApps)) {
            return;
        }

        if (nfwLocationAccessProxyApps.isEmpty()) {
            // Stop listening for app permission changes. Clear the app list in GNSS HAL.
            if (!mProxyAppToLocationPermissions.isEmpty()) {
                mPackageManager.removeOnPermissionsChangeListener(mOnPermissionsChangedListener);
                mProxyAppToLocationPermissions.clear();
                updateNfwLocationAccessProxyAppsInGnssHal();
            }
            return;
        }

        if (mProxyAppToLocationPermissions.isEmpty()) {
            mPackageManager.addOnPermissionsChangeListener(mOnPermissionsChangedListener);
        } else {
            mProxyAppToLocationPermissions.clear();
        }

        for (String proxApp : nfwLocationAccessProxyApps) {
            mProxyAppToLocationPermissions.put(proxApp, hasLocationPermission(proxApp));
        }

        updateNfwLocationAccessProxyAppsInGnssHal();
    }

    private boolean isProxyAppListUpdated(List<String> nfwLocationAccessProxyApps) {
        if (nfwLocationAccessProxyApps.size() != mProxyAppToLocationPermissions.size()) {
            return true;
        }

        for (String nfwLocationAccessProxyApp : nfwLocationAccessProxyApps) {
            if (!mProxyAppToLocationPermissions.containsKey(nfwLocationAccessProxyApp)) {
                return true;
            }
        }
        return false;
    }

    private void handleDeviceLocationSettingsUpdated() {
        final boolean enabled = getDeviceLocationSettings();
        Log.i(TAG, "Device location settings enabled: " + enabled);

        if (mIsDeviceLocationSettingsEnabled == enabled) {
            return;
        }

        mIsDeviceLocationSettingsEnabled = enabled;
        if (!mIsDeviceLocationSettingsEnabled) {
            disableNfwLocationAccess();
            return;
        }

        // When device location settings was disabled, we already set the proxy app list
        // to empty in GNSS HAL. Update only if the proxy app list is not empty.
        String[] locationPermissionEnabledProxyApps = getLocationPermissionEnabledProxyApps();
        if (locationPermissionEnabledProxyApps.length != 0) {
            setNfwLocationAccessProxyAppsInGnssHal(locationPermissionEnabledProxyApps);
        }
    }

    private void disableNfwLocationAccess() {
        setNfwLocationAccessProxyAppsInGnssHal(NO_LOCATION_ENABLED_PROXY_APPS);
    }

    // Represents NfwNotification structure in IGnssVisibilityControlCallback.hal
    private static class NfwNotification {
        private static final String KEY_PROTOCOL_STACK = "ProtocolStack";
        private static final String KEY_OTHER_PROTOCOL_STACK_NAME = "OtherProtocolStackName";
        private static final String KEY_REQUESTOR = "Requestor";
        private static final String KEY_REQUESTOR_ID = "RequestorId";
        private static final String KEY_RESPONSE_TYPE = "ResponseType";
        private static final String KEY_IN_EMERGENCY_MODE = "InEmergencyMode";
        private static final String KEY_IS_CACHED_LOCATION = "IsCachedLocation";

        // This must match with NfwResponseType enum in IGnssVisibilityControlCallback.hal.
        private static final byte NFW_RESPONSE_TYPE_REJECTED = 0;
        private static final byte NFW_RESPONSE_TYPE_ACCEPTED_NO_LOCATION_PROVIDED = 1;
        private static final byte NFW_RESPONSE_TYPE_ACCEPTED_LOCATION_PROVIDED = 2;

        private final String mProxyAppPackageName;
        private final byte mProtocolStack;
        private final String mOtherProtocolStackName;
        private final byte mRequestor;
        private final String mRequestorId;
        private final byte mResponseType;
        private final boolean mInEmergencyMode;
        private final boolean mIsCachedLocation;

        private NfwNotification(String proxyAppPackageName, byte protocolStack,
                String otherProtocolStackName, byte requestor, String requestorId,
                byte responseType, boolean inEmergencyMode, boolean isCachedLocation) {
            mProxyAppPackageName = proxyAppPackageName;
            mProtocolStack = protocolStack;
            mOtherProtocolStackName = otherProtocolStackName;
            mRequestor = requestor;
            mRequestorId = requestorId;
            mResponseType = responseType;
            mInEmergencyMode = inEmergencyMode;
            mIsCachedLocation = isCachedLocation;
        }

        private void copyFieldsToIntent(Intent intent) {
            intent.putExtra(KEY_PROTOCOL_STACK, mProtocolStack);
            if (!TextUtils.isEmpty(mOtherProtocolStackName)) {
                intent.putExtra(KEY_OTHER_PROTOCOL_STACK_NAME, mOtherProtocolStackName);
            }
            intent.putExtra(KEY_REQUESTOR, mRequestor);
            if (!TextUtils.isEmpty(mRequestorId)) {
                intent.putExtra(KEY_REQUESTOR_ID, mRequestorId);
            }
            intent.putExtra(KEY_RESPONSE_TYPE, mResponseType);
            intent.putExtra(KEY_IN_EMERGENCY_MODE, mInEmergencyMode);
            if (mResponseType == NFW_RESPONSE_TYPE_ACCEPTED_LOCATION_PROVIDED) {
                intent.putExtra(KEY_IS_CACHED_LOCATION, mIsCachedLocation);
            }
        }

        @SuppressLint("DefaultLocale")
        public String toString() {
            return String.format(
                    "[Notification] proxyAppPackageName: %s, protocolStack: %d"
                            + ", otherProtocolStackName: %s, requestor: %d, requestorId: %s"
                            + ", responseType: %d, inEmergencyMode: %b, isCachedLocation: %b",
                    mProxyAppPackageName, mProtocolStack, mOtherProtocolStackName,
                    mRequestor, mRequestorId, mResponseType, mInEmergencyMode, mIsCachedLocation);
        }

        private String getResponseTypeAsString() {
            switch (mResponseType) {
                case NFW_RESPONSE_TYPE_REJECTED:
                    return "REJECTED";
                case NFW_RESPONSE_TYPE_ACCEPTED_NO_LOCATION_PROVIDED:
                    return "ACCEPTED_NO_LOCATION_PROVIDED";
                case NFW_RESPONSE_TYPE_ACCEPTED_LOCATION_PROVIDED:
                    return "ACCEPTED_LOCATION_PROVIDED";
                default:
                    return "<Unknown>";
            }
        }
    }

    private void handlePermissionsChanged(int uid) {
        if (mProxyAppToLocationPermissions.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Boolean> entry : mProxyAppToLocationPermissions.entrySet()) {
            // Cannot cache uid since the application could be uninstalled and reinstalled.
            final String proxyApp = entry.getKey();
            final Integer nfwProxyAppUid = getApplicationUid(proxyApp);
            if (nfwProxyAppUid == null || nfwProxyAppUid != uid) {
                continue;
            }

            final boolean isLocationPermissionEnabled = hasLocationPermission(proxyApp);
            if (isLocationPermissionEnabled != entry.getValue()) {
                Log.i(TAG, "Location permission setting is changed to "
                        + (isLocationPermissionEnabled ? "enabled" : "disabled")
                        + " for non-framework location access proxy app "
                        + proxyApp);
                entry.setValue(isLocationPermissionEnabled);
                updateNfwLocationAccessProxyAppsInGnssHal();
                return;
            }
        }
    }

    private Integer getApplicationUid(String pkgName) {
        try {
            return mPackageManager.getApplicationInfo(pkgName, 0).uid;
        } catch (PackageManager.NameNotFoundException e) {
            if (DEBUG) {
                Log.d(TAG, "Non-framework location access proxy app " + pkgName + " is not found.");
            }
            return null;
        }
    }

    private boolean hasLocationPermission(String pkgName) {
        return mPackageManager.checkPermission(LOCATION_PERMISSION_NAME, pkgName)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void updateNfwLocationAccessProxyAppsInGnssHal() {
        if (!mIsDeviceLocationSettingsEnabled) {
            return; // Keep non-framework location access disabled.
        }
        setNfwLocationAccessProxyAppsInGnssHal(getLocationPermissionEnabledProxyApps());
    }

    private void setNfwLocationAccessProxyAppsInGnssHal(
            String[] locationPermissionEnabledProxyApps) {
        final String proxyAppsStr = Arrays.toString(locationPermissionEnabledProxyApps);
        Log.i(TAG, "Updating non-framework location access proxy apps in the GNSS HAL to: "
                + proxyAppsStr);
        boolean result = native_enable_nfw_location_access(locationPermissionEnabledProxyApps);
        if (!result) {
            Log.e(TAG, "Failed to update non-framework location access proxy apps in the"
                    + " GNSS HAL to: " + proxyAppsStr);
        }
    }

    private String[] getLocationPermissionEnabledProxyApps() {
        // Get a count of proxy apps with location permission enabled for array creation size.
        int countLocationPermissionEnabledProxyApps = 0;
        for (Boolean hasLocationPermissionEnabled : mProxyAppToLocationPermissions.values()) {
            if (hasLocationPermissionEnabled) {
                ++countLocationPermissionEnabledProxyApps;
            }
        }

        int i = 0;
        String[] locationPermissionEnabledProxyApps =
                new String[countLocationPermissionEnabledProxyApps];
        for (Map.Entry<String, Boolean> entry : mProxyAppToLocationPermissions.entrySet()) {
            final String proxyApp = entry.getKey();
            final boolean hasLocationPermissionEnabled = entry.getValue();
            if (hasLocationPermissionEnabled) {
                locationPermissionEnabledProxyApps[i++] = proxyApp;
            }
        }
        return locationPermissionEnabledProxyApps;
    }

    private void handleNfwNotification(NfwNotification nfwNotification) {
        if (DEBUG) Log.d(TAG, nfwNotification.toString());

        final String proxyAppPackageName = nfwNotification.mProxyAppPackageName;
        Boolean isLocationPermissionEnabled = mProxyAppToLocationPermissions.get(
                proxyAppPackageName);
        boolean isLocationRequestAccepted =
                nfwNotification.mResponseType != NfwNotification.NFW_RESPONSE_TYPE_REJECTED;
        boolean isPermissionMismatched;
        if (isLocationPermissionEnabled == null) {
            isPermissionMismatched = isLocationRequestAccepted;
        } else {
            isPermissionMismatched = (isLocationPermissionEnabled != isLocationRequestAccepted);
        }
        logEvent(nfwNotification, isPermissionMismatched);

        if (TextUtils.isEmpty(proxyAppPackageName)) {
            Log.e(TAG, "ProxyAppPackageName field is not set. Not sending intent to proxy app for "
                    + nfwNotification);
            return;
        }

        if (isLocationPermissionEnabled == null) {
            // App is not in the configured list.
            Log.e(TAG, "Could not find proxy app with name: " + proxyAppPackageName + " in the "
                    + "value specified for config parameter: "
                    + GnssConfiguration.CONFIG_NFW_PROXY_APPS + ". Not sending intent to proxy app"
                    + " for " + nfwNotification);
            return;
        }

        // Send intent to non-framework location proxy app with notification information.
        final Intent intent = new Intent(
                proxyAppPackageName + NFW_INTENT_ACTION_NFW_LOCATION_ACCESS_SUFFIX);
        final String proxAppActivityName =
                proxyAppPackageName + NFW_PROXY_APP_PKG_ACTIVITY_NAME_SUFFIX;
        intent.setClassName(proxyAppPackageName, proxAppActivityName);
        intent.setType(NFW_INTENT_TYPE);
        nfwNotification.copyFieldsToIntent(intent);

        // Check if the proxy app is still installed.
        final Integer clsAppUid = getApplicationUid(proxyAppPackageName);
        if (clsAppUid == null || intent.resolveActivity(mPackageManager) == null) {
            Log.i(TAG, "Proxy application " + proxyAppPackageName + " and/or activity "
                    + proxAppActivityName + " is not found. Not sending"
                    + " intent to proxy app for " + nfwNotification);
            return;
        }

        // Display location icon attributed to this proxy app.
        mAppOps.noteOpNoThrow(AppOpsManager.OP_FINE_LOCATION, clsAppUid, proxyAppPackageName);

        // Log proxy app permission mismatch between framework and GNSS HAL.
        if (isPermissionMismatched) {
            Log.w(TAG, "Permission mismatch. Framework proxy app " + proxyAppPackageName
                    + " location permission is set to " + isLocationPermissionEnabled
                    + " but GNSS non-framework location access response type is "
                    + nfwNotification.getResponseTypeAsString() + " for " + nfwNotification);
        }

        // Notify proxy app.
        try {
            if (DEBUG) {
                Log.d(TAG, "Sending non-framework location access notification intent: " + intent);
            }
            mContext.startActivityAsUser(intent, UserHandle.getUserHandleForUid(clsAppUid));
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "Activity not found. Failed to send non-framework location access"
                    + " notification intent to proxy app activity: " + proxAppActivityName);
        }
    }

    private void logEvent(NfwNotification notification, boolean isPermissionMismatched) {
        StatsLog.write(StatsLog.GNSS_NFW_NOTIFICATION_REPORTED,
                notification.mProxyAppPackageName,
                notification.mProtocolStack,
                notification.mOtherProtocolStackName,
                notification.mRequestor,
                notification.mRequestorId,
                notification.mResponseType,
                notification.mInEmergencyMode,
                notification.mIsCachedLocation,
                isPermissionMismatched);
    }

    private void runOnHandler(Runnable event) {
        // Hold a wake lock until this message is delivered.
        // Note that this assumes the message will not be removed from the queue before
        // it is handled (otherwise the wake lock would be leaked).
        mWakeLock.acquire(WAKELOCK_TIMEOUT_MILLIS);
        if (!mHandler.post(runEventAndReleaseWakeLock(event))) {
            mWakeLock.release();
        }
    }

    private Runnable runEventAndReleaseWakeLock(Runnable event) {
        return () -> {
            try {
                event.run();
            } finally {
                mWakeLock.release();
            }
        };
    }

    private native boolean native_enable_nfw_location_access(String[] proxyApps);
}
