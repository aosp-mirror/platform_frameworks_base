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

import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiPlaybackClient.OneTouchPlayCallback;
import android.hardware.hdmi.IHdmiControlCallback;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Feature action that performs one touch play against TV/Display device. This action is initiated
 * via {@link android.hardware.hdmi.HdmiPlaybackClient#oneTouchPlay(OneTouchPlayCallback)} from the
 * Android system working as playback device to turn on the TV, and switch the input.
 * <p>
 * Package-private, accessed by {@link HdmiControlService} only.
 */
final class OneTouchPlayAction extends HdmiCecFeatureAction {
    private static final String TAG = "OneTouchPlayAction";

    // State in which the action is waiting for <Report Power Status>. In normal situation
    // source device can simply send <Text|Image View On> and <Active Source> in succession
    // since the standard requires that the TV/Display should buffer the <Active Source>
    // if the TV is brought of out standby state.
    //
    // But there are TV's that fail to buffer the <Active Source> while getting out of
    // standby mode, and do not accept the command until their power status becomes 'ON'.
    // For a workaround, we send <Give Device Power Status> commands periodically to make sure
    // the device switches its status to 'ON'. Then we send additional <Active Source>.
    @VisibleForTesting
    static final int STATE_WAITING_FOR_REPORT_POWER_STATUS = 1;

    // State in which the action is delayed. If the action starts and
    // {@link PowerManager#isInteractive} returns false, it could indicate the beginning of a
    // standby process. In this scenario, the action will be removed when
    // {@link HdmiCecLocalDeviceSource#disableDevice} is called, therefore we delay the action.
    @VisibleForTesting
    static final int STATE_CHECK_STANDBY_PROCESS_STARTED = 2;

    // The maximum number of times we send <Give Device Power Status> before we give up.
    // We wait up to RESPONSE_TIMEOUT_MS * LOOP_COUNTER_MAX = 20 seconds.
    // Every 3 timeouts we send a <Text View On> in case the TV missed it and ignored it.
    @VisibleForTesting
    static final int LOOP_COUNTER_MAX = 10;

    private final int mTargetAddress;
    private final boolean mIsCec20;

    private int mPowerStatusCounter = 0;

    private HdmiCecLocalDeviceSource mSource;

    // Factory method. Ensures arguments are valid.
    static OneTouchPlayAction create(HdmiCecLocalDeviceSource source,
            int targetAddress, IHdmiControlCallback callback) {
        if (source == null || callback == null) {
            Slog.e(TAG, "Wrong arguments");
            return null;
        }
        return new OneTouchPlayAction(source, targetAddress, callback);
    }

    private OneTouchPlayAction(HdmiCecLocalDevice localDevice, int targetAddress,
            IHdmiControlCallback callback) {
        this(localDevice, targetAddress, callback,
                localDevice.getDeviceInfo().getCecVersion()
                        >= HdmiControlManager.HDMI_CEC_VERSION_2_0
                        && getTargetCecVersion(localDevice, targetAddress)
                        >= HdmiControlManager.HDMI_CEC_VERSION_2_0);
    }

    @VisibleForTesting
    OneTouchPlayAction(HdmiCecLocalDevice localDevice, int targetAddress,
            IHdmiControlCallback callback, boolean isCec20) {
        super(localDevice, callback);
        mTargetAddress = targetAddress;
        mIsCec20 = isCec20;
    }

    @Override
    boolean start() {
        // Because only source device can create this action, it's safe to cast.
        mSource = source();

        if (!mSource.mService.getPowerManager().isInteractive()) {
            Slog.d(TAG, "PowerManager is not interactive. Delay the action to check if standby"
                    + " started!");
            mState = STATE_CHECK_STANDBY_PROCESS_STARTED;
            addTimer(mState, HdmiConfig.TIMEOUT_MS);
        } else {
            startAction();
        }

        return true;
    }

    private void startAction() {
        Slog.i(TAG, "Start action.");

        sendCommand(HdmiCecMessageBuilder.buildTextViewOn(getSourceAddress(), mTargetAddress));

        boolean is20TargetOnBefore = mIsCec20 && getTargetDevicePowerStatus(mSource, mTargetAddress,
                HdmiControlManager.POWER_STATUS_UNKNOWN) == HdmiControlManager.POWER_STATUS_ON;
        // Make the device the active source.
        setAndBroadcastActiveSource();
        // If the device is not an audio system itself, request the connected audio system to
        // turn on.
        if (shouldTurnOnConnectedAudioSystem()) {
            sendCommand(HdmiCecMessageBuilder.buildSystemAudioModeRequest(getSourceAddress(),
                    Constants.ADDR_AUDIO_SYSTEM, getSourcePath(), true));
        }

        if (!mIsCec20) {
            queryDevicePowerStatus();
        } else {
            int targetPowerStatus = getTargetDevicePowerStatus(mSource, mTargetAddress,
                    HdmiControlManager.POWER_STATUS_UNKNOWN);
            if (targetPowerStatus == HdmiControlManager.POWER_STATUS_UNKNOWN) {
                queryDevicePowerStatus();
            } else if (targetPowerStatus == HdmiControlManager.POWER_STATUS_ON) {
                if (!is20TargetOnBefore) {
                    // If the device is still the active source, send the <Active Source> message
                    // again.
                    // Suppress 2nd <Active Source> message if the target device was already on when
                    // the 1st one was sent.
                    maySendActiveSource();
                }
                finishWithCallback(HdmiControlManager.RESULT_SUCCESS);
                return;
            }
        }
        mState = STATE_WAITING_FOR_REPORT_POWER_STATUS;
        addTimer(mState, HdmiConfig.TIMEOUT_MS);
    }

    private void setAndBroadcastActiveSource() {
        // If the device wasn´t the active source yet,
        // this makes it the active source and wakes it up.
        mSource.mService.setAndBroadcastActiveSourceFromOneDeviceType(
                mTargetAddress, getSourcePath(), "OneTouchPlayAction#broadcastActiveSource()");
        // When OneTouchPlay is called, client side should be responsible to send out the intent
        // of which internal source, for example YouTube, it would like to switch to.
        // Here we only update the active port and the active source records in the local
        // device as well as claiming Active Source.
        if (mSource.mService.audioSystem() != null) {
            mSource = mSource.mService.audioSystem();
        }
        mSource.setRoutingPort(Constants.CEC_SWITCH_HOME);
        mSource.setLocalActivePort(Constants.CEC_SWITCH_HOME);
    }

    private void maySendActiveSource() {
        // Only send <Active Source> if the device is already the active source at this time.
        mSource.maySendActiveSource(mTargetAddress);
    }

    private void queryDevicePowerStatus() {
        sendCommand(HdmiCecMessageBuilder.buildGiveDevicePowerStatus(getSourceAddress(),
                mTargetAddress));
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        if (mState != STATE_WAITING_FOR_REPORT_POWER_STATUS
                || mTargetAddress != cmd.getSource()) {
            return false;
        }
        if (cmd.getOpcode() == Constants.MESSAGE_REPORT_POWER_STATUS) {
            int status = cmd.getParams()[0];
            if (status == HdmiControlManager.POWER_STATUS_ON) {
                HdmiLogger.debug("TV's power status is on. Action finished successfully");
                // If the device is still the active source, send the <Active Source> message
                // again.
                maySendActiveSource();
                finishWithCallback(HdmiControlManager.RESULT_SUCCESS);
            }
            return true;
        }
        return false;
    }

    @Override
    void handleTimerEvent(int state) {
        if (mState != state) {
            return;
        }
        switch (state) {
            case STATE_WAITING_FOR_REPORT_POWER_STATUS:
                if (mPowerStatusCounter++ < LOOP_COUNTER_MAX) {
                    if (mPowerStatusCounter % 3 == 0) {
                        HdmiLogger.debug("Retry sending <Text View On> in case the TV "
                                + "missed the message.");
                        sendCommand(HdmiCecMessageBuilder.buildTextViewOn(getSourceAddress(),
                                mTargetAddress));
                    }
                    queryDevicePowerStatus();
                    addTimer(mState, HdmiConfig.TIMEOUT_MS);
                } else {
                    // Couldn't wake up the TV for whatever reason. Report failure.
                    finishWithCallback(HdmiControlManager.RESULT_TIMEOUT);
                }
                return;
            case STATE_CHECK_STANDBY_PROCESS_STARTED:
                Slog.d(TAG, "Action was not removed, start the action.");
                startAction();
                return;
            default:
                return;
        }
    }

    private boolean shouldTurnOnConnectedAudioSystem() {
        HdmiControlService service = mSource.mService;
        if (service.isAudioSystemDevice()) {
            return false;
        }
        @HdmiControlManager.PowerControlMode String powerControlMode =
                service.getHdmiCecConfig().getStringValue(
                        HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE);
        return powerControlMode.equals(HdmiControlManager.POWER_CONTROL_MODE_TV_AND_AUDIO_SYSTEM)
                || powerControlMode.equals(HdmiControlManager.POWER_CONTROL_MODE_BROADCAST);
    }

    private static int getTargetCecVersion(HdmiCecLocalDevice localDevice,
            int targetLogicalAddress) {
        HdmiDeviceInfo targetDevice = localDevice.mService.getHdmiCecNetwork().getCecDeviceInfo(
                targetLogicalAddress);
        if (targetDevice != null) {
            return targetDevice.getCecVersion();
        }
        return HdmiControlManager.HDMI_CEC_VERSION_1_4_B;
    }

    private static int getTargetDevicePowerStatus(HdmiCecLocalDevice localDevice,
            int targetLogicalAddress, int defaultPowerStatus) {
        HdmiDeviceInfo targetDevice = localDevice.mService.getHdmiCecNetwork().getCecDeviceInfo(
                targetLogicalAddress);
        if (targetDevice != null) {
            return targetDevice.getDevicePowerStatus();
        }
        return defaultPowerStatus;
    }
}
