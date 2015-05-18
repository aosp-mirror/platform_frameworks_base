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

import android.content.res.Resources;
import android.os.Build;
import com.android.server.LocalServices;
import com.android.server.lights.Light;
import com.android.server.lights.LightsManager;

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayEventReceiver;
import android.view.Surface;
import android.view.SurfaceControl;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * A display adapter for the local displays managed by Surface Flinger.
 * <p>
 * Display adapters are guarded by the {@link DisplayManagerService.SyncRoot} lock.
 * </p>
 */
final class LocalDisplayAdapter extends DisplayAdapter {
    private static final String TAG = "LocalDisplayAdapter";
    private static final boolean DEBUG = false;

    private static final String UNIQUE_ID_PREFIX = "local:";

    private static final String PROPERTY_EMULATOR_CIRCULAR = "ro.emulator.circular";

    private static final int[] BUILT_IN_DISPLAY_IDS_TO_SCAN = new int[] {
            SurfaceControl.BUILT_IN_DISPLAY_ID_MAIN,
            SurfaceControl.BUILT_IN_DISPLAY_ID_HDMI,
    };

    private final SparseArray<LocalDisplayDevice> mDevices =
            new SparseArray<LocalDisplayDevice>();
    @SuppressWarnings("unused")  // Becomes active at instantiation time.
    private HotplugDisplayEventReceiver mHotplugReceiver;

    // Called with SyncRoot lock held.
    public LocalDisplayAdapter(DisplayManagerService.SyncRoot syncRoot,
            Context context, Handler handler, Listener listener) {
        super(syncRoot, context, handler, listener, TAG);
    }

    @Override
    public void registerLocked() {
        super.registerLocked();

        mHotplugReceiver = new HotplugDisplayEventReceiver(getHandler().getLooper());

        for (int builtInDisplayId : BUILT_IN_DISPLAY_IDS_TO_SCAN) {
            tryConnectDisplayLocked(builtInDisplayId);
        }
    }

    private void tryConnectDisplayLocked(int builtInDisplayId) {
        IBinder displayToken = SurfaceControl.getBuiltInDisplay(builtInDisplayId);
        if (displayToken != null) {
            SurfaceControl.PhysicalDisplayInfo[] configs =
                    SurfaceControl.getDisplayConfigs(displayToken);
            if (configs == null) {
                // There are no valid configs for this device, so we can't use it
                Slog.w(TAG, "No valid configs found for display device " +
                        builtInDisplayId);
                return;
            }
            int activeConfig = SurfaceControl.getActiveConfig(displayToken);
            if (activeConfig < 0) {
                // There is no active config, and for now we don't have the
                // policy to set one.
                Slog.w(TAG, "No active config found for display device " +
                        builtInDisplayId);
                return;
            }
            LocalDisplayDevice device = mDevices.get(builtInDisplayId);
            if (device == null) {
                // Display was added.
                device = new LocalDisplayDevice(displayToken, builtInDisplayId,
                        configs, activeConfig);
                mDevices.put(builtInDisplayId, device);
                sendDisplayDeviceEventLocked(device, DISPLAY_DEVICE_EVENT_ADDED);
            } else if (device.updatePhysicalDisplayInfoLocked(configs, activeConfig)) {
                // Display properties changed.
                sendDisplayDeviceEventLocked(device, DISPLAY_DEVICE_EVENT_CHANGED);
            }
        } else {
            // The display is no longer available. Ignore the attempt to add it.
            // If it was connected but has already been disconnected, we'll get a
            // disconnect event that will remove it from mDevices.
        }
    }

    private void tryDisconnectDisplayLocked(int builtInDisplayId) {
        LocalDisplayDevice device = mDevices.get(builtInDisplayId);
        if (device != null) {
            // Display was removed.
            mDevices.remove(builtInDisplayId);
            sendDisplayDeviceEventLocked(device, DISPLAY_DEVICE_EVENT_REMOVED);
        }
    }

    static int getPowerModeForState(int state) {
        switch (state) {
            case Display.STATE_OFF:
                return SurfaceControl.POWER_MODE_OFF;
            case Display.STATE_DOZE:
                return SurfaceControl.POWER_MODE_DOZE;
            case Display.STATE_DOZE_SUSPEND:
                return SurfaceControl.POWER_MODE_DOZE_SUSPEND;
            default:
                return SurfaceControl.POWER_MODE_NORMAL;
        }
    }

