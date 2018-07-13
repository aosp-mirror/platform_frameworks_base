/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.media.AudioManager;
import android.os.SystemProperties;
import com.android.internal.annotations.GuardedBy;
import com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;

/**
 * Represent a logical device of type {@link HdmiDeviceInfo#DEVICE_AUDIO_SYSTEM} residing in
 * Android system.
 */
public class HdmiCecLocalDeviceAudioSystem extends HdmiCecLocalDevice {

    private static final String TAG = "HdmiCecLocalDeviceAudioSystem";

    // Whether System audio mode is activated or not.
    // This becomes true only when all system audio sequences are finished.
    @GuardedBy("mLock")
    private boolean mSystemAudioActivated;

    protected HdmiCecLocalDeviceAudioSystem(HdmiControlService service) {
        super(service, HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
    }

    @Override
    @ServiceThreadOnly
    protected void onAddressAllocated(int logicalAddress, int reason) {
        assertRunOnServiceThread();
        mService.sendCecCommand(HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                mAddress, mService.getPhysicalAddress(), mDeviceType));
        mService.sendCecCommand(HdmiCecMessageBuilder.buildDeviceVendorIdCommand(
                mAddress, mService.getVendorId()));
        startQueuedActions();
    }

    @Override
    @ServiceThreadOnly
    protected int getPreferredAddress() {
        assertRunOnServiceThread();
        return SystemProperties.getInt(Constants.PROPERTY_PREFERRED_ADDRESS_AUDIO_SYSTEM,
                Constants.ADDR_UNREGISTERED);
    }

    @Override
    @ServiceThreadOnly
    protected void setPreferredAddress(int addr) {
        assertRunOnServiceThread();
        SystemProperties.set(Constants.PROPERTY_PREFERRED_ADDRESS_AUDIO_SYSTEM,
                String.valueOf(addr));
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleReportAudioStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // TODO(amyjojo): implement report audio status handler
        HdmiLogger.debug(TAG + "Stub handleReportAudioStatus");
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleInitiateArc(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // TODO(amyjojo): implement initiate arc handler
        HdmiLogger.debug(TAG + "Stub handleInitiateArc");
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleReportArcInitiate(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // TODO(amyjojo): implement report arc initiate handler
        HdmiLogger.debug(TAG + "Stub handleReportArcInitiate");
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleReportArcTermination(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // TODO(amyjojo): implement report arc terminate handler
        HdmiLogger.debug(TAG + "Stub handleReportArcTermination");
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleGiveAudioStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();

        reportAudioStatus(message);
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleGiveSystemAudioModeStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();
        mService.sendCecCommand(HdmiCecMessageBuilder
            .buildReportSystemAudioMode(mAddress, message.getSource(), mSystemAudioActivated));
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleRequestArcInitiate(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // TODO(b/80296911): Check if ARC supported.

        // TODO(b/80296911): Check if port is ready to accept.

        // TODO(b/80296911): if both true, activate ARC functinality and
        mService.sendCecCommand(HdmiCecMessageBuilder
            .buildInitiateArc(mAddress, message.getSource()));
        // TODO(b/80296911): else, send <Feature Abort>["Unrecongnized opcode"]

        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleRequestArcTermination(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // TODO(b/80297105): Check if ARC supported.

        // TODO(b/80297105): Check is currently in arc.

        // TODO(b/80297105): If both true, deactivate ARC functionality and
        mService.sendCecCommand(HdmiCecMessageBuilder
            .buildTerminateArc(mAddress, message.getSource()));
        // TODO(b/80297105): else, send <Feature Abort>["Unrecongnized opcode"]

        return true;
    }

    private void reportAudioStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();

        int volume = mService.getAudioManager().getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean mute = mService.getAudioManager().isStreamMute(AudioManager.STREAM_MUSIC);
        int maxVolume = mService.getAudioManager().getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int scaledVolume = VolumeControlAction.scaleToCecVolume(volume, maxVolume);

        mService.sendCecCommand(HdmiCecMessageBuilder
            .buildReportAudioStatus(mAddress, message.getSource(), scaledVolume, mute));
    }
}
