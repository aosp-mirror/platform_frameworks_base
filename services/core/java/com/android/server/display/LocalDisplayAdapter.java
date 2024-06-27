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

import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.Display.Mode.INVALID_MODE_ID;

import static com.android.server.display.BrightnessMappingStrategy.INVALID_NITS;

import android.annotation.Nullable;
import android.app.ActivityThread;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.display.DisplayManagerInternal.DisplayOffloadSession;
import android.hardware.sidekick.SidekickInternal;
import android.media.MediaDrm;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.DisplayUtils;
import android.util.LongSparseArray;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayAddress;
import android.view.DisplayCutout;
import android.view.DisplayEventReceiver;
import android.view.DisplayShape;
import android.view.RoundedCorners;
import android.view.SurfaceControl;

import com.android.internal.R;
import com.android.internal.annotations.KeepForWeakReference;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.display.BrightnessSynchronizer;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.LocalServices;
import com.android.server.display.feature.DisplayManagerFlags;
import com.android.server.display.mode.DisplayModeDirector;
import com.android.server.display.notifications.DisplayNotificationManager;
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

    private static final String PROPERTY_EMULATOR_CIRCULAR = "ro.boot.emulator.circular";

    private final LongSparseArray<LocalDisplayDevice> mDevices = new LongSparseArray<>();

    private final Injector mInjector;

    private final SurfaceControlProxy mSurfaceControlProxy;

    private final boolean mIsBootDisplayModeSupported;

    private final DisplayNotificationManager mDisplayNotificationManager;

    private Context mOverlayContext;

    // Called with SyncRoot lock held.
    LocalDisplayAdapter(DisplayManagerService.SyncRoot syncRoot, Context context,
            Handler handler, Listener listener, DisplayManagerFlags flags,
            DisplayNotificationManager displayNotificationManager) {
        this(syncRoot, context, handler, listener, flags, displayNotificationManager,
                new Injector());
    }

    @VisibleForTesting
    LocalDisplayAdapter(DisplayManagerService.SyncRoot syncRoot, Context context, Handler handler,
            Listener listener, DisplayManagerFlags flags,
            DisplayNotificationManager displayNotificationManager,
            Injector injector) {
        super(syncRoot, context, handler, listener, TAG, flags);
        mDisplayNotificationManager = displayNotificationManager;
        mInjector = injector;
        mSurfaceControlProxy = mInjector.getSurfaceControlProxy();
        mIsBootDisplayModeSupported = mSurfaceControlProxy.getBootDisplayModeSupport();
    }

    @Override
    public void registerLocked() {
        super.registerLocked();

        mInjector.setDisplayEventListenerLocked(getHandler().getLooper(),
                new LocalDisplayEventListener());

        for (long physicalDisplayId : mSurfaceControlProxy.getPhysicalDisplayIds()) {
            tryConnectDisplayLocked(physicalDisplayId);
        }
    }

    private void tryConnectDisplayLocked(long physicalDisplayId) {
        final IBinder displayToken =
                mSurfaceControlProxy.getPhysicalDisplayToken(physicalDisplayId);
        if (displayToken != null) {
            SurfaceControl.StaticDisplayInfo staticInfo =
                    mSurfaceControlProxy.getStaticDisplayInfo(physicalDisplayId);
            if (staticInfo == null) {
                Slog.w(TAG, "No valid static info found for display device " + physicalDisplayId);
                return;
            }
            SurfaceControl.DynamicDisplayInfo dynamicInfo =
                    mSurfaceControlProxy.getDynamicDisplayInfo(physicalDisplayId);
            if (dynamicInfo == null) {
                Slog.w(TAG, "No valid dynamic info found for display device " + physicalDisplayId);
                return;
            }
            if (dynamicInfo.supportedDisplayModes == null) {
                // There are no valid modes for this device, so we can't use it
                Slog.w(TAG, "No valid modes found for display device " + physicalDisplayId);
                return;
            }
            if (dynamicInfo.activeDisplayModeId < 0) {
                // There is no active mode, and for now we don't have the
                // policy to set one.
                Slog.w(TAG, "No valid active mode found for display device " + physicalDisplayId);
                return;
            }
            if (dynamicInfo.activeColorMode < 0) {
                // We failed to get the active color mode. We don't bail out here since on the next
                // configuration pass we'll go ahead and set it to whatever it was set to last (or
                // COLOR_MODE_NATIVE if this is the first configuration).
                Slog.w(TAG, "No valid active color mode for display device " + physicalDisplayId);
                dynamicInfo.activeColorMode = Display.COLOR_MODE_INVALID;
            }
            SurfaceControl.DesiredDisplayModeSpecs modeSpecs =
                    mSurfaceControlProxy.getDesiredDisplayModeSpecs(displayToken);
            LocalDisplayDevice device = mDevices.get(physicalDisplayId);
            if (device == null) {
                // Display was added.
                final boolean isFirstDisplay = mDevices.size() == 0;
                device = new LocalDisplayDevice(displayToken, physicalDisplayId, staticInfo,
                        dynamicInfo, modeSpecs, isFirstDisplay);
                mDevices.put(physicalDisplayId, device);
                sendDisplayDeviceEventLocked(device, DISPLAY_DEVICE_EVENT_ADDED);
            } else if (device.updateDisplayPropertiesLocked(staticInfo, dynamicInfo,
                    modeSpecs)) {
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
        private final SparseArray<DisplayModeRecord> mSupportedModes = new SparseArray<>();
        private final ArrayList<Integer> mSupportedColorModes = new ArrayList<>();
        private final DisplayModeDirector.DesiredDisplayModeSpecs mDisplayModeSpecs =
                new DisplayModeDirector.DesiredDisplayModeSpecs();
        private final boolean mIsFirstDisplay;
        private final BacklightAdapter mBacklightAdapter;
        private final SidekickInternal mSidekickInternal;

        private DisplayDeviceInfo mInfo;
        private boolean mHavePendingChanges;
        private int mState = Display.STATE_UNKNOWN;
        private int mCommittedState = Display.STATE_UNKNOWN;

        // This is only set in the runnable returned from requestDisplayStateLocked.
        private float mBrightnessState = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        private float mSdrBrightnessState = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        private float mCurrentHdrSdrRatio = Float.NaN;
        private int mDefaultModeId = INVALID_MODE_ID;
        private int mSystemPreferredModeId = INVALID_MODE_ID;
        private int mDefaultModeGroup;
        private int mUserPreferredModeId = INVALID_MODE_ID;
        // This is used only for the purpose of testing, to verify if the mode was correct when the
        // device started or booted.
        private int mActiveSfDisplayModeAtStartId = INVALID_MODE_ID;
        private Display.Mode mUserPreferredMode;
        private int mActiveModeId = INVALID_MODE_ID;
        private boolean mDisplayModeSpecsInvalid;
        private int mActiveColorMode;
        private Display.HdrCapabilities mHdrCapabilities;
        private boolean mAllmSupported;
        private boolean mGameContentTypeSupported;
        private boolean mAllmRequested;
        private boolean mGameContentTypeRequested;
        private boolean mSidekickActive;
        private SurfaceControl.StaticDisplayInfo mStaticDisplayInfo;
        // The supported display modes according to SurfaceFlinger
        private SurfaceControl.DisplayMode[] mSfDisplayModes;
        // The active display mode in SurfaceFlinger
        private SurfaceControl.DisplayMode mActiveSfDisplayMode;
        // The active display vsync period in SurfaceFlinger
        private float mActiveRenderFrameRate;
        // The current HDCP level supported by the display, 0 indicates unset
        // values are defined in hardware/interfaces/drm/aidl/android/hardware/drm/HdcpLevel.aidl
        private int mConnectedHdcpLevel;

        private DisplayEventReceiver.FrameRateOverride[] mFrameRateOverrides =
                new DisplayEventReceiver.FrameRateOverride[0];

        LocalDisplayDevice(IBinder displayToken, long physicalDisplayId,
                SurfaceControl.StaticDisplayInfo staticDisplayInfo,
                SurfaceControl.DynamicDisplayInfo dynamicInfo,
                SurfaceControl.DesiredDisplayModeSpecs modeSpecs, boolean isFirstDisplay) {
            super(LocalDisplayAdapter.this, displayToken, UNIQUE_ID_PREFIX + physicalDisplayId,
                    getContext());
            mPhysicalDisplayId = physicalDisplayId;
            mIsFirstDisplay = isFirstDisplay;
            updateDisplayPropertiesLocked(staticDisplayInfo, dynamicInfo, modeSpecs);
            mSidekickInternal = LocalServices.getService(SidekickInternal.class);
            mBacklightAdapter = new BacklightAdapter(displayToken, isFirstDisplay,
                    mSurfaceControlProxy);
            mActiveSfDisplayModeAtStartId = dynamicInfo.activeDisplayModeId;
        }

        @Override
        public boolean hasStableUniqueId() {
            return true;
        }

        /**
         * Returns the boot display mode of this display.
         * @hide
         */
        @Override
        public Display.Mode getActiveDisplayModeAtStartLocked() {
            return findMode(findMatchingModeIdLocked(mActiveSfDisplayModeAtStartId));
        }

        /**
         * Returns true if there is a change.
         **/
        public boolean updateDisplayPropertiesLocked(SurfaceControl.StaticDisplayInfo staticInfo,
                SurfaceControl.DynamicDisplayInfo dynamicInfo,
                SurfaceControl.DesiredDisplayModeSpecs modeSpecs) {
            boolean changed = updateDisplayModesLocked(
                    dynamicInfo.supportedDisplayModes, dynamicInfo.preferredBootDisplayMode,
                    dynamicInfo.activeDisplayModeId, dynamicInfo.renderFrameRate, modeSpecs);
            changed |= updateStaticInfo(staticInfo);
            changed |= updateColorModesLocked(dynamicInfo.supportedColorModes,
                    dynamicInfo.activeColorMode);
            changed |= updateHdrCapabilitiesLocked(dynamicInfo.hdrCapabilities);
            changed |= updateAllmSupport(dynamicInfo.autoLowLatencyModeSupported);
            changed |= updateGameContentTypeSupport(dynamicInfo.gameContentTypeSupported);

            if (changed) {
                mHavePendingChanges = true;
            }
            return changed;
        }

        public boolean updateDisplayModesLocked(
                SurfaceControl.DisplayMode[] displayModes, int preferredSfDisplayModeId,
                int activeSfDisplayModeId, float renderFrameRate,
                SurfaceControl.DesiredDisplayModeSpecs modeSpecs) {
            mSfDisplayModes = Arrays.copyOf(displayModes, displayModes.length);
            mActiveSfDisplayMode = getModeById(displayModes, activeSfDisplayModeId);
            SurfaceControl.DisplayMode preferredSfDisplayMode =
                        getModeById(displayModes, preferredSfDisplayModeId);

            // Build an updated list of all existing modes.
            ArrayList<DisplayModeRecord> records = new ArrayList<>();
            boolean modesAdded = false;
            for (int i = 0; i < displayModes.length; i++) {
                SurfaceControl.DisplayMode mode = displayModes[i];
                List<Float> alternativeRefreshRates = new ArrayList<>();
                for (int j = 0; j < displayModes.length; j++) {
                    SurfaceControl.DisplayMode other = displayModes[j];
                    boolean isAlternative = j != i && other.width == mode.width
                            && other.height == mode.height
                            && other.peakRefreshRate != mode.peakRefreshRate
                            && other.group == mode.group;
                    if (isAlternative) {
                        alternativeRefreshRates.add(displayModes[j].peakRefreshRate);
                    }
                }
                Collections.sort(alternativeRefreshRates);

                // First, check to see if we've already added a matching mode. Since not all
                // configuration options are exposed via Display.Mode, it's possible that we have
                // multiple DisplayModes that would generate the same Display.Mode.
                boolean existingMode = false;
                for (DisplayModeRecord record : records) {
                    if (record.hasMatchingMode(mode)
                            && refreshRatesEquals(alternativeRefreshRates,
                                    record.mMode.getAlternativeRefreshRates())) {
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
                DisplayModeRecord record = findDisplayModeRecord(mode, alternativeRefreshRates);
                if (record == null) {
                    float[] alternativeRates = new float[alternativeRefreshRates.size()];
                    for (int j = 0; j < alternativeRates.length; j++) {
                        alternativeRates[j] = alternativeRefreshRates.get(j);
                    }
                    record = new DisplayModeRecord(mode, alternativeRates);
                    modesAdded = true;
                }
                records.add(record);
            }

            // Get the currently active mode
            DisplayModeRecord activeRecord = null;
            for (DisplayModeRecord record : records) {
                if (record.hasMatchingMode(mActiveSfDisplayMode)) {
                    activeRecord = record;
                    break;
                }
            }

            boolean preferredModeChanged = false;

            if (preferredSfDisplayModeId != INVALID_MODE_ID && preferredSfDisplayMode != null) {
                DisplayModeRecord preferredRecord = null;
                for (DisplayModeRecord record : records) {
                    if (record.hasMatchingMode(preferredSfDisplayMode)) {
                        preferredRecord = record;
                        break;
                    }
                }

                if (preferredRecord != null) {
                    int preferredModeId = preferredRecord.mMode.getModeId();
                    if (mIsBootDisplayModeSupported && mSystemPreferredModeId != preferredModeId) {
                        mSystemPreferredModeId = preferredModeId;
                        preferredModeChanged = true;
                    }
                }
            }

            boolean activeModeChanged = false;

            // Check whether SurfaceFlinger or the display device changed the active mode out from
            // under us.
            if (mActiveModeId != INVALID_MODE_ID
                    && mActiveModeId != activeRecord.mMode.getModeId()) {
                Slog.d(TAG, "The active mode was changed from SurfaceFlinger or the display"
                        + " device to " + activeRecord.mMode);
                mActiveModeId = activeRecord.mMode.getModeId();
                activeModeChanged = true;
                sendTraversalRequestLocked();
            }

            boolean renderFrameRateChanged = false;

            if (mActiveRenderFrameRate > 0 &&  mActiveRenderFrameRate != renderFrameRate) {
                Slog.d(TAG, "The render frame rate was changed from SurfaceFlinger or the display"
                        + " device to " + renderFrameRate);
                mActiveRenderFrameRate = renderFrameRate;
                renderFrameRateChanged = true;
                sendTraversalRequestLocked();
            }

            // Check whether surface flinger spontaneously changed display config specs out from
            // under us. If so, schedule a traversal to reapply our display config specs.
            if (mDisplayModeSpecs.baseModeId != INVALID_MODE_ID) {
                int activeBaseMode = findMatchingModeIdLocked(modeSpecs.defaultMode);
                // If we can't map the defaultMode index to a mode, then the physical display
                // modes must have changed, and the code below for handling changes to the
                // list of available modes will take care of updating display mode specs.
                if (activeBaseMode == INVALID_MODE_ID
                        || mDisplayModeSpecs.baseModeId != activeBaseMode
                        || !mDisplayModeSpecs.primary.equals(modeSpecs.primaryRanges)
                        || !mDisplayModeSpecs.appRequest.equals(modeSpecs.appRequestRanges)) {
                    mDisplayModeSpecsInvalid = true;
                    sendTraversalRequestLocked();
                }
            }

            boolean recordsChanged = records.size() != mSupportedModes.size() || modesAdded;
            // If the records haven't changed then we're done here.
            if (!recordsChanged) {
                return activeModeChanged || preferredModeChanged || renderFrameRateChanged;
            }

            mSupportedModes.clear();
            for (DisplayModeRecord record : records) {
                mSupportedModes.put(record.mMode.getModeId(), record);
            }

            // For a new display, we need to initialize the default mode ID.
            if (mDefaultModeId == INVALID_MODE_ID) {
                mDefaultModeId = activeRecord.mMode.getModeId();
                mDefaultModeGroup = mActiveSfDisplayMode.group;
                mActiveRenderFrameRate = renderFrameRate;
            } else if (modesAdded && activeModeChanged) {
                Slog.d(TAG, "New display modes are added and the active mode has changed, "
                        + "use active mode as default mode.");
                mDefaultModeId = activeRecord.mMode.getModeId();
                mDefaultModeGroup = mActiveSfDisplayMode.group;
                mActiveRenderFrameRate = renderFrameRate;
            } else if (findSfDisplayModeIdLocked(mDefaultModeId, mDefaultModeGroup) < 0) {
                Slog.w(TAG, "Default display mode no longer available, using currently"
                        + " active mode as default.");
                mDefaultModeId = activeRecord.mMode.getModeId();
                mDefaultModeGroup = mActiveSfDisplayMode.group;
                mActiveRenderFrameRate = renderFrameRate;
            }

            // Determine whether the display mode specs' base mode is still there.
            if (mSupportedModes.indexOfKey(mDisplayModeSpecs.baseModeId) < 0) {
                if (mDisplayModeSpecs.baseModeId != INVALID_MODE_ID) {
                    Slog.w(TAG,
                            "DisplayModeSpecs base mode no longer available, using currently"
                                    + " active mode.");
                }
                mDisplayModeSpecs.baseModeId = activeRecord.mMode.getModeId();
                mDisplayModeSpecsInvalid = true;
            }

            if (mUserPreferredMode != null) {
                mUserPreferredModeId = findUserPreferredModeIdLocked(mUserPreferredMode);
            }

            // Determine whether the active mode is still there.
            if (mSupportedModes.indexOfKey(mActiveModeId) < 0) {
                if (mActiveModeId != INVALID_MODE_ID) {
                    Slog.w(TAG, "Active display mode no longer available, reverting to default"
                            + " mode.");
                }
                mActiveModeId = getPreferredModeId();
            }

            // Schedule traversals so that we apply pending changes.
            sendTraversalRequestLocked();
            return true;
        }

        @Override
        public DisplayDeviceConfig getDisplayDeviceConfig() {
            if (mDisplayDeviceConfig == null) {
                loadDisplayDeviceConfig();
            }
            return mDisplayDeviceConfig;
        }

        private int getPreferredModeId() {
            return mUserPreferredModeId != INVALID_MODE_ID
                    ? mUserPreferredModeId
                    : mDefaultModeId;
        }

        private int getLogicalDensity() {
            DensityMapping densityMapping = getDisplayDeviceConfig().getDensityMapping();
            if (densityMapping == null) {
                return (int) (mStaticDisplayInfo.density * 160 + 0.5);
            }

            return densityMapping.getDensityForResolution(mInfo.width, mInfo.height);
        }

        private void loadDisplayDeviceConfig() {
            // Load display device config
            final Context context = getOverlayContext();
            mDisplayDeviceConfig = mInjector.createDisplayDeviceConfig(context, mPhysicalDisplayId,
                    mIsFirstDisplay, getFeatureFlags());

            // Load brightness HWC quirk
            mBacklightAdapter.setForceSurfaceControl(mDisplayDeviceConfig.hasQuirk(
                    DisplayDeviceConfig.QUIRK_CAN_SET_BRIGHTNESS_VIA_HWC));
        }

        private boolean updateStaticInfo(SurfaceControl.StaticDisplayInfo info) {
            if (Objects.equals(mStaticDisplayInfo, info)) {
                return false;
            }
            mStaticDisplayInfo = info;
            return true;
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

            mSupportedColorModes.clear();
            mSupportedColorModes.addAll(pendingColorModes);
            Collections.sort(mSupportedColorModes);

            // Determine whether the active color mode is still there.
            if (!mSupportedColorModes.contains(mActiveColorMode)) {
                if (mActiveColorMode != Display.COLOR_MODE_DEFAULT) {
                    Slog.w(TAG, "Active color mode no longer available, reverting"
                            + " to default mode.");
                    mActiveColorMode = Display.COLOR_MODE_DEFAULT;
                } else {
                    if (!mSupportedColorModes.isEmpty()) {
                        // This should never happen.
                        Slog.e(TAG, "Default and active color mode is no longer available!"
                                + " Reverting to first available mode.");
                        mActiveColorMode = mSupportedColorModes.get(0);
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

        private boolean updateAllmSupport(boolean supported) {
            if (mAllmSupported == supported) {
                return false;
            }
            mAllmSupported = supported;
            return true;
        }

        private boolean updateGameContentTypeSupport(boolean supported) {
            if (mGameContentTypeSupported == supported) {
                return false;
            }
            mGameContentTypeSupported = supported;
            return true;
        }

        private SurfaceControl.DisplayMode getModeById(SurfaceControl.DisplayMode[] supportedModes,
                int modeId) {
            for (SurfaceControl.DisplayMode mode : supportedModes) {
                if (mode.id == modeId) {
                    return mode;
                }
            }
            Slog.e(TAG, "Can't find display mode with id " + modeId);
            return null;
        }

        private DisplayModeRecord findDisplayModeRecord(SurfaceControl.DisplayMode mode,
                List<Float> alternativeRefreshRates) {
            for (int i = 0; i < mSupportedModes.size(); i++) {
                DisplayModeRecord record = mSupportedModes.valueAt(i);
                if (record.hasMatchingMode(mode)
                        && refreshRatesEquals(alternativeRefreshRates,
                                record.mMode.getAlternativeRefreshRates())
                        && hdrTypesEqual(mode.supportedHdrTypes,
                            record.mMode.getSupportedHdrTypes())) {
                    return record;
                }
            }
            return null;
        }

        private boolean refreshRatesEquals(List<Float> list, float[] array) {
            if (list.size() != array.length) {
                return false;
            }
            for (int i = 0; i < list.size(); i++) {
                if (Float.floatToIntBits(list.get(i)) != Float.floatToIntBits(array[i])) {
                    return false;
                }
            }
            return true;
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
                mInfo = new DisplayDeviceInfo();
                mInfo.width = mActiveSfDisplayMode.width;
                mInfo.height = mActiveSfDisplayMode.height;
                mInfo.modeId = mActiveModeId;
                mInfo.renderFrameRate = mActiveRenderFrameRate;
                mInfo.defaultModeId = getPreferredModeId();
                mInfo.userPreferredModeId = mUserPreferredModeId;
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
                mInfo.appVsyncOffsetNanos = mActiveSfDisplayMode.appVsyncOffsetNanos;
                mInfo.presentationDeadlineNanos = mActiveSfDisplayMode.presentationDeadlineNanos;
                mInfo.state = mState;
                mInfo.committedState = mCommittedState;
                mInfo.uniqueId = getUniqueId();
                final DisplayAddress.Physical physicalAddress =
                        DisplayAddress.fromPhysicalDisplayId(mPhysicalDisplayId);
                mInfo.address = physicalAddress;
                mInfo.densityDpi = getLogicalDensity();
                mInfo.xDpi = mActiveSfDisplayMode.xDpi;
                mInfo.yDpi = mActiveSfDisplayMode.yDpi;
                mInfo.deviceProductInfo = mStaticDisplayInfo.deviceProductInfo;

                if (mConnectedHdcpLevel != 0) {
                    mStaticDisplayInfo.secure = mConnectedHdcpLevel >= MediaDrm.HDCP_V1;
                }
                if (mStaticDisplayInfo.secure) {
                    mInfo.flags = DisplayDeviceInfo.FLAG_SECURE
                            | DisplayDeviceInfo.FLAG_SUPPORTS_PROTECTED_BUFFERS;
                }

                final Resources res = getOverlayContext().getResources();

                mInfo.flags |= DisplayDeviceInfo.FLAG_ALLOWED_TO_BE_DEFAULT_DISPLAY;

                if (mIsFirstDisplay) {
                    if (res.getBoolean(R.bool.config_mainBuiltInDisplayIsRound)
                            || (Build.IS_EMULATOR
                            && SystemProperties.getBoolean(PROPERTY_EMULATOR_CIRCULAR, false))) {
                        mInfo.flags |= DisplayDeviceInfo.FLAG_ROUND;
                    }
                } else {
                    if (!res.getBoolean(R.bool.config_localDisplaysMirrorContent)) {
                        mInfo.flags |= DisplayDeviceInfo.FLAG_OWN_CONTENT_ONLY;
                    }

                    if (isDisplayPrivate(physicalAddress)) {
                        mInfo.flags |= DisplayDeviceInfo.FLAG_PRIVATE;
                    }
                }

                if (DisplayCutout.getMaskBuiltInDisplayCutout(res, mInfo.uniqueId)) {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_MASK_DISPLAY_CUTOUT;
                }

                final Display.Mode maxDisplayMode =
                        DisplayUtils.getMaximumResolutionDisplayMode(mInfo.supportedModes);
                final int maxWidth =
                        maxDisplayMode == null ? mInfo.width : maxDisplayMode.getPhysicalWidth();
                final int maxHeight =
                        maxDisplayMode == null ? mInfo.height : maxDisplayMode.getPhysicalHeight();

                // We cannot determine cutouts and rounded corners of external displays.
                if (mStaticDisplayInfo.isInternal) {
                    mInfo.displayCutout = DisplayCutout.fromResourcesRectApproximation(res,
                            mInfo.uniqueId, maxWidth, maxHeight, mInfo.width, mInfo.height);
                    mInfo.roundedCorners = RoundedCorners.fromResources(
                            res, mInfo.uniqueId, maxWidth, maxHeight, mInfo.width, mInfo.height);
                }

                mInfo.installOrientation = mStaticDisplayInfo.installOrientation;

                mInfo.displayShape = DisplayShape.fromResources(
                        res, mInfo.uniqueId, maxWidth, maxHeight, mInfo.width, mInfo.height);

                mInfo.name = getDisplayDeviceConfig().getName();

                if (mStaticDisplayInfo.isInternal) {
                    mInfo.type = Display.TYPE_INTERNAL;
                    mInfo.touch = DisplayDeviceInfo.TOUCH_INTERNAL;
                    mInfo.flags |= DisplayDeviceInfo.FLAG_ROTATES_WITH_CONTENT;
                    if (mInfo.name == null) {
                        mInfo.name = res.getString(R.string.display_manager_built_in_display_name);
                    }
                } else {
                    mInfo.type = Display.TYPE_EXTERNAL;
                    mInfo.touch = DisplayDeviceInfo.TOUCH_EXTERNAL;
                    mInfo.flags |= DisplayDeviceInfo.FLAG_PRESENTATION;
                    if (mInfo.name == null) {
                        mInfo.name = getContext().getResources().getString(
                                R.string.display_manager_hdmi_display_name);
                    }
                }
                mInfo.frameRateOverrides = mFrameRateOverrides;

                // The display is trusted since it is created by system.
                mInfo.flags |= DisplayDeviceInfo.FLAG_TRUSTED;
                mInfo.brightnessMinimum = PowerManager.BRIGHTNESS_MIN;
                mInfo.brightnessMaximum = PowerManager.BRIGHTNESS_MAX;
                mInfo.brightnessDefault = getDisplayDeviceConfig().getBrightnessDefault();
                mInfo.hdrSdrRatio = mCurrentHdrSdrRatio;
            }
            return mInfo;
        }

        @Override
        public Runnable requestDisplayStateLocked(
                final int state,
                final float brightnessState,
                final float sdrBrightnessState,
                @Nullable DisplayOffloadSessionImpl displayOffloadSession) {

            // Assume that the brightness is off if the display is being turned off.
            assert state != Display.STATE_OFF
                    || brightnessState == PowerManager.BRIGHTNESS_OFF_FLOAT;
            final boolean stateChanged = (mState != state);
            final boolean brightnessChanged = mBrightnessState != brightnessState
                    || mSdrBrightnessState != sdrBrightnessState;
            if (stateChanged || brightnessChanged) {
                final long physicalDisplayId = mPhysicalDisplayId;
                final IBinder token = getDisplayTokenLocked();
                final int oldState = mState;

                if (stateChanged) {
                    mState = state;
                    updateDeviceInfoLocked();
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

                            // If UNKNOWN, we still want to set the initial display state,
                            // otherwise, return early.
                            } else if (oldState != Display.STATE_UNKNOWN) {
                                return; // old state and new state is off
                            }
                        }

                        // Apply brightness changes given that we are in a non-suspended state.
                        if (brightnessChanged) {
                            setDisplayBrightness(brightnessState, sdrBrightnessState);
                            mBrightnessState = brightnessState;
                            mSdrBrightnessState = sdrBrightnessState;
                        }

                        // Enter the final desired state, possibly suspended.
                        if (state != currentState) {
                            setDisplayState(state);
                        }
                    }

                    private void setDisplayState(int state) {
                        if (DEBUG) {
                            Slog.d(TAG, "setDisplayState("
                                    + "id=" + physicalDisplayId
                                    + ", state=" + Display.stateToString(state) + ")");
                        }

                        boolean isDisplayOffloadEnabled =
                                getFeatureFlags().isDisplayOffloadEnabled();

                        // We must tell sidekick/displayoffload to stop controlling the display
                        // before we can change its power mode, so do that first.
                        if (isDisplayOffloadEnabled) {
                            if (displayOffloadSession != null
                                    && !DisplayOffloadSession.isSupportedOffloadState(state)) {
                                displayOffloadSession.stopOffload();
                            }
                        } else {
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
                        }

                        final int mode = getPowerModeForState(state);
                        Trace.traceBegin(Trace.TRACE_TAG_POWER, "setDisplayState("
                                + "id=" + physicalDisplayId
                                + ", state=" + Display.stateToString(state) + ")");
                        try {
                            mSurfaceControlProxy.setDisplayPowerMode(token, mode);
                            Trace.traceCounter(Trace.TRACE_TAG_POWER, "DisplayPowerMode", mode);
                        } finally {
                            Trace.traceEnd(Trace.TRACE_TAG_POWER);
                        }
                        setCommittedState(state);

                        // If we're entering a suspended (but not OFF) power state and we
                        // have a sidekick/displayoffload available, tell it now that it can take
                        // control.
                        if (isDisplayOffloadEnabled) {
                            if (displayOffloadSession != null
                                    && DisplayOffloadSession.isSupportedOffloadState(state)) {
                                displayOffloadSession.startOffload();
                            }
                        } else {
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
                    }

                    private void setCommittedState(int state) {
                        // After the display state is set, let's update the committed state.
                        synchronized (getSyncRoot()) {
                            mCommittedState = state;
                            updateDeviceInfoLocked();
                        }
                    }


                    private void setDisplayBrightness(float brightnessState,
                            float sdrBrightnessState) {
                        // brightnessState includes invalid, off and full range.
                        if (Float.isNaN(brightnessState) || Float.isNaN(sdrBrightnessState)) {
                            return;
                        }

                        if (DEBUG) {
                            Slog.d(TAG, "setDisplayBrightness("
                                    + "id=" + physicalDisplayId
                                    + ", brightnessState=" + brightnessState
                                    + ", sdrBrightnessState=" + sdrBrightnessState + ")");
                        }

                        Trace.traceBegin(Trace.TRACE_TAG_POWER, "setDisplayBrightness("
                                + "id=" + physicalDisplayId + ", brightnessState="
                                + brightnessState + ", sdrBrightnessState=" + sdrBrightnessState
                                + ")");
                        try {
                            final float backlight = brightnessToBacklight(brightnessState);
                            final float sdrBacklight = brightnessToBacklight(sdrBrightnessState);

                            final float nits = backlightToNits(backlight);
                            final float sdrNits = backlightToNits(sdrBacklight);

                            mBacklightAdapter.setBacklight(sdrBacklight, sdrNits, backlight, nits);
                            Trace.traceCounter(Trace.TRACE_TAG_POWER,
                                    "ScreenBrightness",
                                    BrightnessSynchronizer.brightnessFloatToInt(brightnessState));
                            Trace.traceCounter(Trace.TRACE_TAG_POWER,
                                    "SdrScreenBrightness",
                                    BrightnessSynchronizer.brightnessFloatToInt(
                                            sdrBrightnessState));

                            if (getDisplayDeviceConfig().hasSdrToHdrRatioSpline()) {
                                handleHdrSdrNitsChanged(nits, sdrNits);
                            }

                        } finally {
                            Trace.traceEnd(Trace.TRACE_TAG_POWER);
                        }
                    }

                    private float brightnessToBacklight(float brightness) {
                        if (brightness == PowerManager.BRIGHTNESS_OFF_FLOAT) {
                            return PowerManager.BRIGHTNESS_OFF_FLOAT;
                        } else {
                            return getDisplayDeviceConfig().getBacklightFromBrightness(brightness);
                        }
                    }

                    private float backlightToNits(float backlight) {
                        return getDisplayDeviceConfig().getNitsFromBacklight(backlight);
                    }

                    void handleHdrSdrNitsChanged(float displayNits, float sdrNits) {
                        final float newHdrSdrRatio;
                        if (displayNits != INVALID_NITS && sdrNits != INVALID_NITS) {
                            // Ensure the ratio stays >= 1.0f as values below that are nonsensical
                            newHdrSdrRatio = Math.max(1.f, displayNits / sdrNits);
                        } else {
                            newHdrSdrRatio = Float.NaN;
                        }
                        if (!BrightnessSynchronizer.floatEquals(
                                mCurrentHdrSdrRatio, newHdrSdrRatio)) {
                            synchronized (getSyncRoot()) {
                                mCurrentHdrSdrRatio = newHdrSdrRatio;
                                updateDeviceInfoLocked();
                            }
                        }
                    }
                };
            }
            return null;
        }

        @Override
        public void setUserPreferredDisplayModeLocked(Display.Mode mode) {
            final int oldModeId = getPreferredModeId();
            mUserPreferredMode = mode;
            // When clearing the user preferred mode we need to also reset the default mode. This is
            // used by DisplayModeDirector to determine the default resolution, so if we don't clear
            // it then the resolution won't reset to what it would've been prior to setting a user
            // preferred display mode.
            if (mode == null && mSystemPreferredModeId != INVALID_MODE_ID) {
                mDefaultModeId = mSystemPreferredModeId;
            }
            if (mode != null && (mode.isRefreshRateSet() || mode.isResolutionSet())) {
                Display.Mode matchingSupportedMode;
                matchingSupportedMode = findMode(mode.getPhysicalWidth(),
                        mode.getPhysicalHeight(), mode.getRefreshRate());
                if (matchingSupportedMode != null) {
                    mUserPreferredMode = matchingSupportedMode;
                }
            }

            mUserPreferredModeId = findUserPreferredModeIdLocked(mUserPreferredMode);

            if (oldModeId == getPreferredModeId()) {
                return;
            }
            updateDeviceInfoLocked();

            if (!mIsBootDisplayModeSupported) {
                return;
            }
            if (mUserPreferredModeId == INVALID_MODE_ID) {
                mSurfaceControlProxy.clearBootDisplayMode(getDisplayTokenLocked());
            } else {
                int preferredSfDisplayModeId = findSfDisplayModeIdLocked(
                        mUserPreferredMode.getModeId(), mDefaultModeGroup);
                mSurfaceControlProxy.setBootDisplayMode(getDisplayTokenLocked(),
                        preferredSfDisplayModeId);
            }
        }

        @Override
        public Display.Mode getUserPreferredDisplayModeLocked() {
            return mUserPreferredMode;
        }

        @Override
        public Display.Mode getSystemPreferredDisplayModeLocked() {
            return findMode(mSystemPreferredModeId);
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

            // Find the mode Id based on the desired mode specs. In case there is more than one
            // mode matching the mode spec, prefer the one that is in the default mode group.
            // For now the default config mode is taken from the active mode when we got the
            // hotplug event for the display. In the future we might want to change the default
            // mode based on vendor requirements.
            // Note: We prefer the default mode group over the current one as this is the mode
            // group the vendor prefers.
            int baseSfModeId = findSfDisplayModeIdLocked(displayModeSpecs.baseModeId,
                    mDefaultModeGroup);
            if (baseSfModeId < 0) {
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
                        new SurfaceControl.DesiredDisplayModeSpecs(baseSfModeId,
                                mDisplayModeSpecs.allowGroupSwitching,
                                mDisplayModeSpecs.primary,
                                mDisplayModeSpecs.appRequest)));
            }
        }

        private void setDesiredDisplayModeSpecsAsync(IBinder displayToken,
                SurfaceControl.DesiredDisplayModeSpecs modeSpecs) {
            // Do not lock when calling these SurfaceControl methods because they are sync
            // operations that may block for a while when setting display power mode.
            mSurfaceControlProxy.setDesiredDisplayModeSpecs(displayToken, modeSpecs);
        }

        @Override
        public void onOverlayChangedLocked() {
            updateDeviceInfoLocked();
        }

        public void onActiveDisplayModeChangedLocked(int sfModeId, float renderFrameRate) {
            if (updateActiveModeLocked(sfModeId, renderFrameRate)) {
                updateDeviceInfoLocked();
            }
        }

        public void onFrameRateOverridesChanged(
                DisplayEventReceiver.FrameRateOverride[] overrides) {
            if (updateFrameRateOverridesLocked(overrides)) {
                updateDeviceInfoLocked();
            }
        }

        public void onHdcpLevelsChangedLocked(int connectedLevel, int maxLevel) {
            if (updateHdcpLevelsLocked(connectedLevel, maxLevel)) {
                updateDeviceInfoLocked();
            }
        }

        public boolean updateActiveModeLocked(int activeSfModeId, float renderFrameRate) {
            if (mActiveSfDisplayMode.id == activeSfModeId
                    && mActiveRenderFrameRate == renderFrameRate) {
                return false;
            }
            mActiveSfDisplayMode = getModeById(mSfDisplayModes, activeSfModeId);
            mActiveModeId = findMatchingModeIdLocked(activeSfModeId);
            if (mActiveModeId == INVALID_MODE_ID) {
                Slog.w(TAG, "In unknown mode after setting allowed modes"
                        + ", activeModeId=" + activeSfModeId);
            }
            mActiveRenderFrameRate = renderFrameRate;
            return true;
        }

        public boolean updateFrameRateOverridesLocked(
                DisplayEventReceiver.FrameRateOverride[] overrides) {
            if (Arrays.equals(overrides, mFrameRateOverrides)) {
                return false;
            }

            mFrameRateOverrides = overrides;
            return true;
        }

        public boolean updateHdcpLevelsLocked(int connectedLevel, int maxLevel) {
            if (connectedLevel > maxLevel) {
                Slog.w(TAG, "HDCP connected level: " + connectedLevel
                        + " is larger than max level: " + maxLevel
                        + ", ignoring request.");
                return false;
            }

            if (mConnectedHdcpLevel == connectedLevel) {
                return false;
            }

            mConnectedHdcpLevel = connectedLevel;
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
            getHandler().sendMessage(PooledLambda.obtainMessage(
                    LocalDisplayDevice::requestColorModeAsync, this,
                    getDisplayTokenLocked(), colorMode));
        }

        private void requestColorModeAsync(IBinder displayToken, int colorMode) {
            // Do not lock when calling this SurfaceControl method because it is a sync operation
            // that may block for a while when setting display power mode.
            mSurfaceControlProxy.setActiveColorMode(displayToken, colorMode);
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

            mSurfaceControlProxy.setAutoLowLatencyMode(getDisplayTokenLocked(), on);
        }

        @Override
        public void setGameContentTypeLocked(boolean on) {
            if (mGameContentTypeRequested == on) {
                return;
            }

            mGameContentTypeRequested = on;

            // Even if game content type is not supported on the connected display we
            // propagate the requested state down to the HAL. This is because some devices
            // with external displays, such as Android TV set-top boxes, use this signal
            // to disable/enable on-device processing.
            // TODO(b/202378408) set game content type only if it's supported once we have a
            // separate API for disabling on-device processing.
            mSurfaceControlProxy.setGameContentType(getDisplayTokenLocked(), on);
        }

        @Override
        public void dumpLocked(PrintWriter pw) {
            super.dumpLocked(pw);
            pw.println("mPhysicalDisplayId=" + mPhysicalDisplayId);
            pw.println("mDisplayModeSpecs={" + mDisplayModeSpecs + "}");
            pw.println("mDisplayModeSpecsInvalid=" + mDisplayModeSpecsInvalid);
            pw.println("mActiveModeId=" + mActiveModeId);
            pw.println("mActiveColorMode=" + mActiveColorMode);
            pw.println("mDefaultModeId=" + mDefaultModeId);
            pw.println("mUserPreferredModeId=" + mUserPreferredModeId);
            pw.println("mState=" + Display.stateToString(mState));
            pw.println("mCommittedState=" + Display.stateToString(mCommittedState));
            pw.println("mBrightnessState=" + mBrightnessState);
            pw.println("mBacklightAdapter=" + mBacklightAdapter);
            pw.println("mAllmSupported=" + mAllmSupported);
            pw.println("mAllmRequested=" + mAllmRequested);
            pw.println("mGameContentTypeSupported=" + mGameContentTypeSupported);
            pw.println("mGameContentTypeRequested=" + mGameContentTypeRequested);
            pw.println("mStaticDisplayInfo=" + mStaticDisplayInfo);
            pw.println("mSfDisplayModes=");
            for (SurfaceControl.DisplayMode sfDisplayMode : mSfDisplayModes) {
                pw.println("  " + sfDisplayMode);
            }
            pw.println("mActiveSfDisplayMode=" + mActiveSfDisplayMode);
            pw.println("mActiveRenderFrameRate=" + mActiveRenderFrameRate);
            pw.println("mSupportedModes=");
            for (int i = 0; i < mSupportedModes.size(); i++) {
                pw.println("  " + mSupportedModes.valueAt(i));
            }
            pw.println("mSupportedColorModes=" + mSupportedColorModes);
            pw.println("mDisplayDeviceConfig=" + mDisplayDeviceConfig);
        }

        private int findSfDisplayModeIdLocked(int displayModeId, int modeGroup) {
            int matchingSfDisplayModeId = INVALID_MODE_ID;
            DisplayModeRecord record = mSupportedModes.get(displayModeId);
            if (record != null) {
                for (SurfaceControl.DisplayMode mode : mSfDisplayModes) {
                    if (record.hasMatchingMode(mode)) {
                        if (matchingSfDisplayModeId == INVALID_MODE_ID) {
                            matchingSfDisplayModeId = mode.id;
                        }

                        // Prefer to return a mode that matches the modeGroup
                        if (mode.group == modeGroup) {
                            return mode.id;
                        }
                    }
                }
            }
            return matchingSfDisplayModeId;
        }

        // Returns a mode with id = modeId.
        private Display.Mode findMode(int modeId) {
            for (int i = 0; i < mSupportedModes.size(); i++) {
                Display.Mode supportedMode = mSupportedModes.valueAt(i).mMode;
                if (supportedMode.getModeId() == modeId) {
                    return supportedMode;
                }
            }
            return null;
        }

       // Returns a mode with resolution (width, height) and/or refreshRate. If any one of the
       // resolution or refresh-rate is valid, a mode having the valid parameters is returned.
        private Display.Mode findMode(int width, int height, float refreshRate) {
            for (int i = 0; i < mSupportedModes.size(); i++) {
                Display.Mode supportedMode = mSupportedModes.valueAt(i).mMode;
                if (supportedMode.matchesIfValid(width, height, refreshRate)) {
                    return supportedMode;
                }
            }
            return null;
        }

        private int findUserPreferredModeIdLocked(Display.Mode userPreferredMode) {
            if (userPreferredMode != null) {
                for (int i = 0; i < mSupportedModes.size(); i++) {
                    Display.Mode supportedMode = mSupportedModes.valueAt(i).mMode;
                    if (userPreferredMode.matches(supportedMode.getPhysicalWidth(),
                            supportedMode.getPhysicalHeight(),
                            supportedMode.getRefreshRate())) {
                        return supportedMode.getModeId();
                    }
                }
            }
            return INVALID_MODE_ID;
        }

        private int findMatchingModeIdLocked(int sfModeId) {
            SurfaceControl.DisplayMode mode = getModeById(mSfDisplayModes, sfModeId);
            if (mode == null) {
                Slog.e(TAG, "Invalid display mode ID " + sfModeId);
                return INVALID_MODE_ID;
            }
            for (int i = 0; i < mSupportedModes.size(); i++) {
                DisplayModeRecord record = mSupportedModes.valueAt(i);
                if (record.hasMatchingMode(mode)) {
                    return record.mMode.getModeId();
                }
            }
            return INVALID_MODE_ID;
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
            int[] ports = res.getIntArray(R.array.config_localPrivateDisplayPorts);
            if (ports != null) {
                int port = physicalAddress.getPort();
                for (int p : ports) {
                    if (p == port) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private boolean hdrTypesEqual(int[] modeHdrTypes, int[] recordHdrTypes) {
        int[] modeHdrTypesCopy = Arrays.copyOf(modeHdrTypes, modeHdrTypes.length);
        Arrays.sort(modeHdrTypesCopy);
        // Record HDR types are already sorted when we create the DisplayModeRecord
        return Arrays.equals(modeHdrTypesCopy, recordHdrTypes);
    }

    /** Supplies a context whose Resources apply runtime-overlays */
    Context getOverlayContext() {
        if (mOverlayContext == null) {
            mOverlayContext = ActivityThread.currentActivityThread().getSystemUiContext();
        }
        return mOverlayContext;
    }

    /**
     * Keeps track of a display mode.
     */
    private static final class DisplayModeRecord {
        public final Display.Mode mMode;

        DisplayModeRecord(SurfaceControl.DisplayMode mode,
                float[] alternativeRefreshRates) {
            mMode = createMode(mode.width, mode.height, mode.peakRefreshRate, mode.vsyncRate,
                    alternativeRefreshRates, mode.supportedHdrTypes);
        }

        /**
         * Returns whether the mode generated by the given DisplayModes matches the mode
         * contained by the record modulo mode ID.
         *
         * Note that this doesn't necessarily mean that the DisplayModes are identical, just
         * that they generate identical modes.
         */
        public boolean hasMatchingMode(SurfaceControl.DisplayMode mode) {
            return mMode.getPhysicalWidth() == mode.width
                    && mMode.getPhysicalHeight() == mode.height
                    && Float.floatToIntBits(mMode.getRefreshRate())
                            == Float.floatToIntBits(mode.peakRefreshRate)
                    && Float.floatToIntBits(mMode.getVsyncRate())
                            == Float.floatToIntBits(mode.vsyncRate);
        }

        public String toString() {
            return "DisplayModeRecord{mMode=" + mMode + "}";
        }
    }

    public static class Injector {
        // Ensure the callback is kept to preserve native weak reference lifecycle semantics.
        @SuppressWarnings("unused")
        @KeepForWeakReference
        private ProxyDisplayEventReceiver mReceiver;
        public void setDisplayEventListenerLocked(Looper looper, DisplayEventListener listener) {
            mReceiver = new ProxyDisplayEventReceiver(looper, listener);
        }
        public SurfaceControlProxy getSurfaceControlProxy() {
            return new SurfaceControlProxy();
        }

        public DisplayDeviceConfig createDisplayDeviceConfig(Context context,
                long physicalDisplayId, boolean isFirstDisplay, DisplayManagerFlags flags) {
            return DisplayDeviceConfig.create(context, physicalDisplayId, isFirstDisplay, flags);
        }
    }

    public interface DisplayEventListener {
        void onHotplug(long timestampNanos, long physicalDisplayId, boolean connected);
        void onHotplugConnectionError(long timestampNanos, int connectionError);
        void onModeChanged(long timestampNanos, long physicalDisplayId, int modeId,
                long renderPeriod);
        void onFrameRateOverridesChanged(long timestampNanos, long physicalDisplayId,
                DisplayEventReceiver.FrameRateOverride[] overrides);
        void onHdcpLevelsChanged(long physicalDisplayId, int connectedLevel, int maxLevel);

    }

    public static final class ProxyDisplayEventReceiver extends DisplayEventReceiver {
        private final DisplayEventListener mListener;
        ProxyDisplayEventReceiver(Looper looper, DisplayEventListener listener) {
            super(looper, VSYNC_SOURCE_APP,
                    EVENT_REGISTRATION_MODE_CHANGED_FLAG
                            | EVENT_REGISTRATION_FRAME_RATE_OVERRIDE_FLAG);
            mListener = listener;
        }

        @Override
        public void onHotplug(long timestampNanos, long physicalDisplayId, boolean connected) {
            mListener.onHotplug(timestampNanos, physicalDisplayId, connected);
        }

        @Override
        public void onHotplugConnectionError(long timestampNanos, int errorCode) {
            mListener.onHotplugConnectionError(timestampNanos, errorCode);
        }

        @Override
        public void onModeChanged(long timestampNanos, long physicalDisplayId, int modeId,
                long renderPeriod) {
            mListener.onModeChanged(timestampNanos, physicalDisplayId, modeId, renderPeriod);
        }

        @Override
        public void onFrameRateOverridesChanged(long timestampNanos, long physicalDisplayId,
                DisplayEventReceiver.FrameRateOverride[] overrides) {
            mListener.onFrameRateOverridesChanged(timestampNanos, physicalDisplayId, overrides);
        }

        @Override
        public void onHdcpLevelsChanged(long physicalDisplayId, int connectedLevel, int maxLevel) {
            mListener.onHdcpLevelsChanged(physicalDisplayId, connectedLevel, maxLevel);
        }
    }

    private final class LocalDisplayEventListener implements DisplayEventListener {
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
        public void onHotplugConnectionError(long timestampNanos, int connectionError) {
            if (DEBUG) {
                Slog.d(TAG, "onHotplugConnectionError("
                        + "timestampNanos=" + timestampNanos
                        + ", connectionError=" + connectionError + ")");
            }

            mDisplayNotificationManager.onHotplugConnectionError();
        }

        @Override
        public void onModeChanged(long timestampNanos, long physicalDisplayId, int modeId,
                long renderPeriod) {
            if (DEBUG) {
                Slog.d(TAG, "onModeChanged("
                        + "timestampNanos=" + timestampNanos
                        + ", physicalDisplayId=" + physicalDisplayId
                        + ", modeId=" + modeId
                        + ", renderPeriod=" + renderPeriod + ")");
            }
            synchronized (getSyncRoot()) {
                LocalDisplayDevice device = mDevices.get(physicalDisplayId);
                if (device == null) {
                    if (DEBUG) {
                        Slog.d(TAG, "Received mode change for unhandled physical display: "
                                + "physicalDisplayId=" + physicalDisplayId);
                    }
                    return;
                }
                float renderFrameRate = 1e9f / renderPeriod;
                device.onActiveDisplayModeChangedLocked(modeId, renderFrameRate);
            }
        }

        @Override
        public void onFrameRateOverridesChanged(long timestampNanos, long physicalDisplayId,
                DisplayEventReceiver.FrameRateOverride[] overrides) {
            if (DEBUG) {
                Slog.d(TAG, "onFrameRateOverrideChanged(timestampNanos=" + timestampNanos
                        + ", physicalDisplayId=" + physicalDisplayId + " overrides="
                        + Arrays.toString(overrides) + ")");
            }
            synchronized (getSyncRoot()) {
                LocalDisplayDevice device = mDevices.get(physicalDisplayId);
                if (device == null) {
                    if (DEBUG) {
                        Slog.d(TAG, "Received frame rate override event for unhandled physical"
                                + " display: physicalDisplayId=" + physicalDisplayId);
                    }
                    return;
                }
                device.onFrameRateOverridesChanged(overrides);
            }
        }

        @Override
        public void onHdcpLevelsChanged(long physicalDisplayId, int connectedLevel, int maxLevel) {
            if (DEBUG) {
                Slog.d(TAG, "onHdcpLevelsChanged(physicalDisplayId=" + physicalDisplayId
                        + ", connectedLevel=" + connectedLevel + ", maxLevel=" + maxLevel + ")");
            }
            synchronized (getSyncRoot()) {
                LocalDisplayDevice device = mDevices.get(physicalDisplayId);
                if (device == null) {
                    if (DEBUG) {
                        Slog.d(TAG, "Received hdcp levels change for unhandled physical display: "
                                + "physicalDisplayId=" + physicalDisplayId);
                    }
                    return;
                }

                device.onHdcpLevelsChangedLocked(connectedLevel, maxLevel);
            }
        }
    }

    @VisibleForTesting
    public static class SurfaceControlProxy {
        public SurfaceControl.DynamicDisplayInfo getDynamicDisplayInfo(long displayId) {
            return SurfaceControl.getDynamicDisplayInfo(displayId);
        }

        public long[] getPhysicalDisplayIds() {
            return DisplayControl.getPhysicalDisplayIds();
        }

        public IBinder getPhysicalDisplayToken(long physicalDisplayId) {
            return DisplayControl.getPhysicalDisplayToken(physicalDisplayId);
        }

        public SurfaceControl.StaticDisplayInfo getStaticDisplayInfo(long displayId) {
            return SurfaceControl.getStaticDisplayInfo(displayId);
        }

        public SurfaceControl.DesiredDisplayModeSpecs getDesiredDisplayModeSpecs(
                IBinder displayToken) {
            return SurfaceControl.getDesiredDisplayModeSpecs(displayToken);
        }

        public boolean setDesiredDisplayModeSpecs(IBinder token,
                SurfaceControl.DesiredDisplayModeSpecs specs) {
            return SurfaceControl.setDesiredDisplayModeSpecs(token, specs);
        }

        public void setDisplayPowerMode(IBinder displayToken, int mode) {
            SurfaceControl.setDisplayPowerMode(displayToken, mode);
        }

        public boolean setActiveColorMode(IBinder displayToken, int colorMode) {
            return SurfaceControl.setActiveColorMode(displayToken, colorMode);
        }

        public boolean getBootDisplayModeSupport() {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "getBootDisplayModeSupport");
            try {
                return SurfaceControl.getBootDisplayModeSupport();
            } finally {
                Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            }
        }

        public void setBootDisplayMode(IBinder displayToken, int modeId) {
            SurfaceControl.setBootDisplayMode(displayToken, modeId);
        }

        public void clearBootDisplayMode(IBinder displayToken) {
            SurfaceControl.clearBootDisplayMode(displayToken);
        }

        public void setAutoLowLatencyMode(IBinder displayToken, boolean on) {
            SurfaceControl.setAutoLowLatencyMode(displayToken, on);

        }

        public void setGameContentType(IBinder displayToken, boolean on) {
            SurfaceControl.setGameContentType(displayToken, on);
        }

        public boolean getDisplayBrightnessSupport(IBinder displayToken) {
            return SurfaceControl.getDisplayBrightnessSupport(displayToken);
        }

        public boolean setDisplayBrightness(IBinder displayToken, float brightness) {
            return SurfaceControl.setDisplayBrightness(displayToken, brightness);
        }

        public boolean setDisplayBrightness(IBinder displayToken, float sdrBacklight,
                float sdrNits, float displayBacklight, float displayNits) {
            return SurfaceControl.setDisplayBrightness(displayToken, sdrBacklight, sdrNits,
                    displayBacklight, displayNits);
        }
    }

    static class BacklightAdapter {
        private final IBinder mDisplayToken;
        private final LogicalLight mBacklight;
        private final boolean mUseSurfaceControlBrightness;
        private final SurfaceControlProxy mSurfaceControlProxy;

        private boolean mForceSurfaceControl = false;

        /**
         * @param displayToken Token for display associated with this backlight.
         * @param isFirstDisplay {@code true} if it is the first display.
         */
        BacklightAdapter(IBinder displayToken, boolean isFirstDisplay,
                SurfaceControlProxy surfaceControlProxy) {
            mDisplayToken = displayToken;
            mSurfaceControlProxy = surfaceControlProxy;

            mUseSurfaceControlBrightness = mSurfaceControlProxy
                    .getDisplayBrightnessSupport(mDisplayToken);

            if (!mUseSurfaceControlBrightness && isFirstDisplay) {
                LightsManager lights = LocalServices.getService(LightsManager.class);
                mBacklight = lights.getLight(LightsManager.LIGHT_ID_BACKLIGHT);
            } else {
                mBacklight = null;
            }
        }

        // Set backlight within min and max backlight values
        void setBacklight(float sdrBacklight, float sdrNits, float backlight, float nits) {
            if (mUseSurfaceControlBrightness || mForceSurfaceControl) {
                if (BrightnessSynchronizer.floatEquals(
                        sdrBacklight, PowerManager.BRIGHTNESS_INVALID_FLOAT)) {
                    mSurfaceControlProxy.setDisplayBrightness(mDisplayToken, backlight);
                } else {
                    mSurfaceControlProxy.setDisplayBrightness(mDisplayToken, sdrBacklight, sdrNits,
                            backlight, nits);
                }
            } else if (mBacklight != null) {
                mBacklight.setBrightness(backlight);
            }
        }

        void setForceSurfaceControl(boolean forceSurfaceControl) {
            mForceSurfaceControl = forceSurfaceControl;
        }

        @Override
        public String toString() {
            return "BacklightAdapter [useSurfaceControl=" + mUseSurfaceControlBrightness
                    + " (force_anyway? " + mForceSurfaceControl + ")"
                    + ", backlight=" + mBacklight + "]";
        }
    }
}
