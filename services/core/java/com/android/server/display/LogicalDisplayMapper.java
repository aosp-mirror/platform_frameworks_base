/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.content.Context;
import android.os.Process;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayEventReceiver;
import android.view.DisplayInfo;


import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Responsible for creating {@link LogicalDisplay}s and associating them to the
 * {@link DisplayDevice} objects supplied through {@link DisplayAdapter.Listener}.
 *
 * Additionally this class will keep track of which {@link DisplayGroup} each
 * {@link LogicalDisplay} belongs to.
 *
 * For devices with a single internal display, the mapping is done once and left
 * alone. For devices with multiple built-in displays, such as foldable devices,
 * {@link LogicalDisplay}s can be remapped to different {@link DisplayDevice}s.
 */
class LogicalDisplayMapper implements DisplayDeviceRepository.Listener {
    private static final String TAG = "LogicalDisplayMapper";

    private static final boolean DEBUG = false;

    public static final int LOGICAL_DISPLAY_EVENT_ADDED = 1;
    public static final int LOGICAL_DISPLAY_EVENT_CHANGED = 2;
    public static final int LOGICAL_DISPLAY_EVENT_REMOVED = 3;
    public static final int LOGICAL_DISPLAY_EVENT_SWAPPED = 4;
    public static final int LOGICAL_DISPLAY_EVENT_FRAME_RATE_OVERRIDES_CHANGED = 5;

    /**
     * Temporary display info, used for comparing display configurations.
     */
    private final DisplayInfo mTempDisplayInfo = new DisplayInfo();
    private final DisplayInfo mTempNonOverrideDisplayInfo = new DisplayInfo();

    /**
     * True if the display mapper service should pretend there is only one display
     * and only tell applications about the existence of the default logical display.
     * The display manager can still mirror content to secondary displays but applications
     * cannot present unique content on those displays.
     * Used for demonstration purposes only.
     */
    private final boolean mSingleDisplayDemoMode;

    /**
     * Physical Display ID of the DisplayDevice to associate with the default LogicalDisplay
     * when {@link mIsFolded} is set to {@code true}.
     */
    private String mDisplayIdToUseWhenFolded;

    /**
     * Physical Display ID of the DisplayDevice to associate with the default LogicalDisplay
     * when {@link mIsFolded} is set to {@code false}.
     */
    private String mDisplayIdToUseWhenUnfolded;

    /** Overrides the folded state of the device. For use with ADB commands. */
    private Boolean mIsFoldedOverride;

    /** Saves the last device fold state. */
    private boolean mIsFolded;

    /**
     * List of all logical displays indexed by logical display id.
     * Any modification to mLogicalDisplays must invalidate the DisplayManagerGlobal cache.
     * TODO: multi-display - Move the aforementioned comment?
     */
    private final SparseArray<LogicalDisplay> mLogicalDisplays =
            new SparseArray<LogicalDisplay>();
    private int mNextNonDefaultDisplayId = Display.DEFAULT_DISPLAY + 1;
    private int mNextNonDefaultGroupId = Display.DEFAULT_DISPLAY_GROUP + 1;

    /** A mapping from logical display id to display group. */
    private final SparseArray<DisplayGroup> mDisplayGroups = new SparseArray<>();

    private final DisplayDeviceRepository mDisplayDeviceRepo;
    private final Listener mListener;
    private final int mFoldedDeviceState;

    LogicalDisplayMapper(Context context, DisplayDeviceRepository repo, Listener listener) {
        mDisplayDeviceRepo = repo;
        mListener = listener;
        mSingleDisplayDemoMode = SystemProperties.getBoolean("persist.demo.singledisplay", false);
        mDisplayDeviceRepo.addListener(this);

        mFoldedDeviceState = context.getResources().getInteger(
                com.android.internal.R.integer.config_foldedDeviceState);

        loadFoldedDisplayConfig(context);
    }

    @Override
    public void onDisplayDeviceEventLocked(DisplayDevice device, int event) {
        switch (event) {
            case DisplayDeviceRepository.DISPLAY_DEVICE_EVENT_ADDED:
                handleDisplayDeviceAddedLocked(device);
                break;

            case DisplayDeviceRepository.DISPLAY_DEVICE_EVENT_CHANGED:
                updateLogicalDisplaysLocked();
                break;

            case DisplayDeviceRepository.DISPLAY_DEVICE_EVENT_REMOVED:
                updateLogicalDisplaysLocked();
                break;
        }
    }

