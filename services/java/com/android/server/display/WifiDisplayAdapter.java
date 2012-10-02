/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.display;

import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.WifiDisplay;
import android.hardware.display.WifiDisplayStatus;
import android.media.RemoteDisplay;
import android.os.Handler;
import android.os.IBinder;
import android.util.Slog;
import android.view.Surface;

import java.io.PrintWriter;
import java.util.Arrays;

/**
 * Connects to Wifi displays that implement the Miracast protocol.
 * <p>
 * The Wifi display protocol relies on Wifi direct for discovering and pairing
 * with the display.  Once connected, the Media Server opens an RTSP socket and accepts
 * a connection from the display.  After session negotiation, the Media Server
 * streams encoded buffers to the display.
 * </p><p>
 * This class is responsible for connecting to Wifi displays and mediating
 * the interactions between Media Server, Surface Flinger and the Display Manager Service.
 * </p><p>
 * Display adapters are guarded by the {@link DisplayManagerService.SyncRoot} lock.
 * </p>
 */
final class WifiDisplayAdapter extends DisplayAdapter {
    private static final String TAG = "WifiDisplayAdapter";

    private final PersistentDataStore mPersistentDataStore;
    private final boolean mSupportsProtectedBuffers;

    private WifiDisplayController mDisplayController;
    private WifiDisplayDevice mDisplayDevice;

    private WifiDisplayStatus mCurrentStatus;
    private int mFeatureState;
    private int mScanState;
    private int mActiveDisplayState;
    private WifiDisplay mActiveDisplay;
    private WifiDisplay[] mAvailableDisplays = WifiDisplay.EMPTY_ARRAY;
    private WifiDisplay[] mRememberedDisplays = WifiDisplay.EMPTY_ARRAY;

    private boolean mPendingStatusChangeBroadcast;

    public WifiDisplayAdapter(DisplayManagerService.SyncRoot syncRoot,
            Context context, Handler handler, Listener listener,
            PersistentDataStore persistentDataStore) {
        super(syncRoot, context, handler, listener, TAG);
        mPersistentDataStore = persistentDataStore;
        mSupportsProtectedBuffers = context.getResources().getBoolean(
                com.android.internal.R.bool.config_wifiDisplaySupportsProtectedBuffers);
    }

    @Override
    public void dumpLocked(PrintWriter pw) {
        super.dumpLocked(pw);

        pw.println("mCurrentStatus=" + getWifiDisplayStatusLocked());
        pw.println("mFeatureState=" + mFeatureState);
        pw.println("mScanState=" + mScanState);
        pw.println("mActiveDisplayState=" + mActiveDisplayState);
        pw.println("mActiveDisplay=" + mActiveDisplay);
        pw.println("mAvailableDisplays=" + Arrays.toString(mAvailableDisplays));
        pw.println("mRememberedDisplays=" + Arrays.toString(mRememberedDisplays));
        pw.println("mPendingStatusChangeBroadcast=" + mPendingStatusChangeBroadcast);
        pw.println("mSupportsProtectedBuffers=" + mSupportsProtectedBuffers);

        // Try to dump the controller state.
        if (mDisplayController == null) {
            pw.println("mDisplayController=null");
        } else {
            pw.println("mDisplayController:");
            final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
            ipw.increaseIndent();
            DumpUtils.dumpAsync(getHandler(), mDisplayController, ipw, 200);
        }
    }

    @Override
    public void registerLocked() {
        super.registerLocked();

        updateRememberedDisplaysLocked();

        getHandler().post(new Runnable() {
            @Override
            public void run() {
                mDisplayController = new WifiDisplayController(
                        getContext(), getHandler(), mWifiDisplayListener);
            }
        });
    }

    public void requestScanLocked() {
        getHandler().post(new Runnable() {
            @Override
            public void run() {
                if (mDisplayController != null) {
                    mDisplayController.requestScan();
                }
            }
        });
    }

    public void requestConnectLocked(final String address, final boolean trusted) {
        if (!trusted) {
            synchronized (getSyncRoot()) {
                if (!isRememberedDisplayLocked(address)) {
                    Slog.w(TAG, "Ignoring request by an untrusted client to connect to "
                            + "an unknown wifi display: " + address);
                    return;
                }
            }
        }

        getHandler().post(new Runnable() {
            @Override
            public void run() {
                if (mDisplayController != null) {
                    mDisplayController.requestConnect(address);
                }
            }
        });
    }

    private boolean isRememberedDisplayLocked(String address) {
        for (WifiDisplay display : mRememberedDisplays) {
            if (display.getDeviceAddress().equals(address)) {
                return true;
            }
        }
        return false;
    }

