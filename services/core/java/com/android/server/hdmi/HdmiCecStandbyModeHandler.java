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

import android.hardware.hdmi.HdmiDeviceInfo;
import android.util.SparseArray;

/**
 * This class handles the incoming messages when HdmiCecService is in the standby mode.
 */
public final class HdmiCecStandbyModeHandler {

    private interface CecMessageHandler {
        boolean handle(HdmiCecMessage message);
    }

    private static final class Bystander implements CecMessageHandler {
        @Override
        public boolean handle(HdmiCecMessage message) {
            return true;
        }
    }

    private static final class Bypasser implements CecMessageHandler {
        @Override
        public boolean handle(HdmiCecMessage message) {
            return false;
        }
    }

    private final class Aborter implements CecMessageHandler {
        private final int mReason;
        public Aborter(int reason) {
            mReason = reason;
        }
        @Override
        public boolean handle(HdmiCecMessage message) {
            mService.maySendFeatureAbortCommand(message, mReason);
            return true;
        }
    }

    private final class AutoOnHandler implements CecMessageHandler {
        @Override
        public boolean handle(HdmiCecMessage message) {
            HdmiCecLocalDeviceTv tv = (HdmiCecLocalDeviceTv) mDevice;
            if (!tv.getAutoWakeup()) {
                mAborterRefused.handle(message);
                return true;
            }
            return false;
        }
    }

    private final class UserControlProcessedHandler implements CecMessageHandler {
        @Override
        public boolean handle(HdmiCecMessage message) {
            // The power status here is always standby.
            if (HdmiCecLocalDevice.isPowerOnOrToggleCommand(message)) {
                return false;
            } else if (HdmiCecLocalDevice.isPowerOffOrToggleCommand(message)) {
                return true;
            }
            return mAborterIncorrectMode.handle(message);
        }
    }

    private final HdmiControlService mService;
    private final HdmiCecLocalDevice mDevice;

    private final SparseArray<CecMessageHandler> mCecMessageHandlers = new SparseArray<>();
    private final CecMessageHandler mDefaultHandler;

    private final CecMessageHandler mAborterUnrecognizedOpcode = new Aborter(
            Constants.ABORT_UNRECOGNIZED_OPCODE);
    private final CecMessageHandler mAborterIncorrectMode = new Aborter(
            Constants.ABORT_NOT_IN_CORRECT_MODE);
    private final CecMessageHandler mAborterRefused = new Aborter(Constants.ABORT_REFUSED);
    private final CecMessageHandler mAutoOnHandler = new AutoOnHandler();
    private final CecMessageHandler mBypasser = new Bypasser();
    private final CecMessageHandler mBystander = new Bystander();
    private final UserControlProcessedHandler
            mUserControlProcessedHandler = new UserControlProcessedHandler();

    private void addCommonHandlers() {
        addHandler(Constants.MESSAGE_USER_CONTROL_PRESSED, mUserControlProcessedHandler);
    }

