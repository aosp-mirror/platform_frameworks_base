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

package com.android.systemui.statusbar.policy;

import static android.net.TetheringManager.TETHERING_WIFI;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.TetheringManager;
import android.net.TetheringManager.TetheringRequest;
import android.net.wifi.WifiClient;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.UserManager;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.internal.util.ConcurrentUtils;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * Controller used to retrieve information related to a hotspot.
 */
@SysUISingleton
public class HotspotControllerImpl implements HotspotController, WifiManager.SoftApCallback {

    private static final String TAG = "HotspotController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final ArrayList<Callback> mCallbacks = new ArrayList<>();
    private final TetheringManager mTetheringManager;
    private final WifiManager mWifiManager;
    private final Handler mMainHandler;
    private final Context mContext;
    private final UserTracker mUserTracker;

    private int mHotspotState;
    private volatile int mNumConnectedDevices;
    // Assume tethering is available until told otherwise
    private volatile boolean mIsTetheringSupported = true;
    private final boolean mIsTetheringSupportedConfig;
    private volatile boolean mHasTetherableWifiRegexs = true;
    private boolean mWaitingForTerminalState;

    private TetheringManager.TetheringEventCallback mTetheringCallback =
            new TetheringManager.TetheringEventCallback() {
                @Override
                public void onTetheringSupported(boolean supported) {
                    if (mIsTetheringSupported != supported) {
                        mIsTetheringSupported = supported;
                        fireHotspotAvailabilityChanged();
                    }
                }

                @Override
                public void onTetherableInterfaceRegexpsChanged(
                        TetheringManager.TetheringInterfaceRegexps reg) {
                    final boolean newValue = reg.getTetherableWifiRegexs().size() != 0;
                    if (mHasTetherableWifiRegexs != newValue) {
                        mHasTetherableWifiRegexs = newValue;
                        fireHotspotAvailabilityChanged();
                    }
                }
            };

    /**
     * Controller used to retrieve information related to a hotspot.
     */
    @Inject
    public HotspotControllerImpl(
            Context context,
            UserTracker userTracker,
            @Main Handler mainHandler,
            @Background Handler backgroundHandler,
            DumpManager dumpManager) {
        mContext = context;
        mUserTracker = userTracker;
        mTetheringManager = context.getSystemService(TetheringManager.class);
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        mMainHandler = mainHandler;
        mIsTetheringSupportedConfig = context.getResources()
                .getBoolean(R.bool.config_show_wifi_tethering);
        if (mIsTetheringSupportedConfig) {
            mTetheringManager.registerTetheringEventCallback(
                    new HandlerExecutor(backgroundHandler), mTetheringCallback);
        }
        dumpManager.registerDumpable(getClass().getSimpleName(), this);
    }

    /**
     * Whether hotspot is currently supported.
     *
     * This may return {@code true} immediately on creation of the controller, but may be updated
     * later as capabilities are collected from System Server.
     *
     * Callbacks from this controllers will notify if the state changes.
     *
     * @return {@code true} if hotspot is supported (or we haven't been told it's not)
     * @see #addCallback
     */
    @Override
    public boolean isHotspotSupported() {
        return mIsTetheringSupportedConfig && mIsTetheringSupported && mHasTetherableWifiRegexs
                && UserManager.get(mContext).isUserAdmin(mUserTracker.getUserId());
    }

    public void dump(PrintWriter pw, String[] args) {
        pw.println("HotspotController state:");
        pw.print("  available="); pw.println(isHotspotSupported());
        pw.print("  mHotspotState="); pw.println(stateToString(mHotspotState));
        pw.print("  mNumConnectedDevices="); pw.println(mNumConnectedDevices);
        pw.print("  mWaitingForTerminalState="); pw.println(mWaitingForTerminalState);
    }

    private static String stateToString(int hotspotState) {
        switch (hotspotState) {
            case WifiManager.WIFI_AP_STATE_DISABLED:
                return "DISABLED";
            case WifiManager.WIFI_AP_STATE_DISABLING:
                return "DISABLING";
            case WifiManager.WIFI_AP_STATE_ENABLED:
                return "ENABLED";
            case WifiManager.WIFI_AP_STATE_ENABLING:
                return "ENABLING";
            case WifiManager.WIFI_AP_STATE_FAILED:
                return "FAILED";
        }
        return null;
    }