    public void requestDisconnectLocked() {
        getHandler().post(new Runnable() {
            @Override
            public void run() {
                if (mDisplayController != null) {
                    mDisplayController.requestDisconnect();
                }
            }
        });
    }

    public void requestRenameLocked(String address, String alias) {
        if (alias != null) {
            alias = alias.trim();
            if (alias.isEmpty()) {
                alias = null;
            }
        }

        if (mPersistentDataStore.renameWifiDisplay(address, alias)) {
            mPersistentDataStore.saveIfNeeded();
            updateRememberedDisplaysLocked();
            scheduleStatusChangedBroadcastLocked();
        }
    }

    public void requestForgetLocked(String address) {
        if (mPersistentDataStore.forgetWifiDisplay(address)) {
            mPersistentDataStore.saveIfNeeded();
            updateRememberedDisplaysLocked();
            scheduleStatusChangedBroadcastLocked();
        }

        if (mActiveDisplay != null && mActiveDisplay.getDeviceAddress().equals(address)) {
            requestDisconnectLocked();
        }
    }

    public WifiDisplayStatus getWifiDisplayStatusLocked() {
        if (mCurrentStatus == null) {
            mCurrentStatus = new WifiDisplayStatus(
                    mFeatureState, mScanState, mActiveDisplayState,
                    mActiveDisplay, mAvailableDisplays, mRememberedDisplays);
        }
        return mCurrentStatus;
    }

    private void updateRememberedDisplaysLocked() {
        mRememberedDisplays = mPersistentDataStore.getRememberedWifiDisplays();
        mActiveDisplay = mPersistentDataStore.applyWifiDisplayAlias(mActiveDisplay);
        mAvailableDisplays = mPersistentDataStore.applyWifiDisplayAliases(mAvailableDisplays);
    }

    private void handleConnectLocked(WifiDisplay display,
            Surface surface, int width, int height, int flags) {
        handleDisconnectLocked();

        if (mPersistentDataStore.rememberWifiDisplay(display)) {
            mPersistentDataStore.saveIfNeeded();
            updateRememberedDisplaysLocked();
            scheduleStatusChangedBroadcastLocked();
        }

        int deviceFlags = 0;
        if ((flags & RemoteDisplay.DISPLAY_FLAG_SECURE) != 0) {
            deviceFlags |= DisplayDeviceInfo.FLAG_SECURE;
        }
        if (mSupportsProtectedBuffers) {
            deviceFlags |= DisplayDeviceInfo.FLAG_SUPPORTS_PROTECTED_BUFFERS;
        }

        float refreshRate = 60.0f; // TODO: get this for real

        String name = display.getFriendlyDisplayName();
        IBinder displayToken = Surface.createDisplay(name);
        mDisplayDevice = new WifiDisplayDevice(displayToken, name, width, height,
                refreshRate, deviceFlags, surface);
        sendDisplayDeviceEventLocked(mDisplayDevice, DISPLAY_DEVICE_EVENT_ADDED);
    }

    private void handleDisconnectLocked() {
        if (mDisplayDevice != null) {
            mDisplayDevice.clearSurfaceLocked();
            sendDisplayDeviceEventLocked(mDisplayDevice, DISPLAY_DEVICE_EVENT_REMOVED);
            mDisplayDevice = null;
        }
    }

    private void scheduleStatusChangedBroadcastLocked() {
        mCurrentStatus = null;
        if (!mPendingStatusChangeBroadcast) {
            mPendingStatusChangeBroadcast = true;
            getHandler().post(mStatusChangeBroadcast);
        }
    }

    private final Runnable mStatusChangeBroadcast = new Runnable() {
        @Override
        public void run() {
            final Intent intent;
            synchronized (getSyncRoot()) {
                if (!mPendingStatusChangeBroadcast) {
                    return;
                }

                mPendingStatusChangeBroadcast = false;
                intent = new Intent(DisplayManager.ACTION_WIFI_DISPLAY_STATUS_CHANGED);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                intent.putExtra(DisplayManager.EXTRA_WIFI_DISPLAY_STATUS,
                        getWifiDisplayStatusLocked());
            }

            // Send protected broadcast about wifi display status to registered receivers.
            getContext().sendBroadcast(intent);
        }
    };

