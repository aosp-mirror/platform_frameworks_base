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

import android.app.ActivityThread;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.sidekick.SidekickInternal;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.LongSparseArray;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Spline;
import android.view.Display;
import android.view.DisplayAddress;
import android.view.DisplayCutout;
import android.view.DisplayEventReceiver;
import android.view.SurfaceControl;

import com.android.internal.BrightnessSynchronizer;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.LocalServices;
import com.android.server.lights.LightsManager;
import com.android.server.lights.LogicalLight;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A display adapter for the local displays managed by SurfaceFlinger.
 * <p>
 * Display adapters are guarded by the {@link DisplayManagerService.SyncRoot} lock.
 * </p>
 */
final class LocalDisplayAdapter extends DisplayAdapter {
    private static final String TAG = "LocalDisplayAdapter";
    private static final boolean DEBUG = false;

    private static final String UNIQUE_ID_PREFIX = "local:";

    private static final String PROPERTY_EMULATOR_CIRCULAR = "ro.emulator.circular";

    private static final int NO_DISPLAY_MODE_ID = 0;

    private final LongSparseArray<LocalDisplayDevice> mDevices = new LongSparseArray<>();

    @SuppressWarnings("unused")  // Becomes active at instantiation time.
    private PhysicalDisplayEventReceiver mPhysicalDisplayEventReceiver;


    // Called with SyncRoot lock held.
    public LocalDisplayAdapter(DisplayManagerService.SyncRoot syncRoot,
            Context context, Handler handler, Listener listener) {
        super(syncRoot, context, handler, listener, TAG);
    }

    @Override
    public void registerLocked() {
        super.registerLocked();

        mPhysicalDisplayEventReceiver = new PhysicalDisplayEventReceiver(getHandler().getLooper());

        for (long physicalDisplayId : SurfaceControl.getPhysicalDisplayIds()) {
            tryConnectDisplayLocked(physicalDisplayId);
        }
    }

    private void tryConnectDisplayLocked(long physicalDisplayId) {
        final IBinder displayToken = SurfaceControl.getPhysicalDisplayToken(physicalDisplayId);
        if (displayToken != null) {
            SurfaceControl.DisplayInfo info = SurfaceControl.getDisplayInfo(displayToken);
            if (info == null) {
                Slog.w(TAG, "No valid info found for display device " + physicalDisplayId);
                return;
            }
            SurfaceControl.DisplayConfig[] configs = SurfaceControl.getDisplayConfigs(displayToken);
            if (configs == null) {
                // There are no valid configs for this device, so we can't use it
                Slog.w(TAG, "No valid configs found for display device " + physicalDisplayId);
                return;
            }
            int activeConfig = SurfaceControl.getActiveConfig(displayToken);
            if (activeConfig < 0) {
                // There is no active config, and for now we don't have the
                // policy to set one.
                Slog.w(TAG, "No active config found for display device " + physicalDisplayId);
                return;
            }
            int activeColorMode = SurfaceControl.getActiveColorMode(displayToken);
            if (activeColorMode < 0) {
                // We failed to get the active color mode. We don't bail out here since on the next
                // configuration pass we'll go ahead and set it to whatever it was set to last (or
                // COLOR_MODE_NATIVE if this is the first configuration).
                Slog.w(TAG, "Unable to get active color mode for display device " +
                        physicalDisplayId);
                activeColorMode = Display.COLOR_MODE_INVALID;
            }
            SurfaceControl.DesiredDisplayConfigSpecs configSpecs =
                    SurfaceControl.getDesiredDisplayConfigSpecs(displayToken);
            int[] colorModes = SurfaceControl.getDisplayColorModes(displayToken);
            Display.HdrCapabilities hdrCapabilities =
                    SurfaceControl.getHdrCapabilities(displayToken);
            LocalDisplayDevice device = mDevices.get(physicalDisplayId);
            if (device == null) {
                // Display was added.
                final boolean isDefaultDisplay = mDevices.size() == 0;
                device = new LocalDisplayDevice(displayToken, physicalDisplayId, info,
                        configs, activeConfig, configSpecs, colorModes, activeColorMode,
                        hdrCapabilities, isDefaultDisplay);
                mDevices.put(physicalDisplayId, device);
                sendDisplayDeviceEventLocked(device, DISPLAY_DEVICE_EVENT_ADDED);
            } else if (device.updateDisplayProperties(configs, activeConfig,
                    configSpecs, colorModes, activeColorMode, hdrCapabilities)) {
                sendDisplayDeviceEventLocked(device, DISPLAY_DEVICE_EVENT_CHANGED);
            }
        } else {
            // The display is no longer available. Ignore the attempt to add it.
            // If it was connected but has already been disconnected, we'll get a
            // disconnect event that will remove it from mDevices.
        }
    }