    @Override
    public void onTraversalRequested() {
        mListener.onTraversalRequested();
    }

    public LogicalDisplay getLocked(int displayId) {
        return mLogicalDisplays.get(displayId);
    }

    public LogicalDisplay getLocked(DisplayDevice device) {
        final int count = mLogicalDisplays.size();
        for (int i = 0; i < count; i++) {
            LogicalDisplay display = mLogicalDisplays.valueAt(i);
            if (display.getPrimaryDisplayDeviceLocked() == device) {
                return display;
            }
        }
        return null;
    }

    public int[] getDisplayIdsLocked() {
        return getDisplayIdsLocked(Process.SYSTEM_UID);
    }

    public int[] getDisplayIdsLocked(int callingUid) {
        final int count = mLogicalDisplays.size();
        int[] displayIds = new int[count];
        int n = 0;
        for (int i = 0; i < count; i++) {
            LogicalDisplay display = mLogicalDisplays.valueAt(i);
            DisplayInfo info = display.getDisplayInfoLocked();
            if (info.hasAccess(callingUid)) {
                displayIds[n++] = mLogicalDisplays.keyAt(i);
            }
        }
        if (n != count) {
            displayIds = Arrays.copyOfRange(displayIds, 0, n);
        }
        return displayIds;
    }

    public void forEachLocked(Consumer<LogicalDisplay> consumer) {
        final int count = mLogicalDisplays.size();
        for (int i = 0; i < count; i++) {
            consumer.accept(mLogicalDisplays.valueAt(i));
        }
    }

    public int getDisplayGroupIdLocked(int displayId) {
        final DisplayGroup displayGroup = mDisplayGroups.get(displayId);
        if (displayGroup != null) {
            return displayGroup.getGroupId();
        }

        return -1;
    }

    public void dumpLocked(PrintWriter pw) {
        pw.println("LogicalDisplayMapper:");
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        ipw.increaseIndent();

        ipw.println("mSingleDisplayDemoMode=" + mSingleDisplayDemoMode);
        ipw.println("mNextNonDefaultDisplayId=" + mNextNonDefaultDisplayId);

        final int logicalDisplayCount = mLogicalDisplays.size();
        ipw.println();
        ipw.println("Logical Displays: size=" + logicalDisplayCount);


        for (int i = 0; i < logicalDisplayCount; i++) {
            int displayId = mLogicalDisplays.keyAt(i);
            LogicalDisplay display = mLogicalDisplays.valueAt(i);
            ipw.println("Display " + displayId + ":");
            ipw.increaseIndent();
            display.dumpLocked(ipw);
            ipw.decreaseIndent();
            ipw.println();
        }
    }

    void setDeviceStateLocked(int state) {
        setDeviceFoldedLocked(state == mFoldedDeviceState);
    }

