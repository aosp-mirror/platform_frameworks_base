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
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.devicestate.DeviceStateManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.DisplayAddress;
import android.view.DisplayInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.feature.DisplayManagerFlags;
import com.android.server.display.layout.DisplayIdProducer;
import com.android.server.display.layout.Layout;
import com.android.server.display.utils.DebugUtils;
import com.android.server.utils.FoldSettingProvider;

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

    // To enable these logs, run:
    // 'adb shell setprop persist.log.tag.LogicalDisplayMapper DEBUG && adb reboot'
    private static final boolean DEBUG = DebugUtils.isDebuggable(TAG);

    public static final int LOGICAL_DISPLAY_EVENT_ADDED = 1;
    public static final int LOGICAL_DISPLAY_EVENT_CHANGED = 2;
    public static final int LOGICAL_DISPLAY_EVENT_REMOVED = 3;
    public static final int LOGICAL_DISPLAY_EVENT_SWAPPED = 4;
    public static final int LOGICAL_DISPLAY_EVENT_FRAME_RATE_OVERRIDES_CHANGED = 5;
    public static final int LOGICAL_DISPLAY_EVENT_DEVICE_STATE_TRANSITION = 6;
    public static final int LOGICAL_DISPLAY_EVENT_HDR_SDR_RATIO_CHANGED = 7;
    public static final int LOGICAL_DISPLAY_EVENT_CONNECTED = 8;
    public static final int LOGICAL_DISPLAY_EVENT_DISCONNECTED = 9;

    public static final int DISPLAY_GROUP_EVENT_ADDED = 1;
    public static final int DISPLAY_GROUP_EVENT_CHANGED = 2;
    public static final int DISPLAY_GROUP_EVENT_REMOVED = 3;

    private static final int TIMEOUT_STATE_TRANSITION_MILLIS = 500;

    private static final int MSG_TRANSITION_TO_PENDING_DEVICE_STATE = 1;

    private static final int UPDATE_STATE_NEW = 0;
    private static final int UPDATE_STATE_TRANSITION = 1;
    private static final int UPDATE_STATE_UPDATED = 2;

    private static int sNextNonDefaultDisplayId = DEFAULT_DISPLAY + 1;

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

    // Cache whether or not the display was enabled on the last update.
    private final SparseBooleanArray mDisplaysEnabledCache = new SparseBooleanArray();

    /** Map of all display groups indexed by display group id. */
    private final SparseArray<DisplayGroup> mDisplayGroups = new SparseArray<>();

    /**
     * Map of display groups which are linked to virtual devices (all displays in the group are
     * linked to that device). Keyed by virtual device unique id.
     */
    private final SparseIntArray mDeviceDisplayGroupIds = new SparseIntArray();

    /**
     * Map of display group ids indexed by display group name.
     */
    private final ArrayMap<String, Integer> mDisplayGroupIdsByName = new ArrayMap<>();

    private final DisplayDeviceRepository mDisplayDeviceRepo;
    private final DeviceStateToLayoutMap mDeviceStateToLayoutMap;
    private final Listener mListener;
    private final DisplayManagerService.SyncRoot mSyncRoot;
    private final LogicalDisplayMapperHandler mHandler;
    private final FoldSettingProvider mFoldSettingProvider;
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

    /**
     * ArrayMap of display device unique ID to virtual device ID. Used in {@link
     * #updateLogicalDisplaysLocked} to establish which Virtual Devices own which Virtual Displays.
     */
    private final ArrayMap<String, Integer> mVirtualDeviceDisplayMapping = new ArrayMap<>();

    private int mNextNonDefaultGroupId = Display.DEFAULT_DISPLAY_GROUP + 1;
    private final DisplayIdProducer mIdProducer = (isDefault) ->
            isDefault ? DEFAULT_DISPLAY : sNextNonDefaultDisplayId++;
    private Layout mCurrentLayout = null;
    private int mDeviceState = DeviceStateManager.INVALID_DEVICE_STATE_IDENTIFIER;
    private int mPendingDeviceState = DeviceStateManager.INVALID_DEVICE_STATE_IDENTIFIER;
    private int mDeviceStateToBeAppliedAfterBoot =
            DeviceStateManager.INVALID_DEVICE_STATE_IDENTIFIER;
    private boolean mBootCompleted = false;
    private boolean mInteractive;
    private final DisplayManagerFlags mFlags;

    LogicalDisplayMapper(@NonNull Context context, FoldSettingProvider foldSettingProvider,
            @NonNull DisplayDeviceRepository repo,
            @NonNull Listener listener, @NonNull DisplayManagerService.SyncRoot syncRoot,
            @NonNull Handler handler, DisplayManagerFlags flags) {
        this(context, foldSettingProvider, repo, listener, syncRoot, handler,
                new DeviceStateToLayoutMap((isDefault) -> isDefault ? DEFAULT_DISPLAY
                        : sNextNonDefaultDisplayId++, flags), flags);
    }

    LogicalDisplayMapper(@NonNull Context context, FoldSettingProvider foldSettingProvider,
            @NonNull DisplayDeviceRepository repo,
            @NonNull Listener listener, @NonNull DisplayManagerService.SyncRoot syncRoot,
            @NonNull Handler handler, @NonNull DeviceStateToLayoutMap deviceStateToLayoutMap,
            DisplayManagerFlags flags) {
        mSyncRoot = syncRoot;
        mPowerManager = context.getSystemService(PowerManager.class);
        mInteractive = mPowerManager.isInteractive();
        mHandler = new LogicalDisplayMapperHandler(handler.getLooper());
        mDisplayDeviceRepo = repo;
        mListener = listener;
        mFoldSettingProvider = foldSettingProvider;
        mSingleDisplayDemoMode = SystemProperties.getBoolean("persist.demo.singledisplay", false);
        mSupportsConcurrentInternalDisplays = context.getResources().getBoolean(
                com.android.internal.R.bool.config_supportsConcurrentInternalDisplays);
        mDeviceStatesOnWhichToWakeUp = toSparseBooleanArray(context.getResources().getIntArray(
                com.android.internal.R.array.config_deviceStatesOnWhichToWakeUp));
        mDeviceStatesOnWhichToSleep = toSparseBooleanArray(context.getResources().getIntArray(
                com.android.internal.R.array.config_deviceStatesOnWhichToSleep));
        mDisplayDeviceRepo.addListener(this);
        mDeviceStateToLayoutMap = deviceStateToLayoutMap;
        mFlags = flags;
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
    public void onDisplayDeviceChangedLocked(DisplayDevice device, int diff) {
        if (DEBUG) {
            Slog.d(TAG, "Display device changed: " + device.getDisplayDeviceInfoLocked());
        }
        finishStateTransitionLocked(false /*force*/);
        updateLogicalDisplaysLocked(diff);
    }

    @Override
    public void onTraversalRequested() {
        mListener.onTraversalRequested();
    }

    public LogicalDisplay getDisplayLocked(int displayId) {
        return getDisplayLocked(displayId, /* includeDisabled= */ true);
    }

    public LogicalDisplay getDisplayLocked(int displayId, boolean includeDisabled) {
        LogicalDisplay display = mLogicalDisplays.get(displayId);
        if (display == null || display.isEnabledLocked() || includeDisabled) {
            return display;
        }
        return null;
    }

    public LogicalDisplay getDisplayLocked(DisplayDevice device) {
        return getDisplayLocked(device, /* includeDisabled= */ true);
    }

    public LogicalDisplay getDisplayLocked(DisplayDevice device, boolean includeDisabled) {
        if (device == null) {
            return null;
        }
        final int count = mLogicalDisplays.size();
        for (int i = 0; i < count; i++) {
            final LogicalDisplay display = mLogicalDisplays.valueAt(i);
            if (display.getPrimaryDisplayDeviceLocked() == device) {
                if (display.isEnabledLocked() || includeDisabled) {
                    return display;
                }
                return null;
            }
        }
        return null;
    }

    public int[] getDisplayIdsLocked(int callingUid, boolean includeDisabled) {
        final int count = mLogicalDisplays.size();
        int[] displayIds = new int[count];
        int n = 0;
        for (int i = 0; i < count; i++) {
            LogicalDisplay display = mLogicalDisplays.valueAt(i);
            if (display.isEnabledLocked() || includeDisabled) {
                DisplayInfo info = display.getDisplayInfoLocked();
                if (info.hasAccess(callingUid)) {
                    displayIds[n++] = mLogicalDisplays.keyAt(i);
                }
            }
        }
        if (n != count) {
            displayIds = Arrays.copyOfRange(displayIds, 0, n);
        }
        return displayIds;
    }

    public void forEachLocked(Consumer<LogicalDisplay> consumer) {
        forEachLocked(consumer, /* includeDisabled= */ true);
    }

    public void forEachLocked(Consumer<LogicalDisplay> consumer, boolean includeDisabled) {
        final int count = mLogicalDisplays.size();
        for (int i = 0; i < count; i++) {
            LogicalDisplay display = mLogicalDisplays.valueAt(i);
            if (display.isEnabledLocked() || includeDisabled) {
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
     * Returns the {@link DisplayInfo} for this device state, indicated by the given display id. The
     * DisplayInfo represents the attributes of the indicated display in the layout associated with
     * this state. This is used to get display information for various displays in various states;
     * e.g. to help apps preload resources for the possible display states.
     *
     * @param deviceState the state to query possible layouts for
     * @param displayId   the display id to retrieve
     * @return {@code null} if no corresponding {@link DisplayInfo} could be found, or the
     * {@link DisplayInfo} with a matching display id.
     */
    @Nullable
    public DisplayInfo getDisplayInfoForStateLocked(int deviceState, int displayId) {
        // Retrieve the layout for this particular state.
        final Layout layout = mDeviceStateToLayoutMap.get(deviceState);
        if (layout == null) {
            return null;
        }
        // Retrieve the details of the given display within this layout.
        Layout.Display display = layout.getById(displayId);
        if (display == null) {
            return null;
        }
        // Retrieve the display info for the display that matches the display id.
        final DisplayDevice device = mDisplayDeviceRepo.getByAddressLocked(display.getAddress());
        if (device == null) {
            Slog.w(TAG, "The display device (" + display.getAddress() + "), is not available"
                    + " for the display state " + mDeviceState);
            return null;
        }
        LogicalDisplay logicalDisplay = getDisplayLocked(device, /* includeDisabled= */ true);
        if (logicalDisplay == null) {
            Slog.w(TAG, "The logical display associated with address (" + display.getAddress()
                    + "), is not available for the display state " + mDeviceState);
            return null;
        }
        DisplayInfo displayInfo = new DisplayInfo(logicalDisplay.getDisplayInfoLocked());
        displayInfo.displayId = displayId;
        return displayInfo;
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
        ipw.println("mBootCompleted=" + mBootCompleted);

        ipw.println();
        ipw.println("mDeviceState=" + mDeviceState);
        ipw.println("mPendingDeviceState=" + mPendingDeviceState);
        ipw.println("mDeviceStateToBeAppliedAfterBoot=" + mDeviceStateToBeAppliedAfterBoot);

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

    /**
     * Creates an association between a displayDevice and a virtual device. Any displays associated
     * with this virtual device will be grouped together in a single {@link DisplayGroup} unless
     * created with {@link Display.FLAG_OWN_DISPLAY_GROUP}.
     *
     * @param displayDevice the displayDevice to be linked
     * @param virtualDeviceUniqueId the unique ID of the virtual device.
     */
    void associateDisplayDeviceWithVirtualDevice(
            DisplayDevice displayDevice, int virtualDeviceUniqueId) {
        mVirtualDeviceDisplayMapping.put(displayDevice.getUniqueId(), virtualDeviceUniqueId);
    }

    void setDeviceStateLocked(int state, boolean isOverrideActive) {
        if (!mBootCompleted) {
            // The boot animation might still be in progress, we do not want to switch states now
            // as the boot animation would end up with an incorrect size.
            if (DEBUG) {
                Slog.d(TAG, "Postponing transition to state: " + mPendingDeviceState
                        + " until boot is completed");
            }
            mDeviceStateToBeAppliedAfterBoot = state;
            return;
        }

        Slog.i(TAG, "Requesting Transition to state: " + state + ", from state=" + mDeviceState
                + ", interactive=" + mInteractive + ", mBootCompleted=" + mBootCompleted);
        // As part of a state transition, we may need to turn off some displays temporarily so that
        // the transition is smooth. Plus, on some devices, only one internal displays can be
        // on at a time. We use LogicalDisplay.setIsInTransition to mark a display that needs to be
        // temporarily turned off.
        resetLayoutLocked(mDeviceState, state, /* transitionValue= */ true);
        mPendingDeviceState = state;
        mDeviceStateToBeAppliedAfterBoot = DeviceStateManager.INVALID_DEVICE_STATE_IDENTIFIER;
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
                int goToSleepFlag =
                        mFoldSettingProvider.shouldSleepOnFold() ? 0
                                : PowerManager.GO_TO_SLEEP_FLAG_SOFT_SLEEP;
                mHandler.post(() -> {
                    mPowerManager.goToSleep(SystemClock.uptimeMillis(),
                            PowerManager.GO_TO_SLEEP_REASON_DEVICE_FOLD,
                            goToSleepFlag);
                });
            }
        }

        mHandler.sendEmptyMessageDelayed(MSG_TRANSITION_TO_PENDING_DEVICE_STATE,
                TIMEOUT_STATE_TRANSITION_MILLIS);
    }

    void onBootCompleted() {
        synchronized (mSyncRoot) {
            mBootCompleted = true;
            if (mDeviceStateToBeAppliedAfterBoot
                    != DeviceStateManager.INVALID_DEVICE_STATE_IDENTIFIER) {
                setDeviceStateLocked(mDeviceStateToBeAppliedAfterBoot,
                        /* isOverrideActive= */ false);
            }
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
     * Returns if the device should be put to sleep or not.
     *
     * Includes a check to verify that the device state that we are moving to, {@code pendingState},
     * is the same as the physical state of the device, {@code baseState}. Also if the
     * 'Stay Awake On Fold' is not enabled. Different values for these parameters indicate a device
     * state override is active, and we shouldn't put the device to sleep to provide a better user
     * experience.
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
        return currentState != DeviceStateManager.INVALID_DEVICE_STATE_IDENTIFIER
                && mDeviceStatesOnWhichToSleep.get(pendingState)
                && !mDeviceStatesOnWhichToSleep.get(currentState)
                && !isOverrideActive
                && isInteractive && isBootCompleted
                && !mFoldSettingProvider.shouldStayAwakeOnFold();
    }

    private boolean areAllTransitioningDisplaysOffLocked() {
        final int count = mLogicalDisplays.size();
        for (int i = 0; i < count; i++) {
            final LogicalDisplay display = mLogicalDisplays.valueAt(i);
            if (!display.isInTransitionLocked()) {
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
        resetLayoutLocked(mDeviceState, mPendingDeviceState, /* transitionValue= */ false);
        mDeviceState = mPendingDeviceState;
        mPendingDeviceState = DeviceStateManager.INVALID_DEVICE_STATE_IDENTIFIER;
        applyLayoutLocked();
        updateLogicalDisplaysLocked();
    }

    private void finishStateTransitionLocked(boolean force) {
        if (mPendingDeviceState == DeviceStateManager.INVALID_DEVICE_STATE_IDENTIFIER) {
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
                device, mIdProducer.getId(/* isDefault= */ false));

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

        // Remove any virtual device mapping which exists for the display.
        mVirtualDeviceDisplayMapping.remove(device.getUniqueId());

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
                    layout.createDefaultDisplayLocked(nextDeviceInfo.address, mIdProducer);
                    applyLayoutLocked();
                    return;
                }
            }
        }
    }

    @VisibleForTesting
    void updateLogicalDisplays() {
        synchronized (mSyncRoot) {
            updateLogicalDisplaysLocked();
        }
    }

    void updateLogicalDisplaysLocked() {
        updateLogicalDisplaysLocked(DisplayDeviceInfo.DIFF_EVERYTHING);
    }

    private void updateLogicalDisplaysLocked(int diff) {
        updateLogicalDisplaysLocked(diff, /* isSecondLoop= */ false);
    }

    /**
     * Updates the rest of the display system once all the changes are applied for display
     * devices and logical displays. The includes releasing invalid/empty LogicalDisplays,
     * creating/adjusting/removing DisplayGroups, and notifying the rest of the system of the
     * relevant changes.
     *
     * @param diff The DisplayDeviceInfo.DIFF_* of what actually changed to enable finer-grained
     *             display update listeners
     * @param isSecondLoop If true, this is the second time this is called for the same change.
     */
    private void updateLogicalDisplaysLocked(int diff, boolean isSecondLoop) {
        boolean reloop = false;
        // Go through all the displays and figure out if they need to be updated.
        // Loops in reverse so that displays can be removed during the loop without affecting the
        // rest of the loop.
        for (int i = mLogicalDisplays.size() - 1; i >= 0; i--) {
            final int displayId = mLogicalDisplays.keyAt(i);
            LogicalDisplay display = mLogicalDisplays.valueAt(i);
            assignDisplayGroupLocked(display);

            boolean wasDirty = display.isDirtyLocked();
            mTempDisplayInfo.copyFrom(display.getDisplayInfoLocked());
            display.getNonOverrideDisplayInfoLocked(mTempNonOverrideDisplayInfo);

            display.updateLocked(mDisplayDeviceRepo);
            final DisplayInfo newDisplayInfo = display.getDisplayInfoLocked();
            final int updateState = mUpdatedLogicalDisplays.get(displayId, UPDATE_STATE_NEW);
            final boolean wasPreviouslyUpdated = updateState != UPDATE_STATE_NEW;
            final boolean wasPreviouslyEnabled = mDisplaysEnabledCache.get(displayId);
            final boolean isCurrentlyEnabled = display.isEnabledLocked();

            // The display is no longer valid and needs to be removed.
            if (!display.isValidLocked()) {
                // Remove from group
                final DisplayGroup displayGroup = getDisplayGroupLocked(
                        getDisplayGroupIdFromDisplayIdLocked(displayId));
                if (displayGroup != null) {
                    displayGroup.removeDisplayLocked(display);
                }

                if (wasPreviouslyUpdated) {
                    // The display isn't actually removed from our internal data structures until
                    // after the notification is sent; see {@link #sendUpdatesForDisplaysLocked}.
                    if (mFlags.isConnectedDisplayManagementEnabled()) {
                        if (mDisplaysEnabledCache.get(displayId)) {
                            // We still need to send LOGICAL_DISPLAY_EVENT_DISCONNECTED
                            reloop = true;
                            mLogicalDisplaysToUpdate.put(displayId, LOGICAL_DISPLAY_EVENT_REMOVED);
                        } else {
                            mUpdatedLogicalDisplays.delete(displayId);
                            mLogicalDisplaysToUpdate.put(displayId,
                                    LOGICAL_DISPLAY_EVENT_DISCONNECTED);
                        }
                    } else {
                        mUpdatedLogicalDisplays.delete(displayId);
                        mLogicalDisplaysToUpdate.put(displayId, LOGICAL_DISPLAY_EVENT_REMOVED);
                    }
                } else {
                    // This display never left this class, safe to remove without notification
                    mLogicalDisplays.removeAt(i);
                }
                continue;

            // The display is new.
            } else if (!wasPreviouslyUpdated) {
                if (mFlags.isConnectedDisplayManagementEnabled()) {
                    // We still need to send LOGICAL_DISPLAY_EVENT_ADDED
                    reloop = true;
                    mLogicalDisplaysToUpdate.put(displayId, LOGICAL_DISPLAY_EVENT_CONNECTED);
                } else {
                    mLogicalDisplaysToUpdate.put(displayId, LOGICAL_DISPLAY_EVENT_ADDED);
                }
            // Underlying displays device has changed to a different one.
            } else if (!TextUtils.equals(mTempDisplayInfo.uniqueId, newDisplayInfo.uniqueId)) {
                mLogicalDisplaysToUpdate.put(displayId, LOGICAL_DISPLAY_EVENT_SWAPPED);

            // Something about the display device has changed.
            } else if (mFlags.isConnectedDisplayManagementEnabled()
                    && wasPreviouslyEnabled != isCurrentlyEnabled) {
                int event = isCurrentlyEnabled ? LOGICAL_DISPLAY_EVENT_ADDED :
                        LOGICAL_DISPLAY_EVENT_REMOVED;
                mLogicalDisplaysToUpdate.put(displayId, event);
            } else if (wasDirty || !mTempDisplayInfo.equals(newDisplayInfo)) {
                // If only the hdr/sdr ratio changed, then send just the event for that case
                if ((diff == DisplayDeviceInfo.DIFF_HDR_SDR_RATIO)) {
                    mLogicalDisplaysToUpdate.put(displayId,
                            LOGICAL_DISPLAY_EVENT_HDR_SDR_RATIO_CHANGED);
                } else {
                    mLogicalDisplaysToUpdate.put(displayId, LOGICAL_DISPLAY_EVENT_CHANGED);
                }

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
        if (mFlags.isConnectedDisplayManagementEnabled()) {
            sendUpdatesForDisplaysLocked(LOGICAL_DISPLAY_EVENT_DISCONNECTED);
        }
        sendUpdatesForDisplaysLocked(LOGICAL_DISPLAY_EVENT_CHANGED);
        sendUpdatesForDisplaysLocked(LOGICAL_DISPLAY_EVENT_FRAME_RATE_OVERRIDES_CHANGED);
        sendUpdatesForDisplaysLocked(LOGICAL_DISPLAY_EVENT_SWAPPED);
        if (mFlags.isConnectedDisplayManagementEnabled()) {
            sendUpdatesForDisplaysLocked(LOGICAL_DISPLAY_EVENT_CONNECTED);
        }
        sendUpdatesForDisplaysLocked(LOGICAL_DISPLAY_EVENT_ADDED);
        sendUpdatesForDisplaysLocked(LOGICAL_DISPLAY_EVENT_HDR_SDR_RATIO_CHANGED);
        sendUpdatesForGroupsLocked(DISPLAY_GROUP_EVENT_CHANGED);
        sendUpdatesForGroupsLocked(DISPLAY_GROUP_EVENT_REMOVED);

        mLogicalDisplaysToUpdate.clear();
        mDisplayGroupsToUpdate.clear();

        if (reloop) {
            if (isSecondLoop) {
                Slog.wtf(TAG, "Trying to loop a third time");
                return;
            }
            updateLogicalDisplaysLocked(diff, /* isSecondLoop= */ true);
        }
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

            if (mFlags.isConnectedDisplayManagementEnabled()) {
                if (msg == LOGICAL_DISPLAY_EVENT_ADDED) {
                    mDisplaysEnabledCache.put(id, true);
                } else if (msg == LOGICAL_DISPLAY_EVENT_REMOVED) {
                    mDisplaysEnabledCache.delete(id);
                }
            }

            mListener.onLogicalDisplayEventLocked(display, msg);

            if (mFlags.isConnectedDisplayManagementEnabled()) {
                if (msg == LOGICAL_DISPLAY_EVENT_DISCONNECTED) {
                    mLogicalDisplays.delete(id);
                }
            } else if (msg == LOGICAL_DISPLAY_EVENT_REMOVED) {
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
                // Remove possible reference to the removed group.
                int deviceIndex = mDeviceDisplayGroupIds.indexOfValue(id);
                if (deviceIndex >= 0) {
                    mDeviceDisplayGroupIds.removeAt(deviceIndex);
                }
            }
        }
    }

    /** This method should be called before LogicalDisplay.updateLocked,
     * DisplayInfo in LogicalDisplay (display.getDisplayInfoLocked()) is not updated yet,
     * and should not be used directly or indirectly in this method */
    private void assignDisplayGroupLocked(LogicalDisplay display) {
        if (!display.isValidLocked()) { // null check for display.mPrimaryDisplayDevice
            return;
        }
        // updated primary device directly from LogicalDisplay (not from DisplayInfo)
        final DisplayDevice displayDevice = display.getPrimaryDisplayDeviceLocked();
        // final in LogicalDisplay
        final int displayId = display.getDisplayIdLocked();
        final String primaryDisplayUniqueId = displayDevice.getUniqueId();
        final Integer linkedDeviceUniqueId =
                mVirtualDeviceDisplayMapping.get(primaryDisplayUniqueId);

        // Get current display group data
        int groupId = getDisplayGroupIdFromDisplayIdLocked(displayId);
        Integer deviceDisplayGroupId = null;
        if (linkedDeviceUniqueId != null
                && mDeviceDisplayGroupIds.indexOfKey(linkedDeviceUniqueId) > 0) {
            deviceDisplayGroupId = mDeviceDisplayGroupIds.get(linkedDeviceUniqueId);
        }
        final DisplayGroup oldGroup = getDisplayGroupLocked(groupId);

        // groupName directly from LogicalDisplay (not from DisplayInfo)
        final String groupName = display.getDisplayGroupNameLocked();
        // DisplayDeviceInfo is safe to use, it is updated earlier
        final DisplayDeviceInfo displayDeviceInfo = displayDevice.getDisplayDeviceInfoLocked();
        // Get the new display group if a change is needed, if display group name is empty and
        // {@code DisplayDeviceInfo.FLAG_OWN_DISPLAY_GROUP} is not set, the display is assigned
        // to the default display group.
        final boolean needsOwnDisplayGroup =
                (displayDeviceInfo.flags & DisplayDeviceInfo.FLAG_OWN_DISPLAY_GROUP) != 0
                        || !TextUtils.isEmpty(groupName);

        final boolean hasOwnDisplayGroup = groupId != Display.DEFAULT_DISPLAY_GROUP;
        final boolean needsDeviceDisplayGroup =
                !needsOwnDisplayGroup && linkedDeviceUniqueId != null;
        final boolean hasDeviceDisplayGroup =
                deviceDisplayGroupId != null && groupId == deviceDisplayGroupId;
        if (groupId == Display.INVALID_DISPLAY_GROUP
                || hasOwnDisplayGroup != needsOwnDisplayGroup
                || hasDeviceDisplayGroup != needsDeviceDisplayGroup) {
            groupId =
                    assignDisplayGroupIdLocked(needsOwnDisplayGroup,
                            display.getDisplayGroupNameLocked(), needsDeviceDisplayGroup,
                            linkedDeviceUniqueId);
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
     * {@code toState} and un/marks them for transition. When a new layout is requested, we
     * mark the displays that will change into a transitional phase so that they can all be turned
     * OFF. Once all are confirmed OFF, then this method gets called again to reset transition
     * marker. This helps to ensure that all display-OFF requests are made before
     * display-ON which in turn hides any resizing-jank windows might incur when switching displays.
     *
     * @param fromState The state we are switching from.
     * @param toState The state we are switching to.
     * @param transitionValue The value to mark the transition state: true == transitioning.
     */
    private void resetLayoutLocked(int fromState, int toState, boolean transitionValue) {
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

            // Virtual displays do not have addresses, so account for nulls.
            final Layout.Display fromDisplay =
                    address != null ? fromLayout.getByAddress(address) : null;
            final Layout.Display toDisplay =
                    address != null ? toLayout.getByAddress(address) : null;

            // If the display is in one of the layouts but not the other, then the content will
            // change, so in this case we also want to blank the displays to avoid jank.
            final boolean displayNotInBothLayouts = (fromDisplay == null) != (toDisplay == null);

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
            // 4) It's in one layout but not the other, so the content will change.
            final boolean isTransitioning =
                    logicalDisplay.isInTransitionLocked()
                    || (wasEnabled != willBeEnabled)
                    || deviceHasNewLogicalDisplayId
                    || displayNotInBothLayouts;

            if (isTransitioning) {
                if (transitionValue != logicalDisplay.isInTransitionLocked()) {
                    Slog.i(TAG, "Set isInTransition on display " + displayId + ": "
                            + transitionValue);
                }
                // This will either mark the display as "transitioning" if we are starting to change
                // the device state, or remove the transitioning marker if the state change is
                // ending.
                logicalDisplay.setIsInTransitionLocked(transitionValue);
                mUpdatedLogicalDisplays.put(displayId, UPDATE_STATE_TRANSITION);
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
                Slog.w(TAG, "applyLayoutLocked: The display device (" + address + "), is not "
                        + "available for the display state " + mDeviceState);
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
            DisplayDeviceConfig config = device.getDisplayDeviceConfig();

            newDisplay.setDevicePositionLocked(displayLayout.getPosition());
            newDisplay.setLeadDisplayLocked(displayLayout.getLeadDisplayId());
            newDisplay.updateLayoutLimitedRefreshRateLocked(
                    config.getRefreshRange(displayLayout.getRefreshRateZoneId())
            );
            newDisplay.updateThermalRefreshRateThrottling(
                    config.getThermalRefreshRateThrottlingData(
                            displayLayout.getRefreshRateThermalThrottlingMapId()
                    )
            );
            setEnabledLocked(newDisplay, displayLayout.isEnabled());
            newDisplay.setThermalBrightnessThrottlingDataIdLocked(
                    displayLayout.getThermalBrightnessThrottlingMapId() == null
                            ? DisplayDeviceConfig.DEFAULT_ID
                            : displayLayout.getThermalBrightnessThrottlingMapId());
            newDisplay.setPowerThrottlingDataIdLocked(
                    displayLayout.getPowerThrottlingMapId() == null
                            ? DisplayDeviceConfig.DEFAULT_ID
                            : displayLayout.getPowerThrottlingMapId());
            newDisplay.setDisplayGroupNameLocked(displayLayout.getDisplayGroupName());
        }
    }

    /**
     * Creates a new logical display for the specified device and display Id and adds it to the list
     * of logical displays.
     *
     * @param device The device to associate with the LogicalDisplay.
     * @param displayId The display ID to give the new display. If invalid, a new ID is assigned.
     * @return The new logical display if created, null otherwise.
     */
    private LogicalDisplay createNewLogicalDisplayLocked(DisplayDevice device, int displayId) {
        final int layerStack = assignLayerStackLocked(displayId);
        final LogicalDisplay display = new LogicalDisplay(displayId, layerStack, device);
        display.updateLocked(mDisplayDeviceRepo);

        final DisplayInfo info = display.getDisplayInfoLocked();
        if (info.type == Display.TYPE_INTERNAL && mDeviceStateToLayoutMap.size() > 1) {
            // If this is an internal display and the device uses a display layout configuration,
            // the display should be disabled as later we will receive a device state update, which
            // will tell us which internal displays should be enabled and which should be disabled.
            display.setEnabledLocked(false);
        }

        mLogicalDisplays.put(displayId, display);
        return display;
    }

    @VisibleForTesting
    void setEnabledLocked(LogicalDisplay display, boolean isEnabled) {
        final int displayId = display.getDisplayIdLocked();
        final DisplayInfo info = display.getDisplayInfoLocked();

        final boolean disallowSecondaryDisplay = mSingleDisplayDemoMode
                && (info.type != Display.TYPE_INTERNAL);
        if (isEnabled && disallowSecondaryDisplay) {
            Slog.i(TAG, "Not creating a logical display for a secondary display because single"
                    + " display demo mode is enabled: " + display.getDisplayInfoLocked());
            isEnabled = false;
        }

        if (display.isEnabledLocked() != isEnabled) {
            Slog.i(TAG, "SetEnabled on display " + displayId + ": " + isEnabled);
            display.setEnabledLocked(isEnabled);
        }
    }

    private int assignDisplayGroupIdLocked(boolean isOwnDisplayGroup, String displayGroupName,
            boolean isDeviceDisplayGroup, Integer linkedDeviceUniqueId) {
        if (isDeviceDisplayGroup && linkedDeviceUniqueId != null) {
            int deviceDisplayGroupId = mDeviceDisplayGroupIds.get(linkedDeviceUniqueId);
            // A value of 0 indicates that no device display group was found.
            if (deviceDisplayGroupId == 0) {
                deviceDisplayGroupId = mNextNonDefaultGroupId++;
                mDeviceDisplayGroupIds.put(linkedDeviceUniqueId, deviceDisplayGroupId);
            }
            return deviceDisplayGroupId;
        }
        if (!isOwnDisplayGroup) return Display.DEFAULT_DISPLAY_GROUP;
        Integer displayGroupId = mDisplayGroupIdsByName.get(displayGroupName);
        if (displayGroupId == null) {
            displayGroupId = Integer.valueOf(mNextNonDefaultGroupId++);
            mDisplayGroupIdsByName.put(displayGroupName, displayGroupId);
        }
        return displayGroupId;
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
        layout.createDefaultDisplayLocked(info.address, mIdProducer);
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
            case LOGICAL_DISPLAY_EVENT_HDR_SDR_RATIO_CHANGED:
                return "hdr_sdr_ratio_changed";
            case LOGICAL_DISPLAY_EVENT_CONNECTED:
                return "connected";
            case LOGICAL_DISPLAY_EVENT_DISCONNECTED:
                return "disconnected";
        }
        return null;
    }

    void setDisplayEnabledLocked(@NonNull LogicalDisplay display, boolean enabled) {
        boolean isEnabled = display.isEnabledLocked();
        if (isEnabled == enabled) {
            Slog.w(TAG, "Display is already " + (isEnabled ? "enabled" : "disabled") + ": "
                    + display.getDisplayIdLocked());
            return;
        }
        setEnabledLocked(display, enabled);
        updateLogicalDisplaysLocked();
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
