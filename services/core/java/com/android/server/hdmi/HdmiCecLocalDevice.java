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

import android.annotation.CallSuper;
import android.annotation.Nullable;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.input.InputManager;
import android.hardware.tv.cec.V1_0.Result;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.media.AudioManager;
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
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.hdmi.Constants.LocalActivePort;
import com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;
import com.android.server.hdmi.HdmiControlService.SendMessageCallback;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Class that models a logical CEC device hosted in this system. Handles initialization, CEC
 * commands that call for actions customized per device type.
 */
abstract class HdmiCecLocalDevice {
    private static final String TAG = "HdmiCecLocalDevice";

    private static final int MAX_HDMI_ACTIVE_SOURCE_HISTORY = 10;
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

    // Stores recent changes to the active source in the CEC network.
    private final ArrayBlockingQueue<HdmiCecController.Dumpable> mActiveSourceHistory =
            new ArrayBlockingQueue<>(MAX_HDMI_ACTIVE_SOURCE_HISTORY);

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
            StringBuilder s = new StringBuilder();
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
    @VisibleForTesting
    final ArrayList<HdmiCecFeatureAction> mActions = new ArrayList<>();

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
    @VisibleForTesting
    @Constants.HandleMessageResult
    protected int dispatchMessage(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int dest = message.getDestination();
        if (dest != mAddress && dest != Constants.ADDR_BROADCAST) {
            return Constants.NOT_HANDLED;
        }
        // Cache incoming message if it is included in the list of cacheable opcodes.
        mCecMessageCache.cacheMessage(message);
        return onMessage(message);
    }

    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected final int onMessage(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (dispatchMessageToAction(message)) {
            return Constants.HANDLED;
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
                return handleGiveDeviceVendorId(message);
            case Constants.MESSAGE_CEC_VERSION:
                return handleCecVersion();
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
            case Constants.MESSAGE_GIVE_FEATURES:
                return handleGiveFeatures(message);
            default:
                return Constants.NOT_HANDLED;
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
    @Constants.HandleMessageResult
    protected int handleGivePhysicalAddress(@Nullable SendMessageCallback callback) {
        assertRunOnServiceThread();

        int physicalAddress = mService.getPhysicalAddress();
        HdmiCecMessage cecMessage =
                HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                        mAddress, physicalAddress, mDeviceType);
        mService.sendCecCommand(cecMessage, callback);
        return Constants.HANDLED;
    }

    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleGiveDeviceVendorId(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int vendorId = mService.getVendorId();
        if (vendorId == Result.FAILURE_UNKNOWN) {
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_UNABLE_TO_DETERMINE);
        } else {
            HdmiCecMessage cecMessage =
                    HdmiCecMessageBuilder.buildDeviceVendorIdCommand(mAddress, vendorId);
            mService.sendCecCommand(cecMessage);
        }
        return Constants.HANDLED;
    }

    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleGetCecVersion(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int version = mService.getCecVersion();
        HdmiCecMessage cecMessage =
                HdmiCecMessageBuilder.buildCecVersion(
                        message.getDestination(), message.getSource(), version);
        mService.sendCecCommand(cecMessage);
        return Constants.HANDLED;
    }

    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleCecVersion() {
        assertRunOnServiceThread();

        // Return true to avoid <Feature Abort> responses. Cec Version is tracked in HdmiCecNetwork.
        return Constants.HANDLED;
    }

    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleActiveSource(HdmiCecMessage message) {
        return Constants.NOT_HANDLED;
    }

    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleInactiveSource(HdmiCecMessage message) {
        return Constants.NOT_HANDLED;
    }

    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleRequestActiveSource(HdmiCecMessage message) {
        return Constants.NOT_HANDLED;
    }

    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleGetMenuLanguage(HdmiCecMessage message) {
        assertRunOnServiceThread();
        Slog.w(TAG, "Only TV can handle <Get Menu Language>:" + message.toString());
        return Constants.NOT_HANDLED;
    }

    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleSetMenuLanguage(HdmiCecMessage message) {
        assertRunOnServiceThread();
        Slog.w(TAG, "Only Playback device can handle <Set Menu Language>:" + message.toString());
        return Constants.NOT_HANDLED;
    }

    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleGiveOsdName(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // Note that since this method is called after logical address allocation is done,
        // mDeviceInfo should not be null.
        buildAndSendSetOsdName(message.getSource());
        return Constants.HANDLED;
    }