    void setDeviceFoldedLocked(boolean isFolded) {
        mIsFolded = isFolded;
        if (mIsFoldedOverride != null) {
            isFolded = mIsFoldedOverride.booleanValue();
        }

        if (mDisplayIdToUseWhenFolded == null || mDisplayIdToUseWhenUnfolded == null
                || mLogicalDisplays.size() < 2) {
            // Do nothing if this behavior is disabled or there are less than two displays.
            return;
        }

        final DisplayDevice deviceFolded =
                mDisplayDeviceRepo.getByIdLocked(mDisplayIdToUseWhenFolded);
        final DisplayDevice deviceUnfolded =
                mDisplayDeviceRepo.getByIdLocked(mDisplayIdToUseWhenUnfolded);
        if (deviceFolded == null || deviceUnfolded == null) {
            // If the expected devices for folding functionality are not present, return early.
            return;
        }

        // Find the associated LogicalDisplays for the configured "folding" DeviceDisplays.
        final LogicalDisplay displayFolded = getLocked(deviceFolded);
        final LogicalDisplay displayUnfolded = getLocked(deviceUnfolded);
        if (displayFolded == null || displayUnfolded == null) {
            // If the expected displays are not present, return early.
            return;
        }

        // Find out which display is currently default and which is disabled.
        final LogicalDisplay defaultDisplay = mLogicalDisplays.get(Display.DEFAULT_DISPLAY);
        final LogicalDisplay disabledDisplay;
        if (defaultDisplay == displayFolded) {
            disabledDisplay = displayUnfolded;
        } else if (defaultDisplay == displayUnfolded) {
            disabledDisplay = displayFolded;
        } else {
            // If neither folded or unfolded displays are currently set to the default display, we
            // are in an unknown state and it's best to log the error and bail.
            Slog.e(TAG, "Unexpected: when attempting to swap displays, neither of the two"
                    + " configured displays were set up as the default display. Default: "
                    + defaultDisplay.getDisplayInfoLocked() + ",  ConfiguredDisplays: [ folded="
                    + displayFolded.getDisplayInfoLocked() + ", unfolded="
                    + displayUnfolded.getDisplayInfoLocked() + " ]");
            return;
        }

        if (isFolded == (defaultDisplay == displayFolded)) {
            // Nothing to do, already in the right state.
            return;
        }

        // Everything was checked and we need to swap, lets swap.
        displayFolded.swapDisplaysLocked(displayUnfolded);

        // We ensure that the non-default Display is always forced to be off. This was likely
        // already done in a previous iteration, but we do it with each swap in case something in
        // the underlying LogicalDisplays changed: like LogicalDisplay recreation, for example.
        defaultDisplay.setEnabled(true);
        disabledDisplay.setEnabled(false);

        // Update the world
        updateLogicalDisplaysLocked();

        if (DEBUG) {
            Slog.d(TAG, "Folded displays: isFolded: " + isFolded + ", defaultDisplay? "
                    + defaultDisplay.getDisplayInfoLocked());
        }
    }

    void setFoldOverrideLocked(Boolean isFolded) {
        if (!Objects.equals(isFolded, mIsFoldedOverride)) {
            mIsFoldedOverride = isFolded;
            setDeviceFoldedLocked(mIsFolded);
        }
    }

    private void handleDisplayDeviceAddedLocked(DisplayDevice device) {
        DisplayDeviceInfo deviceInfo = device.getDisplayDeviceInfoLocked();
        boolean isDefault = (deviceInfo.flags & DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY) != 0;
        if (isDefault && mLogicalDisplays.get(Display.DEFAULT_DISPLAY) != null) {
            Slog.w(TAG, "Ignoring attempt to add a second default display: " + deviceInfo);
            isDefault = false;
        }

        if (!isDefault && mSingleDisplayDemoMode) {
            Slog.i(TAG, "Not creating a logical display for a secondary display "
                    + " because single display demo mode is enabled: " + deviceInfo);
            return;
        }

        final int displayId = assignDisplayIdLocked(isDefault);
        final int layerStack = assignLayerStackLocked(displayId);

        final DisplayGroup displayGroup;
        final boolean addNewDisplayGroup =
                isDefault || (deviceInfo.flags & DisplayDeviceInfo.FLAG_OWN_DISPLAY_GROUP) != 0;
        if (addNewDisplayGroup) {
            final int groupId = assignDisplayGroupIdLocked(isDefault);
            displayGroup = new DisplayGroup(groupId);
        } else {
            displayGroup = mDisplayGroups.get(Display.DEFAULT_DISPLAY);
        }

        LogicalDisplay display = new LogicalDisplay(displayId, layerStack, device);
        display.updateDisplayGroupIdLocked(displayGroup.getGroupId());
        display.updateLocked(mDisplayDeviceRepo);
        if (!display.isValidLocked()) {
            // This should never happen currently.
            Slog.w(TAG, "Ignoring display device because the logical display "
                    + "created from it was not considered valid: " + deviceInfo);
            return;
        }

        mLogicalDisplays.put(displayId, display);

        displayGroup.addDisplay(display);
        mDisplayGroups.append(displayId, displayGroup);

        mListener.onLogicalDisplayEventLocked(display,
                LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_ADDED);
    }

