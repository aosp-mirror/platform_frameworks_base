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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.hardware.display.WifiDisplay;
import android.hardware.display.WifiDisplaySessionInfo;
import android.hardware.display.WifiDisplayStatus;
import android.media.RemoteDisplay;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.util.Slog;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;

import libcore.util.Objects;

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

    private static final boolean DEBUG = false;

    private static final int MSG_SEND_STATUS_CHANGE_BROADCAST = 1;

    private static final String ACTION_DISCONNECT = "android.server.display.wfd.DISCONNECT";

    // Unique id prefix for wifi displays
    private static final String DISPLAY_NAME_PREFIX = "wifi:";

    private final WifiDisplayHandler mHandler;
    private final PersistentDataStore mPersistentDataStore;
    private final boolean mSupportsProtectedBuffers;

    private WifiDisplayController mDisplayController;
    private WifiDisplayDevice mDisplayDevice;

    private WifiDisplayStatus mCurrentStatus;
    private int mFeatureState;
    private int mScanState;
    private int mActiveDisplayState;
    private WifiDisplay mActiveDisplay;
    private WifiDisplay[] mDisplays = WifiDisplay.EMPTY_ARRAY;
    private WifiDisplay[] mAvailableDisplays = WifiDisplay.EMPTY_ARRAY;
    private WifiDisplay[] mRememberedDisplays = WifiDisplay.EMPTY_ARRAY;
    private WifiDisplaySessionInfo mSessionInfo;

    private boolean mPendingStatusChangeBroadcast;

    // Called with SyncRoot lock held.
    public WifiDisplayAdapter(DisplayManagerService.SyncRoot syncRoot,
            Context context, Handler handler, Listener listener,
            PersistentDataStore persistentDataStore) {
        super(syncRoot, context, handler, listener, TAG);
        mHandler = new WifiDisplayHandler(handler.getLooper());
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
        pw.println("mDisplays=" + Arrays.toString(mDisplays));
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
            DumpUtils.dumpAsync(getHandler(), mDisplayController, ipw, "", 200);
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

                getContext().registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL,
                        new IntentFilter(ACTION_DISCONNECT), null, mHandler);
            }
        });
    }

    public void requestStartScanLocked() {
        if (DEBUG) {
            Slog.d(TAG, "requestStartScanLocked");
        }

        getHandler().post(new Runnable() {
            @Override
            public void run() {
                if (mDisplayController != null) {
                    mDisplayController.requestStartScan();
                }
            }
        });
    }

    public void requestStopScanLocked() {
        if (DEBUG) {
            Slog.d(TAG, "requestStopScanLocked");
        }

        getHandler().post(new Runnable() {
            @Override
            public void run() {
                if (mDisplayController != null) {
                    mDisplayController.requestStopScan();
                }
            }
        });
    }

    public void requestConnectLocked(final String address) {
        if (DEBUG) {
            Slog.d(TAG, "requestConnectLocked: address=" + address);
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

    public void requestPauseLocked() {
        if (DEBUG) {
            Slog.d(TAG, "requestPauseLocked");
        }

        getHandler().post(new Runnable() {
            @Override
            public void run() {
                if (mDisplayController != null) {
                    mDisplayController.requestPause();
                }
            }
        });
      }

    public void requestResumeLocked() {
        if (DEBUG) {
            Slog.d(TAG, "requestResumeLocked");
        }

        getHandler().post(new Runnable() {
            @Override
            public void run() {
                if (mDisplayController != null) {
                    mDisplayController.requestResume();
                }
            }
        });
    }

    public void requestDisconnectLocked() {
        if (DEBUG) {
            Slog.d(TAG, "requestDisconnectedLocked");
        }

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
        if (DEBUG) {
            Slog.d(TAG, "requestRenameLocked: address=" + address + ", alias=" + alias);
        }

        if (alias != null) {
            alias = alias.trim();
            if (alias.isEmpty() || alias.equals(address)) {
                alias = null;
            }
        }

        WifiDisplay display = mPersistentDataStore.getRememberedWifiDisplay(address);
        if (display != null && !Objects.equal(display.getDeviceAlias(), alias)) {
            display = new WifiDisplay(address, display.getDeviceName(), alias,
                    false, false, false);
            if (mPersistentDataStore.rememberWifiDisplay(display)) {
                mPersistentDataStore.saveIfNeeded();
                updateRememberedDisplaysLocked();
                scheduleStatusChangedBroadcastLocked();
            }
        }

        if (mActiveDisplay != null && mActiveDisplay.getDeviceAddress().equals(address)) {
            renameDisplayDeviceLocked(mActiveDisplay.getFriendlyDisplayName());
        }
    }

    public void requestForgetLocked(String address) {
        if (DEBUG) {
            Slog.d(TAG, "requestForgetLocked: address=" + address);
        }

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
                    mActiveDisplay, mDisplays, mSessionInfo);
        }

        if (DEBUG) {
            Slog.d(TAG, "getWifiDisplayStatusLocked: result=" + mCurrentStatus);
        }
        return mCurrentStatus;
    }

    private void updateDisplaysLocked() {
        List<WifiDisplay> displays = new ArrayList<WifiDisplay>(
                mAvailableDisplays.length + mRememberedDisplays.length);
        boolean[] remembered = new boolean[mAvailableDisplays.length];
        for (WifiDisplay d : mRememberedDisplays) {
            boolean available = false;
            for (int i = 0; i < mAvailableDisplays.length; i++) {
                if (d.equals(mAvailableDisplays[i])) {
                    remembered[i] = available = true;
                    break;
                }
            }
            if (!available) {
                displays.add(new WifiDisplay(d.getDeviceAddress(), d.getDeviceName(),
                        d.getDeviceAlias(), false, false, true));
            }
        }
        for (int i = 0; i < mAvailableDisplays.length; i++) {
            WifiDisplay d = mAvailableDisplays[i];
            displays.add(new WifiDisplay(d.getDeviceAddress(), d.getDeviceName(),
                    d.getDeviceAlias(), true, d.canConnect(), remembered[i]));
        }
        mDisplays = displays.toArray(WifiDisplay.EMPTY_ARRAY);
    }

    private void updateRememberedDisplaysLocked() {
        mRememberedDisplays = mPersistentDataStore.getRememberedWifiDisplays();
        mActiveDisplay = mPersistentDataStore.applyWifiDisplayAlias(mActiveDisplay);
        mAvailableDisplays = mPersistentDataStore.applyWifiDisplayAliases(mAvailableDisplays);
        updateDisplaysLocked();
    }

    private void fixRememberedDisplayNamesFromAvailableDisplaysLocked() {
        // It may happen that a display name has changed since it was remembered.
        // Consult the list of available displays and update the name if needed.
        // We don't do anything special for the active display here.  The display
        // controller will send a separate event when it needs to be updates.
        boolean changed = false;
        for (int i = 0; i < mRememberedDisplays.length; i++) {
            WifiDisplay rememberedDisplay = mRememberedDisplays[i];
            WifiDisplay availableDisplay = findAvailableDisplayLocked(
                    rememberedDisplay.getDeviceAddress());
            if (availableDisplay != null && !rememberedDisplay.equals(availableDisplay)) {
                if (DEBUG) {
                    Slog.d(TAG, "fixRememberedDisplayNamesFromAvailableDisplaysLocked: "
                            + "updating remembered display to " + availableDisplay);
                }
                mRememberedDisplays[i] = availableDisplay;
                changed |= mPersistentDataStore.rememberWifiDisplay(availableDisplay);
            }
        }
        if (changed) {
            mPersistentDataStore.saveIfNeeded();
        }
    }

    private WifiDisplay findAvailableDisplayLocked(String address) {
        for (WifiDisplay display : mAvailableDisplays) {
            if (display.getDeviceAddress().equals(address)) {
                return display;
            }
        }
        return null;
    }

    private void addDisplayDeviceLocked(WifiDisplay display,
            Surface surface, int width, int height, int flags) {
        removeDisplayDeviceLocked();

        if (mPersistentDataStore.rememberWifiDisplay(display)) {
            mPersistentDataStore.saveIfNeeded();
            updateRememberedDisplaysLocked();
            scheduleStatusChangedBroadcastLocked();
        }

        boolean secure = (flags & RemoteDisplay.DISPLAY_FLAG_SECURE) != 0;
        int deviceFlags = DisplayDeviceInfo.FLAG_PRESENTATION;
        if (secure) {
            deviceFlags |= DisplayDeviceInfo.FLAG_SECURE;
            if (mSupportsProtectedBuffers) {
                deviceFlags |= DisplayDeviceInfo.FLAG_SUPPORTS_PROTECTED_BUFFERS;
            }
        }

        float refreshRate = 60.0f; // TODO: get this for real

        String name = display.getFriendlyDisplayName();
        String address = display.getDeviceAddress();
        IBinder displayToken = SurfaceControl.createDisplay(name, secure);
        mDisplayDevice = new WifiDisplayDevice(displayToken, name, width, height,
                refreshRate, deviceFlags, address, surface);
        sendDisplayDeviceEventLocked(mDisplayDevice, DISPLAY_DEVICE_EVENT_ADDED);
    }

    private void removeDisplayDeviceLocked() {
        if (mDisplayDevice != null) {
            mDisplayDevice.destroyLocked();
            sendDisplayDeviceEventLocked(mDisplayDevice, DISPLAY_DEVICE_EVENT_REMOVED);
            mDisplayDevice = null;
        }
    }

    private void renameDisplayDeviceLocked(String name) {
        if (mDisplayDevice != null && !mDisplayDevice.getNameLocked().equals(name)) {
            mDisplayDevice.setNameLocked(name);
            sendDisplayDeviceEventLocked(mDisplayDevice, DISPLAY_DEVICE_EVENT_CHANGED);
        }
    }

    private void scheduleStatusChangedBroadcastLocked() {
        mCurrentStatus = null;
        if (!mPendingStatusChangeBroadcast) {
            mPendingStatusChangeBroadcast = true;
            mHandler.sendEmptyMessage(MSG_SEND_STATUS_CHANGE_BROADCAST);
        }
    }

    // Runs on the handler.
    private void handleSendStatusChangeBroadcast() {
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
        getContext().sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(ACTION_DISCONNECT)) {
                synchronized (getSyncRoot()) {
                    requestDisconnectLocked();
                }
            }
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

        @Override
        public void onScanResults(WifiDisplay[] availableDisplays) {
            synchronized (getSyncRoot()) {
                availableDisplays = mPersistentDataStore.applyWifiDisplayAliases(
                        availableDisplays);

                boolean changed = !Arrays.equals(mAvailableDisplays, availableDisplays);

                // Check whether any of the available displays changed canConnect status.
                for (int i = 0; !changed && i<availableDisplays.length; i++) {
                    changed = availableDisplays[i].canConnect()
                            != mAvailableDisplays[i].canConnect();
                }

                if (changed) {
                    mAvailableDisplays = availableDisplays;
                    fixRememberedDisplayNamesFromAvailableDisplaysLocked();
                    updateDisplaysLocked();
                    scheduleStatusChangedBroadcastLocked();
                }
            }
        }

        @Override
        public void onScanFinished() {
            synchronized (getSyncRoot()) {
                if (mScanState != WifiDisplayStatus.SCAN_STATE_NOT_SCANNING) {
                    mScanState = WifiDisplayStatus.SCAN_STATE_NOT_SCANNING;
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
                addDisplayDeviceLocked(display, surface, width, height, flags);

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
        public void onDisplaySessionInfo(WifiDisplaySessionInfo sessionInfo) {
            synchronized (getSyncRoot()) {
                mSessionInfo = sessionInfo;
                scheduleStatusChangedBroadcastLocked();
            }
        }

        @Override
        public void onDisplayChanged(WifiDisplay display) {
            synchronized (getSyncRoot()) {
                display = mPersistentDataStore.applyWifiDisplayAlias(display);
                if (mActiveDisplay != null
                        && mActiveDisplay.hasSameAddress(display)
                        && !mActiveDisplay.equals(display)) {
                    mActiveDisplay = display;
                    renameDisplayDeviceLocked(display.getFriendlyDisplayName());
                    scheduleStatusChangedBroadcastLocked();
                }
            }
        }

        @Override
        public void onDisplayDisconnected() {
            // Stop listening.
            synchronized (getSyncRoot()) {
                removeDisplayDeviceLocked();

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
        private String mName;
        private final int mWidth;
        private final int mHeight;
        private final float mRefreshRate;
        private final int mFlags;
        private final String mAddress;
        private final Display.Mode mMode;

        private Surface mSurface;
        private DisplayDeviceInfo mInfo;

        public WifiDisplayDevice(IBinder displayToken, String name,
                int width, int height, float refreshRate, int flags, String address,
                Surface surface) {
            super(WifiDisplayAdapter.this, displayToken, DISPLAY_NAME_PREFIX + address);
            mName = name;
            mWidth = width;
            mHeight = height;
            mRefreshRate = refreshRate;
            mFlags = flags;
            mAddress = address;
            mSurface = surface;
            mMode = createMode(width, height, refreshRate);
        }

        public void destroyLocked() {
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }
            SurfaceControl.destroyDisplay(getDisplayTokenLocked());
        }

        public void setNameLocked(String name) {
            mName = name;
            mInfo = null;
        }

        @Override
        public void performTraversalInTransactionLocked() {
            if (mSurface != null) {
                setSurfaceInTransactionLocked(mSurface);
            }
        }

        @Override
        public DisplayDeviceInfo getDisplayDeviceInfoLocked() {
            if (mInfo == null) {
                mInfo = new DisplayDeviceInfo();
                mInfo.name = mName;
                mInfo.uniqueId = getUniqueId();
                mInfo.width = mWidth;
                mInfo.height = mHeight;
                mInfo.modeId = mMode.getModeId();
                mInfo.defaultModeId = mMode.getModeId();
                mInfo.supportedModes = new Display.Mode[] { mMode };
                mInfo.presentationDeadlineNanos = 1000000000L / (int) mRefreshRate; // 1 frame
                mInfo.flags = mFlags;
                mInfo.type = Display.TYPE_WIFI;
                mInfo.address = mAddress;
                mInfo.touch = DisplayDeviceInfo.TOUCH_EXTERNAL;
                mInfo.setAssumedDensityForExternalDisplay(mWidth, mHeight);
            }
            return mInfo;
        }
    }

    private final class WifiDisplayHandler extends Handler {
        public WifiDisplayHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SEND_STATUS_CHANGE_BROADCAST:
                    handleSendStatusChangeBroadcast();
                    break;
            }
        }
    }
}