    protected void buildAndSendSetOsdName(int dest) {
        HdmiCecMessage cecMessage =
            HdmiCecMessageBuilder.buildSetOsdNameCommand(
                mAddress, dest, mDeviceInfo.getDisplayName());
        if (cecMessage != null) {
            mService.sendCecCommand(cecMessage, new SendMessageCallback() {
                @Override
                public void onSendCompleted(int error) {
                    if (error != SendMessageResult.SUCCESS) {
                        HdmiLogger.debug("Failed to send cec command " + cecMessage);
                    }
                }
            });
        } else {
            Slog.w(TAG, "Failed to build <Get Osd Name>:" + mDeviceInfo.getDisplayName());
        }
    }

    // Audio System device with no Playback device type
    // needs to refactor this function if it's also a switch
    @Constants.HandleMessageResult
    protected int handleRoutingChange(HdmiCecMessage message) {
        return Constants.NOT_HANDLED;
    }

    // Audio System device with no Playback device type
    // needs to refactor this function if it's also a switch
    @Constants.HandleMessageResult
    protected int handleRoutingInformation(HdmiCecMessage message) {
        return Constants.NOT_HANDLED;
    }

    @CallSuper
    @Constants.HandleMessageResult
    protected int handleReportPhysicalAddress(HdmiCecMessage message) {
        // <Report Physical Address>  is also handled in HdmiCecNetwork to update the local network
        // state

        int address = message.getSource();

        // Ignore if [Device Discovery Action] is going on.
        if (hasAction(DeviceDiscoveryAction.class)) {
            Slog.i(TAG, "Ignored while Device Discovery Action is in progress: " + message);
            return Constants.HANDLED;
        }

        HdmiDeviceInfo cecDeviceInfo = mService.getHdmiCecNetwork().getCecDeviceInfo(address);
        // If no non-default display name is available for the device, request the devices OSD name.
        if (cecDeviceInfo != null && cecDeviceInfo.getDisplayName().equals(
                HdmiUtils.getDefaultDeviceName(address))) {
            mService.sendCecCommand(
                    HdmiCecMessageBuilder.buildGiveOsdNameCommand(mAddress, address));
        }

        return Constants.HANDLED;
    }

    @Constants.HandleMessageResult
    protected int handleSystemAudioModeStatus(HdmiCecMessage message) {
        return Constants.NOT_HANDLED;
    }

    @Constants.HandleMessageResult
    protected int handleGiveSystemAudioModeStatus(HdmiCecMessage message) {
        return Constants.NOT_HANDLED;
    }

    @Constants.HandleMessageResult
    protected int handleSetSystemAudioMode(HdmiCecMessage message) {
        return Constants.NOT_HANDLED;
    }

    @Constants.HandleMessageResult
    protected int handleSystemAudioModeRequest(HdmiCecMessage message) {
        return Constants.NOT_HANDLED;
    }

    @Constants.HandleMessageResult
    protected int handleTerminateArc(HdmiCecMessage message) {
        return Constants.NOT_HANDLED;
    }

    @Constants.HandleMessageResult
    protected int handleInitiateArc(HdmiCecMessage message) {
        return Constants.NOT_HANDLED;
    }

    @Constants.HandleMessageResult
    protected int handleRequestArcInitiate(HdmiCecMessage message) {
        return Constants.NOT_HANDLED;
    }

    @Constants.HandleMessageResult
    protected int handleRequestArcTermination(HdmiCecMessage message) {
        return Constants.NOT_HANDLED;
    }