    /**
     * Adds {@code callback} to the controller. The controller will update the callback on state
     * changes. It will immediately trigger the callback added to notify current state.
     */
    @Override
    public void addCallback(@NonNull Callback callback) {
        synchronized (mCallbacks) {
            if (callback == null || mCallbacks.contains(callback)) return;
            if (DEBUG) Log.d(TAG, "addCallback " + callback);
            mCallbacks.add(callback);
            if (mWifiManager != null) {
                if (mCallbacks.size() == 1) {
                    mWifiManager.registerSoftApCallback(new HandlerExecutor(mMainHandler), this);
                } else {
                    // mWifiManager#registerSoftApCallback triggers a call to onNumClientsChanged
                    // on the Main Handler. In order to always update the callback on added, we
                    // make this call when adding callbacks after the first.
                    mMainHandler.post(() ->
                            callback.onHotspotChanged(isHotspotEnabled(), mNumConnectedDevices));
                }
            }
        }
    }

    @Override
    public void removeCallback(@NonNull Callback callback) {
        if (callback == null) return;
        if (DEBUG) Log.d(TAG, "removeCallback " + callback);
        synchronized (mCallbacks) {
            mCallbacks.remove(callback);
            if (mCallbacks.isEmpty() && mWifiManager != null) {
                mWifiManager.unregisterSoftApCallback(this);
            }
        }
    }

    @Override
    public boolean isHotspotEnabled() {
        return mHotspotState == WifiManager.WIFI_AP_STATE_ENABLED;
    }

    @Override
    public boolean isHotspotTransient() {
        return mWaitingForTerminalState || (mHotspotState == WifiManager.WIFI_AP_STATE_ENABLING);
    }

    @Override
    public void setHotspotEnabled(boolean enabled) {
        if (mWaitingForTerminalState) {
            if (DEBUG) Log.d(TAG, "Ignoring setHotspotEnabled; waiting for terminal state.");
            return;
        }
        if (enabled) {
            mWaitingForTerminalState = true;
            if (DEBUG) Log.d(TAG, "Starting tethering");
            mTetheringManager.startTethering(new TetheringRequest.Builder(
                    TETHERING_WIFI).setShouldShowEntitlementUi(false).build(),
                    ConcurrentUtils.DIRECT_EXECUTOR,
                    new TetheringManager.StartTetheringCallback() {
                        @Override
                        public void onTetheringFailed(final int result) {
                            if (DEBUG) Log.d(TAG, "onTetheringFailed");
                            maybeResetSoftApState();
                            fireHotspotChangedCallback();
                        }
                    });
        } else {
            mTetheringManager.stopTethering(ConnectivityManager.TETHERING_WIFI);
        }
    }

    @Override
    public int getNumConnectedDevices() {
        return mNumConnectedDevices;
    }

    /**
     * Sends a hotspot changed callback.
     * Be careful when calling over multiple threads, especially if one of them is the main thread
     * (as it can be blocked).
     */
    private void fireHotspotChangedCallback() {
        List<Callback> list;
        synchronized (mCallbacks) {
            list = new ArrayList<>(mCallbacks);
        }
        for (Callback callback : list) {
            callback.onHotspotChanged(isHotspotEnabled(), mNumConnectedDevices);
        }
    }

    /**
     * Sends a hotspot available changed callback.
     */
    private void fireHotspotAvailabilityChanged() {
        List<Callback> list;
        synchronized (mCallbacks) {
            list = new ArrayList<>(mCallbacks);
        }
        for (Callback callback : list) {
            callback.onHotspotAvailabilityChanged(isHotspotSupported());
        }
    }

    @Override
    public void onStateChanged(int state, int failureReason) {
        // Update internal hotspot state for tracking before using any enabled/callback methods.
        mHotspotState = state;

        maybeResetSoftApState();
        if (!isHotspotEnabled()) {
            // Reset num devices if the hotspot is no longer enabled so we don't get ghost
            // counters.
            mNumConnectedDevices = 0;
        }

        fireHotspotChangedCallback();
    }

    private void maybeResetSoftApState() {
        if (!mWaitingForTerminalState) {
            return; // Only reset soft AP state if enabled from this controller.
        }
        switch (mHotspotState) {
            case WifiManager.WIFI_AP_STATE_FAILED:
                // TODO(b/110697252): must be called to reset soft ap state after failure
                mTetheringManager.stopTethering(ConnectivityManager.TETHERING_WIFI);
                // Fall through
            case WifiManager.WIFI_AP_STATE_ENABLED:
            case WifiManager.WIFI_AP_STATE_DISABLED:
                mWaitingForTerminalState = false;
                break;
            case WifiManager.WIFI_AP_STATE_ENABLING:
            case WifiManager.WIFI_AP_STATE_DISABLING:
            default:
                break;
        }
    }

    @Override
    public void onConnectedClientsChanged(List<WifiClient> clients) {
        mNumConnectedDevices = clients.size();
        fireHotspotChangedCallback();
    }
}
