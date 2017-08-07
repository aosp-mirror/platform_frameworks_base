/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.content.Context;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.util.Preconditions;

import java.util.Arrays;


/**
 * Feature action that handles Request Short Audio Description.
 * To detect AVR device supported audio codec.
 */
final class RequestShortAudioDescriptorAction extends HdmiCecFeatureAction {
    private static final String TAG = "RequestShortAudioDescriptor";

    /**
    * Interface used to update Short Audio Descriptor.
    */
    interface RequestSADCallback {
        /**
         * Called when system audio mode is set.
         *
         * @param keyValuePairs a set of SADs needs to pass to audio HAL. Format will be like as
         *         set_ARC_format=[Parameter Length, ARC port] [SADs(3 bytes for 1 SAD)]
         *         If Parameter Length is 0, restore EDID.
         * @param supportMultiChannels indicates if support multi-channels
         */
        void updateSAD(String keyValuePairs, boolean supportMultiChannels);
    }

    // State in which the action sent <Request Short Audio Descriptor> and
    // is waiting for time out. If it receives <Feature Abort> within timeout.
    private static final int STATE_WAITING_TIMEOUT = 1;

    private final boolean mEnabled;
    private final int mAvrAddress;
    private final int mAvrPort;
    private static byte[] mParamsBackup;
    private final int SAD_LEN_MAX = 12;
    private final int SAD_LEN = 3;   //length of short audio descriptor is 3 bytes
    private final RequestSADCallback mCallback;

    /**
     * Constructor
     *
     * @param source {@link HdmiCecLocalDevice} instance
     * @param avrAddress logical address of AVR
     * @param avrPort indicates which port is connected to AVR
     * @param systemAudioModeEnabled if not enabled, reset short audio descriptors
     * @throw IllegalArugmentException if device type of sourceAddress and avrAddress
     *                      is invalid
     */
    RequestShortAudioDescriptorAction(HdmiCecLocalDevice source, int avrAddress,
            int avrPort, boolean systemAudioModeEnabled, RequestSADCallback callback) {
        super(source);
        HdmiUtils.verifyAddressType(getSourceAddress(), HdmiDeviceInfo.DEVICE_TV);
        HdmiUtils.verifyAddressType(avrAddress, HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
        mAvrAddress = avrAddress;
        mEnabled = systemAudioModeEnabled;
        mAvrPort = avrPort;
        mCallback = Preconditions.checkNotNull(callback);
    }

    @Override
    boolean start() {
        if (mEnabled) {
            mState = STATE_WAITING_TIMEOUT;
            addTimer(mState, HdmiConfig.TIMEOUT_MS);
            if (mParamsBackup != null) {
                HdmiLogger.debug("Set old audio format");
                setAudioFormat();
            } else {
                HdmiLogger.debug("No old audio format. Send a command to reqeust.");
                sendRequestShortAudioDescriptor();
            }
        } else {
            resetShortAudioDescriptor();
            finish();
        }
        return true;
    }

    private void sendRequestShortAudioDescriptor() {
        byte[] params = new byte[4];
        params[0] = (byte) Constants.MSAPI_CODEC_DD;
        params[1] = (byte) Constants.MSAPI_CODEC_AAC;
        params[2] = (byte) Constants.MSAPI_CODEC_DTS;
        params[3] = (byte) Constants.MSAPI_CODEC_DDP;

        HdmiCecMessage command =
                HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(getSourceAddress(),
                    mAvrAddress, params);
        sendCommand(command, new HdmiControlService.SendMessageCallback() {
            @Override
            public void onSendCompleted(int error) {
                switch (error) {
                    case Constants.SEND_RESULT_SUCCESS:
                    case Constants.SEND_RESULT_BUSY:
                    case Constants.SEND_RESULT_FAILURE:
                        //Ignores it silently.
                        break;
                    case Constants.SEND_RESULT_NAK:
                        HdmiLogger.debug("Failed to send <Request Short Audio Descriptor>.");
                        finish();
                        break;
                }
            }
        });
    }

    private void resetShortAudioDescriptor() {
        String audioParameter = "set_ARC_format=";
        String keyValuePairs;
        byte[] buffer = new byte[2];
        buffer[0] = (byte) 0x00;
        buffer[1] = (byte) mAvrPort;
        keyValuePairs = audioParameter + Arrays.toString(buffer);
        mCallback.updateSAD(keyValuePairs, false);
    }

    public static void removeAudioFormat() {
        HdmiLogger.debug("Remove audio format.");
        mParamsBackup = null;
    }

    private boolean isMultiChannelsSupported() {
        byte codec = Constants.MSAPI_CODEC_NONE;
        byte channels = 0;
        for (int index = 0; index < mParamsBackup.length; index += SAD_LEN) {
            // bit 6~3: Audio Format Code
            codec = (byte) ((mParamsBackup[index] & 0x78) >> 3); //enAudioFormatCode
            // bit 2~0: Max number of channels -1
            channels = (byte) (mParamsBackup[index] & 0x07);
            if ((codec == Constants.MSAPI_CODEC_DDP) || (codec == Constants.MSAPI_CODEC_DD)) {
                if (channels >= 5) {
                    return true;
                }
            }
        }
        return false;
    }

    private void setAudioFormat() {
        byte[] buffer = new byte[2];
        String audioParameter = "set_ARC_format=";
        String keyValuePairs;

        buffer[0] = (byte) (mParamsBackup.length);
        buffer[1] = (byte) (mAvrPort);
        keyValuePairs = audioParameter + Arrays.toString(buffer);
        keyValuePairs += Arrays.toString(mParamsBackup);
        HdmiLogger.debug("keyValuePairs:" + keyValuePairs);
        mCallback.updateSAD(keyValuePairs, isMultiChannelsSupported());
        finish();
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        if (mState != STATE_WAITING_TIMEOUT) {
            return false;
        }

        int opcode = cmd.getOpcode();
        byte[] params = cmd.getParams();
        if (opcode == Constants.MESSAGE_FEATURE_ABORT) {
            int originalOpcode = cmd.getParams()[0] & 0xFF;
            if (originalOpcode == Constants.MESSAGE_REQUEST_SHORT_AUDIO_DESCRIPTOR) {
                HdmiLogger.debug("Feature aborted for <Request Short Audio Descriptor>");
                finish();
                return true;
            }
        } else if (opcode == Constants.MESSAGE_REPORT_SHORT_AUDIO_DESCRIPTOR) {
            HdmiLogger.debug("ProcessCommand: <Report Short Audio Descriptor>");
            HdmiLogger.debug("length:" + params.length);
            if ((params.length == 0) || (params.length > SAD_LEN_MAX)) {
                finish();
                return false;
            }
            if ((params[0] & 0xFF) == Constants.MSAPI_CODEC_NONE) {
                resetShortAudioDescriptor();
                finish();
                return true;
            }

            mParamsBackup = new byte[params.length];
            mParamsBackup = Arrays.copyOf(params, params.length);
            setAudioFormat();
            return true;
        }
        return false;
    }

    @Override
    void handleTimerEvent(int state) {
        if (mState != state || mState != STATE_WAITING_TIMEOUT) {
            return;
        }
        // Expire timeout for <Feature Abort>.
        HdmiLogger.debug("[T]RequestShortAudioDescriptorAction.");
        finish();
    }
}
