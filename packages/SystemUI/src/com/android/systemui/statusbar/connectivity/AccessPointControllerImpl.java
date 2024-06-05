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

package com.android.systemui.statusbar.connectivity;

import android.content.Intent;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.IndentingPrintWriter;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.android.systemui.settings.UserTracker;
import com.android.wifitrackerlib.MergedCarrierEntry;
import com.android.wifitrackerlib.WifiEntry;
import com.android.wifitrackerlib.WifiPickerTracker;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/** */
public class AccessPointControllerImpl implements AccessPointController,
        WifiPickerTracker.WifiPickerTrackerCallback,
        LifecycleOwner {
    private static final String TAG = "AccessPointController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // This string extra specifies a network to open the connect dialog on, so the user can enter
    // network credentials.  This is used by quick settings for secured networks.
    private static final String EXTRA_START_CONNECT_SSID = "wifi_start_connect_ssid";

    private static final int[] ICONS = WifiIcons.WIFI_FULL_ICONS;

    private final ArrayList<AccessPointCallback> mCallbacks = new ArrayList<AccessPointCallback>();
    private final UserManager mUserManager;
    private final UserTracker mUserTracker;
    private final Executor mMainExecutor;

    private @Nullable WifiPickerTracker mWifiPickerTracker;
    private WifiPickerTrackerFactory mWifiPickerTrackerFactory;

    private final LifecycleRegistry mLifecycle = new LifecycleRegistry(this);

    private int mCurrentUser;

    public AccessPointControllerImpl(
            UserManager userManager,
            UserTracker userTracker,
            Executor mainExecutor,
            WifiPickerTrackerFactory wifiPickerTrackerFactory
    ) {
        mUserManager = userManager;
        mUserTracker = userTracker;
        mCurrentUser = userTracker.getUserId();
        mMainExecutor = mainExecutor;
        mWifiPickerTrackerFactory = wifiPickerTrackerFactory;
        mMainExecutor.execute(() -> mLifecycle.setCurrentState(Lifecycle.State.CREATED));
    }

    /**
     * Initializes the controller.
     *
     * Will create a WifiPickerTracker associated to this controller.
     */
    public void init() {
        if (mWifiPickerTracker == null) {
            mWifiPickerTracker = mWifiPickerTrackerFactory.create(this.getLifecycle(), this, TAG);
        }
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return mLifecycle;
    }

    @Override
    protected void finalize() throws Throwable {
        mMainExecutor.execute(() -> mLifecycle.setCurrentState(Lifecycle.State.DESTROYED));
        super.finalize();
    }

    public boolean canConfigWifi() {
        if (!mWifiPickerTrackerFactory.isSupported()) return false;
        return !mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_WIFI,
                new UserHandle(mCurrentUser));
    }

    public boolean canConfigMobileData() {
        return !mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
                UserHandle.of(mCurrentUser)) && mUserTracker.getUserInfo().isAdmin();
    }

    void onUserSwitched(int newUserId) {
        mCurrentUser = newUserId;
    }

    @Override
    public void addAccessPointCallback(AccessPointCallback callback) {
        if (callback == null || mCallbacks.contains(callback)) return;
        if (DEBUG) Log.d(TAG, "addCallback " + callback);
        mCallbacks.add(callback);
        if (mCallbacks.size() == 1) {
            mMainExecutor.execute(() -> mLifecycle.setCurrentState(Lifecycle.State.STARTED));
        }
    }

    @Override
    public void removeAccessPointCallback(AccessPointCallback callback) {
        if (callback == null) return;
        if (DEBUG) Log.d(TAG, "removeCallback " + callback);
        mCallbacks.remove(callback);
        if (mCallbacks.isEmpty()) {
            mMainExecutor.execute(() -> mLifecycle.setCurrentState(Lifecycle.State.CREATED));
        }
    }

    @Override
    public void scanForAccessPoints() {
        if (mWifiPickerTracker == null) {
            fireAccessPointsCallback(Collections.emptyList());
            return;
        }
        List<WifiEntry> entries = mWifiPickerTracker.getWifiEntries();
        WifiEntry connectedEntry = mWifiPickerTracker.getConnectedWifiEntry();
        if (connectedEntry != null) {
            entries.add(0, connectedEntry);
        }
        fireAccessPointsCallback(entries);
    }

    @Override
    public MergedCarrierEntry getMergedCarrierEntry() {
        if (mWifiPickerTracker == null) {
            fireAccessPointsCallback(Collections.emptyList());
            return null;
        }
        return mWifiPickerTracker.getMergedCarrierEntry();
    }

    @Override
    public int getIcon(WifiEntry ap) {
        int level = ap.getLevel();
        return ICONS[Math.max(0, level)];
    }

    /**
     * Connects to a {@link WifiEntry} if it's saved or does not require security.
     *
     * If the entry is not saved and requires security, will trigger
     * {@link AccessPointCallback#onSettingsActivityTriggered}.
     * @param ap
     * @return {@code true} if {@link AccessPointCallback#onSettingsActivityTriggered} is triggered
     */
    public boolean connect(@Nullable WifiEntry ap) {
        if (ap == null) return false;
        if (DEBUG) {
            if (ap.getWifiConfiguration() != null) {
                Log.d(TAG, "connect networkId=" + ap.getWifiConfiguration().networkId);
            } else {
                Log.d(TAG, "connect to unsaved network " + ap.getTitle());
            }
        }
        if (ap.isSaved()) {
            ap.connect(mConnectCallback);
        } else {
            // Unknown network, need to add it.
            if (ap.getSecurity() != WifiEntry.SECURITY_NONE) {
                Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                intent.putExtra(EXTRA_START_CONNECT_SSID, ap.getSsid());
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                fireSettingsIntentCallback(intent);
                return true;
            } else {
                ap.connect(mConnectCallback);
            }
        }
        return false;
    }

    private void fireSettingsIntentCallback(Intent intent) {
        for (AccessPointCallback callback : mCallbacks) {
            callback.onSettingsActivityTriggered(intent);
        }
    }

    private void fireAccessPointsCallback(List<WifiEntry> aps) {
        for (AccessPointCallback callback : mCallbacks) {
            callback.onAccessPointsChanged(aps);
        }
    }

    private void fireWifiScanCallback(boolean isScan) {
        for (AccessPointCallback callback : mCallbacks) {
            callback.onWifiScan(isScan);
        }
    }

    void dump(PrintWriter pw) {
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw);
        ipw.println("AccessPointControllerImpl:");
        ipw.increaseIndent();
        ipw.println("Callbacks: " + Arrays.toString(mCallbacks.toArray()));
        ipw.println("WifiPickerTracker: " + mWifiPickerTracker.toString());
        if (mWifiPickerTracker != null && !mCallbacks.isEmpty()) {
            ipw.println("Connected: " + mWifiPickerTracker.getConnectedWifiEntry());
            ipw.println("Other wifi entries: "
                    + Arrays.toString(mWifiPickerTracker.getWifiEntries().toArray()));
        } else if (mWifiPickerTracker != null) {
            ipw.println("WifiPickerTracker not started, cannot get reliable entries");
        }
        ipw.decreaseIndent();
    }

    @Override
    public void onWifiStateChanged() {
        scanForAccessPoints();
    }

    @Override
    public void onWifiEntriesChanged() {
        scanForAccessPoints();
    }

    @Override
    public void onWifiEntriesChanged(@WifiPickerTracker.WifiEntriesChangedReason int reason) {
        onWifiEntriesChanged();
        if (reason == WifiPickerTracker.WIFI_ENTRIES_CHANGED_REASON_SCAN_RESULTS) {
            fireWifiScanCallback(false /* isScan */);
        }
    }

    @Override
    public void onNumSavedNetworksChanged() {
        // Do nothing
    }

    @Override
    public void onNumSavedSubscriptionsChanged() {
        // Do nothing
    }

    @Override
    public void onScanRequested() {
        fireWifiScanCallback(true /* isScan */);
    }

    private final WifiEntry.ConnectCallback mConnectCallback = new WifiEntry.ConnectCallback() {
        @Override
        public void onConnectResult(int status) {
            if (status == CONNECT_STATUS_SUCCESS) {
                if (DEBUG) Log.d(TAG, "connect success");
            } else {
                if (DEBUG) Log.d(TAG, "connect failure reason=" + status);
            }
        }
    };
}
