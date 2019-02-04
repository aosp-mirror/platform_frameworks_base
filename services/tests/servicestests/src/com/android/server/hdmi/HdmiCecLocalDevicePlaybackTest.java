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

import static com.android.server.hdmi.Constants.ADDR_TV;
import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;

import static com.google.common.truth.Truth.assertThat;

import android.os.Looper;
import android.os.test.TestLooper;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;

@SmallTest
@RunWith(JUnit4.class)
/** Tests for {@link HdmiCecLocalDevicePlayback} class. */
public class HdmiCecLocalDevicePlaybackTest {

    private HdmiControlService mHdmiControlService;
    private HdmiCecController mHdmiCecController;
    private HdmiCecLocalDevicePlayback mHdmiCecLocalDevicePlayback;
    private FakeNativeWrapper mNativeWrapper;
    private Looper mMyLooper;
    private TestLooper mTestLooper = new TestLooper();
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();
    private int mPlaybackPhysicalAddress;

    @Before
    public void setUp() {
        mHdmiControlService =
            new HdmiControlService(InstrumentationRegistry.getTargetContext()) {
                @Override
                void wakeUp() {
                }

                @Override
                boolean isControlEnabled() {
                    return true;
                }

                @Override
                void writeStringSystemProperty(String key, String value) {
                    // do nothing
                }
            };

        mMyLooper = mTestLooper.getLooper();
        mHdmiCecLocalDevicePlayback = new HdmiCecLocalDevicePlayback(mHdmiControlService);
        mHdmiCecLocalDevicePlayback.init();
        mHdmiControlService.setIoLooper(mMyLooper);
        mNativeWrapper = new FakeNativeWrapper();
        mHdmiCecController =
            HdmiCecController.createWithNativeWrapper(mHdmiControlService, mNativeWrapper);
        mHdmiControlService.setCecController(mHdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        mHdmiControlService.setMessageValidator(new HdmiCecMessageValidator(mHdmiControlService));
        mLocalDevices.add(mHdmiCecLocalDevicePlayback);
        mHdmiControlService.initPortInfo();
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();
        mPlaybackPhysicalAddress = 0x2000;
        mNativeWrapper.setPhysicalAddress(mPlaybackPhysicalAddress);
    }

    // Playback device does not handle routing control related feature right now
    @Ignore("b/120845532")
    @Test
    public void handleSetStreamPath_underCurrentDevice() {
        assertThat(mHdmiCecLocalDevicePlayback.getLocalActivePath()).isEqualTo(0);
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV, 0x2100);
        assertThat(mHdmiCecLocalDevicePlayback.handleSetStreamPath(message)).isTrue();
        // TODO(amyjojo): Move set and get LocalActivePath to Control Service.
        assertThat(mHdmiCecLocalDevicePlayback.getLocalActivePath()).isEqualTo(1);
    }

    @Test
    public void handleSetSystemAudioModeOn_audioSystemBroadcast() {
        mHdmiControlService.setSystemAudioActivated(false);
        assertThat(mHdmiCecLocalDevicePlayback.mService.isSystemAudioActivated()).isFalse();
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildSetSystemAudioMode(
                        Constants.ADDR_AUDIO_SYSTEM, Constants.ADDR_BROADCAST, true);
        assertThat(mHdmiCecLocalDevicePlayback.handleSetSystemAudioMode(message)).isTrue();
        assertThat(mHdmiCecLocalDevicePlayback.mService.isSystemAudioActivated()).isTrue();
    }

    @Test
    public void handleSetSystemAudioModeOff_audioSystemToPlayback() {
        mHdmiCecLocalDevicePlayback.mService.setSystemAudioActivated(true);
        assertThat(mHdmiCecLocalDevicePlayback.mService.isSystemAudioActivated()).isTrue();
        // This direct message to Playback device is invalid.
        // Test should ignore it and still keep the system audio mode on.
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildSetSystemAudioMode(
                        Constants.ADDR_AUDIO_SYSTEM, mHdmiCecLocalDevicePlayback.mAddress, false);
        assertThat(mHdmiCecLocalDevicePlayback.handleSetSystemAudioMode(message)).isTrue();
        assertThat(mHdmiCecLocalDevicePlayback.mService.isSystemAudioActivated()).isTrue();
    }

    @Test
    public void handleSystemAudioModeStatusOn_DirectltToLocalDeviceFromAudioSystem() {
        mHdmiControlService.setSystemAudioActivated(false);
        assertThat(mHdmiCecLocalDevicePlayback.mService.isSystemAudioActivated()).isFalse();
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildReportSystemAudioMode(
                        Constants.ADDR_AUDIO_SYSTEM, mHdmiCecLocalDevicePlayback.mAddress, true);
        assertThat(mHdmiCecLocalDevicePlayback.handleSystemAudioModeStatus(message)).isTrue();
        assertThat(mHdmiCecLocalDevicePlayback.mService.isSystemAudioActivated()).isTrue();
    }
}