    private void tryDisconnectDisplayLocked(long physicalDisplayId) {
        LocalDisplayDevice device = mDevices.get(physicalDisplayId);
        if (device != null) {
            // Display was removed.
            mDevices.remove(physicalDisplayId);
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
            case Display.STATE_ON_SUSPEND:
                return SurfaceControl.POWER_MODE_ON_SUSPEND;
            default:
                return SurfaceControl.POWER_MODE_NORMAL;
        }
    }

    private final class LocalDisplayDevice extends DisplayDevice {
        private final long mPhysicalDisplayId;
        private final LogicalLight mBacklight;
        private final SparseArray<DisplayModeRecord> mSupportedModes = new SparseArray<>();
        private final ArrayList<Integer> mSupportedColorModes = new ArrayList<>();
        private final boolean mIsDefaultDisplay;

        private DisplayDeviceInfo mInfo;
        private boolean mHavePendingChanges;
        private int mState = Display.STATE_UNKNOWN;
        private float mBrightnessState = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        private int mDefaultModeId;
        private int mDefaultConfigGroup;
        private int mActiveModeId;
        private boolean mActiveModeInvalid;
        private DisplayModeDirector.DesiredDisplayModeSpecs mDisplayModeSpecs =
                new DisplayModeDirector.DesiredDisplayModeSpecs();
        private boolean mDisplayModeSpecsInvalid;
        private int mActiveConfigId;
        private int mActiveColorMode;
        private boolean mActiveColorModeInvalid;
        private Display.HdrCapabilities mHdrCapabilities;
        private boolean mAllmSupported;
        private boolean mGameContentTypeSupported;
        private boolean mAllmRequested;
        private boolean mGameContentTypeRequested;
        private boolean mSidekickActive;
        private SidekickInternal mSidekickInternal;
        private SurfaceControl.DisplayInfo mDisplayInfo;
        private SurfaceControl.DisplayConfig[] mDisplayConfigs;
        private Spline mSystemBrightnessToNits;
        private Spline mNitsToHalBrightness;
        private boolean mHalBrightnessSupport;

        LocalDisplayDevice(IBinder displayToken, long physicalDisplayId,
                SurfaceControl.DisplayInfo info, SurfaceControl.DisplayConfig[] configs,
                int activeConfigId, SurfaceControl.DesiredDisplayConfigSpecs configSpecs,
                int[] colorModes, int activeColorMode, Display.HdrCapabilities hdrCapabilities,
                boolean isDefaultDisplay) {
            super(LocalDisplayAdapter.this, displayToken, UNIQUE_ID_PREFIX + physicalDisplayId);
            mPhysicalDisplayId = physicalDisplayId;
            mIsDefaultDisplay = isDefaultDisplay;
            mDisplayInfo = info;
            updateDisplayProperties(configs, activeConfigId, configSpecs, colorModes,
                    activeColorMode, hdrCapabilities);
            mSidekickInternal = LocalServices.getService(SidekickInternal.class);
            if (mIsDefaultDisplay) {
                LightsManager lights = LocalServices.getService(LightsManager.class);
                mBacklight = lights.getLight(LightsManager.LIGHT_ID_BACKLIGHT);
            } else {
                mBacklight = null;
            }
            mAllmSupported = SurfaceControl.getAutoLowLatencyModeSupport(displayToken);
            mGameContentTypeSupported = SurfaceControl.getGameContentTypeSupport(displayToken);
            mHalBrightnessSupport = SurfaceControl.getDisplayBrightnessSupport(displayToken);

            // Defer configuration file loading
            BackgroundThread.getHandler().sendMessage(PooledLambda.obtainMessage(
                    LocalDisplayDevice::loadDisplayConfigurationBrightnessMapping, this));
        }

        @Override
        public boolean hasStableUniqueId() {
            return true;
        }

        /**
         * Returns true if there is a change.
         **/
        public boolean updateDisplayProperties(SurfaceControl.DisplayConfig[] configs,
                int activeConfigId, SurfaceControl.DesiredDisplayConfigSpecs configSpecs,
                int[] colorModes, int activeColorMode, Display.HdrCapabilities hdrCapabilities) {
            boolean changed = updateDisplayConfigsLocked(configs, activeConfigId, configSpecs);
            changed |= updateColorModesLocked(colorModes, activeColorMode);
            changed |= updateHdrCapabilitiesLocked(hdrCapabilities);
            return changed;
        }