    private final class LocalDisplayDevice extends DisplayDevice {
        private final int mBuiltInDisplayId;
        private final Light mBacklight;
        private final SparseArray<DisplayModeRecord> mSupportedModes = new SparseArray<>();

        private DisplayDeviceInfo mInfo;
        private boolean mHavePendingChanges;
        private int mState = Display.STATE_UNKNOWN;
        private int mBrightness = PowerManager.BRIGHTNESS_DEFAULT;
        private int mDefaultModeId;
        private int mActiveModeId;
        private boolean mActiveModeInvalid;

        public LocalDisplayDevice(IBinder displayToken, int builtInDisplayId,
                SurfaceControl.PhysicalDisplayInfo[] physicalDisplayInfos, int activeDisplayInfo) {
            super(LocalDisplayAdapter.this, displayToken, UNIQUE_ID_PREFIX + builtInDisplayId);
            mBuiltInDisplayId = builtInDisplayId;
            updatePhysicalDisplayInfoLocked(physicalDisplayInfos, activeDisplayInfo);
            if (mBuiltInDisplayId == SurfaceControl.BUILT_IN_DISPLAY_ID_MAIN) {
                LightsManager lights = LocalServices.getService(LightsManager.class);
                mBacklight = lights.getLight(LightsManager.LIGHT_ID_BACKLIGHT);
            } else {
                mBacklight = null;
            }
        }

        public boolean updatePhysicalDisplayInfoLocked(
                SurfaceControl.PhysicalDisplayInfo[] physicalDisplayInfos, int activeDisplayInfo) {
            // Build an updated list of all existing modes.
            boolean modesAdded = false;
            DisplayModeRecord activeRecord = null;
            ArrayList<DisplayModeRecord> records = new ArrayList<DisplayModeRecord>();
            for (int i = 0; i < physicalDisplayInfos.length; i++) {
                SurfaceControl.PhysicalDisplayInfo info = physicalDisplayInfos[i];
                DisplayModeRecord record = findDisplayModeRecord(info);
                if (record != null) {
                    record.mPhysIndex = i;
                } else {
                    record = new DisplayModeRecord(info, i);
                    modesAdded = true;
                }
                records.add(record);
                if (i == activeDisplayInfo) {
                    activeRecord = record;
                }
            }
            // Check whether surface flinger spontaneously changed modes out from under us. Schedule
            // traversals to ensure that the correct state is reapplied if necessary.
            if (mActiveModeId != 0
                    && mActiveModeId != activeRecord.mMode.getModeId()) {
                mActiveModeInvalid = true;
                sendTraversalRequestLocked();
            }
            // If no modes were added and we have the same number of modes as before, then nothing
            // actually changed except possibly the physical index (which we only care about when
            // setting the mode) so we're done.
            if (records.size() == mSupportedModes.size() && !modesAdded) {
                return false;
            }
            // Update the index of modes.
            mHavePendingChanges = true;
            mSupportedModes.clear();
            for (DisplayModeRecord record : records) {
                mSupportedModes.put(record.mMode.getModeId(), record);
            }
            // Update the default mode if needed.
            if (mSupportedModes.indexOfKey(mDefaultModeId) < 0) {
                if (mDefaultModeId != 0) {
                    Slog.w(TAG, "Default display mode no longer available, using currently active"
                            + " mode as default.");
                }
                mDefaultModeId = activeRecord.mMode.getModeId();
            }
            // Determine whether the active mode is still there.
            if (mSupportedModes.indexOfKey(mActiveModeId) < 0) {
                if (mActiveModeId != 0) {
                    Slog.w(TAG, "Active display mode no longer available, reverting to default"
                            + " mode.");
                }
                mActiveModeId = mDefaultModeId;
                mActiveModeInvalid = true;
            }
            // Schedule traversals so that we apply pending changes.
            sendTraversalRequestLocked();
            return true;
        }

        private DisplayModeRecord findDisplayModeRecord(SurfaceControl.PhysicalDisplayInfo info) {
            for (int i = 0; i < mSupportedModes.size(); i++) {
                DisplayModeRecord record = mSupportedModes.valueAt(i);
                if (record.mPhys.equals(info)) {
                    return record;
                }
            }
            return null;
        }

        @Override
        public void applyPendingDisplayDeviceInfoChangesLocked() {
            if (mHavePendingChanges) {
                mInfo = null;
                mHavePendingChanges = false;
            }
        }