    @Constants.HandleMessageResult
    protected int handleReportArcInitiate(HdmiCecMessage message) {
        return Constants.NOT_HANDLED;
    }

    @Constants.HandleMessageResult
    protected int handleReportArcTermination(HdmiCecMessage message) {
        return Constants.NOT_HANDLED;
    }

    @Constants.HandleMessageResult
    protected int handleReportAudioStatus(HdmiCecMessage message) {
        return Constants.NOT_HANDLED;
    }

    @Constants.HandleMessageResult
    protected int handleGiveAudioStatus(HdmiCecMessage message) {
        return Constants.NOT_HANDLED;
    }

    @Constants.HandleMessageResult
    protected int handleRequestShortAudioDescriptor(HdmiCecMessage message) {
        return Constants.NOT_HANDLED;
    }

    @Constants.HandleMessageResult
    protected int handleReportShortAudioDescriptor(HdmiCecMessage message) {
        return Constants.NOT_HANDLED;
    }

    @Constants.RcProfile
    protected abstract int getRcProfile();

    protected abstract List<Integer> getRcFeatures();

    protected abstract List<Integer> getDeviceFeatures();

    @Constants.HandleMessageResult
    protected int handleGiveFeatures(HdmiCecMessage message) {
        if (mService.getCecVersion() < HdmiControlManager.HDMI_CEC_VERSION_2_0) {
            return Constants.ABORT_UNRECOGNIZED_OPCODE;
        }

        reportFeatures();
        return Constants.HANDLED;
    }

