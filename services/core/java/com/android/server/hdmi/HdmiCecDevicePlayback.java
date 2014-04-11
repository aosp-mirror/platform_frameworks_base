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

import android.hardware.hdmi.HdmiCec;

/**
 * Class for the logical device of playback type. Devices such as DVD/Blueray player
 * that support 'playback' feature are classified as playback device. It is common
 * that they don't have built-in display, therefore need to talk, stream their contents
 * to TV/display device which is connected through HDMI cable.
 *
 * <p>It closely monitors the status of display device (other devices can be of interest
 * too, but with much less priority), declares itself as 'active source' to have
 * display show its output, switch the source state as ordered by display that may be
 * talking to many other devices connected to it. It also receives commands from display
 * such as remote control signal, standby, status report, playback mode.
 *
 * <p>Declared as package-private, accessed by HdmiCecService only.
 */
final class HdmiCecDevicePlayback extends HdmiCecDevice {
    private static final String TAG = "HdmiCecDevicePlayback";

    private int mSinkDevicePowerStatus;

    /**
     * Constructor.
     */
    public HdmiCecDevicePlayback(HdmiCecService service, int type) {
        super(service, type);
        mSinkDevicePowerStatus = HdmiCec.POWER_STATUS_UNKNOWN;
    }

    @Override
    public void initialize() {
        // Playback device tries to obtain the power status of TV/display when created,
        // and maintains it all through its lifecycle. CEC spec says there is
        // a maximum 1 second response time. Therefore it should be kept in mind
        // that there can be as much amount of period of time the power status
        // of the display remains unknown after the query is sent out.
        queryTvPowerStatus();
    }

    private void queryTvPowerStatus() {
        getService().sendMessage(getType(), HdmiCec.ADDR_TV,
                HdmiCec.MESSAGE_GIVE_DEVICE_POWER_STATUS, HdmiCecService.EMPTY_PARAM);
    }

    @Override
    public void handleMessage(int srcAddress, int dstAddress, int opcode, byte[] params) {
        // Updates power status of display. The cases are:
        // 1) Response for the queried power status request arrives. Update the status.
        // 2) Broadcast or direct <Standby> command from TV, which is sent as TV itself is going
        //    into standby mode too.
        if (opcode == HdmiCec.MESSAGE_REPORT_POWER_STATUS) {
            mSinkDevicePowerStatus = params[0];
        } else if (srcAddress == HdmiCec.ADDR_TV) {
            if (opcode == HdmiCec.MESSAGE_STANDBY) {
                mSinkDevicePowerStatus = HdmiCec.POWER_STATUS_STANDBY;
            }
        }
        super.handleMessage(srcAddress, dstAddress, opcode, params);
    }

    @Override
    public void handleHotplug(boolean connected) {
        // If cable get disconnected sink device becomes unreachable. Switch the status
        // to unknown, and query the status once the cable gets connected back.
        if (!connected) {
            mSinkDevicePowerStatus = HdmiCec.POWER_STATUS_UNKNOWN;
        } else {
            queryTvPowerStatus();
        }
        super.handleHotplug(connected);
    }

    @Override
    public boolean isSinkDeviceOn() {
        return mSinkDevicePowerStatus == HdmiCec.POWER_STATUS_ON;
    }

    @Override
    public void sendActiveSource(int physicalAddress) {
        setIsActiveSource(true);
        byte[] param = new byte[] {
                (byte) ((physicalAddress >> 8) & 0xff),
                (byte) (physicalAddress & 0xff)
        };
        getService().sendMessage(getType(), HdmiCec.ADDR_BROADCAST, HdmiCec.MESSAGE_ACTIVE_SOURCE,
                param);
    }

    @Override
    public void sendInactiveSource(int physicalAddress) {
        setIsActiveSource(false);
        byte[] param = new byte[] {
                (byte) ((physicalAddress >> 8) & 0xff),
                (byte) (physicalAddress & 0xff)
        };
        getService().sendMessage(getType(), HdmiCec.ADDR_TV, HdmiCec.MESSAGE_INACTIVE_SOURCE,
                param);
    }

    @Override
    public void sendImageViewOn() {
        getService().sendMessage(getType(), HdmiCec.ADDR_TV, HdmiCec.MESSAGE_IMAGE_VIEW_ON,
                HdmiCecService.EMPTY_PARAM);
    }

    @Override
    public void sendTextViewOn() {
        getService().sendMessage(getType(), HdmiCec.ADDR_TV, HdmiCec.MESSAGE_TEXT_VIEW_ON,
                HdmiCecService.EMPTY_PARAM);
    }
}
