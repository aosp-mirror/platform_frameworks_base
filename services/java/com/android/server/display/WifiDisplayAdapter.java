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

    private WifiDisplayHandle mDisplayHandle;
    private WifiDisplayController mDisplayController;

    private WifiDisplayStatus mCurrentStatus;
    private boolean mEnabled;
    private WifiDisplay mConnectedDisplay;
    private WifiDisplay[] mKnownDisplays = WifiDisplay.EMPTY_ARRAY;
    private boolean mScanInProgress;
    private boolean mConnectionInProgress;

    private boolean mPendingStatusChangeBroadcast;

    public WifiDisplayAdapter(DisplayManagerService.SyncRoot syncRoot,
            Context context, Handler handler, Listener listener) {
        super(syncRoot, context, handler, listener, TAG);
    }

    @Override
    public void dumpLocked(PrintWriter pw) {
        super.dumpLocked(pw);

        if (mDisplayHandle == null) {
            pw.println("mDisplayHandle=null");
        } else {
            pw.println("mDisplayHandle:");
            mDisplayHandle.dumpLocked(pw);
        }

        pw.println("mCurrentStatus=" + getWifiDisplayStatusLocked());
        pw.println("mEnabled=" + mEnabled);
        pw.println("mConnectedDisplay=" + mConnectedDisplay);
        pw.println("mKnownDisplays=" + Arrays.toString(mKnownDisplays));
        pw.println("mScanInProgress=" + mScanInProgress);
        pw.println("mConnectionInProgress=" + mConnectionInProgress);
        pw.println("mPendingStatusChangeBroadcast=" + mPendingStatusChangeBroadcast);

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

    public void requestConnectLocked(final String address) {
        getHandler().post(new Runnable() {
            @Override
            public void run() {
                if (mDisplayController != null) {
                    mDisplayController.requestConnect(address);
                }
            }
        });
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

    public WifiDisplayStatus getWifiDisplayStatusLocked() {
        if (mCurrentStatus == null) {
            mCurrentStatus = new WifiDisplayStatus(mEnabled,
                    mConnectedDisplay, mKnownDisplays,
                    mScanInProgress, mConnectionInProgress);
        }
        return mCurrentStatus;
    }

    private void handleConnectLocked(WifiDisplay display, String iface) {
        handleDisconnectLocked();

        mDisplayHandle = new WifiDisplayHandle(display.getDeviceName(), iface);
    }

    private void handleDisconnectLocked() {
        if (mDisplayHandle != null) {
            mDisplayHandle.disposeLocked();
            mDisplayHandle = null;
        }
    }

    private void scheduleStatusChangedBroadcastLocked() {
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

            // Send protected broadcast about wifi display status to receivers that
            // have the required permission.
            getContext().sendBroadcast(intent,
                    android.Manifest.permission.CONFIGURE_WIFI_DISPLAY);
        }
    };

    private final WifiDisplayController.Listener mWifiDisplayListener =
            new WifiDisplayController.Listener() {
        @Override
        public void onEnablementChanged(boolean enabled) {
            synchronized (getSyncRoot()) {
                if (mEnabled != enabled) {
                    mCurrentStatus = null;
                    mEnabled = enabled;
                    scheduleStatusChangedBroadcastLocked();
                }
            }
        }

        @Override
        public void onScanStarted() {
            synchronized (getSyncRoot()) {
                if (!mScanInProgress) {
                    mCurrentStatus = null;
                    mScanInProgress = true;
                    scheduleStatusChangedBroadcastLocked();
                }
            }
        }

        public void onScanFinished(WifiDisplay[] knownDisplays) {
            synchronized (getSyncRoot()) {
                if (!Arrays.equals(mKnownDisplays, knownDisplays) || mScanInProgress) {
                    mCurrentStatus = null;
                    mKnownDisplays = knownDisplays;
                    mScanInProgress = false;
                    scheduleStatusChangedBroadcastLocked();
                }
            }
        }

        @Override
        public void onDisplayConnecting(WifiDisplay display) {
            synchronized (getSyncRoot()) {
                if (!mConnectionInProgress) {
                    mCurrentStatus = null;
                    mConnectionInProgress = true;
                    scheduleStatusChangedBroadcastLocked();
                }
            }
        }

        @Override
        public void onDisplayConnectionFailed() {
            synchronized (getSyncRoot()) {
                if (mConnectionInProgress) {
                    mCurrentStatus = null;
                    mConnectionInProgress = false;
                    scheduleStatusChangedBroadcastLocked();
                }
            }
        }

        @Override
        public void onDisplayConnected(WifiDisplay display, String iface) {
            synchronized (getSyncRoot()) {
                handleConnectLocked(display, iface);

                if (mConnectedDisplay == null || !mConnectedDisplay.equals(display)
                        || mConnectionInProgress) {
                    mCurrentStatus = null;
                    mConnectedDisplay = display;
                    mConnectionInProgress = false;
                    scheduleStatusChangedBroadcastLocked();
                }
            }
        }

        @Override
        public void onDisplayDisconnected() {
            // Stop listening.
            synchronized (getSyncRoot()) {
                handleDisconnectLocked();

                if (mConnectedDisplay != null || mConnectionInProgress) {
                    mCurrentStatus = null;
                    mConnectedDisplay = null;
                    mConnectionInProgress = false;
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

    private final class WifiDisplayHandle implements RemoteDisplay.Listener {
        private final String mName;
        private final String mIface;
        private final RemoteDisplay mRemoteDisplay;

        private WifiDisplayDevice mDevice;
        private int mLastError;

        public WifiDisplayHandle(String name, String iface) {
            mName = name;
            mIface = iface;
            mRemoteDisplay = RemoteDisplay.listen(iface, this, getHandler());

            Slog.i(TAG, "Listening for Wifi display connections on " + iface
                    + " from " + mName);
        }

        public void disposeLocked() {
            Slog.i(TAG, "Stopped listening for Wifi display connections on " + mIface
                    + " from " + mName);

            removeDisplayLocked();
            mRemoteDisplay.dispose();
        }

        public void dumpLocked(PrintWriter pw) {
            pw.println("  " + mName + ": " + (mDevice != null ? "connected" : "disconnected"));
            pw.println("    mIface=" + mIface);
            pw.println("    mLastError=" + mLastError);
        }

        // Called on the handler thread.
        @Override
        public void onDisplayConnected(Surface surface, int width, int height, int flags) {
            synchronized (getSyncRoot()) {
                mLastError = 0;
                removeDisplayLocked();
                addDisplayLocked(surface, width, height, flags);

                Slog.i(TAG, "Wifi display connected: " + mName);
            }
        }

        // Called on the handler thread.
        @Override
        public void onDisplayDisconnected() {
            synchronized (getSyncRoot()) {
                mLastError = 0;
                removeDisplayLocked();

                Slog.i(TAG, "Wifi display disconnected: " + mName);
            }
        }

        // Called on the handler thread.
        @Override
        public void onDisplayError(int error) {
            synchronized (getSyncRoot()) {
                mLastError = error;
                removeDisplayLocked();

                Slog.i(TAG, "Wifi display disconnected due to error " + error + ": " + mName);
            }
        }

        private void addDisplayLocked(Surface surface, int width, int height, int flags) {
            int deviceFlags = 0;
            if ((flags & RemoteDisplay.DISPLAY_FLAG_SECURE) != 0) {
                deviceFlags |= DisplayDeviceInfo.FLAG_SECURE;
            }

            float refreshRate = 60.0f; // TODO: get this for real

            IBinder displayToken = Surface.createDisplay(mName);
            mDevice = new WifiDisplayDevice(displayToken, mName, width, height,
                    refreshRate, deviceFlags, surface);
            sendDisplayDeviceEventLocked(mDevice, DISPLAY_DEVICE_EVENT_ADDED);
        }

        private void removeDisplayLocked() {
            if (mDevice != null) {
                mDevice.clearSurfaceLocked();
                sendDisplayDeviceEventLocked(mDevice, DISPLAY_DEVICE_EVENT_REMOVED);
                mDevice = null;
            }
        }
    }
}
