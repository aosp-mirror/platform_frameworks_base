/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.hdmi;

import android.annotation.Nullable;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Slog;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.hdmi.Constants.LocalActivePort;
import com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;
import com.android.server.hdmi.HdmiControlService.SendMessageCallback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Class that models a logical CEC device hosted in this system. Handles initialization, CEC
 * commands that call for actions customized per device type.
 */
abstract class HdmiCecLocalDevice {
    private static final String TAG = "HdmiCecLocalDevice";

    private static final int MSG_DISABLE_DEVICE_TIMEOUT = 1;
    private static final int MSG_USER_CONTROL_RELEASE_TIMEOUT = 2;
    // Timeout in millisecond for device clean up (5s).
    // Normal actions timeout is 2s but some of them would have several sequence of timeout.
    private static final int DEVICE_CLEANUP_TIMEOUT = 5000;
    // Within the timer, a received <User Control Pressed> will start "Press and Hold" behavior.
    // When it expires, we can assume <User Control Release> is received.
    private static final int FOLLOWER_SAFETY_TIMEOUT = 550;

    protected final HdmiControlService mService;
    protected final int mDeviceType;
    protected int mAddress;
    protected int mPreferredAddress;
    @GuardedBy("mLock")
    protected HdmiDeviceInfo mDeviceInfo;
    protected int mLastKeycode = HdmiCecKeycode.UNSUPPORTED_KEYCODE;
    protected int mLastKeyRepeatCount = 0;

    static class ActiveSource {
        int logicalAddress;
        int physicalAddress;

        public ActiveSource() {
            invalidate();
        }

        public ActiveSource(int logical, int physical) {
            logicalAddress = logical;
            physicalAddress = physical;
        }

        public static ActiveSource of(ActiveSource source) {
            return new ActiveSource(source.logicalAddress, source.physicalAddress);
        }

        public static ActiveSource of(int logical, int physical) {
            return new ActiveSource(logical, physical);
        }

        public boolean isValid() {
            return HdmiUtils.isValidAddress(logicalAddress);
        }

        public void invalidate() {
            logicalAddress = Constants.ADDR_INVALID;
            physicalAddress = Constants.INVALID_PHYSICAL_ADDRESS;
        }

        public boolean equals(int logical, int physical) {
            return logicalAddress == logical && physicalAddress == physical;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof ActiveSource) {
                ActiveSource that = (ActiveSource) obj;
                return that.logicalAddress == logicalAddress
                        && that.physicalAddress == physicalAddress;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return logicalAddress * 29 + physicalAddress;
        }

        @Override
        public String toString() {
            StringBuffer s = new StringBuffer();
            String logicalAddressString =
                    (logicalAddress == Constants.ADDR_INVALID)
                            ? "invalid"
                            : String.format("0x%02x", logicalAddress);
            s.append("(").append(logicalAddressString);
            String physicalAddressString =
                    (physicalAddress == Constants.INVALID_PHYSICAL_ADDRESS)
                            ? "invalid"
                            : String.format("0x%04x", physicalAddress);
            s.append(", ").append(physicalAddressString).append(")");
            return s.toString();
        }
    }

    // Active routing path. Physical address of the active source but not all the time, such as
    // when the new active source does not claim itself to be one. Note that we don't keep
    // the active port id (or active input) since it can be gotten by {@link #pathToPortId(int)}.
    @GuardedBy("mLock")
    private int mActiveRoutingPath;

    protected final HdmiCecMessageCache mCecMessageCache = new HdmiCecMessageCache();
    protected final Object mLock;

    // A collection of FeatureAction.
    // Note that access to this collection should happen in service thread.
    private final ArrayList<HdmiCecFeatureAction> mActions = new ArrayList<>();