        @Override
        public DisplayDeviceInfo getDisplayDeviceInfoLocked() {
            if (mInfo == null) {
                SurfaceControl.PhysicalDisplayInfo phys = mSupportedModes.get(mActiveModeId).mPhys;
                mInfo = new DisplayDeviceInfo();
                mInfo.width = phys.width;
                mInfo.height = phys.height;
                mInfo.modeId = mActiveModeId;
                mInfo.defaultModeId = mDefaultModeId;
                mInfo.supportedModes = new Display.Mode[mSupportedModes.size()];
                for (int i = 0; i < mSupportedModes.size(); i++) {
                    DisplayModeRecord record = mSupportedModes.valueAt(i);
                    mInfo.supportedModes[i] = record.mMode;
                }
                mInfo.appVsyncOffsetNanos = phys.appVsyncOffsetNanos;
                mInfo.presentationDeadlineNanos = phys.presentationDeadlineNanos;
                mInfo.state = mState;
                mInfo.uniqueId = getUniqueId();

                // Assume that all built-in displays that have secure output (eg. HDCP) also
                // support compositing from gralloc protected buffers.
                if (phys.secure) {
                    mInfo.flags = DisplayDeviceInfo.FLAG_SECURE
                            | DisplayDeviceInfo.FLAG_SUPPORTS_PROTECTED_BUFFERS;
                }

                if (mBuiltInDisplayId == SurfaceControl.BUILT_IN_DISPLAY_ID_MAIN) {
                    final Resources res = getContext().getResources();
                    mInfo.name = res.getString(
                            com.android.internal.R.string.display_manager_built_in_display_name);
                    mInfo.flags |= DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY
                            | DisplayDeviceInfo.FLAG_ROTATES_WITH_CONTENT;
                    if (res.getBoolean(com.android.internal.R.bool.config_mainBuiltInDisplayIsRound)
                            || (Build.HARDWARE.contains("goldfish")
                            && SystemProperties.getBoolean(PROPERTY_EMULATOR_CIRCULAR, false))) {
                        mInfo.flags |= DisplayDeviceInfo.FLAG_ROUND;
                    }
                    mInfo.type = Display.TYPE_BUILT_IN;
                    mInfo.densityDpi = (int)(phys.density * 160 + 0.5f);
                    mInfo.xDpi = phys.xDpi;
                    mInfo.yDpi = phys.yDpi;
                    mInfo.touch = DisplayDeviceInfo.TOUCH_INTERNAL;
                } else {
                    mInfo.type = Display.TYPE_HDMI;
                    mInfo.flags |= DisplayDeviceInfo.FLAG_PRESENTATION;
                    mInfo.name = getContext().getResources().getString(
                            com.android.internal.R.string.display_manager_hdmi_display_name);
                    mInfo.touch = DisplayDeviceInfo.TOUCH_EXTERNAL;
                    mInfo.setAssumedDensityForExternalDisplay(phys.width, phys.height);

                    // For demonstration purposes, allow rotation of the external display.
                    // In the future we might allow the user to configure this directly.
                    if ("portrait".equals(SystemProperties.get("persist.demo.hdmirotation"))) {
                        mInfo.rotation = Surface.ROTATION_270;
                    }

                    // For demonstration purposes, allow rotation of the external display
                    // to follow the built-in display.
                    if (SystemProperties.getBoolean("persist.demo.hdmirotates", false)) {
                        mInfo.flags |= DisplayDeviceInfo.FLAG_ROTATES_WITH_CONTENT;
                    }
                }
            }
            return mInfo;
        }

