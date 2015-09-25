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
import android.util.SparseBooleanArray;
import android.view.Display;
import android.view.DisplayEventReceiver;
import android.view.Surface;
import android.view.SurfaceControl;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;

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
        private final SparseArray<Display.ColorTransform> mSupportedColorTransforms =
                new SparseArray<>();

        private DisplayDeviceInfo mInfo;
        private boolean mHavePendingChanges;
        private int mState = Display.STATE_UNKNOWN;
        private int mBrightness = PowerManager.BRIGHTNESS_DEFAULT;
        private int mActivePhysIndex;
        private int mDefaultModeId;
        private int mActiveModeId;
        private boolean mActiveModeInvalid;
        private int mDefaultColorTransformId;
        private int mActiveColorTransformId;
        private boolean mActiveColorTransformInvalid;

        private  SurfaceControl.PhysicalDisplayInfo mDisplayInfos[];

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
            mDisplayInfos = Arrays.copyOf(physicalDisplayInfos, physicalDisplayInfos.length);
            mActivePhysIndex = activeDisplayInfo;
            ArrayList<Display.ColorTransform> colorTransforms = new ArrayList<>();

            // Build an updated list of all existing color transforms.
            boolean colorTransformsAdded = false;
            Display.ColorTransform activeColorTransform = null;
            for (int i = 0; i < physicalDisplayInfos.length; i++) {
                SurfaceControl.PhysicalDisplayInfo info = physicalDisplayInfos[i];
                // First check to see if we've already added this color transform
                boolean existingMode = false;
                for (int j = 0; j < colorTransforms.size(); j++) {
                    if (colorTransforms.get(j).getColorTransform() == info.colorTransform) {
                        existingMode = true;
                        break;
                    }
                }
                if (existingMode) {
                    continue;
                }
                Display.ColorTransform colorTransform = findColorTransform(info);
                if (colorTransform == null) {
                    colorTransform = createColorTransform(info.colorTransform);
                    colorTransformsAdded = true;
                }
                colorTransforms.add(colorTransform);
                if (i == activeDisplayInfo) {
                    activeColorTransform = colorTransform;
                }
            }

            // Build an updated list of all existing modes.
            ArrayList<DisplayModeRecord> records = new ArrayList<DisplayModeRecord>();
            boolean modesAdded = false;
            for (int i = 0; i < physicalDisplayInfos.length; i++) {
                SurfaceControl.PhysicalDisplayInfo info = physicalDisplayInfos[i];
                // First, check to see if we've already added a matching mode. Since not all
                // configuration options are exposed via Display.Mode, it's possible that we have
                // multiple PhysicalDisplayInfos that would generate the same Display.Mode.
                boolean existingMode = false;
                for (int j = 0; j < records.size(); j++) {
                    if (records.get(j).hasMatchingMode(info)) {
                        existingMode = true;
                        break;
                    }
                }
                if (existingMode) {
                    continue;
                }
                // If we haven't already added a mode for this configuration to the new set of
                // supported modes then check to see if we have one in the prior set of supported
                // modes to reuse.
                DisplayModeRecord record = findDisplayModeRecord(info);
                if (record == null) {
                    record = new DisplayModeRecord(info);
                    modesAdded = true;
                }
                records.add(record);
            }

            // Get the currently active mode
            DisplayModeRecord activeRecord = null;
            for (int i = 0; i < records.size(); i++) {
                DisplayModeRecord record = records.get(i);
                if (record.hasMatchingMode(physicalDisplayInfos[activeDisplayInfo])){
                    activeRecord = record;
                    break;
                }
            }
            // Check whether surface flinger spontaneously changed modes out from under us. Schedule
            // traversals to ensure that the correct state is reapplied if necessary.
            if (mActiveModeId != 0
                    && mActiveModeId != activeRecord.mMode.getModeId()) {
                mActiveModeInvalid = true;
                sendTraversalRequestLocked();
            }
            // Check whether surface flinger spontaneously changed color transforms out from under
            // us.
            if (mActiveColorTransformId != 0
                    && mActiveColorTransformId != activeColorTransform.getId()) {
                mActiveColorTransformInvalid = true;
                sendTraversalRequestLocked();
            }

            boolean colorTransformsChanged =
                    colorTransforms.size() != mSupportedColorTransforms.size()
                    || colorTransformsAdded;
            boolean recordsChanged = records.size() != mSupportedModes.size() || modesAdded;
            // If neither the records nor the supported color transforms have changed then we're
            // done here.
            if (!recordsChanged && !colorTransformsChanged) {
                return false;
            }
            // Update the index of modes.
            mHavePendingChanges = true;

            mSupportedModes.clear();
            for (DisplayModeRecord record : records) {
                mSupportedModes.put(record.mMode.getModeId(), record);
            }
            mSupportedColorTransforms.clear();
            for (Display.ColorTransform colorTransform : colorTransforms) {
                mSupportedColorTransforms.put(colorTransform.getId(), colorTransform);
            }

            // Update the default mode and color transform if needed. This needs to be done in
            // tandem so we always have a default state to fall back to.
            if (findDisplayInfoIndexLocked(mDefaultColorTransformId, mDefaultModeId) < 0) {
                if (mDefaultModeId != 0) {
                    Slog.w(TAG, "Default display mode no longer available, using currently"
                            + " active mode as default.");
                }
                mDefaultModeId = activeRecord.mMode.getModeId();
                if (mDefaultColorTransformId != 0) {
                    Slog.w(TAG, "Default color transform no longer available, using currently"
                            + " active color transform as default");
                }
                mDefaultColorTransformId = activeColorTransform.getId();
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

            // Determine whether the active color transform is still there.
            if (mSupportedColorTransforms.indexOfKey(mActiveColorTransformId) < 0) {
                if (mActiveColorTransformId != 0) {
                    Slog.w(TAG, "Active color transform no longer available, reverting"
                            + " to default transform.");
                }
                mActiveColorTransformId = mDefaultColorTransformId;
                mActiveColorTransformInvalid = true;
            }
            // Schedule traversals so that we apply pending changes.
            sendTraversalRequestLocked();
            return true;
        }

        private DisplayModeRecord findDisplayModeRecord(SurfaceControl.PhysicalDisplayInfo info) {
            for (int i = 0; i < mSupportedModes.size(); i++) {
                DisplayModeRecord record = mSupportedModes.valueAt(i);
                if (record.hasMatchingMode(info)) {
                    return record;
                }
            }
            return null;
        }

        private Display.ColorTransform findColorTransform(SurfaceControl.PhysicalDisplayInfo info) {
            for (int i = 0; i < mSupportedColorTransforms.size(); i++) {
                Display.ColorTransform transform = mSupportedColorTransforms.valueAt(i);
                if (transform.getColorTransform() == info.colorTransform) {
                    return transform;
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
                SurfaceControl.PhysicalDisplayInfo phys = mDisplayInfos[mActivePhysIndex];
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
                mInfo.colorTransformId = mActiveColorTransformId;
                mInfo.defaultColorTransformId = mDefaultColorTransformId;
                mInfo.supportedColorTransforms =
                        new Display.ColorTransform[mSupportedColorTransforms.size()];
                for (int i = 0; i < mSupportedColorTransforms.size(); i++) {
                    mInfo.supportedColorTransforms[i] = mSupportedColorTransforms.valueAt(i);
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
        public void requestColorTransformAndModeInTransactionLocked(
                int colorTransformId, int modeId) {
            if (modeId == 0) {
                modeId = mDefaultModeId;
            } else if (mSupportedModes.indexOfKey(modeId) < 0) {
                Slog.w(TAG, "Requested mode " + modeId + " is not supported by this display,"
                        + " reverting to default display mode.");
                modeId = mDefaultModeId;
            }

            if (colorTransformId == 0) {
                colorTransformId = mDefaultColorTransformId;
            } else if (mSupportedColorTransforms.indexOfKey(colorTransformId) < 0) {
                Slog.w(TAG, "Requested color transform " + colorTransformId + " is not supported"
                        + " by this display, reverting to the default color transform");
                colorTransformId = mDefaultColorTransformId;
            }
            int physIndex = findDisplayInfoIndexLocked(colorTransformId, modeId);
            if (physIndex < 0) {
                Slog.w(TAG, "Requested color transform, mode ID pair (" + colorTransformId + ", "
                        + modeId + ") not available, trying color transform with default mode ID");
                modeId = mDefaultModeId;
                physIndex = findDisplayInfoIndexLocked(colorTransformId, modeId);
                if (physIndex < 0) {
                    Slog.w(TAG, "Requested color transform with default mode ID still not"
                            + " available, falling back to default color transform with default"
                            + " mode.");
                    colorTransformId = mDefaultColorTransformId;
                    physIndex = findDisplayInfoIndexLocked(colorTransformId, modeId);
                }
            }
            if (mActivePhysIndex == physIndex) {
                return;
            }
            SurfaceControl.setActiveConfig(getDisplayTokenLocked(), physIndex);
            mActivePhysIndex = physIndex;
            mActiveModeId = modeId;
            mActiveModeInvalid = false;
            mActiveColorTransformId = colorTransformId;
            mActiveColorTransformInvalid = false;
            updateDeviceInfoLocked();
        }

        @Override
        public void dumpLocked(PrintWriter pw) {
            super.dumpLocked(pw);
            pw.println("mBuiltInDisplayId=" + mBuiltInDisplayId);
            pw.println("mActivePhysIndex=" + mActivePhysIndex);
            pw.println("mActiveModeId=" + mActiveModeId);
            pw.println("mActiveColorTransformId=" + mActiveColorTransformId);
            pw.println("mState=" + Display.stateToString(mState));
            pw.println("mBrightness=" + mBrightness);
            pw.println("mBacklight=" + mBacklight);
            pw.println("mDisplayInfos=");
            for (int i = 0; i < mDisplayInfos.length; i++) {
                pw.println("  " + mDisplayInfos[i]);
            }
            pw.println("mSupportedModes=");
            for (int i = 0; i < mSupportedModes.size(); i++) {
                pw.println("  " + mSupportedModes.valueAt(i));
            }
            pw.println("mSupportedColorTransforms=[");
            for (int i = 0; i < mSupportedColorTransforms.size(); i++) {
                if (i != 0) {
                    pw.print(", ");
                }
                pw.print(mSupportedColorTransforms.valueAt(i));
            }
            pw.println("]");
        }

        private int findDisplayInfoIndexLocked(int colorTransformId, int modeId) {
            DisplayModeRecord record = mSupportedModes.get(modeId);
            Display.ColorTransform transform = mSupportedColorTransforms.get(colorTransformId);
            if (record != null && transform != null) {
                for (int i = 0; i < mDisplayInfos.length; i++) {
                    SurfaceControl.PhysicalDisplayInfo info = mDisplayInfos[i];
                    if (info.colorTransform == transform.getColorTransform()
                            && record.hasMatchingMode(info)){
                        return i;
                    }
                }
            }
            return -1;
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

        public DisplayModeRecord(SurfaceControl.PhysicalDisplayInfo phys) {
            mMode = createMode(phys.width, phys.height, phys.refreshRate);
        }

        /**
         * Returns whether the mode generated by the given PhysicalDisplayInfo matches the mode
         * contained by the record modulo mode ID.
         *
         * Note that this doesn't necessarily mean the the PhysicalDisplayInfos are identical, just
         * that they generate identical modes.
         */
        public boolean hasMatchingMode(SurfaceControl.PhysicalDisplayInfo info) {
            int modeRefreshRate = Float.floatToIntBits(mMode.getRefreshRate());
            int displayInfoRefreshRate = Float.floatToIntBits(info.refreshRate);
            return mMode.getPhysicalWidth() == info.width
                    && mMode.getPhysicalHeight() == info.height
                    && modeRefreshRate == displayInfoRefreshRate;
        }

        public String toString() {
            return "DisplayModeRecord{mMode=" + mMode + "}";
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