    private final Handler mHandler =
            new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                        case MSG_DISABLE_DEVICE_TIMEOUT:
                            handleDisableDeviceTimeout();
                            break;
                        case MSG_USER_CONTROL_RELEASE_TIMEOUT:
                            handleUserControlReleased();
                            break;
                    }
                }
            };

    /**
     * A callback interface to get notified when all pending action is cleared. It can be called
     * when timeout happened.
     */
    interface PendingActionClearedCallback {
        void onCleared(HdmiCecLocalDevice device);
    }

    protected PendingActionClearedCallback mPendingActionClearedCallback;

    protected HdmiCecLocalDevice(HdmiControlService service, int deviceType) {
        mService = service;
        mDeviceType = deviceType;
        mAddress = Constants.ADDR_UNREGISTERED;
        mLock = service.getServiceLock();
    }

    // Factory method that returns HdmiCecLocalDevice of corresponding type.
    static HdmiCecLocalDevice create(HdmiControlService service, int deviceType) {
        switch (deviceType) {
            case HdmiDeviceInfo.DEVICE_TV:
                return new HdmiCecLocalDeviceTv(service);
            case HdmiDeviceInfo.DEVICE_PLAYBACK:
                return new HdmiCecLocalDevicePlayback(service);
            case HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM:
                return new HdmiCecLocalDeviceAudioSystem(service);
            default:
                return null;
        }
    }

    @ServiceThreadOnly
    void init() {
        assertRunOnServiceThread();
        mPreferredAddress = getPreferredAddress();
        mPendingActionClearedCallback = null;
    }

    /** Called once a logical address of the local device is allocated. */
    protected abstract void onAddressAllocated(int logicalAddress, int reason);

    /** Get the preferred logical address from system properties. */
    protected abstract int getPreferredAddress();

    /** Set the preferred logical address to system properties. */
    protected abstract void setPreferredAddress(int addr);

    /**
     * Returns true if the TV input associated with the CEC device is ready to accept further
     * processing such as input switching.
     *
     * <p>This is used to buffer certain CEC commands and process it later if the input is not ready
     * yet. For other types of local devices(non-TV), this method returns true by default to let the
     * commands be processed right away.
     */
    protected boolean isInputReady(int deviceId) {
        return true;
    }

    /**
     * Returns true if the local device allows the system to be put to standby.
     *
     * <p>The default implementation returns true.
     */
    protected boolean canGoToStandby() {
        return true;
    }

    /**
     * Dispatch incoming message.
     *
     * @param message incoming message
     * @return true if consumed a message; otherwise, return false.
     */
    @ServiceThreadOnly
    boolean dispatchMessage(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int dest = message.getDestination();
        if (dest != mAddress && dest != Constants.ADDR_BROADCAST) {
            return false;
        }
        // Cache incoming message. Note that it caches only white-listed one.
        mCecMessageCache.cacheMessage(message);
        return onMessage(message);
    }

    @ServiceThreadOnly
    protected final boolean onMessage(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (dispatchMessageToAction(message)) {
            return true;
        }
        switch (message.getOpcode()) {
            case Constants.MESSAGE_ACTIVE_SOURCE:
                return handleActiveSource(message);
            case Constants.MESSAGE_INACTIVE_SOURCE:
                return handleInactiveSource(message);
            case Constants.MESSAGE_REQUEST_ACTIVE_SOURCE:
                return handleRequestActiveSource(message);
            case Constants.MESSAGE_GET_MENU_LANGUAGE:
                return handleGetMenuLanguage(message);
            case Constants.MESSAGE_SET_MENU_LANGUAGE:
                return handleSetMenuLanguage(message);
            case Constants.MESSAGE_GIVE_PHYSICAL_ADDRESS:
                return handleGivePhysicalAddress(null);
            case Constants.MESSAGE_GIVE_OSD_NAME:
                return handleGiveOsdName(message);
            case Constants.MESSAGE_GIVE_DEVICE_VENDOR_ID:
                return handleGiveDeviceVendorId(null);
            case Constants.MESSAGE_GET_CEC_VERSION:
                return handleGetCecVersion(message);
            case Constants.MESSAGE_REPORT_PHYSICAL_ADDRESS:
                return handleReportPhysicalAddress(message);
            case Constants.MESSAGE_ROUTING_CHANGE:
                return handleRoutingChange(message);
            case Constants.MESSAGE_ROUTING_INFORMATION:
                return handleRoutingInformation(message);
            case Constants.MESSAGE_REQUEST_ARC_INITIATION:
                return handleRequestArcInitiate(message);
            case Constants.MESSAGE_REQUEST_ARC_TERMINATION:
                return handleRequestArcTermination(message);
            case Constants.MESSAGE_INITIATE_ARC:
                return handleInitiateArc(message);
            case Constants.MESSAGE_TERMINATE_ARC:
                return handleTerminateArc(message);
            case Constants.MESSAGE_REPORT_ARC_INITIATED:
                return handleReportArcInitiate(message);
            case Constants.MESSAGE_REPORT_ARC_TERMINATED:
                return handleReportArcTermination(message);
            case Constants.MESSAGE_SYSTEM_AUDIO_MODE_REQUEST:
                return handleSystemAudioModeRequest(message);
            case Constants.MESSAGE_SET_SYSTEM_AUDIO_MODE:
                return handleSetSystemAudioMode(message);
            case Constants.MESSAGE_SYSTEM_AUDIO_MODE_STATUS:
                return handleSystemAudioModeStatus(message);
            case Constants.MESSAGE_GIVE_SYSTEM_AUDIO_MODE_STATUS:
                return handleGiveSystemAudioModeStatus(message);
            case Constants.MESSAGE_GIVE_AUDIO_STATUS:
                return handleGiveAudioStatus(message);
            case Constants.MESSAGE_REPORT_AUDIO_STATUS:
                return handleReportAudioStatus(message);
            case Constants.MESSAGE_STANDBY:
                return handleStandby(message);
            case Constants.MESSAGE_TEXT_VIEW_ON:
                return handleTextViewOn(message);
            case Constants.MESSAGE_IMAGE_VIEW_ON:
                return handleImageViewOn(message);
            case Constants.MESSAGE_USER_CONTROL_PRESSED:
                return handleUserControlPressed(message);
            case Constants.MESSAGE_USER_CONTROL_RELEASED:
                return handleUserControlReleased();
            case Constants.MESSAGE_SET_STREAM_PATH:
                return handleSetStreamPath(message);
            case Constants.MESSAGE_GIVE_DEVICE_POWER_STATUS:
                return handleGiveDevicePowerStatus(message);
            case Constants.MESSAGE_MENU_REQUEST:
                return handleMenuRequest(message);
            case Constants.MESSAGE_MENU_STATUS:
                return handleMenuStatus(message);
            case Constants.MESSAGE_VENDOR_COMMAND:
                return handleVendorCommand(message);
            case Constants.MESSAGE_VENDOR_COMMAND_WITH_ID:
                return handleVendorCommandWithId(message);
            case Constants.MESSAGE_SET_OSD_NAME:
                return handleSetOsdName(message);
            case Constants.MESSAGE_RECORD_TV_SCREEN:
                return handleRecordTvScreen(message);
            case Constants.MESSAGE_TIMER_CLEARED_STATUS:
                return handleTimerClearedStatus(message);
            case Constants.MESSAGE_REPORT_POWER_STATUS:
                return handleReportPowerStatus(message);
            case Constants.MESSAGE_TIMER_STATUS:
                return handleTimerStatus(message);
            case Constants.MESSAGE_RECORD_STATUS:
                return handleRecordStatus(message);
            case Constants.MESSAGE_REQUEST_SHORT_AUDIO_DESCRIPTOR:
                return handleRequestShortAudioDescriptor(message);
            case Constants.MESSAGE_REPORT_SHORT_AUDIO_DESCRIPTOR:
                return handleReportShortAudioDescriptor(message);
            default:
                return false;
        }
    }

    @ServiceThreadOnly
    private boolean dispatchMessageToAction(HdmiCecMessage message) {
        assertRunOnServiceThread();
        boolean processed = false;
        // Use copied action list in that processCommand may remove itself.
        for (HdmiCecFeatureAction action : new ArrayList<>(mActions)) {
            // Iterates all actions to check whether incoming message is consumed.
            boolean result = action.processCommand(message);
            processed = processed || result;
        }
        return processed;
    }

    @ServiceThreadOnly
    protected boolean handleGivePhysicalAddress(@Nullable SendMessageCallback callback) {
        assertRunOnServiceThread();

        int physicalAddress = mService.getPhysicalAddress();
        HdmiCecMessage cecMessage =
                HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                        mAddress, physicalAddress, mDeviceType);
        mService.sendCecCommand(cecMessage, callback);
        return true;
    }

    @ServiceThreadOnly
    protected boolean handleGiveDeviceVendorId(@Nullable SendMessageCallback callback) {
        assertRunOnServiceThread();
        int vendorId = mService.getVendorId();
        HdmiCecMessage cecMessage =
                HdmiCecMessageBuilder.buildDeviceVendorIdCommand(mAddress, vendorId);
        mService.sendCecCommand(cecMessage, callback);
        return true;
    }

    @ServiceThreadOnly
    protected boolean handleGetCecVersion(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int version = mService.getCecVersion();
        HdmiCecMessage cecMessage =
                HdmiCecMessageBuilder.buildCecVersion(
                        message.getDestination(), message.getSource(), version);
        mService.sendCecCommand(cecMessage);
        return true;
    }

    @ServiceThreadOnly
    protected boolean handleActiveSource(HdmiCecMessage message) {
        return false;
    }

    @ServiceThreadOnly
    protected boolean handleInactiveSource(HdmiCecMessage message) {
        return false;
    }

    @ServiceThreadOnly
    protected boolean handleRequestActiveSource(HdmiCecMessage message) {
        return false;
    }

    @ServiceThreadOnly
    protected boolean handleGetMenuLanguage(HdmiCecMessage message) {
        assertRunOnServiceThread();
        Slog.w(TAG, "Only TV can handle <Get Menu Language>:" + message.toString());
        // 'return false' will cause to reply with <Feature Abort>.
        return false;
    }

    @ServiceThreadOnly
    protected boolean handleSetMenuLanguage(HdmiCecMessage message) {
        assertRunOnServiceThread();
        Slog.w(TAG, "Only Playback device can handle <Set Menu Language>:" + message.toString());
        // 'return false' will cause to reply with <Feature Abort>.
        return false;
    }

    @ServiceThreadOnly
    protected boolean handleGiveOsdName(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // Note that since this method is called after logical address allocation is done,
        // mDeviceInfo should not be null.
        HdmiCecMessage cecMessage =
                HdmiCecMessageBuilder.buildSetOsdNameCommand(
                        mAddress, message.getSource(), mDeviceInfo.getDisplayName());
        if (cecMessage != null) {
            mService.sendCecCommand(cecMessage);
        } else {
            Slog.w(TAG, "Failed to build <Get Osd Name>:" + mDeviceInfo.getDisplayName());
        }
        return true;
    }

    // Audio System device with no Playback device type
    // needs to refactor this function if it's also a switch
    protected boolean handleRoutingChange(HdmiCecMessage message) {
        return false;
    }

    // Audio System device with no Playback device type
    // needs to refactor this function if it's also a switch
    protected boolean handleRoutingInformation(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleReportPhysicalAddress(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleSystemAudioModeStatus(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleGiveSystemAudioModeStatus(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleSetSystemAudioMode(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleSystemAudioModeRequest(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleTerminateArc(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleInitiateArc(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleRequestArcInitiate(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleRequestArcTermination(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleReportArcInitiate(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleReportArcTermination(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleReportAudioStatus(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleGiveAudioStatus(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleRequestShortAudioDescriptor(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleReportShortAudioDescriptor(HdmiCecMessage message) {
        return false;
    }

    @ServiceThreadOnly
    protected boolean handleStandby(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // Seq #12
        if (mService.isControlEnabled()
                && !mService.isProhibitMode()
                && mService.isPowerOnOrTransient()) {
            mService.standby();
            return true;
        }
        return false;
    }

    @ServiceThreadOnly
    protected boolean handleUserControlPressed(HdmiCecMessage message) {
        assertRunOnServiceThread();
        mHandler.removeMessages(MSG_USER_CONTROL_RELEASE_TIMEOUT);
        if (mService.isPowerOnOrTransient() && isPowerOffOrToggleCommand(message)) {
            mService.standby();
            return true;
        } else if (mService.isPowerStandbyOrTransient() && isPowerOnOrToggleCommand(message)) {
            mService.wakeUp();
            return true;
        }

        final long downTime = SystemClock.uptimeMillis();
        final byte[] params = message.getParams();
        final int keycode = HdmiCecKeycode.cecKeycodeAndParamsToAndroidKey(params);
        int keyRepeatCount = 0;
        if (mLastKeycode != HdmiCecKeycode.UNSUPPORTED_KEYCODE) {
            if (keycode == mLastKeycode) {
                keyRepeatCount = mLastKeyRepeatCount + 1;
            } else {
                injectKeyEvent(downTime, KeyEvent.ACTION_UP, mLastKeycode, 0);
            }
        }
        mLastKeycode = keycode;
        mLastKeyRepeatCount = keyRepeatCount;

        if (keycode != HdmiCecKeycode.UNSUPPORTED_KEYCODE) {
            injectKeyEvent(downTime, KeyEvent.ACTION_DOWN, keycode, keyRepeatCount);
            mHandler.sendMessageDelayed(
                    Message.obtain(mHandler, MSG_USER_CONTROL_RELEASE_TIMEOUT),
                    FOLLOWER_SAFETY_TIMEOUT);
            return true;
        }
        return false;
    }

    @ServiceThreadOnly
    protected boolean handleUserControlReleased() {
        assertRunOnServiceThread();
        mHandler.removeMessages(MSG_USER_CONTROL_RELEASE_TIMEOUT);
        mLastKeyRepeatCount = 0;
        if (mLastKeycode != HdmiCecKeycode.UNSUPPORTED_KEYCODE) {
            final long upTime = SystemClock.uptimeMillis();
            injectKeyEvent(upTime, KeyEvent.ACTION_UP, mLastKeycode, 0);
            mLastKeycode = HdmiCecKeycode.UNSUPPORTED_KEYCODE;
            return true;
        }
        return false;
    }

    static void injectKeyEvent(long time, int action, int keycode, int repeat) {
        KeyEvent keyEvent =
                KeyEvent.obtain(
                        time,
                        time,
                        action,
                        keycode,
                        repeat,
                        0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD,
                        0,
                        KeyEvent.FLAG_FROM_SYSTEM,
                        InputDevice.SOURCE_HDMI,
                        null);
        InputManager.getInstance()
                .injectInputEvent(keyEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        keyEvent.recycle();
    }

    static boolean isPowerOnOrToggleCommand(HdmiCecMessage message) {
        byte[] params = message.getParams();
        return message.getOpcode() == Constants.MESSAGE_USER_CONTROL_PRESSED
                && (params[0] == HdmiCecKeycode.CEC_KEYCODE_POWER
                        || params[0] == HdmiCecKeycode.CEC_KEYCODE_POWER_ON_FUNCTION
                        || params[0] == HdmiCecKeycode.CEC_KEYCODE_POWER_TOGGLE_FUNCTION);
    }

    static boolean isPowerOffOrToggleCommand(HdmiCecMessage message) {
        byte[] params = message.getParams();
        return message.getOpcode() == Constants.MESSAGE_USER_CONTROL_PRESSED
                && (params[0] == HdmiCecKeycode.CEC_KEYCODE_POWER_OFF_FUNCTION
                        || params[0] == HdmiCecKeycode.CEC_KEYCODE_POWER_TOGGLE_FUNCTION);
    }

    protected boolean handleTextViewOn(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleImageViewOn(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleSetStreamPath(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleGiveDevicePowerStatus(HdmiCecMessage message) {
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildReportPowerStatus(
                        mAddress, message.getSource(), mService.getPowerStatus()));
        return true;
    }

    protected boolean handleMenuRequest(HdmiCecMessage message) {
        // Always report menu active to receive Remote Control.
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildReportMenuStatus(
                        mAddress, message.getSource(), Constants.MENU_STATE_ACTIVATED));
        return true;
    }

    protected boolean handleMenuStatus(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleVendorCommand(HdmiCecMessage message) {
        if (!mService.invokeVendorCommandListenersOnReceived(
                mDeviceType,
                message.getSource(),
                message.getDestination(),
                message.getParams(),
                false)) {
            // Vendor command listener may not have been registered yet. Respond with
            // <Feature Abort> [NOT_IN_CORRECT_MODE] so that the sender can try again later.
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_NOT_IN_CORRECT_MODE);
        }
        return true;
    }

    protected boolean handleVendorCommandWithId(HdmiCecMessage message) {
        byte[] params = message.getParams();
        int vendorId = HdmiUtils.threeBytesToInt(params);
        if (vendorId == mService.getVendorId()) {
            if (!mService.invokeVendorCommandListenersOnReceived(
                    mDeviceType, message.getSource(), message.getDestination(), params, true)) {
                mService.maySendFeatureAbortCommand(message, Constants.ABORT_NOT_IN_CORRECT_MODE);
            }
        } else if (message.getDestination() != Constants.ADDR_BROADCAST
                && message.getSource() != Constants.ADDR_UNREGISTERED) {
            Slog.v(TAG, "Wrong direct vendor command. Replying with <Feature Abort>");
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_UNRECOGNIZED_OPCODE);
        } else {
            Slog.v(TAG, "Wrong broadcast vendor command. Ignoring");
        }
        return true;
    }

    protected void sendStandby(int deviceId) {
        // Do nothing.
    }

    protected boolean handleSetOsdName(HdmiCecMessage message) {
        // The default behavior of <Set Osd Name> is doing nothing.
        return true;
    }

    protected boolean handleRecordTvScreen(HdmiCecMessage message) {
        // The default behavior of <Record TV Screen> is replying <Feature Abort> with
        // "Cannot provide source".
        mService.maySendFeatureAbortCommand(message, Constants.ABORT_CANNOT_PROVIDE_SOURCE);
        return true;
    }

    protected boolean handleTimerClearedStatus(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleReportPowerStatus(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleTimerStatus(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleRecordStatus(HdmiCecMessage message) {
        return false;
    }

    @ServiceThreadOnly
    final void handleAddressAllocated(int logicalAddress, int reason) {
        assertRunOnServiceThread();
        mAddress = mPreferredAddress = logicalAddress;
        onAddressAllocated(logicalAddress, reason);
        setPreferredAddress(logicalAddress);
    }

    int getType() {
        return mDeviceType;
    }

    @GuardedBy("mLock")
    HdmiDeviceInfo getDeviceInfo() {
        synchronized (mLock) {
            return mDeviceInfo;
        }
    }

    @GuardedBy("mLock")
    void setDeviceInfo(HdmiDeviceInfo info) {
        synchronized (mLock) {
            mDeviceInfo = info;
        }
    }

    // Returns true if the logical address is same as the argument.
    @ServiceThreadOnly
    boolean isAddressOf(int addr) {
        assertRunOnServiceThread();
        return addr == mAddress;
    }

    // Resets the logical address to unregistered(15), meaning the logical device is invalid.
    @ServiceThreadOnly
    void clearAddress() {
        assertRunOnServiceThread();
        mAddress = Constants.ADDR_UNREGISTERED;
    }

    @ServiceThreadOnly
    void addAndStartAction(final HdmiCecFeatureAction action) {
        assertRunOnServiceThread();
        mActions.add(action);
        if (mService.isPowerStandby() || !mService.isAddressAllocated()) {
            Slog.i(TAG, "Not ready to start action. Queued for deferred start:" + action);
            return;
        }
        action.start();
    }

    @ServiceThreadOnly
    void startQueuedActions() {
        assertRunOnServiceThread();
        // Use copied action list in that start() may remove itself.
        for (HdmiCecFeatureAction action : new ArrayList<>(mActions)) {
            if (!action.started()) {
                Slog.i(TAG, "Starting queued action:" + action);
                action.start();
            }
        }
    }

    // See if we have an action of a given type in progress.
    @ServiceThreadOnly
    <T extends HdmiCecFeatureAction> boolean hasAction(final Class<T> clazz) {
        assertRunOnServiceThread();
        for (HdmiCecFeatureAction action : mActions) {
            if (action.getClass().equals(clazz)) {
                return true;
            }
        }
        return false;
    }

    // Returns all actions matched with given class type.
    @ServiceThreadOnly
    <T extends HdmiCecFeatureAction> List<T> getActions(final Class<T> clazz) {
        assertRunOnServiceThread();
        List<T> actions = Collections.<T>emptyList();
        for (HdmiCecFeatureAction action : mActions) {
            if (action.getClass().equals(clazz)) {
                if (actions.isEmpty()) {
                    actions = new ArrayList<T>();
                }
                actions.add((T) action);
            }
        }
        return actions;
    }

    /**
     * Remove the given {@link HdmiCecFeatureAction} object from the action queue.
     *
     * @param action {@link HdmiCecFeatureAction} to remove
     */
    @ServiceThreadOnly
    void removeAction(final HdmiCecFeatureAction action) {
        assertRunOnServiceThread();
        action.finish(false);
        mActions.remove(action);
        checkIfPendingActionsCleared();
    }

    // Remove all actions matched with the given Class type.
    @ServiceThreadOnly
    <T extends HdmiCecFeatureAction> void removeAction(final Class<T> clazz) {
        assertRunOnServiceThread();
        removeActionExcept(clazz, null);
    }

    // Remove all actions matched with the given Class type besides |exception|.
    @ServiceThreadOnly
    <T extends HdmiCecFeatureAction> void removeActionExcept(
            final Class<T> clazz, final HdmiCecFeatureAction exception) {
        assertRunOnServiceThread();
        Iterator<HdmiCecFeatureAction> iter = mActions.iterator();
        while (iter.hasNext()) {
            HdmiCecFeatureAction action = iter.next();
            if (action != exception && action.getClass().equals(clazz)) {
                action.finish(false);
                iter.remove();
            }
        }
        checkIfPendingActionsCleared();
    }

    protected void checkIfPendingActionsCleared() {
        if (mActions.isEmpty() && mPendingActionClearedCallback != null) {
            PendingActionClearedCallback callback = mPendingActionClearedCallback;
            // To prevent from calling the callback again during handling the callback itself.
            mPendingActionClearedCallback = null;
            callback.onCleared(this);
        }
    }

    protected void assertRunOnServiceThread() {
        if (Looper.myLooper() != mService.getServiceLooper()) {
            throw new IllegalStateException("Should run on service thread.");
        }
    }

    void setAutoDeviceOff(boolean enabled) {}

    /**
     * Called when a hot-plug event issued.
     *
     * @param portId id of port where a hot-plug event happened
     * @param connected whether to connected or not on the event
     */
    void onHotplug(int portId, boolean connected) {}

    final HdmiControlService getService() {
        return mService;
    }

    @ServiceThreadOnly
    final boolean isConnectedToArcPort(int path) {
        assertRunOnServiceThread();
        return mService.isConnectedToArcPort(path);
    }

    ActiveSource getActiveSource() {
        return mService.getActiveSource();
    }

    void setActiveSource(ActiveSource newActive) {
        setActiveSource(newActive.logicalAddress, newActive.physicalAddress);
    }

    void setActiveSource(HdmiDeviceInfo info) {
        setActiveSource(info.getLogicalAddress(), info.getPhysicalAddress());
    }

    void setActiveSource(int logicalAddress, int physicalAddress) {
        mService.setActiveSource(logicalAddress, physicalAddress);
        mService.setLastInputForMhl(Constants.INVALID_PORT_ID);
    }

    int getActivePath() {
        synchronized (mLock) {
            return mActiveRoutingPath;
        }
    }

    void setActivePath(int path) {
        synchronized (mLock) {
            mActiveRoutingPath = path;
        }
        mService.setActivePortId(pathToPortId(path));
    }

    /**
     * Returns the ID of the active HDMI port. The active port is the one that has the active
     * routing path connected to it directly or indirectly under the device hierarchy.
     */
    int getActivePortId() {
        synchronized (mLock) {
            return mService.pathToPortId(mActiveRoutingPath);
        }
    }

    /**
     * Update the active port.
     *
     * @param portId the new active port id
     */
    void setActivePortId(int portId) {
        // We update active routing path instead, since we get the active port id from
        // the active routing path.
        setActivePath(mService.portIdToPath(portId));
    }

    // Returns the id of the port that the target device is connected to.
    int getPortId(int physicalAddress) {
        return mService.pathToPortId(physicalAddress);
    }

    @ServiceThreadOnly
    HdmiCecMessageCache getCecMessageCache() {
        assertRunOnServiceThread();
        return mCecMessageCache;
    }

    @ServiceThreadOnly
    int pathToPortId(int newPath) {
        assertRunOnServiceThread();
        return mService.pathToPortId(newPath);
    }

    /**
     * Called when the system goes to standby mode.
     *
     * @param initiatedByCec true if this power sequence is initiated by the reception the CEC
     *     messages like &lt;Standby&gt;
     * @param standbyAction Intent action that drives the standby process, either {@link
     *     HdmiControlService#STANDBY_SCREEN_OFF} or {@link HdmiControlService#STANDBY_SHUTDOWN}
     */
    protected void onStandby(boolean initiatedByCec, int standbyAction) {}

    /**
     * Disable device. {@code callback} is used to get notified when all pending actions are
     * completed or timeout is issued.
     *
     * @param initiatedByCec true if this sequence is initiated by the reception the CEC messages
     *     like &lt;Standby&gt;
     * @param originalCallback callback interface to get notified when all pending actions are
     *     cleared
     */
    protected void disableDevice(
            boolean initiatedByCec, final PendingActionClearedCallback originalCallback) {
        mPendingActionClearedCallback =
                new PendingActionClearedCallback() {
                    @Override
                    public void onCleared(HdmiCecLocalDevice device) {
                        mHandler.removeMessages(MSG_DISABLE_DEVICE_TIMEOUT);
                        originalCallback.onCleared(device);
                    }
                };
        mHandler.sendMessageDelayed(
                Message.obtain(mHandler, MSG_DISABLE_DEVICE_TIMEOUT), DEVICE_CLEANUP_TIMEOUT);
    }

    @ServiceThreadOnly
    private void handleDisableDeviceTimeout() {
        assertRunOnServiceThread();

        // If all actions are not cleared in DEVICE_CLEANUP_TIMEOUT, enforce to finish them.
        // onCleard will be called at the last action's finish method.
        Iterator<HdmiCecFeatureAction> iter = mActions.iterator();
        while (iter.hasNext()) {
            HdmiCecFeatureAction action = iter.next();
            action.finish(false);
            iter.remove();
        }
        if (mPendingActionClearedCallback != null) {
            mPendingActionClearedCallback.onCleared(this);
        }
    }

    /**
     * Send a key event to other CEC device. The logical address of target device will be given by
     * {@link #findKeyReceiverAddress}.
     *
     * @param keyCode key code defined in {@link android.view.KeyEvent}
     * @param isPressed {@code true} for key down event
     * @see #findKeyReceiverAddress()
     */
    @ServiceThreadOnly
    protected void sendKeyEvent(int keyCode, boolean isPressed) {
        assertRunOnServiceThread();
        if (!HdmiCecKeycode.isSupportedKeycode(keyCode)) {
            Slog.w(TAG, "Unsupported key: " + keyCode);
            return;
        }
        List<SendKeyAction> action = getActions(SendKeyAction.class);
        int logicalAddress = findKeyReceiverAddress();
        if (logicalAddress == Constants.ADDR_INVALID || logicalAddress == mAddress) {
            // Don't send key event to invalid device or itself.
            Slog.w(
                    TAG,
                    "Discard key event: "
                            + keyCode
                            + ", pressed:"
                            + isPressed
                            + ", receiverAddr="
                            + logicalAddress);
        } else if (!action.isEmpty()) {
            action.get(0).processKeyEvent(keyCode, isPressed);
        } else if (isPressed) {
            addAndStartAction(new SendKeyAction(this, logicalAddress, keyCode));
        }
    }

    /**
     * Send a volume key event to other CEC device. The logical address of target device will be
     * given by {@link #findAudioReceiverAddress()}.
     *
     * @param keyCode key code defined in {@link android.view.KeyEvent}
     * @param isPressed {@code true} for key down event
     * @see #findAudioReceiverAddress()
     */
    @ServiceThreadOnly
    protected void sendVolumeKeyEvent(int keyCode, boolean isPressed) {
        assertRunOnServiceThread();
        if (!HdmiCecKeycode.isVolumeKeycode(keyCode)) {
            Slog.w(TAG, "Not a volume key: " + keyCode);
            return;
        }
        List<SendKeyAction> action = getActions(SendKeyAction.class);
        int logicalAddress = findAudioReceiverAddress();
        if (logicalAddress == Constants.ADDR_INVALID || logicalAddress == mAddress) {
            // Don't send key event to invalid device or itself.
            Slog.w(
                TAG,
                "Discard volume key event: "
                    + keyCode
                    + ", pressed:"
                    + isPressed
                    + ", receiverAddr="
                    + logicalAddress);
        } else if (!action.isEmpty()) {
            action.get(0).processKeyEvent(keyCode, isPressed);
        } else if (isPressed) {
            addAndStartAction(new SendKeyAction(this, logicalAddress, keyCode));
        }
    }

    /**
     * Returns the logical address of the device which will receive key events via {@link
     * #sendKeyEvent}.
     *
     * @see #sendKeyEvent(int, boolean)
     */
    protected int findKeyReceiverAddress() {
        Slog.w(TAG, "findKeyReceiverAddress is not implemented");
        return Constants.ADDR_INVALID;
    }

    /**
     * Returns the logical address of the audio receiver device which will receive volume key events
     * via {@link#sendVolumeKeyEvent}.
     *
     * @see #sendVolumeKeyEvent(int, boolean)
     */
    protected int findAudioReceiverAddress() {
        Slog.w(TAG, "findAudioReceiverAddress is not implemented");
        return Constants.ADDR_INVALID;
    }

    @ServiceThreadOnly
    void invokeCallback(IHdmiControlCallback callback, int result) {
        assertRunOnServiceThread();
        if (callback == null) {
            return;
        }
        try {
            callback.onComplete(result);
        } catch (RemoteException e) {
            Slog.e(TAG, "Invoking callback failed:" + e);
        }
    }

    void sendUserControlPressedAndReleased(int targetAddress, int cecKeycode) {
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildUserControlPressed(mAddress, targetAddress, cecKeycode));
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildUserControlReleased(mAddress, targetAddress));
    }

    /** Dump internal status of HdmiCecLocalDevice object. */
    protected void dump(final IndentingPrintWriter pw) {
        pw.println("mDeviceType: " + mDeviceType);
        pw.println("mAddress: " + mAddress);
        pw.println("mPreferredAddress: " + mPreferredAddress);
        pw.println("mDeviceInfo: " + mDeviceInfo);
        pw.println("mActiveSource: " + getActiveSource());
        pw.println(String.format("mActiveRoutingPath: 0x%04x", mActiveRoutingPath));
    }

    /** Calculates the physical address for {@code activePortId}.
     *
     * <p>This method assumes current device physical address is valid.
     * <p>If the current device is already the leaf of the whole CEC system
     * and can't have devices under it, will return its own physical address.
     *
     * @param activePortId is the local active port Id
     * @return the calculated physical address of the port
     */
    protected int getActivePathOnSwitchFromActivePortId(@LocalActivePort int activePortId) {
        int myPhysicalAddress = mService.getPhysicalAddress();
        int finalMask = activePortId << 8;
        int mask;
        for (mask = 0x0F00; mask > 0x000F;  mask >>= 4) {
            if ((myPhysicalAddress & mask) == 0)  {
                break;
            } else {
                finalMask >>= 4;
            }
        }
        return finalMask | myPhysicalAddress;
    }
}
