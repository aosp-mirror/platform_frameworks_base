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

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.devicestate.DeviceStateManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.DisplayAddress;
import android.view.DisplayInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.LogicalDisplay.DisplayPhase;
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
    public static final int LOGICAL_DISPLAY_EVENT_DEVICE_STATE_TRANSITION = 6;

    public static final int DISPLAY_GROUP_EVENT_ADDED = 1;
    public static final int DISPLAY_GROUP_EVENT_CHANGED = 2;
    public static final int DISPLAY_GROUP_EVENT_REMOVED = 3;

    private static final int TIMEOUT_STATE_TRANSITION_MILLIS = 300;

    private static final int MSG_TRANSITION_TO_PENDING_DEVICE_STATE = 1;

    private static final int UPDATE_STATE_NEW = 0;
    private static final int UPDATE_STATE_TRANSITION = 1;
    private static final int UPDATE_STATE_UPDATED = 2;

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
     * True if the device can have more than one internal display on at a time.
     */
    private final boolean mSupportsConcurrentInternalDisplays;

    /**
     * Wake the device when transitioning into this device state.
     */
    private final int mDeviceStateOnWhichToWakeUp;

    /**
     * Map of all logical displays indexed by logical display id.
     * Any modification to mLogicalDisplays must invalidate the DisplayManagerGlobal cache.
     * TODO: multi-display - Move the aforementioned comment?
     */
    private final SparseArray<LogicalDisplay> mLogicalDisplays =
            new SparseArray<LogicalDisplay>();

    /** Map of all display groups indexed by display group id. */
    private final SparseArray<DisplayGroup> mDisplayGroups = new SparseArray<>();

    private final DisplayDeviceRepository mDisplayDeviceRepo;
    private final DeviceStateToLayoutMap mDeviceStateToLayoutMap;
    private final Listener mListener;
    private final DisplayManagerService.SyncRoot mSyncRoot;
    private final LogicalDisplayMapperHandler mHandler;
    private final PowerManager mPowerManager;

    /**
     * Has an entry for every logical display that the rest of the system has been notified about.
     * Any entry in here requires us to send a {@link  LOGICAL_DISPLAY_EVENT_REMOVED} event when it
     * is deleted or {@link  LOGICAL_DISPLAY_EVENT_CHANGED} when it is changed. The values are any
     * of the {@code UPDATE_STATE_*} constant types.
     */
    private final SparseIntArray mUpdatedLogicalDisplays = new SparseIntArray();

    /**
     * Keeps track of all the display groups that we already told other people about. IOW, if a
     * display group is in this array, then we *must* send change and remove notifications for it
     * because other components know about them. Also, what this array stores is a change counter
     * for each group, so we know if the group itself has changes since we last sent out a
     * notification.  See {@link DisplayGroup#getChangeCountLocked}.
     */
    private final SparseIntArray mUpdatedDisplayGroups = new SparseIntArray();

    /**
     * Array used in {@link #updateLogicalDisplaysLocked} to track events that need to be sent out.
     */
    private final SparseIntArray mLogicalDisplaysToUpdate = new SparseIntArray();

    /**
     * Array used in {@link #updateLogicalDisplaysLocked} to track events that need to be sent out.
     */
    private final SparseIntArray mDisplayGroupsToUpdate = new SparseIntArray();

    private int mNextNonDefaultGroupId = Display.DEFAULT_DISPLAY_GROUP + 1;
    private Layout mCurrentLayout = null;
    private int mDeviceState = DeviceStateManager.INVALID_DEVICE_STATE;
    private int mPendingDeviceState = DeviceStateManager.INVALID_DEVICE_STATE;

    LogicalDisplayMapper(@NonNull Context context, @NonNull DisplayDeviceRepository repo,
            @NonNull Listener listener, @NonNull DisplayManagerService.SyncRoot syncRoot,
            @NonNull Handler handler) {
        mSyncRoot = syncRoot;
        mPowerManager = context.getSystemService(PowerManager.class);
        mHandler = new LogicalDisplayMapperHandler(handler.getLooper());
        mDisplayDeviceRepo = repo;
        mListener = listener;
        mSingleDisplayDemoMode = SystemProperties.getBoolean("persist.demo.singledisplay", false);
        mSupportsConcurrentInternalDisplays = context.getResources().getBoolean(
                com.android.internal.R.bool.config_supportsConcurrentInternalDisplays);
        mDeviceStateOnWhichToWakeUp = context.getResources().getInteger(
                com.android.internal.R.integer.config_deviceStateOnWhichToWakeUp);
        mDisplayDeviceRepo.addListener(this);
        mDeviceStateToLayoutMap = new DeviceStateToLayoutMap();
    }

    @Override
    public void onDisplayDeviceEventLocked(DisplayDevice device, int event) {
        switch (event) {
            case DisplayDeviceRepository.DISPLAY_DEVICE_EVENT_ADDED:
                if (DEBUG) {
                    Slog.d(TAG, "Display device added: " + device.getDisplayDeviceInfoLocked());
                }
                handleDisplayDeviceAddedLocked(device);
                break;

            case DisplayDeviceRepository.DISPLAY_DEVICE_EVENT_CHANGED:
                if (DEBUG) {
                    Slog.d(TAG, "Display device changed: " + device.getDisplayDeviceInfoLocked());
                }
                finishStateTransitionLocked(false /*force*/);
                updateLogicalDisplaysLocked();
                break;

            case DisplayDeviceRepository.DISPLAY_DEVICE_EVENT_REMOVED:
                if (DEBUG) {
                    Slog.d(TAG, "Display device removed: " + device.getDisplayDeviceInfoLocked());
                }
                updateLogicalDisplaysLocked();
                break;
        }
    }

    @Override
    public void onTraversalRequested() {
        mListener.onTraversalRequested();
    }

    public LogicalDisplay getDisplayLocked(int displayId) {
        return mLogicalDisplays.get(displayId);
    }

    public LogicalDisplay getDisplayLocked(DisplayDevice device) {
        if (device == null) {
            return null;
        }
        final int count = mLogicalDisplays.size();
        for (int i = 0; i < count; i++) {
            final LogicalDisplay display = mLogicalDisplays.valueAt(i);
            if (display.getPrimaryDisplayDeviceLocked() == device) {
                return display;
            }
        }
        return null;
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

    @VisibleForTesting
    public int getDisplayGroupIdFromDisplayIdLocked(int displayId) {
        final LogicalDisplay display = getDisplayLocked(displayId);
        if (display == null) {
            return Display.INVALID_DISPLAY_GROUP;
        }

        final int size = mDisplayGroups.size();
        for (int i = 0; i < size; i++) {
            final DisplayGroup displayGroup = mDisplayGroups.valueAt(i);
            if (displayGroup.containsLocked(display)) {
                return mDisplayGroups.keyAt(i);
            }
        }

        return Display.INVALID_DISPLAY_GROUP;
    }

    public DisplayGroup getDisplayGroupLocked(int groupId) {
        return mDisplayGroups.get(groupId);
    }

    public void dumpLocked(PrintWriter pw) {
        pw.println("LogicalDisplayMapper:");
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        ipw.increaseIndent();

        ipw.println("mSingleDisplayDemoMode=" + mSingleDisplayDemoMode);
        ipw.println("mCurrentLayout=" + mCurrentLayout);
        ipw.println("mDeviceStateOnWhichToWakeUp=" + mDeviceStateOnWhichToWakeUp);

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
        final boolean isInteractive  = mPowerManager.isInteractive();
        Slog.i(TAG, "Requesting Transition to state: " + state + ", from state=" + mDeviceState
                + ", interactive=" + isInteractive);
        // As part of a state transition, we may need to turn off some displays temporarily so that
        // the transition is smooth. Plus, on some devices, only one internal displays can be
        // on at a time. We use DISPLAY_PHASE_LAYOUT_TRANSITION to mark a display that needs to be
        // temporarily turned off.
        if (mDeviceState != DeviceStateManager.INVALID_DEVICE_STATE) {
            resetLayoutLocked(mDeviceState, state, LogicalDisplay.DISPLAY_PHASE_LAYOUT_TRANSITION);
        }
        mPendingDeviceState = state;
        final boolean wakeDevice = mPendingDeviceState == mDeviceStateOnWhichToWakeUp
                && !isInteractive;

        // If all displays are off already, we can just transition here, unless the device is asleep
        // and we plan on waking it up. In that case, fall through to the call to wakeUp, and defer
        // the final transition until later once the device is awake.
        if (areAllTransitioningDisplaysOffLocked() && !wakeDevice) {
            transitionToPendingStateLocked();
            return;
        }

        if (DEBUG) {
            Slog.d(TAG, "Postponing transition to state: " + mPendingDeviceState);
        }
        // Send the transitioning phase updates to DisplayManager so that the displays can
        // start turning OFF in preparation for the new layout.
        updateLogicalDisplaysLocked();

        if (wakeDevice) {
            // We already told the displays to turn off, now we need to wake the device as
            // we transition to this new state. We do it here so that the waking happens between the
            // transition from one layout to another.
            mPowerManager.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_UNFOLD_DEVICE,
                    "server.display:unfold");
        }
        mHandler.sendEmptyMessageDelayed(MSG_TRANSITION_TO_PENDING_DEVICE_STATE,
                TIMEOUT_STATE_TRANSITION_MILLIS);
    }

    private boolean areAllTransitioningDisplaysOffLocked() {
        final int count = mLogicalDisplays.size();
        for (int i = 0; i < count; i++) {
            final LogicalDisplay display = mLogicalDisplays.valueAt(i);
            if (display.getPhase() != LogicalDisplay.DISPLAY_PHASE_LAYOUT_TRANSITION) {
                continue;
            }

            final DisplayDevice device = display.getPrimaryDisplayDeviceLocked();
            if (device != null) {
                final DisplayDeviceInfo info = device.getDisplayDeviceInfoLocked();
                if (info.state != Display.STATE_OFF) {
                    return false;
                }
            }
        }
        return true;
    }

    private void transitionToPendingStateLocked() {
        resetLayoutLocked(mDeviceState, mPendingDeviceState, LogicalDisplay.DISPLAY_PHASE_ENABLED);
        mDeviceState = mPendingDeviceState;
        mPendingDeviceState = DeviceStateManager.INVALID_DEVICE_STATE;
        applyLayoutLocked();
        updateLogicalDisplaysLocked();
    }

    private void finishStateTransitionLocked(boolean force) {
        if (mPendingDeviceState == DeviceStateManager.INVALID_DEVICE_STATE) {
            return;
        }

        final boolean displaysOff = areAllTransitioningDisplaysOffLocked();
        if (displaysOff || force) {
            transitionToPendingStateLocked();
            mHandler.removeMessages(MSG_TRANSITION_TO_PENDING_DEVICE_STATE);
        } else if (DEBUG) {
            Slog.d(TAG, "Not yet ready to transition to state=" + mPendingDeviceState
                    + " with displays-off=" + displaysOff + " and force=" + force);
        }
    }

    private void handleDisplayDeviceAddedLocked(DisplayDevice device) {
        DisplayDeviceInfo deviceInfo = device.getDisplayDeviceInfoLocked();
        // Internal Displays need to have additional initialization.
        // This initializes a default dynamic display layout for INTERNAL
        // devices, which is used as a fallback in case no static layout definitions
        // exist or cannot be loaded.
        if (deviceInfo.type == Display.TYPE_INTERNAL) {
            initializeInternalDisplayDeviceLocked(device);
        }

        // Create a logical display for the new display device
        LogicalDisplay display = createNewLogicalDisplayLocked(
                device, Layout.assignDisplayIdLocked(false /*isDefault*/));

        applyLayoutLocked();
        updateLogicalDisplaysLocked();
    }

    /**
     * Updates the rest of the display system once all the changes are applied for display
     * devices and logical displays. The includes releasing invalid/empty LogicalDisplays,
     * creating/adjusting/removing DisplayGroups, and notifying the rest of the system of the
     * relevant changes.
     */
    private void updateLogicalDisplaysLocked() {
        // Go through all the displays and figure out if they need to be updated.
        // Loops in reverse so that displays can be removed during the loop without affecting the
        // rest of the loop.
        for (int i = mLogicalDisplays.size() - 1; i >= 0; i--) {
            final int displayId = mLogicalDisplays.keyAt(i);
            LogicalDisplay display = mLogicalDisplays.valueAt(i);

            mTempDisplayInfo.copyFrom(display.getDisplayInfoLocked());
            display.getNonOverrideDisplayInfoLocked(mTempNonOverrideDisplayInfo);

            display.updateLocked(mDisplayDeviceRepo);
            final DisplayInfo newDisplayInfo = display.getDisplayInfoLocked();
            final int updateState = mUpdatedLogicalDisplays.get(displayId, UPDATE_STATE_NEW);
            final boolean wasPreviouslyUpdated = updateState != UPDATE_STATE_NEW;

            // The display is no longer valid and needs to be removed.
            if (!display.isValidLocked()) {
                mUpdatedLogicalDisplays.delete(displayId);

                // Remove from group
                final DisplayGroup displayGroup = getDisplayGroupLocked(
                        getDisplayGroupIdFromDisplayIdLocked(displayId));
                if (displayGroup != null) {
                    displayGroup.removeDisplayLocked(display);
                }

                if (wasPreviouslyUpdated) {
                    // The display isn't actually removed from our internal data structures until
                    // after the notification is sent; see {@link #sendUpdatesForDisplaysLocked}.
                    Slog.i(TAG, "Removing display: " + displayId);
                    mLogicalDisplaysToUpdate.put(displayId, LOGICAL_DISPLAY_EVENT_REMOVED);
                } else {
                    // This display never left this class, safe to remove without notification
                    mLogicalDisplays.removeAt(i);
                }
                continue;

            // The display is new.
            } else if (!wasPreviouslyUpdated) {
                Slog.i(TAG, "Adding new display: " + displayId + ": " + newDisplayInfo);
                assignDisplayGroupLocked(display);
                mLogicalDisplaysToUpdate.put(displayId, LOGICAL_DISPLAY_EVENT_ADDED);

            // Underlying displays device has changed to a different one.
            } else if (!TextUtils.equals(mTempDisplayInfo.uniqueId, newDisplayInfo.uniqueId)) {
                // FLAG_OWN_DISPLAY_GROUP could have changed, recalculate just in case
                assignDisplayGroupLocked(display);
                mLogicalDisplaysToUpdate.put(displayId, LOGICAL_DISPLAY_EVENT_SWAPPED);

            // Something about the display device has changed.
            } else if (!mTempDisplayInfo.equals(newDisplayInfo)) {
                // FLAG_OWN_DISPLAY_GROUP could have changed, recalculate just in case
                assignDisplayGroupLocked(display);
                mLogicalDisplaysToUpdate.put(displayId, LOGICAL_DISPLAY_EVENT_CHANGED);

            // The display is involved in a display layout transition
            } else if (updateState == UPDATE_STATE_TRANSITION) {
                mLogicalDisplaysToUpdate.put(displayId,
                        LOGICAL_DISPLAY_EVENT_DEVICE_STATE_TRANSITION);

            // Display frame rate overrides changed.
            } else if (!display.getPendingFrameRateOverrideUids().isEmpty()) {
                mLogicalDisplaysToUpdate.put(
                        displayId, LOGICAL_DISPLAY_EVENT_FRAME_RATE_OVERRIDES_CHANGED);

            // Non-override display values changed.
            } else {
                // While application shouldn't know nor care about the non-overridden info, we
                // still need to let WindowManager know so it can update its own internal state for
                // things like display cutouts.
                display.getNonOverrideDisplayInfoLocked(mTempDisplayInfo);
                if (!mTempNonOverrideDisplayInfo.equals(mTempDisplayInfo)) {
                    mLogicalDisplaysToUpdate.put(displayId, LOGICAL_DISPLAY_EVENT_CHANGED);
                }
            }

            mUpdatedLogicalDisplays.put(displayId, UPDATE_STATE_UPDATED);
        }

        // Go through the groups and do the same thing. We do this after displays since group
        // information can change in the previous loop.
        // Loops in reverse so that groups can be removed during the loop without affecting the
        // rest of the loop.
        for (int i = mDisplayGroups.size() - 1; i >= 0; i--) {
            final int groupId = mDisplayGroups.keyAt(i);
            final DisplayGroup group = mDisplayGroups.valueAt(i);
            final boolean wasPreviouslyUpdated = mUpdatedDisplayGroups.indexOfKey(groupId) > -1;
            final int changeCount = group.getChangeCountLocked();

            if (group.isEmptyLocked()) {
                mUpdatedDisplayGroups.delete(groupId);
                if (wasPreviouslyUpdated) {
                    mDisplayGroupsToUpdate.put(groupId, DISPLAY_GROUP_EVENT_REMOVED);
                }
                continue;
            } else if (!wasPreviouslyUpdated) {
                mDisplayGroupsToUpdate.put(groupId, DISPLAY_GROUP_EVENT_ADDED);
            } else if (mUpdatedDisplayGroups.get(groupId) != changeCount) {
                mDisplayGroupsToUpdate.put(groupId, DISPLAY_GROUP_EVENT_CHANGED);
            }
            mUpdatedDisplayGroups.put(groupId, changeCount);
        }

        // Send the display and display group updates in order by message type. This is important
        // to ensure that addition and removal notifications happen in the right order.
        sendUpdatesForDisplaysLocked(LOGICAL_DISPLAY_EVENT_DEVICE_STATE_TRANSITION);
        sendUpdatesForGroupsLocked(DISPLAY_GROUP_EVENT_ADDED);
        sendUpdatesForDisplaysLocked(LOGICAL_DISPLAY_EVENT_REMOVED);
        sendUpdatesForDisplaysLocked(LOGICAL_DISPLAY_EVENT_CHANGED);
        sendUpdatesForDisplaysLocked(LOGICAL_DISPLAY_EVENT_FRAME_RATE_OVERRIDES_CHANGED);
        sendUpdatesForDisplaysLocked(LOGICAL_DISPLAY_EVENT_SWAPPED);
        sendUpdatesForDisplaysLocked(LOGICAL_DISPLAY_EVENT_ADDED);
        sendUpdatesForGroupsLocked(DISPLAY_GROUP_EVENT_CHANGED);
        sendUpdatesForGroupsLocked(DISPLAY_GROUP_EVENT_REMOVED);

        mLogicalDisplaysToUpdate.clear();
        mDisplayGroupsToUpdate.clear();
    }

    /**
     * Send the specified message for all relevant displays in the specified display-to-message map.
     */
    private void sendUpdatesForDisplaysLocked(int msg) {
        for (int i = mLogicalDisplaysToUpdate.size() - 1; i >= 0; --i) {
            final int currMsg = mLogicalDisplaysToUpdate.valueAt(i);
            if (currMsg != msg) {
                continue;
            }

            final int id = mLogicalDisplaysToUpdate.keyAt(i);
            final LogicalDisplay display = getDisplayLocked(id);
            if (DEBUG) {
                final DisplayDevice device = display.getPrimaryDisplayDeviceLocked();
                final String uniqueId = device == null ? "null" : device.getUniqueId();
                Slog.d(TAG, "Sending " + displayEventToString(msg) + " for display=" + id
                        + " with device=" + uniqueId);
            }
            mListener.onLogicalDisplayEventLocked(display, msg);
            if (msg == LOGICAL_DISPLAY_EVENT_REMOVED) {
                // We wait until we sent the EVENT_REMOVED event before actually removing the
                // display.
                mLogicalDisplays.delete(id);
            }
        }
    }

    /**
     * Send the specified message for all relevant display groups in the specified message map.
     */
    private void sendUpdatesForGroupsLocked(int msg) {
        for (int i = mDisplayGroupsToUpdate.size() - 1; i >= 0; --i) {
            final int currMsg = mDisplayGroupsToUpdate.valueAt(i);
            if (currMsg != msg) {
                continue;
            }

            final int id = mDisplayGroupsToUpdate.keyAt(i);
            mListener.onDisplayGroupEventLocked(id, msg);
            if (msg == DISPLAY_GROUP_EVENT_REMOVED) {
                // We wait until we sent the EVENT_REMOVED event before actually removing the
                // group.
                mDisplayGroups.delete(id);
            }
        }
    }

    private void assignDisplayGroupLocked(LogicalDisplay display) {
        final int displayId = display.getDisplayIdLocked();

        // Get current display group data
        int groupId = getDisplayGroupIdFromDisplayIdLocked(displayId);
        final DisplayGroup oldGroup = getDisplayGroupLocked(groupId);

        // Get the new display group if a change is needed
        final DisplayInfo info = display.getDisplayInfoLocked();
        final boolean needsOwnDisplayGroup = (info.flags & Display.FLAG_OWN_DISPLAY_GROUP) != 0;
        final boolean hasOwnDisplayGroup = groupId != Display.DEFAULT_DISPLAY_GROUP;
        if (groupId == Display.INVALID_DISPLAY_GROUP
                || hasOwnDisplayGroup != needsOwnDisplayGroup) {
            groupId = assignDisplayGroupIdLocked(needsOwnDisplayGroup);
        }

        // Create a new group if needed
        DisplayGroup newGroup = getDisplayGroupLocked(groupId);
        if (newGroup == null) {
            newGroup = new DisplayGroup(groupId);
            mDisplayGroups.append(groupId, newGroup);
        }
        if (oldGroup != newGroup) {
            if (oldGroup != null) {
                oldGroup.removeDisplayLocked(display);
            }
            newGroup.addDisplayLocked(display);
            display.updateDisplayGroupIdLocked(groupId);
            Slog.i(TAG, "Setting new display group " + groupId + " for display "
                    + displayId + ", from previous group: "
                    + (oldGroup != null ? oldGroup.getGroupId() : "null"));
        }
    }

    /**
     * Goes through all the displays used in the layouts for the specified {@code fromState} and
     * {@code toState} and applies the specified {@code phase}. When a new layout is requested, we
     * put the displays that will change into a transitional phase so that they can all be turned
     * OFF. Once all are confirmed OFF, then this method gets called again to reset the phase to
     * normal operation. This helps to ensure that all display-OFF requests are made before
     * display-ON which in turn hides any resizing-jank windows might incur when switching displays.
     *
     * @param fromState The state we are switching from.
     * @param toState The state we are switching to.
     * @param phase The new phase to apply to the displays.
     */
    private void resetLayoutLocked(int fromState, int toState, @DisplayPhase int phase) {
        final Layout fromLayout = mDeviceStateToLayoutMap.get(fromState);
        final Layout toLayout = mDeviceStateToLayoutMap.get(toState);

        final int count = mLogicalDisplays.size();
        for (int i = 0; i < count; i++) {
            final LogicalDisplay logicalDisplay = mLogicalDisplays.valueAt(i);
            final int displayId = logicalDisplay.getDisplayIdLocked();
            final DisplayDevice device = logicalDisplay.getPrimaryDisplayDeviceLocked();
            if (device == null) {
                // If there's no device, then the logical display is due to be removed. Ignore it.
                continue;
            }

            // Grab the display associations this display-device has in the old layout and the
            // new layout.
            final DisplayAddress address = device.getDisplayDeviceInfoLocked().address;

            // Virtual displays do not have addresses.
            final Layout.Display fromDisplay =
                    address != null ? fromLayout.getByAddress(address) : null;
            final Layout.Display toDisplay =
                    address != null ? toLayout.getByAddress(address) : null;

            // If a layout doesn't mention a display-device at all, then the display-device defaults
            // to enabled. This is why we treat null as "enabled" in the code below.
            final boolean wasEnabled = fromDisplay == null || fromDisplay.isEnabled();
            final boolean willBeEnabled = toDisplay == null || toDisplay.isEnabled();

            final boolean deviceHasNewLogicalDisplayId = fromDisplay != null && toDisplay != null
                    && fromDisplay.getLogicalDisplayId() != toDisplay.getLogicalDisplayId();

            // We consider a display-device as changing/transition if
            // 1) It's already marked as transitioning
            // 2) It's going from enabled to disabled, or vice versa
            // 3) It's enabled, but it's mapped to a new logical display ID. To the user this
            //    would look like apps moving from one screen to another since task-stacks stay
            //    with the logical display [ID].
            final boolean isTransitioning =
                    (logicalDisplay.getPhase() == LogicalDisplay.DISPLAY_PHASE_LAYOUT_TRANSITION)
                    || (wasEnabled != willBeEnabled)
                    || deviceHasNewLogicalDisplayId;

            if (isTransitioning) {
                setDisplayPhase(logicalDisplay, phase);
                if (phase == LogicalDisplay.DISPLAY_PHASE_LAYOUT_TRANSITION) {
                    mUpdatedLogicalDisplays.put(displayId, UPDATE_STATE_TRANSITION);
                }
            }
        }
    }

    /**
     * Apply (or reapply) the currently selected display layout.
     */
    private void applyLayoutLocked() {
        final Layout oldLayout = mCurrentLayout;
        mCurrentLayout = mDeviceStateToLayoutMap.get(mDeviceState);
        Slog.i(TAG, "Applying layout: " + mCurrentLayout + ", Previous layout: " + oldLayout);

        // Go through each of the displays in the current layout set.
        final int size = mCurrentLayout.size();
        for (int i = 0; i < size; i++) {
            final Layout.Display displayLayout = mCurrentLayout.getAt(i);

            // If the underlying display-device we want to use for this display
            // doesn't exist, then skip it. This can happen at startup as display-devices
            // trickle in one at a time. When the new display finally shows up, the layout is
            // recalculated so that the display is properly added to the current layout.
            final DisplayAddress address = displayLayout.getAddress();
            final DisplayDevice device = mDisplayDeviceRepo.getByAddressLocked(address);
            if (device == null) {
                Slog.w(TAG, "The display device (" + address + "), is not available"
                        + " for the display state " + mDeviceState);
                continue;
            }

            // Now that we have a display-device, we need a LogicalDisplay to map it to. Find the
            // right one, if it doesn't exist, create a new one.
            final int logicalDisplayId = displayLayout.getLogicalDisplayId();
            LogicalDisplay newDisplay = getDisplayLocked(logicalDisplayId);
            if (newDisplay == null) {
                newDisplay = createNewLogicalDisplayLocked(
                        null /*displayDevice*/, logicalDisplayId);
            }

            // Now swap the underlying display devices between the old display and the new display
            final LogicalDisplay oldDisplay = getDisplayLocked(device);
            if (newDisplay != oldDisplay) {
                newDisplay.swapDisplaysLocked(oldDisplay);
            }

            if (!displayLayout.isEnabled()) {
                setDisplayPhase(newDisplay, LogicalDisplay.DISPLAY_PHASE_DISABLED);
            }
        }

    }


    /**
     * Creates a new logical display for the specified device and display Id and adds it to the list
     * of logical displays.
     *
     * @param device The device to associate with the LogicalDisplay.
     * @param displayId The display ID to give the new display. If invalid, a new ID is assigned.
     * @param isDefault Indicates if we are creating the default display.
     * @return The new logical display if created, null otherwise.
     */
    private LogicalDisplay createNewLogicalDisplayLocked(DisplayDevice device, int displayId) {
        final int layerStack = assignLayerStackLocked(displayId);
        final LogicalDisplay display = new LogicalDisplay(displayId, layerStack, device);
        display.updateLocked(mDisplayDeviceRepo);
        mLogicalDisplays.put(displayId, display);
        setDisplayPhase(display, LogicalDisplay.DISPLAY_PHASE_ENABLED);
        return display;
    }

    private void setDisplayPhase(LogicalDisplay display, @DisplayPhase int phase) {
        final int displayId = display.getDisplayIdLocked();
        final DisplayInfo info = display.getDisplayInfoLocked();

        final boolean disallowSecondaryDisplay = mSingleDisplayDemoMode
                && (info.type != Display.TYPE_INTERNAL);
        if (phase != LogicalDisplay.DISPLAY_PHASE_DISABLED && disallowSecondaryDisplay) {
            Slog.i(TAG, "Not creating a logical display for a secondary display because single"
                    + " display demo mode is enabled: " + display.getDisplayInfoLocked());
            phase = LogicalDisplay.DISPLAY_PHASE_DISABLED;
        }

        display.setPhase(phase);
    }

    private int assignDisplayGroupIdLocked(boolean isOwnDisplayGroup) {
        return isOwnDisplayGroup ? mNextNonDefaultGroupId++ : Display.DEFAULT_DISPLAY_GROUP;
    }

    private void initializeInternalDisplayDeviceLocked(DisplayDevice device) {
        // We always want to make sure that our default layout creates a logical
        // display for every internal display device that is found.
        // To that end, when we are notified of a new internal display, we add it to
        // the default layout definition if it is not already there.
        final Layout layout = mDeviceStateToLayoutMap.get(DeviceStateToLayoutMap.STATE_DEFAULT);
        final DisplayDeviceInfo info = device.getDisplayDeviceInfoLocked();
        final boolean isDefault = (info.flags & DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY) != 0;
        final boolean isEnabled = isDefault || mSupportsConcurrentInternalDisplays;
        layout.createDisplayLocked(info.address, isDefault, isEnabled);
    }

    private int assignLayerStackLocked(int displayId) {
        // Currently layer stacks and display ids are the same.
        // This need not be the case.
        return displayId;
    }

    private String displayEventToString(int msg) {
        switch(msg) {
            case LOGICAL_DISPLAY_EVENT_ADDED:
                return "added";
            case LOGICAL_DISPLAY_EVENT_DEVICE_STATE_TRANSITION:
                return "transition";
            case LOGICAL_DISPLAY_EVENT_CHANGED:
                return "changed";
            case LOGICAL_DISPLAY_EVENT_FRAME_RATE_OVERRIDES_CHANGED:
                return "framerate_override";
            case LOGICAL_DISPLAY_EVENT_SWAPPED:
                return "swapped";
            case LOGICAL_DISPLAY_EVENT_REMOVED:
                return "removed";
        }
        return null;
    }

    public interface Listener {
        void onLogicalDisplayEventLocked(LogicalDisplay display, int event);
        void onDisplayGroupEventLocked(int groupId, int event);
        void onTraversalRequested();
    }

    private class LogicalDisplayMapperHandler extends Handler {
        LogicalDisplayMapperHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_TRANSITION_TO_PENDING_DEVICE_STATE:
                    synchronized (mSyncRoot) {
                        finishStateTransitionLocked(true /*force*/);
                    }
                    break;
            }
        }
    }

}