    /**
     * Updates all existing logical displays given the current set of display devices.
     * Removes invalid logical displays. Sends notifications if needed.
     */
    private void updateLogicalDisplaysLocked() {
        for (int i = mLogicalDisplays.size() - 1; i >= 0; i--) {
            final int displayId = mLogicalDisplays.keyAt(i);
            LogicalDisplay display = mLogicalDisplays.valueAt(i);

            mTempDisplayInfo.copyFrom(display.getDisplayInfoLocked());
            display.getNonOverrideDisplayInfoLocked(mTempNonOverrideDisplayInfo);
            DisplayEventReceiver.FrameRateOverride[] frameRatesOverrides =
                    display.getFrameRateOverrides();
            display.updateLocked(mDisplayDeviceRepo);
            if (!display.isValidLocked()) {
                mLogicalDisplays.removeAt(i);
                mDisplayGroups.removeReturnOld(displayId).removeDisplay(display);

                mListener.onLogicalDisplayEventLocked(display,
                        LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_REMOVED);
            } else if (!mTempDisplayInfo.equals(display.getDisplayInfoLocked())) {
                final int flags = display.getDisplayInfoLocked().flags;
                final DisplayGroup defaultDisplayGroup = mDisplayGroups.get(
                        Display.DEFAULT_DISPLAY);
                if ((flags & Display.FLAG_OWN_DISPLAY_GROUP) != 0) {
                    // The display should have its own DisplayGroup.
                    if (defaultDisplayGroup.removeDisplay(display)) {
                        final int groupId = assignDisplayGroupIdLocked(false);
                        final DisplayGroup displayGroup = new DisplayGroup(groupId);
                        displayGroup.addDisplay(display);
                        mDisplayGroups.append(display.getDisplayIdLocked(), displayGroup);
                        display.updateDisplayGroupIdLocked(groupId);
                    }
                } else {
                    // The display should be a part of the default DisplayGroup.
                    final DisplayGroup displayGroup = mDisplayGroups.get(displayId);
                    if (displayGroup != defaultDisplayGroup) {
                        displayGroup.removeDisplay(display);
                        defaultDisplayGroup.addDisplay(display);
                        mDisplayGroups.put(displayId, defaultDisplayGroup);
                        display.updateDisplayGroupIdLocked(defaultDisplayGroup.getGroupId());
                    }
                }

                final String oldUniqueId = mTempDisplayInfo.uniqueId;
                final String newUniqueId = display.getDisplayInfoLocked().uniqueId;
                final int eventMsg = TextUtils.equals(oldUniqueId, newUniqueId)
                        ? LOGICAL_DISPLAY_EVENT_CHANGED : LOGICAL_DISPLAY_EVENT_SWAPPED;
                mListener.onLogicalDisplayEventLocked(display, eventMsg);
            } else if (!display.getPendingFrameRateOverrideUids().isEmpty()) {
                mListener.onLogicalDisplayEventLocked(display,
                        LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_FRAME_RATE_OVERRIDES_CHANGED);
            } else {
                // While applications shouldn't know nor care about the non-overridden info, we
                // still need to let WindowManager know so it can update its own internal state for
                // things like display cutouts.
                display.getNonOverrideDisplayInfoLocked(mTempDisplayInfo);
                if (!mTempNonOverrideDisplayInfo.equals(mTempDisplayInfo)) {
                    mListener.onLogicalDisplayEventLocked(display,
                            LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_CHANGED);
                }
            }
        }
    }

    private int assignDisplayIdLocked(boolean isDefault) {
        return isDefault ? Display.DEFAULT_DISPLAY : mNextNonDefaultDisplayId++;
    }

    private int assignDisplayGroupIdLocked(boolean isDefault) {
        return isDefault ? Display.DEFAULT_DISPLAY_GROUP : mNextNonDefaultGroupId++;
    }

    private int assignLayerStackLocked(int displayId) {
        // Currently layer stacks and display ids are the same.
        // This need not be the case.
        return displayId;
    }

    private void loadFoldedDisplayConfig(Context context) {
        final String[] displayIds = context.getResources().getStringArray(
                com.android.internal.R.array.config_internalFoldedPhysicalDisplayIds);

        if (displayIds.length != 2 || TextUtils.isEmpty(displayIds[0])
                || TextUtils.isEmpty(displayIds[1])) {
            Slog.w(TAG, "Folded display configuration invalid: [" + Arrays.toString(displayIds)
                    + "]");
            return;
        }

        mDisplayIdToUseWhenFolded = displayIds[0];
        mDisplayIdToUseWhenUnfolded = displayIds[1];
    }

    public interface Listener {
        void onLogicalDisplayEventLocked(LogicalDisplay display, int event);
        void onTraversalRequested();
    }
}