        @Override
        public Runnable requestDisplayStateLocked(final int state, final int brightness) {
            // Assume that the brightness is off if the display is being turned off.
            assert state != Display.STATE_OFF || brightness == PowerManager.BRIGHTNESS_OFF;

            final boolean stateChanged = (mState != state);
            final boolean brightnessChanged = (mBrightness != brightness) && mBacklight != null;
            if (stateChanged || brightnessChanged) {
                final int displayId = mBuiltInDisplayId;
                final IBinder token = getDisplayTokenLocked();
                final int oldState = mState;

                if (stateChanged) {
                    mState = state;
                    updateDeviceInfoLocked();
                }

                if (brightnessChanged) {
                    mBrightness = brightness;
                }

                // Defer actually setting the display state until after we have exited
                // the critical section since it can take hundreds of milliseconds
                // to complete.
                return new Runnable() {
                    @Override
                    public void run() {
                        // Exit a suspended state before making any changes.
                        int currentState = oldState;
                        if (Display.isSuspendedState(oldState)
                                || oldState == Display.STATE_UNKNOWN) {
                            if (!Display.isSuspendedState(state)) {
                                setDisplayState(state);
                                currentState = state;
                            } else if (state == Display.STATE_DOZE_SUSPEND
                                    || oldState == Display.STATE_DOZE_SUSPEND) {
                                setDisplayState(Display.STATE_DOZE);
                                currentState = Display.STATE_DOZE;
                            } else {
                                return; // old state and new state is off
                            }
                        }

                        // Apply brightness changes given that we are in a non-suspended state.
                        if (brightnessChanged) {
                            setDisplayBrightness(brightness);
                        }

                        // Enter the final desired state, possibly suspended.
                        if (state != currentState) {
                            setDisplayState(state);
                        }
                    }

                    private void setDisplayState(int state) {
                        if (DEBUG) {
                            Slog.d(TAG, "setDisplayState("
                                    + "id=" + displayId
                                    + ", state=" + Display.stateToString(state) + ")");
                        }

                        Trace.traceBegin(Trace.TRACE_TAG_POWER, "setDisplayState("
                                + "id=" + displayId
                                + ", state=" + Display.stateToString(state) + ")");
                        try {
                            final int mode = getPowerModeForState(state);
                            SurfaceControl.setDisplayPowerMode(token, mode);
                        } finally {
                            Trace.traceEnd(Trace.TRACE_TAG_POWER);
                        }
                    }

                    private void setDisplayBrightness(int brightness) {
                        if (DEBUG) {
                            Slog.d(TAG, "setDisplayBrightness("
                                    + "id=" + displayId + ", brightness=" + brightness + ")");
                        }

                        Trace.traceBegin(Trace.TRACE_TAG_POWER, "setDisplayBrightness("
                                + "id=" + displayId + ", brightness=" + brightness + ")");
                        try {
                            mBacklight.setBrightness(brightness);
                        } finally {
                            Trace.traceEnd(Trace.TRACE_TAG_POWER);
                        }
                    }
                };
            }
            return null;
        }

        @Override
        public void requestModeInTransactionLocked(int modeId) {
            if (modeId == 0) {
                modeId = mDefaultModeId;
            } else if (mSupportedModes.indexOfKey(modeId) < 0) {
                Slog.w(TAG, "Requested mode " + modeId + " is not supported by this display,"
                        + " reverting to default display mode.");
                modeId = mDefaultModeId;
            }
            if (mActiveModeId == modeId && !mActiveModeInvalid) {
                return;
            }
            DisplayModeRecord record = mSupportedModes.get(modeId);
            SurfaceControl.setActiveConfig(getDisplayTokenLocked(), record.mPhysIndex);
            mActiveModeId = modeId;
            mActiveModeInvalid = false;
            updateDeviceInfoLocked();
        }

        @Override
        public void dumpLocked(PrintWriter pw) {
            super.dumpLocked(pw);
            pw.println("mBuiltInDisplayId=" + mBuiltInDisplayId);
            pw.println("mActiveModeId=" + mActiveModeId);
            pw.println("mState=" + Display.stateToString(mState));
            pw.println("mBrightness=" + mBrightness);
            pw.println("mBacklight=" + mBacklight);
        }

        private void updateDeviceInfoLocked() {
            mInfo = null;
            sendDisplayDeviceEventLocked(this, DISPLAY_DEVICE_EVENT_CHANGED);
        }
    }

    /**
     * Keeps track of a display configuration.
     */
    private static final class DisplayModeRecord {
        public final Display.Mode mMode;
        public final SurfaceControl.PhysicalDisplayInfo mPhys;
        public int mPhysIndex;

        public DisplayModeRecord(SurfaceControl.PhysicalDisplayInfo phys, int physIndex) {
            mMode = createMode(phys.width, phys.height, phys.refreshRate);
            mPhys = phys;
            mPhysIndex = physIndex;
        }
    }

    private final class HotplugDisplayEventReceiver extends DisplayEventReceiver {
        public HotplugDisplayEventReceiver(Looper looper) {
            super(looper);
        }

        @Override
        public void onHotplug(long timestampNanos, int builtInDisplayId, boolean connected) {
            synchronized (getSyncRoot()) {
                if (connected) {
                    tryConnectDisplayLocked(builtInDisplayId);
                } else {
                    tryDisconnectDisplayLocked(builtInDisplayId);
                }
            }
        }
    }
}
