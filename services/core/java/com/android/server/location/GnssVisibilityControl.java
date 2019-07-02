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

import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.StatsLog;

import com.android.internal.R;
import com.android.internal.location.GpsNetInitiatedHandler;
import com.android.internal.notification.SystemNotificationChannels;

import java.util.Arrays;
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

    private static final String LOCATION_PERMISSION_NAME =
            "android.permission.ACCESS_FINE_LOCATION";

    private static final String[] NO_LOCATION_ENABLED_PROXY_APPS = new String[0];

    // Max wait time for synchronous method onGpsEnabledChanged() to run.
    private static final long ON_GPS_ENABLED_CHANGED_TIMEOUT_MILLIS = 3 * 1000;

    // How long to display location icon for each non-framework non-emergency location request.
    private static final long LOCATION_ICON_DISPLAY_DURATION_MILLIS = 5 * 1000;

    // Wakelocks
    private static final String WAKELOCK_KEY = TAG;
    private static final long WAKELOCK_TIMEOUT_MILLIS = 60 * 1000;
    private final PowerManager.WakeLock mWakeLock;

    private final AppOpsManager mAppOps;
    private final PackageManager mPackageManager;

    private final Handler mHandler;
    private final Context mContext;
    private final GpsNetInitiatedHandler mNiHandler;

    private boolean mIsGpsEnabled;

    private static final class ProxyAppState {
        private boolean mHasLocationPermission;
        private boolean mIsLocationIconOn;

        private ProxyAppState(boolean hasLocationPermission) {
            mHasLocationPermission = hasLocationPermission;
        }
    }

    // Number of non-framework location access proxy apps is expected to be small (< 5).
    private static final int ARRAY_MAP_INITIAL_CAPACITY_PROXY_APPS_STATE = 5;
    private ArrayMap<String, ProxyAppState> mProxyAppsState = new ArrayMap<>(
            ARRAY_MAP_INITIAL_CAPACITY_PROXY_APPS_STATE);

    private PackageManager.OnPermissionsChangedListener mOnPermissionsChangedListener =
            uid -> runOnHandler(() -> handlePermissionsChanged(uid));

    GnssVisibilityControl(Context context, Looper looper, GpsNetInitiatedHandler niHandler) {
        mContext = context;
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_KEY);
        mHandler = new Handler(looper);
        mNiHandler = niHandler;
        mAppOps = mContext.getSystemService(AppOpsManager.class);
        mPackageManager = mContext.getPackageManager();

        // Complete initialization as the first event to run in mHandler thread. After that,
        // all object state read/update events run in the mHandler thread.
        runOnHandler(this::handleInitialize);
    }

    void onGpsEnabledChanged(boolean isEnabled) {
        // The GnssLocationProvider's methods: handleEnable() calls this method after native_init()
        // and handleDisable() calls this method before native_cleanup(). This method must be
        // executed synchronously so that the NFW location access permissions are disabled in
        // the HAL before native_cleanup() method is called.
        //
        // NOTE: Since improper use of runWithScissors() method can result in deadlocks, the method
        // doc recommends limiting its use to cases where some initialization steps need to be
        // executed in sequence before continuing which fits this scenario.
        if (mHandler.runWithScissors(() -> handleGpsEnabledChanged(isEnabled),
                ON_GPS_ENABLED_CHANGED_TIMEOUT_MILLIS)) {
            return;
        }

        // After timeout, the method remains posted in the queue and hence future enable/disable
        // calls to this method will all get executed in the correct sequence. But this timeout
        // situation should not even arise because runWithScissors() will run in the caller's
        // thread without blocking as it is the same thread as mHandler's thread.
        if (!isEnabled) {
            Log.w(TAG, "Native call to disable non-framework location access in GNSS HAL may"
                    + " get executed after native_cleanup().");
        }
    }

    void reportNfwNotification(String proxyAppPackageName, byte protocolStack,
            String otherProtocolStackName, byte requestor, String requestorId, byte responseType,
            boolean inEmergencyMode, boolean isCachedLocation) {
        runOnHandler(() -> handleNfwNotification(
                new NfwNotification(proxyAppPackageName, protocolStack, otherProtocolStackName,
                        requestor, requestorId, responseType, inEmergencyMode, isCachedLocation)));
    }

    void onConfigurationUpdated(GnssConfiguration configuration) {
        // The configuration object must be accessed only in the caller thread and not in mHandler.
        List<String> nfwLocationAccessProxyApps = configuration.getProxyApps();
        runOnHandler(() -> handleUpdateProxyApps(nfwLocationAccessProxyApps));
    }

    private void handleInitialize() {
        listenForProxyAppsPackageUpdates();
    }

    private void listenForProxyAppsPackageUpdates() {
        // Listen for proxy apps package installation, removal events.
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
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
                    case Intent.ACTION_PACKAGE_CHANGED:
                        String pkgName = intent.getData().getEncodedSchemeSpecificPart();
                        handleProxyAppPackageUpdate(pkgName, action);
                        break;
                }
            }
        }, UserHandle.ALL, intentFilter, null, mHandler);
    }

    private void handleProxyAppPackageUpdate(String pkgName, String action) {
        final ProxyAppState proxyAppState = mProxyAppsState.get(pkgName);
        if (proxyAppState == null) {
            return; // ignore, pkgName is not one of the proxy apps in our list.
        }

        if (DEBUG) Log.d(TAG, "Proxy app " + pkgName + " package changed: " + action);
        final boolean updatedLocationPermission = shouldEnableLocationPermissionInGnssHal(pkgName);
        if (proxyAppState.mHasLocationPermission != updatedLocationPermission) {
            // Permission changed. So, update the GNSS HAL with the updated list.
            Log.i(TAG, "Proxy app " + pkgName + " location permission changed."
                    + " IsLocationPermissionEnabled: " + updatedLocationPermission);
            proxyAppState.mHasLocationPermission = updatedLocationPermission;
            updateNfwLocationAccessProxyAppsInGnssHal();
        }
    }

    private void handleUpdateProxyApps(List<String> nfwLocationAccessProxyApps) {
        if (!isProxyAppListUpdated(nfwLocationAccessProxyApps)) {
            return;
        }

        if (nfwLocationAccessProxyApps.isEmpty()) {
            // Stop listening for app permission changes. Clear the app list in GNSS HAL.
            if (!mProxyAppsState.isEmpty()) {
                mPackageManager.removeOnPermissionsChangeListener(mOnPermissionsChangedListener);
                resetProxyAppsState();
                updateNfwLocationAccessProxyAppsInGnssHal();
            }
            return;
        }

        if (mProxyAppsState.isEmpty()) {
            mPackageManager.addOnPermissionsChangeListener(mOnPermissionsChangedListener);
        } else {
            resetProxyAppsState();
        }

        for (String proxyAppPkgName : nfwLocationAccessProxyApps) {
            ProxyAppState proxyAppState = new ProxyAppState(shouldEnableLocationPermissionInGnssHal(
                    proxyAppPkgName));
            mProxyAppsState.put(proxyAppPkgName, proxyAppState);
        }

        updateNfwLocationAccessProxyAppsInGnssHal();
    }

    private void resetProxyAppsState() {
        // Clear location icons displayed.
        for (Map.Entry<String, ProxyAppState> entry : mProxyAppsState.entrySet()) {
            ProxyAppState proxyAppState = entry.getValue();
            if (!proxyAppState.mIsLocationIconOn) {
                continue;
            }

            mHandler.removeCallbacksAndMessages(proxyAppState);
            final ApplicationInfo proxyAppInfo = getProxyAppInfo(entry.getKey());
            if (proxyAppInfo != null) {
                clearLocationIcon(proxyAppState, proxyAppInfo.uid, entry.getKey());
            }
        }
        mProxyAppsState.clear();
    }

    private boolean isProxyAppListUpdated(List<String> nfwLocationAccessProxyApps) {
        if (nfwLocationAccessProxyApps.size() != mProxyAppsState.size()) {
            return true;
        }

        for (String nfwLocationAccessProxyApp : nfwLocationAccessProxyApps) {
            if (!mProxyAppsState.containsKey(nfwLocationAccessProxyApp)) {
                return true;
            }
        }
        return false;
    }

    private void handleGpsEnabledChanged(boolean isGpsEnabled) {
        if (DEBUG) {
            Log.d(TAG, "handleGpsEnabledChanged, mIsGpsEnabled: " + mIsGpsEnabled
                    + ", isGpsEnabled: " + isGpsEnabled);
        }

        // The proxy app list in the GNSS HAL needs to be configured if it restarts after
        // a crash. So, update HAL irrespective of the previous GPS enabled state.
        mIsGpsEnabled = isGpsEnabled;
        if (!mIsGpsEnabled) {
            disableNfwLocationAccess();
            return;
        }

        setNfwLocationAccessProxyAppsInGnssHal(getLocationPermissionEnabledProxyApps());
    }

    private void disableNfwLocationAccess() {
        setNfwLocationAccessProxyAppsInGnssHal(NO_LOCATION_ENABLED_PROXY_APPS);
    }

    // Represents NfwNotification structure in IGnssVisibilityControlCallback.hal
    private static class NfwNotification {
        // These must match with NfwResponseType enum in IGnssVisibilityControlCallback.hal.
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

        @SuppressLint("DefaultLocale")
        public String toString() {
            return String.format(
                    "{proxyAppPackageName: %s, protocolStack: %d, otherProtocolStackName: %s, "
                            + "requestor: %d, requestorId: %s, responseType: %s, inEmergencyMode:"
                            + " %b, isCachedLocation: %b}",
                    mProxyAppPackageName, mProtocolStack, mOtherProtocolStackName, mRequestor,
                    mRequestorId, getResponseTypeAsString(), mInEmergencyMode, mIsCachedLocation);
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

        private boolean isRequestAccepted() {
            return mResponseType != NfwNotification.NFW_RESPONSE_TYPE_REJECTED;
        }

        private boolean isLocationProvided() {
            return mResponseType == NfwNotification.NFW_RESPONSE_TYPE_ACCEPTED_LOCATION_PROVIDED;
        }

        private boolean isRequestAttributedToProxyApp() {
            return !TextUtils.isEmpty(mProxyAppPackageName);
        }

        private boolean isEmergencyRequestNotification() {
            return mInEmergencyMode && !isRequestAttributedToProxyApp();
        }
    }

    private void handlePermissionsChanged(int uid) {
        if (mProxyAppsState.isEmpty()) {
            return;
        }

        for (Map.Entry<String, ProxyAppState> entry : mProxyAppsState.entrySet()) {
            final String proxyAppPkgName = entry.getKey();
            final ApplicationInfo proxyAppInfo = getProxyAppInfo(proxyAppPkgName);
            if (proxyAppInfo == null || proxyAppInfo.uid != uid) {
                continue;
            }

            final boolean isLocationPermissionEnabled = shouldEnableLocationPermissionInGnssHal(
                    proxyAppPkgName);
            ProxyAppState proxyAppState = entry.getValue();
            if (isLocationPermissionEnabled != proxyAppState.mHasLocationPermission) {
                Log.i(TAG, "Proxy app " + proxyAppPkgName + " location permission changed."
                        + " IsLocationPermissionEnabled: " + isLocationPermissionEnabled);
                proxyAppState.mHasLocationPermission = isLocationPermissionEnabled;
                updateNfwLocationAccessProxyAppsInGnssHal();
            }
            return;
        }
    }

    private ApplicationInfo getProxyAppInfo(String proxyAppPkgName) {
        try {
            return mPackageManager.getApplicationInfo(proxyAppPkgName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            if (DEBUG) Log.d(TAG, "Proxy app " + proxyAppPkgName + " is not found.");
            return null;
        }
    }

    private boolean shouldEnableLocationPermissionInGnssHal(String proxyAppPkgName) {
        return isProxyAppInstalled(proxyAppPkgName) && hasLocationPermission(proxyAppPkgName);
    }

    private boolean isProxyAppInstalled(String pkgName) {
        ApplicationInfo proxyAppInfo = getProxyAppInfo(pkgName);
        return (proxyAppInfo != null) && proxyAppInfo.enabled;
    }

    private boolean hasLocationPermission(String pkgName) {
        return mPackageManager.checkPermission(LOCATION_PERMISSION_NAME, pkgName)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void updateNfwLocationAccessProxyAppsInGnssHal() {
        if (!mIsGpsEnabled) {
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
        for (ProxyAppState proxyAppState : mProxyAppsState.values()) {
            if (proxyAppState.mHasLocationPermission) {
                ++countLocationPermissionEnabledProxyApps;
            }
        }

        int i = 0;
        String[] locationPermissionEnabledProxyApps =
                new String[countLocationPermissionEnabledProxyApps];
        for (Map.Entry<String, ProxyAppState> entry : mProxyAppsState.entrySet()) {
            final String proxyApp = entry.getKey();
            if (entry.getValue().mHasLocationPermission) {
                locationPermissionEnabledProxyApps[i++] = proxyApp;
            }
        }
        return locationPermissionEnabledProxyApps;
    }

    private void handleNfwNotification(NfwNotification nfwNotification) {
        if (DEBUG) Log.d(TAG, "Non-framework location access notification: " + nfwNotification);

        if (nfwNotification.isEmergencyRequestNotification()) {
            handleEmergencyNfwNotification(nfwNotification);
            return;
        }

        final String proxyAppPkgName = nfwNotification.mProxyAppPackageName;
        final ProxyAppState proxyAppState = mProxyAppsState.get(proxyAppPkgName);
        final boolean isLocationRequestAccepted = nfwNotification.isRequestAccepted();
        final boolean isPermissionMismatched = isPermissionMismatched(proxyAppState,
                nfwNotification);
        logEvent(nfwNotification, isPermissionMismatched);

        if (!nfwNotification.isRequestAttributedToProxyApp()) {
            // Handle cases where GNSS HAL implementation correctly rejected NFW location request.
            // 1. GNSS HAL implementation doesn't provide location to any NFW location use cases.
            //    There is no Location Attribution App configured in the framework.
            // 2. GNSS HAL implementation doesn't provide location to some NFW location use cases.
            //    Location Attribution Apps are configured only for the supported NFW location
            //    use cases. All other use cases which are not supported (and always rejected) by
            //    the GNSS HAL implementation will have proxyAppPackageName set to empty string.
            if (!isLocationRequestAccepted) {
                if (DEBUG) {
                    Log.d(TAG, "Non-framework location request rejected. ProxyAppPackageName field"
                            + " is not set in the notification: " + nfwNotification + ". Number of"
                            + " configured proxy apps: " + mProxyAppsState.size());
                }
                return;
            }

            Log.e(TAG, "ProxyAppPackageName field is not set. AppOps service not notified"
                    + " for notification: " + nfwNotification);
            return;
        }

        if (proxyAppState == null) {
            Log.w(TAG, "Could not find proxy app " + proxyAppPkgName + " in the value specified for"
                    + " config parameter: " + GnssConfiguration.CONFIG_NFW_PROXY_APPS
                    + ". AppOps service not notified for notification: " + nfwNotification);
            return;
        }

        // Display location icon attributed to this proxy app.
        final ApplicationInfo proxyAppInfo = getProxyAppInfo(proxyAppPkgName);
        if (proxyAppInfo == null) {
            Log.e(TAG, "Proxy app " + proxyAppPkgName + " is not found. AppOps service not "
                    + "notified for notification: " + nfwNotification);
            return;
        }

        if (nfwNotification.isLocationProvided()) {
            showLocationIcon(proxyAppState, nfwNotification, proxyAppInfo.uid, proxyAppPkgName);
            mAppOps.noteOpNoThrow(AppOpsManager.OP_FINE_LOCATION, proxyAppInfo.uid,
                    proxyAppPkgName);
        }

        // Log proxy app permission mismatch between framework and GNSS HAL.
        if (isPermissionMismatched) {
            Log.w(TAG, "Permission mismatch. Proxy app " + proxyAppPkgName
                    + " location permission is set to " + proxyAppState.mHasLocationPermission
                    + " and GNSS HAL enabled is set to " + mIsGpsEnabled
                    + " but GNSS non-framework location access response type is "
                    + nfwNotification.getResponseTypeAsString() + " for notification: "
                    + nfwNotification);
        }
    }

    private boolean isPermissionMismatched(ProxyAppState proxyAppState,
            NfwNotification nfwNotification) {
        // Non-framework non-emergency location requests must be accepted only when IGnss.hal
        // is enabled and the proxy app has location permission.
        final boolean isLocationRequestAccepted = nfwNotification.isRequestAccepted();
        return (proxyAppState == null || !mIsGpsEnabled) ? isLocationRequestAccepted
                        : (proxyAppState.mHasLocationPermission != isLocationRequestAccepted);
    }

    private void showLocationIcon(ProxyAppState proxyAppState, NfwNotification nfwNotification,
            int uid, String proxyAppPkgName) {
        // If we receive a new NfwNotification before the location icon is turned off for the
        // previous notification, update the timer to extend the location icon display duration.
        final boolean isLocationIconOn = proxyAppState.mIsLocationIconOn;
        if (!isLocationIconOn) {
            if (!updateLocationIcon(/* displayLocationIcon = */ true, uid, proxyAppPkgName)) {
                Log.w(TAG, "Failed to show Location icon for notification: " + nfwNotification);
                return;
            }
            proxyAppState.mIsLocationIconOn = true;
        } else {
            // Extend timer by canceling the current one and starting a new one.
            mHandler.removeCallbacksAndMessages(proxyAppState);
        }

        // Start timer to turn off location icon. proxyAppState is used as a token to cancel timer.
        if (DEBUG) {
            Log.d(TAG, "Location icon on. " + (isLocationIconOn ? "Extending" : "Setting")
                    + " icon display timer. Uid: " + uid + ", proxyAppPkgName: " + proxyAppPkgName);
        }
        if (!mHandler.postDelayed(() -> handleLocationIconTimeout(proxyAppPkgName),
                /* token = */ proxyAppState, LOCATION_ICON_DISPLAY_DURATION_MILLIS)) {
            clearLocationIcon(proxyAppState, uid, proxyAppPkgName);
            Log.w(TAG, "Failed to show location icon for the full duration for notification: "
                    + nfwNotification);
        }
    }

    private void handleLocationIconTimeout(String proxyAppPkgName) {
        // Get uid again instead of using the one provided in startOp() call as the app could have
        // been uninstalled and reinstalled during the timeout duration (unlikely in real world).
        final ApplicationInfo proxyAppInfo = getProxyAppInfo(proxyAppPkgName);
        if (proxyAppInfo != null) {
            clearLocationIcon(mProxyAppsState.get(proxyAppPkgName), proxyAppInfo.uid,
                    proxyAppPkgName);
        }
    }

    private void clearLocationIcon(@Nullable ProxyAppState proxyAppState, int uid,
            String proxyAppPkgName) {
        updateLocationIcon(/* displayLocationIcon = */ false, uid, proxyAppPkgName);
        if (proxyAppState != null) proxyAppState.mIsLocationIconOn = false;
        if (DEBUG) {
            Log.d(TAG, "Location icon off. Uid: " + uid + ", proxyAppPkgName: " + proxyAppPkgName);
        }
    }

    private boolean updateLocationIcon(boolean displayLocationIcon, int uid,
            String proxyAppPkgName) {
        if (displayLocationIcon) {
            // Need two calls to startOp() here with different op code so that the proxy app shows
            // up in the recent location requests page and also the location icon gets displayed.
            if (mAppOps.startOpNoThrow(AppOpsManager.OP_MONITOR_LOCATION, uid,
                    proxyAppPkgName) != AppOpsManager.MODE_ALLOWED) {
                return false;
            }
            if (mAppOps.startOpNoThrow(AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION, uid,
                    proxyAppPkgName) != AppOpsManager.MODE_ALLOWED) {
                mAppOps.finishOp(AppOpsManager.OP_MONITOR_LOCATION, uid, proxyAppPkgName);
                return false;
            }
        } else {
            mAppOps.finishOp(AppOpsManager.OP_MONITOR_LOCATION, uid, proxyAppPkgName);
            mAppOps.finishOp(AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION, uid, proxyAppPkgName);
        }
        sendHighPowerMonitoringBroadcast();
        return true;
    }

    private void sendHighPowerMonitoringBroadcast() {
        // Send an intent to notify that a high power request has been added/removed so that
        // the SystemUi checks the state of AppOps and updates the location icon accordingly.
        Intent intent = new Intent(LocationManager.HIGH_POWER_REQUEST_CHANGE_ACTION);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void handleEmergencyNfwNotification(NfwNotification nfwNotification) {
        boolean isPermissionMismatched = false;
        if (!nfwNotification.isRequestAccepted()) {
            Log.e(TAG, "Emergency non-framework location request incorrectly rejected."
                    + " Notification: " + nfwNotification);
            isPermissionMismatched = true;
        }

        if (!mNiHandler.getInEmergency()) {
            Log.w(TAG, "Emergency state mismatch. Device currently not in user initiated emergency"
                    + " session. Notification: " + nfwNotification);
            isPermissionMismatched = true;
        }

        logEvent(nfwNotification, isPermissionMismatched);

        if (nfwNotification.isLocationProvided()) {
            postEmergencyLocationUserNotification(nfwNotification);
        }
    }

    private void postEmergencyLocationUserNotification(NfwNotification nfwNotification) {
        // Emulate deprecated IGnssNi.hal user notification of emergency NI requests.
        NotificationManager notificationManager = (NotificationManager) mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) {
            Log.w(TAG, "Could not notify user of emergency location request. Notification: "
                    + nfwNotification);
            return;
        }

        notificationManager.notifyAsUser(/* tag= */ null, /* notificationId= */ 0,
                createEmergencyLocationUserNotification(mContext), UserHandle.ALL);
    }

    private static Notification createEmergencyLocationUserNotification(Context context) {
        // NOTE: Do not reuse the returned notification object as it will not reflect
        //       changes to notification text when the system language is changed.
        final String firstLineText = context.getString(R.string.gpsNotifTitle);
        final String secondLineText =  context.getString(R.string.global_action_emergency);
        final String accessibilityServicesText = firstLineText + " (" + secondLineText + ")";
        return new Notification.Builder(context, SystemNotificationChannels.NETWORK_ALERTS)
                .setSmallIcon(com.android.internal.R.drawable.stat_sys_gps_on)
                .setWhen(0)
                .setOngoing(true)
                .setAutoCancel(true)
                .setColor(context.getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .setDefaults(0)
                .setTicker(accessibilityServicesText)
                .setContentTitle(firstLineText)
                .setContentText(secondLineText)
                .setContentIntent(PendingIntent.getBroadcast(context, 0, new Intent(), 0))
                .build();
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