        public boolean updateDisplayConfigsLocked(
                SurfaceControl.DisplayConfig[] configs, int activeConfigId,
                SurfaceControl.DesiredDisplayConfigSpecs configSpecs) {
            mDisplayConfigs = Arrays.copyOf(configs, configs.length);
            mActiveConfigId = activeConfigId;
            // Build an updated list of all existing modes.
            ArrayList<DisplayModeRecord> records = new ArrayList<>();
            boolean modesAdded = false;
            for (int i = 0; i < configs.length; i++) {
                SurfaceControl.DisplayConfig config = configs[i];
                // First, check to see if we've already added a matching mode. Since not all
                // configuration options are exposed via Display.Mode, it's possible that we have
                // multiple DisplayConfigs that would generate the same Display.Mode.
                boolean existingMode = false;
                for (int j = 0; j < records.size(); j++) {
                    if (records.get(j).hasMatchingMode(config)) {
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
                DisplayModeRecord record = findDisplayModeRecord(config);
                if (record == null) {
                    record = new DisplayModeRecord(config);
                    modesAdded = true;
                }
                records.add(record);
            }

            // Get the currently active mode
            DisplayModeRecord activeRecord = null;
            for (int i = 0; i < records.size(); i++) {
                DisplayModeRecord record = records.get(i);
                if (record.hasMatchingMode(configs[activeConfigId])) {
                    activeRecord = record;
                    break;
                }
            }

            // Check whether surface flinger spontaneously changed modes out from under us.
            // Schedule traversals to ensure that the correct state is reapplied if necessary.
            if (mActiveModeId != NO_DISPLAY_MODE_ID
                    && mActiveModeId != activeRecord.mMode.getModeId()) {
                mActiveModeInvalid = true;
                sendTraversalRequestLocked();
            }

            // Check whether surface flinger spontaneously changed display config specs out from
            // under us. If so, schedule a traversal to reapply our display config specs.
            if (mDisplayModeSpecs.baseModeId != NO_DISPLAY_MODE_ID) {
                int activeBaseMode = findMatchingModeIdLocked(configSpecs.defaultConfig);
                // If we can't map the defaultConfig index to a mode, then the physical display
                // configs must have changed, and the code below for handling changes to the
                // list of available modes will take care of updating display config specs.
                if (activeBaseMode != NO_DISPLAY_MODE_ID) {
                    if (mDisplayModeSpecs.baseModeId != activeBaseMode
                            || mDisplayModeSpecs.refreshRateRange.min != configSpecs.minRefreshRate
                            || mDisplayModeSpecs.refreshRateRange.max
                                    != configSpecs.maxRefreshRate) {
                        mDisplayModeSpecsInvalid = true;
                        sendTraversalRequestLocked();
                    }
                }
            }

            boolean recordsChanged = records.size() != mSupportedModes.size() || modesAdded;
            // If the records haven't changed then we're done here.
            if (!recordsChanged) {
                return false;
            }
            // Update the index of modes.
            mHavePendingChanges = true;

            mSupportedModes.clear();
            for (DisplayModeRecord record : records) {
                mSupportedModes.put(record.mMode.getModeId(), record);
            }

            // For a new display, we need to initialize the default mode ID.
            if (mDefaultModeId == NO_DISPLAY_MODE_ID) {
                mDefaultModeId = activeRecord.mMode.getModeId();
                mDefaultConfigGroup = configs[activeConfigId].configGroup;
            } else if (modesAdded && mActiveModeId != activeRecord.mMode.getModeId()) {
                Slog.d(TAG, "New display modes are added and the active mode has changed, "
                        + "use active mode as default mode.");
                mActiveModeId = activeRecord.mMode.getModeId();
                mDefaultModeId = activeRecord.mMode.getModeId();
                mDefaultConfigGroup = configs[activeConfigId].configGroup;
            } else if (findDisplayConfigIdLocked(mDefaultModeId, mDefaultConfigGroup) < 0) {
                Slog.w(TAG, "Default display mode no longer available, using currently"
                        + " active mode as default.");
                mDefaultModeId = activeRecord.mMode.getModeId();
                mDefaultConfigGroup = configs[activeConfigId].configGroup;
            }

            // Determine whether the display mode specs' base mode is still there.
            if (mSupportedModes.indexOfKey(mDisplayModeSpecs.baseModeId) < 0) {
                if (mDisplayModeSpecs.baseModeId != NO_DISPLAY_MODE_ID) {
                    Slog.w(TAG,
                            "DisplayModeSpecs base mode no longer available, using currently"
                                    + " active mode.");
                }
                mDisplayModeSpecs.baseModeId = activeRecord.mMode.getModeId();
                mDisplayModeSpecsInvalid = true;
            }

            // Determine whether the active mode is still there.
            if (mSupportedModes.indexOfKey(mActiveModeId) < 0) {
                if (mActiveModeId != NO_DISPLAY_MODE_ID) {
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

        private void loadDisplayConfigurationBrightnessMapping() {
            Spline nitsToHal = null;
            Spline sysToNits = null;

            // Load the mapping from nits to HAL brightness range (display-device-config.xml)
            DisplayDeviceConfig config = DisplayDeviceConfig.create(mPhysicalDisplayId);
            if (config == null) {
                return;
            }
            final float[] halNits = config.getNits();
            final float[] halBrightness = config.getBrightness();
            if (halNits == null || halBrightness == null) {
                return;
            }
            nitsToHal = Spline.createSpline(halNits, halBrightness);

            // Load the mapping from system brightness range to nits (config.xml)
            final Resources res = getOverlayContext().getResources();
            final float[] sysNits = BrightnessMappingStrategy.getFloatArray(res.obtainTypedArray(
                            com.android.internal.R.array.config_screenBrightnessNits));
            final int[] sysBrightness = res.getIntArray(
                    com.android.internal.R.array.config_screenBrightnessBacklight);
            if (sysNits.length == 0 || sysBrightness.length != sysNits.length) {
                return;
            }
            final float[] sysBrightnessFloat = new float[sysBrightness.length];
            for (int i = 0; i < sysBrightness.length; i++) {
                sysBrightnessFloat[i] = sysBrightness[i];
            }
            sysToNits = Spline.createSpline(sysBrightnessFloat, sysNits);

            mNitsToHalBrightness = nitsToHal;
            mSystemBrightnessToNits = sysToNits;
        }

        private boolean updateColorModesLocked(int[] colorModes, int activeColorMode) {
            if (colorModes == null) {
                return false;
            }

            List<Integer> pendingColorModes = new ArrayList<>();
            // Build an updated list of all existing color modes.
            boolean colorModesAdded = false;
            for (int colorMode : colorModes) {
                if (!mSupportedColorModes.contains(colorMode)) {
                    colorModesAdded = true;
                }
                pendingColorModes.add(colorMode);
            }

            boolean colorModesChanged =
                    pendingColorModes.size() != mSupportedColorModes.size()
                    || colorModesAdded;

            // If the supported color modes haven't changed then we're done here.
            if (!colorModesChanged) {
                return false;
            }

            mHavePendingChanges = true;

            mSupportedColorModes.clear();
            mSupportedColorModes.addAll(pendingColorModes);
            Collections.sort(mSupportedColorModes);

            // Determine whether the active color mode is still there.
            if (!mSupportedColorModes.contains(mActiveColorMode)) {
                if (mActiveColorMode != 0) {
                    Slog.w(TAG, "Active color mode no longer available, reverting"
                            + " to default mode.");
                    mActiveColorMode = Display.COLOR_MODE_DEFAULT;
                    mActiveColorModeInvalid = true;
                } else {
                    if (!mSupportedColorModes.isEmpty()) {
                        // This should never happen.
                        Slog.e(TAG, "Default and active color mode is no longer available!"
                                + " Reverting to first available mode.");
                        mActiveColorMode = mSupportedColorModes.get(0);
                        mActiveColorModeInvalid = true;
                    } else {
                        // This should really never happen.
                        Slog.e(TAG, "No color modes available!");
                    }
                }
            }
            return true;
        }

        private boolean updateHdrCapabilitiesLocked(Display.HdrCapabilities newHdrCapabilities) {
            // If the HDR capabilities haven't changed, then we're done here.
            if (Objects.equals(mHdrCapabilities, newHdrCapabilities)) {
                return false;
            }
            mHdrCapabilities = newHdrCapabilities;
            return true;
        }

        private DisplayModeRecord findDisplayModeRecord(SurfaceControl.DisplayConfig config) {
            for (int i = 0; i < mSupportedModes.size(); i++) {
                DisplayModeRecord record = mSupportedModes.valueAt(i);
                if (record.hasMatchingMode(config)) {
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
                SurfaceControl.DisplayConfig config = mDisplayConfigs[mActiveConfigId];
                mInfo = new DisplayDeviceInfo();
                mInfo.width = config.width;
                mInfo.height = config.height;
                mInfo.modeId = mActiveModeId;
                mInfo.defaultModeId = mDefaultModeId;
                mInfo.supportedModes = getDisplayModes(mSupportedModes);
                mInfo.colorMode = mActiveColorMode;
                mInfo.allmSupported = mAllmSupported;
                mInfo.gameContentTypeSupported = mGameContentTypeSupported;
                mInfo.supportedColorModes =
                        new int[mSupportedColorModes.size()];
                for (int i = 0; i < mSupportedColorModes.size(); i++) {
                    mInfo.supportedColorModes[i] = mSupportedColorModes.get(i);
                }
                mInfo.hdrCapabilities = mHdrCapabilities;
                mInfo.appVsyncOffsetNanos = config.appVsyncOffsetNanos;
                mInfo.presentationDeadlineNanos = config.presentationDeadlineNanos;
                mInfo.state = mState;
                mInfo.uniqueId = getUniqueId();
                final DisplayAddress.Physical physicalAddress =
                        DisplayAddress.fromPhysicalDisplayId(mPhysicalDisplayId);
                mInfo.address = physicalAddress;
                mInfo.densityDpi = (int) (mDisplayInfo.density * 160 + 0.5f);
                mInfo.xDpi = config.xDpi;
                mInfo.yDpi = config.yDpi;
                mInfo.deviceProductInfo = mDisplayInfo.deviceProductInfo;

                // Assume that all built-in displays that have secure output (eg. HDCP) also
                // support compositing from gralloc protected buffers.
                if (mDisplayInfo.secure) {
                    mInfo.flags = DisplayDeviceInfo.FLAG_SECURE
                            | DisplayDeviceInfo.FLAG_SUPPORTS_PROTECTED_BUFFERS;
                }

                final Resources res = getOverlayContext().getResources();

                if (mIsDefaultDisplay) {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY;

                    if (res.getBoolean(com.android.internal.R.bool.config_mainBuiltInDisplayIsRound)
                            || (Build.IS_EMULATOR
                            && SystemProperties.getBoolean(PROPERTY_EMULATOR_CIRCULAR, false))) {
                        mInfo.flags |= DisplayDeviceInfo.FLAG_ROUND;
                    }
                    if (res.getBoolean(
                            com.android.internal.R.bool.config_maskMainBuiltInDisplayCutout)) {
                        mInfo.flags |= DisplayDeviceInfo.FLAG_MASK_DISPLAY_CUTOUT;
                    }
                    mInfo.displayCutout = DisplayCutout.fromResourcesRectApproximation(res,
                            mInfo.width, mInfo.height);
                } else {
                    if (!res.getBoolean(
                                com.android.internal.R.bool.config_localDisplaysMirrorContent)) {
                        mInfo.flags |= DisplayDeviceInfo.FLAG_OWN_CONTENT_ONLY;
                    }

                    if (isDisplayPrivate(physicalAddress)) {
                        mInfo.flags |= DisplayDeviceInfo.FLAG_PRIVATE;
                    }
                }

                if (mDisplayInfo.isInternal) {
                    mInfo.type = Display.TYPE_INTERNAL;
                    mInfo.touch = DisplayDeviceInfo.TOUCH_INTERNAL;
                    mInfo.flags |= DisplayDeviceInfo.FLAG_ROTATES_WITH_CONTENT;
                    mInfo.name = res.getString(
                            com.android.internal.R.string.display_manager_built_in_display_name);
                } else {
                    mInfo.type = Display.TYPE_EXTERNAL;
                    mInfo.touch = DisplayDeviceInfo.TOUCH_EXTERNAL;
                    mInfo.flags |= DisplayDeviceInfo.FLAG_PRESENTATION;
                    mInfo.name = getContext().getResources().getString(
                            com.android.internal.R.string.display_manager_hdmi_display_name);
                }
            }
            return mInfo;
        }

        @Override
        public Runnable requestDisplayStateLocked(final int state, final float brightnessState) {
            // Assume that the brightness is off if the display is being turned off.
            assert state != Display.STATE_OFF || BrightnessSynchronizer.floatEquals(
                    brightnessState, PowerManager.BRIGHTNESS_OFF_FLOAT);
            final boolean stateChanged = (mState != state);
            final boolean brightnessChanged = (!BrightnessSynchronizer.floatEquals(
                    mBrightnessState, brightnessState))
                    && mBacklight != null;
            if (stateChanged || brightnessChanged) {
                final long physicalDisplayId = mPhysicalDisplayId;
                final IBinder token = getDisplayTokenLocked();
                final int oldState = mState;

                if (stateChanged) {
                    mState = state;
                    updateDeviceInfoLocked();
                }

                if (brightnessChanged) {
                    mBrightnessState = brightnessState;
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
                            } else if (state == Display.STATE_ON_SUSPEND
                                    || oldState == Display.STATE_ON_SUSPEND) {
                                setDisplayState(Display.STATE_ON);
                                currentState = Display.STATE_ON;
                            } else {
                                return; // old state and new state is off
                            }
                        }

                        // If the state change was from or to VR, then we need to tell the light
                        // so that it can apply appropriate VR brightness settings. Also, update the
                        // brightness so the state is propogated to light.
                        boolean vrModeChange = false;
                        if ((state == Display.STATE_VR || currentState == Display.STATE_VR) &&
                                currentState != state) {
                            setVrMode(state == Display.STATE_VR);
                            vrModeChange = true;
                        }

                        // Apply brightness changes given that we are in a non-suspended state.
                        if (brightnessChanged || vrModeChange) {
                            setDisplayBrightness(brightnessState);
                        }

                        // Enter the final desired state, possibly suspended.
                        if (state != currentState) {
                            setDisplayState(state);
                        }
                    }

                    private void setVrMode(boolean isVrEnabled) {
                        if (DEBUG) {
                            Slog.d(TAG, "setVrMode("
                                    + "id=" + physicalDisplayId
                                    + ", state=" + Display.stateToString(state) + ")");
                        }
                        if (mBacklight != null) {
                            mBacklight.setVrMode(isVrEnabled);
                        }
                    }

                    private void setDisplayState(int state) {
                        if (DEBUG) {
                            Slog.d(TAG, "setDisplayState("
                                    + "id=" + physicalDisplayId
                                    + ", state=" + Display.stateToString(state) + ")");
                        }

                        // We must tell sidekick to stop controlling the display before we
                        // can change its power mode, so do that first.
                        if (mSidekickActive) {
                            Trace.traceBegin(Trace.TRACE_TAG_POWER,
                                    "SidekickInternal#endDisplayControl");
                            try {
                                mSidekickInternal.endDisplayControl();
                            } finally {
                                Trace.traceEnd(Trace.TRACE_TAG_POWER);
                            }
                            mSidekickActive = false;
                        }
                        final int mode = getPowerModeForState(state);
                        Trace.traceBegin(Trace.TRACE_TAG_POWER, "setDisplayState("
                                + "id=" + physicalDisplayId
                                + ", state=" + Display.stateToString(state) + ")");
                        try {
                            SurfaceControl.setDisplayPowerMode(token, mode);
                            Trace.traceCounter(Trace.TRACE_TAG_POWER, "DisplayPowerMode", mode);
                        } finally {
                            Trace.traceEnd(Trace.TRACE_TAG_POWER);
                        }
                        // If we're entering a suspended (but not OFF) power state and we
                        // have a sidekick available, tell it now that it can take control.
                        if (Display.isSuspendedState(state) && state != Display.STATE_OFF
                                && mSidekickInternal != null && !mSidekickActive) {
                            Trace.traceBegin(Trace.TRACE_TAG_POWER,
                                    "SidekickInternal#startDisplayControl");
                            try {
                                mSidekickActive = mSidekickInternal.startDisplayControl(state);
                            } finally {
                                Trace.traceEnd(Trace.TRACE_TAG_POWER);
                            }
                        }
                    }

                    private void setDisplayBrightness(float brightness) {
                        if (DEBUG) {
                            Slog.d(TAG, "setDisplayBrightness("
                                    + "id=" + physicalDisplayId
                                    + ", brightness=" + brightness + ")");
                        }

                        Trace.traceBegin(Trace.TRACE_TAG_POWER, "setDisplayBrightness("
                                + "id=" + physicalDisplayId + ", brightness=" + brightness + ")");
                        try {
                            // TODO: make it float
                            if (isHalBrightnessRangeSpecified()) {
                                brightness = displayBrightnessToHalBrightness(
                                        BrightnessSynchronizer.brightnessFloatToInt(getContext(),
                                                brightness));
                            }
                            if (mBacklight != null) {
                                mBacklight.setBrightness(brightness);
                            }
                            Trace.traceCounter(Trace.TRACE_TAG_POWER,
                                    "ScreenBrightness",
                                    BrightnessSynchronizer.brightnessFloatToInt(
                                            getContext(), brightness));
                        } finally {
                            Trace.traceEnd(Trace.TRACE_TAG_POWER);
                        }
                    }

                    private boolean isHalBrightnessRangeSpecified() {
                        return !(mSystemBrightnessToNits == null || mNitsToHalBrightness == null);
                    }

                    /**
                     * Converts brightness range from the framework's brightness space to the
                     * Hal brightness space if the HAL brightness space has been provided via
                     * a display device configuration file.
                     */
                    private float displayBrightnessToHalBrightness(int brightness) {
                        if (!isHalBrightnessRangeSpecified()) {
                            return PowerManager.BRIGHTNESS_INVALID_FLOAT;
                        }

                        if (brightness == 0) {
                            return PowerManager.BRIGHTNESS_OFF_FLOAT;
                        }

                        final float nits = mSystemBrightnessToNits.interpolate(brightness);
                        final float halBrightness = mNitsToHalBrightness.interpolate(nits);
                        return halBrightness;
                    }
                };
            }
            return null;
        }

        @Override
        public void setRequestedColorModeLocked(int colorMode) {
            requestColorModeLocked(colorMode);
        }

        @Override
        public void setDesiredDisplayModeSpecsLocked(
                DisplayModeDirector.DesiredDisplayModeSpecs displayModeSpecs) {
            if (displayModeSpecs.baseModeId == 0) {
                // Bail if the caller is requesting a null mode. We'll get called again shortly with
                // a valid mode.
                return;
            }

            // Find the config Id based on the desired mode specs. In case there is more than one
            // config matching the mode spec, prefer the one that is in the default config group.
            // For now the default config group is taken from the active config when we got the
            // hotplug event for the display. In the future we might want to change the default
            // config based on vendor requirements.
            // Note: We prefer the default config group over the current one as this is the config
            // group the vendor prefers.
            int baseConfigId = findDisplayConfigIdLocked(displayModeSpecs.baseModeId,
                    mDefaultConfigGroup);
            if (baseConfigId < 0) {
                // When a display is hotplugged, it's possible for a mode to be removed that was
                // previously valid. Because of the way display changes are propagated through the
                // framework, and the caching of the display mode specs in LogicalDisplay, it's
                // possible we'll get called with a stale mode id that no longer represents a valid
                // mode. This should only happen in extremely rare cases. A followup call will
                // contain a valid mode id.
                Slog.w(TAG,
                        "Ignoring request for invalid base mode id " + displayModeSpecs.baseModeId);
                updateDeviceInfoLocked();
                return;
            }
            if (mDisplayModeSpecsInvalid || !displayModeSpecs.equals(mDisplayModeSpecs)) {
                mDisplayModeSpecsInvalid = false;
                mDisplayModeSpecs.copyFrom(displayModeSpecs);
                getHandler().sendMessage(PooledLambda.obtainMessage(
                        LocalDisplayDevice::setDesiredDisplayModeSpecsAsync, this,
                        getDisplayTokenLocked(),
                        new SurfaceControl.DesiredDisplayConfigSpecs(baseConfigId,
                                mDisplayModeSpecs.refreshRateRange.min,
                                mDisplayModeSpecs.refreshRateRange.max)));
            }
        }

        private void setDesiredDisplayModeSpecsAsync(IBinder displayToken,
                SurfaceControl.DesiredDisplayConfigSpecs configSpecs) {
            // Do not lock when calling these SurfaceControl methods because they are sync
            // operations that may block for a while when setting display power mode.
            SurfaceControl.setDesiredDisplayConfigSpecs(displayToken, configSpecs);
            final int activePhysIndex = SurfaceControl.getActiveConfig(displayToken);
            synchronized (getSyncRoot()) {
                if (updateActiveModeLocked(activePhysIndex)) {
                    updateDeviceInfoLocked();
                }
            }
        }

        @Override
        public void onOverlayChangedLocked() {
            updateDeviceInfoLocked();
        }

        public void onActiveDisplayConfigChangedLocked(int configId) {
            if (updateActiveModeLocked(configId)) {
                updateDeviceInfoLocked();
            }
        }

        public boolean updateActiveModeLocked(int activeConfigId) {
            if (mActiveConfigId == activeConfigId) {
                return false;
            }
            mActiveConfigId = activeConfigId;
            mActiveModeId = findMatchingModeIdLocked(activeConfigId);
            mActiveModeInvalid = mActiveModeId == NO_DISPLAY_MODE_ID;
            if (mActiveModeInvalid) {
                Slog.w(TAG, "In unknown mode after setting allowed configs"
                        + ", activeConfigId=" + mActiveConfigId);
            }
            return true;
        }

        public void requestColorModeLocked(int colorMode) {
            if (mActiveColorMode == colorMode) {
                return;
            }
            if (!mSupportedColorModes.contains(colorMode)) {
                Slog.w(TAG, "Unable to find color mode " + colorMode
                        + ", ignoring request.");
                return;
            }

            mActiveColorMode = colorMode;
            mActiveColorModeInvalid = false;
            getHandler().sendMessage(PooledLambda.obtainMessage(
                    LocalDisplayDevice::requestColorModeAsync, this,
                    getDisplayTokenLocked(), colorMode));
        }

        private void requestColorModeAsync(IBinder displayToken, int colorMode) {
            // Do not lock when calling this SurfaceControl method because it is a sync operation
            // that may block for a while when setting display power mode.
            SurfaceControl.setActiveColorMode(displayToken, colorMode);
            synchronized (getSyncRoot()) {
                updateDeviceInfoLocked();
            }
        }

        @Override
        public void setAutoLowLatencyModeLocked(boolean on) {
            if (mAllmRequested == on) {
                return;
            }

            mAllmRequested = on;

            if (!mAllmSupported) {
                Slog.d(TAG, "Unable to set ALLM because the connected display "
                        + "does not support ALLM.");
                return;
            }

            SurfaceControl.setAutoLowLatencyMode(getDisplayTokenLocked(), on);
        }

        @Override
        public void setGameContentTypeLocked(boolean on) {
            if (mGameContentTypeRequested == on) {
                return;
            }

            mGameContentTypeRequested = on;

            if (!mGameContentTypeSupported) {
                Slog.d(TAG, "Unable to set game content type because the connected "
                        + "display does not support game content type.");
                return;
            }

            SurfaceControl.setGameContentType(getDisplayTokenLocked(), on);
        }

        @Override
        public void dumpLocked(PrintWriter pw) {
            super.dumpLocked(pw);
            pw.println("mPhysicalDisplayId=" + mPhysicalDisplayId);
            pw.println("mDisplayModeSpecs={" + mDisplayModeSpecs + "}");
            pw.println("mDisplayModeSpecsInvalid=" + mDisplayModeSpecsInvalid);
            pw.println("mActiveConfigId=" + mActiveConfigId);
            pw.println("mActiveModeId=" + mActiveModeId);
            pw.println("mActiveColorMode=" + mActiveColorMode);
            pw.println("mDefaultModeId=" + mDefaultModeId);
            pw.println("mState=" + Display.stateToString(mState));
            pw.println("mBrightnessState=" + mBrightnessState);
            pw.println("mBacklight=" + mBacklight);
            pw.println("mAllmSupported=" + mAllmSupported);
            pw.println("mAllmRequested=" + mAllmRequested);
            pw.println("mGameContentTypeSupported=" + mGameContentTypeSupported);
            pw.println("mGameContentTypeRequested=" + mGameContentTypeRequested);
            pw.println("mDisplayInfo=" + mDisplayInfo);
            pw.println("mDisplayConfigs=");
            for (int i = 0; i < mDisplayConfigs.length; i++) {
                pw.println("  " + mDisplayConfigs[i]);
            }
            pw.println("mSupportedModes=");
            for (int i = 0; i < mSupportedModes.size(); i++) {
                pw.println("  " + mSupportedModes.valueAt(i));
            }
            pw.print("mSupportedColorModes=" + mSupportedColorModes.toString());
        }

        private int findDisplayConfigIdLocked(int modeId, int configGroup) {
            int matchingConfigId = SurfaceControl.DisplayConfig.INVALID_DISPLAY_CONFIG_ID;
            DisplayModeRecord record = mSupportedModes.get(modeId);
            if (record != null) {
                for (int i = 0; i < mDisplayConfigs.length; i++) {
                    SurfaceControl.DisplayConfig config = mDisplayConfigs[i];
                    if (record.hasMatchingMode(config)) {
                        if (matchingConfigId
                                == SurfaceControl.DisplayConfig.INVALID_DISPLAY_CONFIG_ID) {
                            matchingConfigId = i;
                        }

                        // Prefer to return a config that matches the configGroup
                        if (config.configGroup == configGroup) {
                            return i;
                        }
                    }
                }
            }
            return matchingConfigId;
        }

        private int findMatchingModeIdLocked(int configId) {
            SurfaceControl.DisplayConfig config = mDisplayConfigs[configId];
            for (int i = 0; i < mSupportedModes.size(); i++) {
                DisplayModeRecord record = mSupportedModes.valueAt(i);
                if (record.hasMatchingMode(config)) {
                    return record.mMode.getModeId();
                }
            }
            return NO_DISPLAY_MODE_ID;
        }

        private void updateDeviceInfoLocked() {
            mInfo = null;
            sendDisplayDeviceEventLocked(this, DISPLAY_DEVICE_EVENT_CHANGED);
        }

        private Display.Mode[] getDisplayModes(SparseArray<DisplayModeRecord> records) {
            final int size = records.size();
            Display.Mode[] modes = new Display.Mode[size];
            for (int i = 0; i < size; i++) {
                DisplayModeRecord record = records.valueAt(i);
                modes[i] = record.mMode;
            }
            return modes;
        }

        private boolean isDisplayPrivate(DisplayAddress.Physical physicalAddress) {
            if (physicalAddress == null) {
                return false;
            }
            final Resources res = getOverlayContext().getResources();
            int[] ports = res.getIntArray(
                    com.android.internal.R.array.config_localPrivateDisplayPorts);
            if (ports != null) {
                int port = Byte.toUnsignedInt(physicalAddress.getPort());
                for (int p : ports) {
                    if (p == port) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /** Supplies a context whose Resources apply runtime-overlays */
    Context getOverlayContext() {
        return ActivityThread.currentActivityThread().getSystemUiContext();
    }

    /**
     * Keeps track of a display configuration.
     */
    private static final class DisplayModeRecord {
        public final Display.Mode mMode;

        DisplayModeRecord(SurfaceControl.DisplayConfig config) {
            mMode = createMode(config.width, config.height, config.refreshRate);
        }

        /**
         * Returns whether the mode generated by the given DisplayConfig matches the mode
         * contained by the record modulo mode ID.
         *
         * Note that this doesn't necessarily mean that the DisplayConfigs are identical, just
         * that they generate identical modes.
         */
        public boolean hasMatchingMode(SurfaceControl.DisplayConfig config) {
            int modeRefreshRate = Float.floatToIntBits(mMode.getRefreshRate());
            int configRefreshRate = Float.floatToIntBits(config.refreshRate);
            return mMode.getPhysicalWidth() == config.width
                    && mMode.getPhysicalHeight() == config.height
                    && modeRefreshRate == configRefreshRate;
        }

        public String toString() {
            return "DisplayModeRecord{mMode=" + mMode + "}";
        }
    }

    private final class PhysicalDisplayEventReceiver extends DisplayEventReceiver {
        PhysicalDisplayEventReceiver(Looper looper) {
            super(looper, VSYNC_SOURCE_APP, CONFIG_CHANGED_EVENT_DISPATCH);
        }

        @Override
        public void onHotplug(long timestampNanos, long physicalDisplayId, boolean connected) {
            synchronized (getSyncRoot()) {
                if (connected) {
                    tryConnectDisplayLocked(physicalDisplayId);
                } else {
                    tryDisconnectDisplayLocked(physicalDisplayId);
                }
            }
        }

        @Override
        public void onConfigChanged(long timestampNanos, long physicalDisplayId, int configId) {
            if (DEBUG) {
                Slog.d(TAG, "onConfigChanged("
                        + "timestampNanos=" + timestampNanos
                        + ", physicalDisplayId=" + physicalDisplayId
                        + ", configId=" + configId + ")");
            }
            synchronized (getSyncRoot()) {
                LocalDisplayDevice device = mDevices.get(physicalDisplayId);
                if (device == null) {
                    if (DEBUG) {
                        Slog.d(TAG, "Received config change for unhandled physical display: "
                                + "physicalDisplayId=" + physicalDisplayId);
                    }
                    return;
                }
                device.onActiveDisplayConfigChangedLocked(configId);
            }
        }
    }
}