    protected void reportFeatures() {
        List<Integer> localDeviceTypes = new ArrayList<>();
        for (HdmiCecLocalDevice localDevice : mService.getAllLocalDevices()) {
            localDeviceTypes.add(localDevice.mDeviceType);
        }


        int rcProfile = getRcProfile();
        List<Integer> rcFeatures = getRcFeatures();
        List<Integer> deviceFeatures = getDeviceFeatures();

        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildReportFeatures(mAddress, mService.getCecVersion(),
                        localDeviceTypes, rcProfile, rcFeatures, deviceFeatures));
    }

    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleStandby(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // Seq #12
        if (mService.isControlEnabled()
                && !mService.isProhibitMode()
                && mService.isPowerOnOrTransient()) {
            mService.standby();
            return Constants.HANDLED;
        }
        return Constants.ABORT_NOT_IN_CORRECT_MODE;
    }

    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleUserControlPressed(HdmiCecMessage message) {
        assertRunOnServiceThread();
        mHandler.removeMessages(MSG_USER_CONTROL_RELEASE_TIMEOUT);
        if (mService.isPowerOnOrTransient() && isPowerOffOrToggleCommand(message)) {
            mService.standby();
            return Constants.HANDLED;
        } else if (mService.isPowerStandbyOrTransient() && isPowerOnOrToggleCommand(message)) {
            mService.wakeUp();
            return Constants.HANDLED;
        } else if (mService.getHdmiCecVolumeControl()
                == HdmiControlManager.VOLUME_CONTROL_DISABLED && isVolumeOrMuteCommand(
                message)) {
            return Constants.ABORT_REFUSED;
        }

        if (isPowerOffOrToggleCommand(message) || isPowerOnOrToggleCommand(message)) {
            // Power commands should already be handled above. Don't continue and convert the CEC
            // keycode to Android keycode.
            // Do not <Feature Abort> as the local device should already be in the correct power
            // state.
            return Constants.HANDLED;
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
            return Constants.HANDLED;
        } else if (params.length > 0) {
            // Handle CEC UI commands that are not mapped to an Android keycode
            return handleUnmappedCecKeycode(params[0]);
        }

        return Constants.ABORT_INVALID_OPERAND;
    }

    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleUnmappedCecKeycode(int cecKeycode) {
        if (cecKeycode == HdmiCecKeycode.CEC_KEYCODE_MUTE_FUNCTION) {
            mService.getAudioManager().adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI);
            return Constants.HANDLED;
        } else if (cecKeycode == HdmiCecKeycode.CEC_KEYCODE_RESTORE_VOLUME_FUNCTION) {
            mService.getAudioManager().adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI);
            return Constants.HANDLED;
        }
        return Constants.ABORT_INVALID_OPERAND;
    }

    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleUserControlReleased() {
        assertRunOnServiceThread();
        mHandler.removeMessages(MSG_USER_CONTROL_RELEASE_TIMEOUT);
        mLastKeyRepeatCount = 0;
        if (mLastKeycode != HdmiCecKeycode.UNSUPPORTED_KEYCODE) {
            final long upTime = SystemClock.uptimeMillis();
            injectKeyEvent(upTime, KeyEvent.ACTION_UP, mLastKeycode, 0);
            mLastKeycode = HdmiCecKeycode.UNSUPPORTED_KEYCODE;
        }
        return Constants.HANDLED;
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

    static boolean isVolumeOrMuteCommand(HdmiCecMessage message) {
        byte[] params = message.getParams();
        return message.getOpcode() == Constants.MESSAGE_USER_CONTROL_PRESSED
                && (params[0] == HdmiCecKeycode.CEC_KEYCODE_VOLUME_DOWN
                    || params[0] == HdmiCecKeycode.CEC_KEYCODE_VOLUME_UP
                    || params[0] == HdmiCecKeycode.CEC_KEYCODE_MUTE
                    || params[0] == HdmiCecKeycode.CEC_KEYCODE_MUTE_FUNCTION
                    || params[0] == HdmiCecKeycode.CEC_KEYCODE_RESTORE_VOLUME_FUNCTION);
    }

    @Constants.HandleMessageResult
    protected int handleTextViewOn(HdmiCecMessage message) {
        return Constants.NOT_HANDLED;
    }

    @Constants.HandleMessageResult
    protected int handleImageViewOn(HdmiCecMessage message) {
        return Constants.NOT_HANDLED;
    }

    @Constants.HandleMessageResult
    protected int handleSetStreamPath(HdmiCecMessage message) {
        return Constants.NOT_HANDLED;
    }

    @Constants.HandleMessageResult
    protected int handleGiveDevicePowerStatus(HdmiCecMessage message) {
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildReportPowerStatus(
                        mAddress, message.getSource(), mService.getPowerStatus()));
        return Constants.HANDLED;
    }

    @Constants.HandleMessageResult
    protected int handleMenuRequest(HdmiCecMessage message) {
        // Always report menu active to receive Remote Control.
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildReportMenuStatus(
                        mAddress, message.getSource(), Constants.MENU_STATE_ACTIVATED));
        return Constants.HANDLED;
    }

    @Constants.HandleMessageResult
    protected int handleMenuStatus(HdmiCecMessage message) {
        return Constants.NOT_HANDLED;
    }

    @Constants.HandleMessageResult
    protected int handleVendorCommand(HdmiCecMessage message) {
        if (!mService.invokeVendorCommandListenersOnReceived(
                mDeviceType,
                message.getSource(),
                message.getDestination(),
                message.getParams(),
                false)) {
            // Vendor command listener may not have been registered yet. Respond with
            // <Feature Abort> [Refused] so that the sender can try again later.
            return Constants.ABORT_REFUSED;
        }
        return Constants.HANDLED;
    }

    @Constants.HandleMessageResult
    protected int handleVendorCommandWithId(HdmiCecMessage message) {
        byte[] params = message.getParams();
        int vendorId = HdmiUtils.threeBytesToInt(params);
        if (vendorId == mService.getVendorId()) {
            if (!mService.invokeVendorCommandListenersOnReceived(
                    mDeviceType, message.getSource(), message.getDestination(), params, true)) {
                return Constants.ABORT_REFUSED;
            }
        } else if (message.getDestination() != Constants.ADDR_BROADCAST
                && message.getSource() != Constants.ADDR_UNREGISTERED) {
            Slog.v(TAG, "Wrong direct vendor command. Replying with <Feature Abort>");
            return Constants.ABORT_UNRECOGNIZED_OPCODE;
        } else {
            Slog.v(TAG, "Wrong broadcast vendor command. Ignoring");
        }
        return Constants.HANDLED;
    }

    protected void sendStandby(int deviceId) {
        // Do nothing.
    }

    @Constants.HandleMessageResult
    protected int handleSetOsdName(HdmiCecMessage message) {
        // <Set OSD name> is also handled in HdmiCecNetwork to update the local network state
        return Constants.HANDLED;
    }

    @Constants.HandleMessageResult
    protected int handleRecordTvScreen(HdmiCecMessage message) {
        return Constants.NOT_HANDLED;
    }

    @Constants.HandleMessageResult
    protected int handleTimerClearedStatus(HdmiCecMessage message) {
        return Constants.NOT_HANDLED;
    }

    @Constants.HandleMessageResult
    protected int handleReportPowerStatus(HdmiCecMessage message) {
        // <Report Power Status> is also handled in HdmiCecNetwork to update the local network state
        return Constants.HANDLED;
    }

    @Constants.HandleMessageResult
    protected int handleTimerStatus(HdmiCecMessage message) {
        return Constants.NOT_HANDLED;
    }

    @Constants.HandleMessageResult
    protected int handleRecordStatus(HdmiCecMessage message) {
        return Constants.NOT_HANDLED;
    }

    @ServiceThreadOnly
    final void handleAddressAllocated(int logicalAddress, int reason) {
        assertRunOnServiceThread();
        mAddress = mPreferredAddress = logicalAddress;
        if (mService.getCecVersion() >= HdmiControlManager.HDMI_CEC_VERSION_2_0) {
            reportFeatures();
        }
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
        return mService.getLocalActiveSource();
    }

    void setActiveSource(ActiveSource newActive, String caller) {
        setActiveSource(newActive.logicalAddress, newActive.physicalAddress, caller);
    }

    void setActiveSource(HdmiDeviceInfo info, String caller) {
        setActiveSource(info.getLogicalAddress(), info.getPhysicalAddress(), caller);
    }

    void setActiveSource(int logicalAddress, int physicalAddress, String caller) {
        mService.setActiveSource(logicalAddress, physicalAddress, caller);
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
     * Called when the initialization of local devices is complete.
     */
    protected void onInitializeCecComplete(int initiatedBy) {}

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
        if (mService.getHdmiCecVolumeControl()
                == HdmiControlManager.VOLUME_CONTROL_DISABLED) {
            return;
        }
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

    void addActiveSourceHistoryItem(ActiveSource activeSource, boolean isActiveSource,
            String caller) {
        ActiveSourceHistoryRecord record = new ActiveSourceHistoryRecord(activeSource,
                isActiveSource, caller);
        if (!mActiveSourceHistory.offer(record)) {
            mActiveSourceHistory.poll();
            mActiveSourceHistory.offer(record);
        }
    }

    public ArrayBlockingQueue<HdmiCecController.Dumpable> getActiveSourceHistory() {
        return this.mActiveSourceHistory;
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

    private static final class ActiveSourceHistoryRecord extends HdmiCecController.Dumpable {
        private final ActiveSource mActiveSource;
        private final boolean mIsActiveSource;
        private final String mCaller;

        private ActiveSourceHistoryRecord(ActiveSource mActiveSource, boolean mIsActiveSource,
                String caller) {
            this.mActiveSource = mActiveSource;
            this.mIsActiveSource = mIsActiveSource;
            this.mCaller = caller;
        }

        @Override
        void dump(final IndentingPrintWriter pw, SimpleDateFormat sdf) {
            pw.print("time=");
            pw.print(sdf.format(new Date(mTime)));
            pw.print(" active source=");
            pw.print(mActiveSource);
            pw.print(" isActiveSource=");
            pw.print(mIsActiveSource);
            pw.print(" from=");
            pw.println(mCaller);
        }
    }
}
