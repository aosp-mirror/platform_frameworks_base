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

import static android.view.Display.DEFAULT_DISPLAY;

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
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.DisplayAddress;
import android.view.DisplayInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.LogicalDisplay.DisplayPhase;
import com.android.server.display.layout.Layout;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Set;
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

    private static final int TIMEOUT_STATE_TRANSITION_MILLIS = 500;

    private static final int MSG_TRANSITION_TO_PENDING_DEVICE_STATE = 1;

    private static final int UPDATE_STATE_NEW = 0;
    private static final int UPDATE_STATE_UPDATED = 1;
    private static final int UPDATE_STATE_DISABLED = 2;

    private static final int UPDATE_STATE_MASK = 0x3;

    private static final int UPDATE_STATE_FLAG_TRANSITION = 0x100;

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
     * Wake the device when transitioning into these device state.
     */
    private final SparseBooleanArray mDeviceStatesOnWhichToWakeUp;

    /**
     * Sleep the device when transitioning into these device state.
     */
    private final SparseBooleanArray mDeviceStatesOnWhichToSleep;

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
    private boolean mBootCompleted = false;
    private boolean mInteractive;

    LogicalDisplayMapper(@NonNull Context context, @NonNull DisplayDeviceRepository repo,
            @NonNull Listener listener, @NonNull DisplayManagerService.SyncRoot syncRoot,
            @NonNull Handler handler, @NonNull DeviceStateToLayoutMap deviceStateToLayoutMap) {
        mSyncRoot = syncRoot;
        mPowerManager = context.getSystemService(PowerManager.class);
        mInteractive = mPowerManager.isInteractive();
        mHandler = new LogicalDisplayMapperHandler(handler.getLooper());
        mDisplayDeviceRepo = repo;
        mListener = listener;
        mSingleDisplayDemoMode = SystemProperties.getBoolean("persist.demo.singledisplay", false);
        mSupportsConcurrentInternalDisplays = context.getResources().getBoolean(
                com.android.internal.R.bool.config_supportsConcurrentInternalDisplays);
        mDeviceStatesOnWhichToWakeUp = toSparseBooleanArray(context.getResources().getIntArray(
                com.android.internal.R.array.config_deviceStatesOnWhichToWakeUp));
        mDeviceStatesOnWhichToSleep = toSparseBooleanArray(context.getResources().getIntArray(
                com.android.internal.R.array.config_deviceStatesOnWhichToSleep));
        mDisplayDeviceRepo.addListener(this);
        mDeviceStateToLayoutMap = deviceStateToLayoutMap;
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
                handleDisplayDeviceRemovedLocked(device);
                updateLogicalDisplaysLocked();
                break;
        }
    }

    @Override
    public void onTraversalRequested() {
        mListener.onTraversalRequested();
    }

    public LogicalDisplay getDisplayLocked(int displayId) {
        return getDisplayLocked(displayId, /* includeDisabled= */ false);
    }

    LogicalDisplay getDisplayLocked(int displayId, boolean includeDisabled) {
        LogicalDisplay display = mLogicalDisplays.get(displayId);
        if (display != null && (display.isEnabled() || includeDisabled)) {
            return display;
        }
        return null;
    }

    public LogicalDisplay getDisplayLocked(DisplayDevice device) {
        return getDisplayLocked(device, /* includeDisabled= */ false);
    }

    /**
     * Loops through the existing list of displays and returns one that is associated with the
     * specified display device.
     *
     * @param device The display device that should be associated with the LogicalDisplay.
     * @param includeDisabled True if this method should return disabled displays as well.
     */
    private LogicalDisplay getDisplayLocked(DisplayDevice device, boolean includeDisabled) {
        if (device == null) {
            return null;
        }
        final int count = mLogicalDisplays.size();
        for (int i = 0; i < count; i++) {
            final LogicalDisplay display = mLogicalDisplays.valueAt(i);
            if (display.getPrimaryDisplayDeviceLocked() == device) {
                if (display.isEnabled() || includeDisabled) {
                    return display;
                } else {
                    return null;
                }
            }
        }
        return null;
    }

    // Returns display Ids, defaults to enabled only.
    public int[] getDisplayIdsLocked(int callingUid) {
        return getDisplayIdsLocked(callingUid, /* includeDisabledDisplays= */ false);
    }

    // Returns display Ids, specified whether enabled only, or all displays.
    public int[] getDisplayIdsLocked(int callingUid, boolean includeDisabledDisplays) {
        final int count = mLogicalDisplays.size();
        int[] displayIds = new int[count];
        int n = 0;
        for (int i = 0; i < count; i++) {
            LogicalDisplay display = mLogicalDisplays.valueAt(i);
            if (!includeDisabledDisplays && !display.isEnabled()) {
                continue; // Ignore disabled displays.
            }

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
            LogicalDisplay display = mLogicalDisplays.valueAt(i);
            if (display.isEnabled()) {
                consumer.accept(display);
            }
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

    /**
     * Returns the set of {@link DisplayInfo} for this device state, only fetching the info that is
     * part of the same display group as the provided display id. The DisplayInfo represent the
     * logical display layouts possible for the given device state.
     *
     * @param deviceState the state to query possible layouts for
     * @param displayId   the display id to apply to all displays within the group
     * @param groupId     the display group to filter display info for. Must be the same group as
     *                    the display with the provided display id.
     */
    public Set<DisplayInfo> getDisplayInfoForStateLocked(int deviceState, int displayId,
            int groupId) {
        Set<DisplayInfo> displayInfos = new ArraySet<>();
        final Layout layout = mDeviceStateToLayoutMap.get(deviceState);
        final int layoutSize = layout.size();
        for (int i = 0; i < layoutSize; i++) {
            Layout.Display displayLayout = layout.getAt(i);
            if (displayLayout == null) {
                continue;
            }

            // If the underlying display-device we want to use for this display
            // doesn't exist, then skip it. This can happen at startup as display-devices
            // trickle in one at a time. When the new display finally shows up, the layout is
            // recalculated so that the display is properly added to the current layout.
            final DisplayAddress address = displayLayout.getAddress();
            final DisplayDevice device = mDisplayDeviceRepo.getByAddressLocked(address);
            if (device == null) {
                Slog.w(TAG, "The display device (" + address + "), is not available"
                        + " for the display state " + deviceState);
                continue;
            }

            // Find or create the LogicalDisplay to map the DisplayDevice to.
            final int logicalDisplayId = displayLayout.getLogicalDisplayId();
            final LogicalDisplay logicalDisplay =
                    getDisplayLocked(logicalDisplayId, /* includeDisabled= */ true);
            if (logicalDisplay == null) {
                Slog.w(TAG, "The logical display (" + address + "), is not available"
                        + " for the display state " + deviceState);
                continue;
            }
            final DisplayInfo temp = logicalDisplay.getDisplayInfoLocked();
            DisplayInfo displayInfo = new DisplayInfo(temp);
            if (displayInfo.displayGroupId != groupId) {
                // Ignore any displays not in the provided group.
                continue;
            }
            // A display in the same group can be swapped out at any point, so set the display id
            // for all results to the provided display id.
            displayInfo.displayId = displayId;
            displayInfos.add(displayInfo);
        }
        return displayInfos;
    }

    public void dumpLocked(PrintWriter pw) {
        pw.println("LogicalDisplayMapper:");
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        ipw.increaseIndent();

        ipw.println("mSingleDisplayDemoMode=" + mSingleDisplayDemoMode);
        ipw.println("mCurrentLayout=" + mCurrentLayout);
        ipw.println("mDeviceStatesOnWhichToWakeUp=" + mDeviceStatesOnWhichToWakeUp);
        ipw.println("mDeviceStatesOnWhichToSleep=" + mDeviceStatesOnWhichToSleep);
        ipw.println("mInteractive=" + mInteractive);

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

    void setDeviceStateLocked(int state, boolean isOverrideActive) {
        Slog.i(TAG, "Requesting Transition to state: " + state + ", from state=" + mDeviceState
                + ", interactive=" + mInteractive);
        // As part of a state transition, we may need to turn off some displays temporarily so that
        // the transition is smooth. Plus, on some devices, only one internal displays can be
        // on at a time. We use DISPLAY_PHASE_LAYOUT_TRANSITION to mark a display that needs to be
        // temporarily turned off.
        if (mDeviceState != DeviceStateManager.INVALID_DEVICE_STATE) {
            resetLayoutLocked(mDeviceState, state, LogicalDisplay.DISPLAY_PHASE_LAYOUT_TRANSITION);
        }
        mPendingDeviceState = state;
        final boolean wakeDevice = shouldDeviceBeWoken(mPendingDeviceState, mDeviceState,
                mInteractive, mBootCompleted);
        final boolean sleepDevice = shouldDeviceBePutToSleep(mPendingDeviceState, mDeviceState,
                isOverrideActive, mInteractive, mBootCompleted);

        // If all displays are off already, we can just transition here, unless we are trying to
        // wake or sleep the device as part of this transition. In that case defer the final
        // transition until later once the device is awake/asleep.
        if (areAllTransitioningDisplaysOffLocked() && !wakeDevice && !sleepDevice) {
            transitionToPendingStateLocked();
            return;
        }

        if (DEBUG) {
            Slog.d(TAG, "Postponing transition to state: " + mPendingDeviceState);
        }
        // Send the transitioning phase updates to DisplayManager so that the displays can
        // start turning OFF in preparation for the new layout.
        updateLogicalDisplaysLocked();

        if (wakeDevice || sleepDevice) {
            if (wakeDevice) {
                // We already told the displays to turn off, now we need to wake the device as
                // we transition to this new state. We do it here so that the waking happens
                // between the transition from one layout to another.
                mHandler.post(() -> {
                    mPowerManager.wakeUp(SystemClock.uptimeMillis(),
                            PowerManager.WAKE_REASON_UNFOLD_DEVICE, "server.display:unfold");
                });
            } else if (sleepDevice) {
                // Send the device to sleep when required.
                mHandler.post(() -> {
                    mPowerManager.goToSleep(SystemClock.uptimeMillis(),
                            PowerManager.GO_TO_SLEEP_REASON_DEVICE_FOLD, 0);
                });
            }
        }

        mHandler.sendEmptyMessageDelayed(MSG_TRANSITION_TO_PENDING_DEVICE_STATE,
                TIMEOUT_STATE_TRANSITION_MILLIS);
    }

    void onBootCompleted() {
        synchronized (mSyncRoot) {
            mBootCompleted = true;
        }
    }

    void onEarlyInteractivityChange(boolean interactive) {
        synchronized (mSyncRoot) {
            if (mInteractive != interactive) {
                mInteractive = interactive;
                finishStateTransitionLocked(false /*force*/);
            }
        }
    }

    /**
     * Returns if the device should be woken up or not. Called to check if the device state we are
     * moving to is one that should awake the device, as well as if we are moving from a device
     * state that shouldn't have been already woken from.
     *
     * @param pendingState device state we are moving to
     * @param currentState device state we are currently in
     * @param isInteractive if the device is in an interactive state
     * @param isBootCompleted is the device fully booted
     *
     * @see #shouldDeviceBePutToSleep
     * @see #setDeviceStateLocked
     */
    @VisibleForTesting
    boolean shouldDeviceBeWoken(int pendingState, int currentState, boolean isInteractive,
            boolean isBootCompleted) {
        return mDeviceStatesOnWhichToWakeUp.get(pendingState)
                && !mDeviceStatesOnWhichToWakeUp.get(currentState)
                && !isInteractive && isBootCompleted;
    }

    /**
     * Returns true if the device should be put to sleep or not.
     *
     * Includes a check to verify that the device state that we are moving to, {@code pendingState},
     * is the same as the physical state of the device, {@code baseState}. Different values for
     * these parameters indicate a device state override is active, and we shouldn't put the device
     * to sleep to provide a better user experience.
     *
     * @param pendingState device state we are moving to
     * @param currentState device state we are currently in
     * @param isOverrideActive if a device state override is currently active or not
     * @param isInteractive if the device is in an interactive state
     * @param isBootCompleted is the device fully booted
     *
     * @see #shouldDeviceBeWoken
     * @see #setDeviceStateLocked
     */
    @VisibleForTesting
    boolean shouldDeviceBePutToSleep(int pendingState, int currentState, boolean isOverrideActive,
            boolean isInteractive, boolean isBootCompleted) {
        return mDeviceStatesOnWhichToSleep.get(pendingState)
                && !mDeviceStatesOnWhichToSleep.get(currentState)
                && !isOverrideActive
                && isInteractive && isBootCompleted;
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

        final boolean waitingToWakeDevice = mDeviceStatesOnWhichToWakeUp.get(mPendingDeviceState)
                && !mDeviceStatesOnWhichToWakeUp.get(mDeviceState)
                && !mInteractive && mBootCompleted;
        final boolean waitingToSleepDevice = mDeviceStatesOnWhichToSleep.get(mPendingDeviceState)
                && !mDeviceStatesOnWhichToSleep.get(mDeviceState)
                && mInteractive && mBootCompleted;

        final boolean displaysOff = areAllTransitioningDisplaysOffLocked();
        final boolean isReadyToTransition = displaysOff && !waitingToWakeDevice
                && !waitingToSleepDevice;

        if (isReadyToTransition || force) {
            transitionToPendingStateLocked();
            mHandler.removeMessages(MSG_TRANSITION_TO_PENDING_DEVICE_STATE);
        } else if (DEBUG) {
            Slog.d(TAG, "Not yet ready to transition to state=" + mPendingDeviceState
                    + " with displays-off=" + displaysOff + ", force=" + force
                    + ", mInteractive=" + mInteractive + ", isReady=" + isReadyToTransition);
        }
    }

    private void handleDisplayDeviceAddedLocked(DisplayDevice device) {
        DisplayDeviceInfo deviceInfo = device.getDisplayDeviceInfoLocked();
        // The default Display needs to have additional initialization.
        // This initializes a default dynamic display layout for the default
        // device, which is used as a fallback in case no static layout definitions
        // exist or cannot be loaded.
        if ((deviceInfo.flags & DisplayDeviceInfo.FLAG_ALLOWED_TO_BE_DEFAULT_DISPLAY) != 0) {
            initializeDefaultDisplayDeviceLocked(device);
        }

        // Create a logical display for the new display device
        LogicalDisplay display = createNewLogicalDisplayLocked(
                device, Layout.assignDisplayIdLocked(false /*isDefault*/));

        applyLayoutLocked();
        updateLogicalDisplaysLocked();
    }

    private void handleDisplayDeviceRemovedLocked(DisplayDevice device) {
        final Layout layout = mDeviceStateToLayoutMap.get(DeviceStateToLayoutMap.STATE_DEFAULT);
        Layout.Display layoutDisplay = layout.getById(DEFAULT_DISPLAY);
        if (layoutDisplay == null) {
            return;
        }
        DisplayDeviceInfo deviceInfo = device.getDisplayDeviceInfoLocked();

        if (layoutDisplay.getAddress().equals(deviceInfo.address)) {
            layout.removeDisplayLocked(DEFAULT_DISPLAY);

            // Need to find another local display and make it default
            for (int i = 0; i < mLogicalDisplays.size(); i++) {
                LogicalDisplay nextDisplay = mLogicalDisplays.valueAt(i);
                DisplayDevice nextDevice = nextDisplay.getPrimaryDisplayDeviceLocked();
                if (nextDevice == null) {
                    continue;
                }
                DisplayDeviceInfo nextDeviceInfo = nextDevice.getDisplayDeviceInfoLocked();

                if ((nextDeviceInfo.flags
                        & DisplayDeviceInfo.FLAG_ALLOWED_TO_BE_DEFAULT_DISPLAY) != 0
                        && !nextDeviceInfo.address.equals(deviceInfo.address)) {
                    layout.createDisplayLocked(nextDeviceInfo.address,
                            /* isDefault= */ true, /* isEnabled= */ true);
                    applyLayoutLocked();
                    return;
                }
            }
        }
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
            DisplayInfo newDisplayInfo = display.getDisplayInfoLocked();

            final int storedState = mUpdatedLogicalDisplays.get(displayId, UPDATE_STATE_NEW);
            final int updateState = storedState & UPDATE_STATE_MASK;
            final boolean isTransitioning = (storedState & UPDATE_STATE_FLAG_TRANSITION) != 0;
            final boolean wasPreviouslyUpdated = updateState == UPDATE_STATE_UPDATED;

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

            // The display has been newly disabled, we report this as a removed display but
            // don't actually remove it from our internal list in LogicalDisplayMapper. The reason
            // is that LogicalDisplayMapper assumes and relies on the fact that every DisplayDevice
            // has a LogicalDisplay wrapper, but certain displays that are unusable (like the inner
            // display on a folded foldable device) are not available for use by the system and
            // we keep them hidden. To do this, we mark those LogicalDisplays as "disabled".
            // Also, if the display is in TRANSITION but was previously reported as disabled
            // then keep it unreported.
            } else if (!display.isEnabled()
                    || (display.getPhase() == LogicalDisplay.DISPLAY_PHASE_LAYOUT_TRANSITION
                        && updateState == UPDATE_STATE_DISABLED)) {
                mUpdatedLogicalDisplays.put(displayId, UPDATE_STATE_DISABLED);

                // If we never told anyone about this display, nothing to do
                if (!wasPreviouslyUpdated) {
                    continue;
                }

                // Remove from group
                final DisplayGroup displayGroup = getDisplayGroupLocked(
                        getDisplayGroupIdFromDisplayIdLocked(displayId));
                if (displayGroup != null) {
                    displayGroup.removeDisplayLocked(display);
                }

                Slog.i(TAG, "Removing (disabled) display: " + displayId);
                mLogicalDisplaysToUpdate.put(displayId, LOGICAL_DISPLAY_EVENT_REMOVED);
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
            } else if (isTransitioning) {
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
            final LogicalDisplay display = getDisplayLocked(id, /* includeDisabled= */ true);
            if (DEBUG) {
                final DisplayDevice device = display.getPrimaryDisplayDeviceLocked();
                final String uniqueId = device == null ? "null" : device.getUniqueId();
                Slog.d(TAG, "Sending " + displayEventToString(msg) + " for display=" + id
                        + " with device=" + uniqueId);
            }
            mListener.onLogicalDisplayEventLocked(display, msg);
            if (msg == LOGICAL_DISPLAY_EVENT_REMOVED && !display.isValidLocked()) {
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
                    int oldState = mUpdatedLogicalDisplays.get(displayId, UPDATE_STATE_NEW);
                    mUpdatedLogicalDisplays.put(displayId, oldState | UPDATE_STATE_FLAG_TRANSITION);
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
            LogicalDisplay newDisplay =
                    getDisplayLocked(logicalDisplayId, /* includeDisabled= */ true);
            if (newDisplay == null) {
                newDisplay = createNewLogicalDisplayLocked(
                        /* displayDevice= */ null, logicalDisplayId);
            }

            // Now swap the underlying display devices between the old display and the new display
            final LogicalDisplay oldDisplay = getDisplayLocked(device, /* includeDisabled= */ true);
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
     * @param displayDevice The displayDevice to associate with the LogicalDisplay.
     * @param displayId The display ID to give the new display. If invalid, a new ID is assigned.
     * @return The new logical display if created, null otherwise.
     */
    private LogicalDisplay createNewLogicalDisplayLocked(DisplayDevice displayDevice,
            int displayId) {
        final int layerStack = assignLayerStackLocked(displayId);
        final LogicalDisplay display = new LogicalDisplay(displayId, layerStack, displayDevice);
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

    private void initializeDefaultDisplayDeviceLocked(DisplayDevice device) {
        // We always want to make sure that our default layout creates a logical
        // display for the default display device that is found.
        // To that end, when we are notified of a new default display, we add it to
        // the default layout definition if it is not already there.
        final Layout layout = mDeviceStateToLayoutMap.get(DeviceStateToLayoutMap.STATE_DEFAULT);
        if (layout.getById(DEFAULT_DISPLAY) != null) {
            // The layout should only have one default display
            return;
        }
        final DisplayDeviceInfo info = device.getDisplayDeviceInfoLocked();
        layout.createDisplayLocked(info.address, /* isDefault= */ true, /* isEnabled= */ true);
    }

    private int assignLayerStackLocked(int displayId) {
        // Currently layer stacks and display ids are the same.
        // This need not be the case.
        return displayId;
    }

    private SparseBooleanArray toSparseBooleanArray(int[] input) {
        final SparseBooleanArray retval = new SparseBooleanArray(2);
        for (int i = 0; input != null && i < input.length; i++) {
            retval.put(input[i], true);
        }
        return retval;
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