    private void addTvHandlers() {
        addHandler(Constants.MESSAGE_ACTIVE_SOURCE, mBystander);
        addHandler(Constants.MESSAGE_REQUEST_ACTIVE_SOURCE, mBystander);
        addHandler(Constants.MESSAGE_ROUTING_CHANGE, mBystander);
        addHandler(Constants.MESSAGE_ROUTING_INFORMATION, mBystander);
        addHandler(Constants.MESSAGE_SET_STREAM_PATH, mBystander);
        addHandler(Constants.MESSAGE_STANDBY, mBystander);
        addHandler(Constants.MESSAGE_SET_MENU_LANGUAGE, mBystander);
        addHandler(Constants.MESSAGE_USER_CONTROL_RELEASED, mBystander);
        addHandler(Constants.MESSAGE_FEATURE_ABORT, mBystander);
        addHandler(Constants.MESSAGE_INACTIVE_SOURCE, mBystander);
        addHandler(Constants.MESSAGE_SYSTEM_AUDIO_MODE_STATUS, mBystander);
        addHandler(Constants.MESSAGE_REPORT_AUDIO_STATUS, mBystander);

        addHandler(Constants.MESSAGE_GIVE_PHYSICAL_ADDRESS, mBypasser);
        addHandler(Constants.MESSAGE_GET_MENU_LANGUAGE, mBypasser);
        addHandler(Constants.MESSAGE_REPORT_PHYSICAL_ADDRESS, mBypasser);
        addHandler(Constants.MESSAGE_GIVE_DEVICE_VENDOR_ID, mBypasser);
        addHandler(Constants.MESSAGE_GIVE_OSD_NAME, mBypasser);
        addHandler(Constants.MESSAGE_SET_OSD_NAME, mBypasser);
        addHandler(Constants.MESSAGE_DEVICE_VENDOR_ID, mBypasser);
        addHandler(Constants.MESSAGE_REPORT_POWER_STATUS, mBypasser);
        addHandler(Constants.MESSAGE_GIVE_FEATURES, mBypasser);

        addHandler(Constants.MESSAGE_GIVE_DEVICE_POWER_STATUS, mBypasser);
        addHandler(Constants.MESSAGE_ABORT, mBypasser);
        addHandler(Constants.MESSAGE_GET_CEC_VERSION, mBypasser);

        addHandler(Constants.MESSAGE_VENDOR_COMMAND_WITH_ID, mAborterIncorrectMode);
        addHandler(Constants.MESSAGE_SET_SYSTEM_AUDIO_MODE, mAborterIncorrectMode);

        addHandler(Constants.MESSAGE_IMAGE_VIEW_ON, mAutoOnHandler);
        addHandler(Constants.MESSAGE_TEXT_VIEW_ON, mAutoOnHandler);

        // If TV supports the following messages during power-on, ignore them and do nothing,
        // else reply with <Feature Abort>["Unrecognized Opcode"]
        // <Deck Status>, <Tuner Device Status>, <Tuner Cleared Status>, <Timer Status>
        addHandler(Constants.MESSAGE_RECORD_STATUS, mBystander);

        // If TV supports the following messages during power-on, reply with <Feature Abort>["Not
        // in correct mode to respond"], else reply with <Feature Abort>["Unrecognized Opcode"]
        // <Give Tuner Device Status>, <Select Digital Service>, <Tuner Step Decrement>,
        // <Tuner Stem Increment>, <Menu Status>.
        addHandler(Constants.MESSAGE_RECORD_TV_SCREEN, mAborterIncorrectMode);
        addHandler(Constants.MESSAGE_INITIATE_ARC, mAborterIncorrectMode);
        addHandler(Constants.MESSAGE_TERMINATE_ARC, mAborterIncorrectMode);
    }

    public HdmiCecStandbyModeHandler(HdmiControlService service, HdmiCecLocalDevice device) {
        mService = service;
        mDevice = device;

        addCommonHandlers();
        if (mDevice.getType() == HdmiDeviceInfo.DEVICE_TV) {
            addTvHandlers();
            mDefaultHandler = mAborterUnrecognizedOpcode;
        } else {
            mDefaultHandler = mBypasser;
        }
    }

    private void addHandler(int opcode, CecMessageHandler handler) {
        mCecMessageHandlers.put(opcode, handler);
    }

    /**
     * Handles the CEC message in the standby mode.
     *
     * @param message {@link HdmiCecMessage} to be processed
     * @return true if the message is handled in the handler, false means that the message is need
     *         to be dispatched to the local device.
     */
    boolean handleCommand(HdmiCecMessage message) {
        CecMessageHandler handler = mCecMessageHandlers.get(message.getOpcode());
        if (handler != null) {
            return handler.handle(message);
        }
        return mDefaultHandler.handle(message);
    }
}