    private final WifiDisplayController.Listener mWifiDisplayListener =
            new WifiDisplayController.Listener() {
        @Override
        public void onFeatureStateChanged(int featureState) {
            synchronized (getSyncRoot()) {
                if (mFeatureState != featureState) {
                    mFeatureState = featureState;
                    scheduleStatusChangedBroadcastLocked();
                }
            }
        }

        @Override
        public void onScanStarted() {
            synchronized (getSyncRoot()) {
                if (mScanState != WifiDisplayStatus.SCAN_STATE_SCANNING) {
                    mScanState = WifiDisplayStatus.SCAN_STATE_SCANNING;
                    scheduleStatusChangedBroadcastLocked();
                }
            }
        }

        public void onScanFinished(WifiDisplay[] availableDisplays) {
            synchronized (getSyncRoot()) {
                availableDisplays = mPersistentDataStore.applyWifiDisplayAliases(
                        availableDisplays);

                if (mScanState != WifiDisplayStatus.SCAN_STATE_NOT_SCANNING
                        || !Arrays.equals(mAvailableDisplays, availableDisplays)) {
                    mScanState = WifiDisplayStatus.SCAN_STATE_NOT_SCANNING;
                    mAvailableDisplays = availableDisplays;
                    scheduleStatusChangedBroadcastLocked();
                }
            }
        }

        @Override
        public void onDisplayConnecting(WifiDisplay display) {
            synchronized (getSyncRoot()) {
                display = mPersistentDataStore.applyWifiDisplayAlias(display);

                if (mActiveDisplayState != WifiDisplayStatus.DISPLAY_STATE_CONNECTING
                        || mActiveDisplay == null
                        || !mActiveDisplay.equals(display)) {
                    mActiveDisplayState = WifiDisplayStatus.DISPLAY_STATE_CONNECTING;
                    mActiveDisplay = display;
                    scheduleStatusChangedBroadcastLocked();
                }
            }
        }

        @Override
        public void onDisplayConnectionFailed() {
            synchronized (getSyncRoot()) {
                if (mActiveDisplayState != WifiDisplayStatus.DISPLAY_STATE_NOT_CONNECTED
                        || mActiveDisplay != null) {
                    mActiveDisplayState = WifiDisplayStatus.DISPLAY_STATE_NOT_CONNECTED;
                    mActiveDisplay = null;
                    scheduleStatusChangedBroadcastLocked();
                }
            }
        }

        @Override
        public void onDisplayConnected(WifiDisplay display, Surface surface,
                int width, int height, int flags) {
            synchronized (getSyncRoot()) {
                display = mPersistentDataStore.applyWifiDisplayAlias(display);
                handleConnectLocked(display, surface, width, height, flags);

                if (mActiveDisplayState != WifiDisplayStatus.DISPLAY_STATE_CONNECTED
                        || mActiveDisplay == null
                        || !mActiveDisplay.equals(display)) {
                    mActiveDisplayState = WifiDisplayStatus.DISPLAY_STATE_CONNECTED;
                    mActiveDisplay = display;
                    scheduleStatusChangedBroadcastLocked();
                }
            }
        }

        @Override
        public void onDisplayDisconnected() {
            // Stop listening.
            synchronized (getSyncRoot()) {
                handleDisconnectLocked();

                if (mActiveDisplayState != WifiDisplayStatus.DISPLAY_STATE_NOT_CONNECTED
                        || mActiveDisplay != null) {
                    mActiveDisplayState = WifiDisplayStatus.DISPLAY_STATE_NOT_CONNECTED;
                    mActiveDisplay = null;
                    scheduleStatusChangedBroadcastLocked();
                }
            }
        }
    };

    private final class WifiDisplayDevice extends DisplayDevice {
        private final String mName;
        private final int mWidth;
        private final int mHeight;
        private final float mRefreshRate;
        private final int mFlags;

        private Surface mSurface;
        private DisplayDeviceInfo mInfo;

        public WifiDisplayDevice(IBinder displayToken, String name,
                int width, int height, float refreshRate, int flags,
                Surface surface) {
            super(WifiDisplayAdapter.this, displayToken);
            mName = name;
            mWidth = width;
            mHeight = height;
            mRefreshRate = refreshRate;
            mFlags = flags;
            mSurface = surface;
        }

        public void clearSurfaceLocked() {
            mSurface = null;
            sendTraversalRequestLocked();
        }

        @Override
        public void performTraversalInTransactionLocked() {
            setSurfaceInTransactionLocked(mSurface);
        }

        @Override
        public DisplayDeviceInfo getDisplayDeviceInfoLocked() {
            if (mInfo == null) {
                mInfo = new DisplayDeviceInfo();
                mInfo.name = mName;
                mInfo.width = mWidth;
                mInfo.height = mHeight;
                mInfo.refreshRate = mRefreshRate;
                mInfo.flags = mFlags;
                mInfo.touch = DisplayDeviceInfo.TOUCH_EXTERNAL;
                mInfo.setAssumedDensityForExternalDisplay(mWidth, mHeight);
            }
            return mInfo;
        }
    }
}
