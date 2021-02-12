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

import com.android.server.display.layout.Layout;

import java.io.PrintWriter;
import java.util.Arrays;
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

    public static final int DISPLAY_GROUP_EVENT_ADDED = 1;
    public static final int DISPLAY_GROUP_EVENT_CHANGED = 2;
    public static final int DISPLAY_GROUP_EVENT_REMOVED = 3;

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
     * List of all logical displays indexed by logical display id.
     * Any modification to mLogicalDisplays must invalidate the DisplayManagerGlobal cache.
     * TODO: multi-display - Move the aforementioned comment?
     */
    private final SparseArray<LogicalDisplay> mLogicalDisplays =
            new SparseArray<LogicalDisplay>();

    /** A mapping from logical display id to display group. */
    private final SparseArray<DisplayGroup> mDisplayIdToGroupMap = new SparseArray<>();

    private final DisplayDeviceRepository mDisplayDeviceRepo;
    private final DeviceStateToLayoutMap mDeviceStateToLayoutMap;
    private final Listener mListener;
    private final int[] mFoldedDeviceStates;

    private int mNextNonDefaultGroupId = Display.DEFAULT_DISPLAY_GROUP + 1;
    private Layout mCurrentLayout = null;
    private boolean mIsFolded = false;

    LogicalDisplayMapper(Context context, DisplayDeviceRepository repo, Listener listener) {
        mDisplayDeviceRepo = repo;
        mListener = listener;
        mSingleDisplayDemoMode = SystemProperties.getBoolean("persist.demo.singledisplay", false);
        mDisplayDeviceRepo.addListener(this);

        mFoldedDeviceStates = context.getResources().getIntArray(
                com.android.internal.R.array.config_foldedDeviceStates);

        mDeviceStateToLayoutMap = new DeviceStateToLayoutMap(context);
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
        final DisplayGroup displayGroup = mDisplayIdToGroupMap.get(displayId);
        if (displayGroup != null) {
            return displayGroup.getGroupId();
        }

        return -1;
    }

    public DisplayGroup getDisplayGroupLocked(int groupId) {
        final int size = mDisplayIdToGroupMap.size();
        for (int i = 0; i < size; i++) {
            final DisplayGroup displayGroup = mDisplayIdToGroupMap.valueAt(i);
            if (displayGroup.getGroupId() == groupId) {
                return displayGroup;
            }
        }

        return null;
    }

    public void dumpLocked(PrintWriter pw) {
        pw.println("LogicalDisplayMapper:");
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        ipw.increaseIndent();

        ipw.println("mSingleDisplayDemoMode=" + mSingleDisplayDemoMode);

        ipw.println("mCurrentLayout=" + mCurrentLayout);

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
        mDeviceStateToLayoutMap.dumpLocked(ipw);
    }

    void setDeviceStateLocked(int state) {
        boolean folded = false;
        for (int i = 0; i < mFoldedDeviceStates.length; i++) {
            if (state == mFoldedDeviceStates[i]) {
                folded = true;
                break;
            }
        }
        setDeviceFoldedLocked(folded);
    }

    void setDeviceFoldedLocked(boolean isFolded) {
        mIsFolded = isFolded;

        // Until we have fully functioning state mapping, use hardcoded states based on isFolded
        final int state = mIsFolded ? DeviceStateToLayoutMap.STATE_FOLDED
                : DeviceStateToLayoutMap.STATE_UNFOLDED;

        if (DEBUG) {
            Slog.d(TAG, "New device state: " + state);
        }

        final Layout layout = mDeviceStateToLayoutMap.get(state);
        if (layout == null) {
            return;
        }
        final Layout.Display displayLayout = layout.getById(Display.DEFAULT_DISPLAY);
        if (displayLayout == null) {
            return;
        }
        final DisplayDevice newDefaultDevice =
                mDisplayDeviceRepo.getByAddressLocked(displayLayout.getAddress());
        if (newDefaultDevice == null) {
            return;
        }

        final LogicalDisplay defaultDisplay = mLogicalDisplays.get(Display.DEFAULT_DISPLAY);
        mCurrentLayout = layout;

        // If we're already set up accurately, return early
        if (defaultDisplay.getPrimaryDisplayDeviceLocked() == newDefaultDevice) {
            return;
        }

        // We need to swap the default display's display-device with the one that is supposed
        // to be the default in the new layout.
        final LogicalDisplay displayToSwap = getLocked(newDefaultDevice);
        if (displayToSwap == null) {
            Slog.w(TAG, "Canceling display swap - unexpected empty second display for: "
                    + newDefaultDevice);
            return;
        }
        defaultDisplay.swapDisplaysLocked(displayToSwap);

        // We ensure that the non-default Display is always forced to be off. This was likely
        // already done in a previous iteration, but we do it with each swap in case something in
        // the underlying LogicalDisplays changed: like LogicalDisplay recreation, for example.
        defaultDisplay.setEnabled(true);
        displayToSwap.setEnabled(false);

        // Update the world
        updateLogicalDisplaysLocked();
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

        final int displayId = Layout.assignDisplayIdLocked(isDefault);
        final int layerStack = assignLayerStackLocked(displayId);

        final DisplayGroup displayGroup;
        final boolean addNewDisplayGroup =
                isDefault || (deviceInfo.flags & DisplayDeviceInfo.FLAG_OWN_DISPLAY_GROUP) != 0;
        if (addNewDisplayGroup) {
            final int groupId = assignDisplayGroupIdLocked(isDefault);
            displayGroup = new DisplayGroup(groupId);
        } else {
            displayGroup = mDisplayIdToGroupMap.get(Display.DEFAULT_DISPLAY);
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

        // For foldable devices, we start the internal non-default displays as disabled.
        // TODO - b/168208162 - this will be removed when we recalculate the layout with each
        // display-device addition.
        if (mFoldedDeviceStates.length > 0 && deviceInfo.type == Display.TYPE_INTERNAL
                && !isDefault) {
            display.setEnabled(false);
        }

        mLogicalDisplays.put(displayId, display);
        displayGroup.addDisplayLocked(display);
        mDisplayIdToGroupMap.append(displayId, displayGroup);

        if (addNewDisplayGroup) {
            // Group added events happen before Logical Display added events.
            mListener.onDisplayGroupEventLocked(displayGroup.getGroupId(),
                    LogicalDisplayMapper.DISPLAY_GROUP_EVENT_ADDED);
        }

        mListener.onLogicalDisplayEventLocked(display,
                LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_ADDED);

        if (!addNewDisplayGroup) {
            // Group changed events happen after Logical Display added events.
            mListener.onDisplayGroupEventLocked(displayGroup.getGroupId(),
                    LogicalDisplayMapper.DISPLAY_GROUP_EVENT_CHANGED);
        }

        if (DEBUG) {
            Slog.d(TAG, "New Display added: " + display);
        }
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
            final DisplayGroup changedDisplayGroup;
            if (!display.isValidLocked()) {
                mLogicalDisplays.removeAt(i);
                final DisplayGroup displayGroup = mDisplayIdToGroupMap.removeReturnOld(displayId);
                displayGroup.removeDisplayLocked(display);

                mListener.onLogicalDisplayEventLocked(display,
                        LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_REMOVED);

                changedDisplayGroup = displayGroup;
            } else if (!mTempDisplayInfo.equals(display.getDisplayInfoLocked())) {
                final int flags = display.getDisplayInfoLocked().flags;
                final DisplayGroup defaultDisplayGroup = mDisplayIdToGroupMap.get(
                        Display.DEFAULT_DISPLAY);
                if ((flags & Display.FLAG_OWN_DISPLAY_GROUP) != 0) {
                    // The display should have its own DisplayGroup.
                    if (defaultDisplayGroup.removeDisplayLocked(display)) {
                        final int groupId = assignDisplayGroupIdLocked(false);
                        final DisplayGroup displayGroup = new DisplayGroup(groupId);
                        displayGroup.addDisplayLocked(display);
                        display.updateDisplayGroupIdLocked(groupId);
                        mDisplayIdToGroupMap.append(display.getDisplayIdLocked(), displayGroup);
                        mListener.onDisplayGroupEventLocked(displayGroup.getGroupId(),
                                LogicalDisplayMapper.DISPLAY_GROUP_EVENT_ADDED);
                        changedDisplayGroup = defaultDisplayGroup;
                    } else {
                        changedDisplayGroup = null;
                    }
                } else {
                    // The display should be a part of the default DisplayGroup.
                    final DisplayGroup displayGroup = mDisplayIdToGroupMap.get(displayId);
                    if (displayGroup != defaultDisplayGroup) {
                        displayGroup.removeDisplayLocked(display);
                        defaultDisplayGroup.addDisplayLocked(display);
                        display.updateDisplayGroupIdLocked(defaultDisplayGroup.getGroupId());
                        mListener.onDisplayGroupEventLocked(defaultDisplayGroup.getGroupId(),
                                LogicalDisplayMapper.DISPLAY_GROUP_EVENT_CHANGED);
                        mDisplayIdToGroupMap.put(displayId, defaultDisplayGroup);
                        changedDisplayGroup = displayGroup;
                    } else {
                        changedDisplayGroup = null;
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
                changedDisplayGroup = null;
            } else {
                // While applications shouldn't know nor care about the non-overridden info, we
                // still need to let WindowManager know so it can update its own internal state for
                // things like display cutouts.
                display.getNonOverrideDisplayInfoLocked(mTempDisplayInfo);
                if (!mTempNonOverrideDisplayInfo.equals(mTempDisplayInfo)) {
                    mListener.onLogicalDisplayEventLocked(display,
                            LogicalDisplayMapper.LOGICAL_DISPLAY_EVENT_CHANGED);
                }
                changedDisplayGroup = null;
            }

            // CHANGED and REMOVED DisplayGroup events should always fire after Display events.
            if (changedDisplayGroup != null) {
                final int event = changedDisplayGroup.isEmptyLocked()
                        ? LogicalDisplayMapper.DISPLAY_GROUP_EVENT_REMOVED
                        : LogicalDisplayMapper.DISPLAY_GROUP_EVENT_CHANGED;
                mListener.onDisplayGroupEventLocked(changedDisplayGroup.getGroupId(), event);
            }
        }
    }

    private int assignDisplayGroupIdLocked(boolean isDefault) {
        return isDefault ? Display.DEFAULT_DISPLAY_GROUP : mNextNonDefaultGroupId++;
    }

    private int assignLayerStackLocked(int displayId) {
        // Currently layer stacks and display ids are the same.
        // This need not be the case.
        return displayId;
    }

    public interface Listener {
        void onLogicalDisplayEventLocked(LogicalDisplay display, int event);
        void onDisplayGroupEventLocked(int groupId, int event);
        void onTraversalRequested();
    }
}
