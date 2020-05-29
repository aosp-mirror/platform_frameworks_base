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

package com.android.server.hdmi;

import static com.android.server.hdmi.Constants.ADDR_TV;
import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IPowerManager;
import android.os.IThermalService;
import android.os.Looper;
import android.os.PowerManager;
import android.os.test.TestLooper;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

/** Tests for {@link ActiveSourceAction} */
@SmallTest
@RunWith(JUnit4.class)
public class ActiveSourceActionTest {

    private Context mContextSpy;
    private HdmiControlService mHdmiControlService;
    private FakeNativeWrapper mNativeWrapper;

    private TestLooper mTestLooper = new TestLooper();
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();
    private int mPhysicalAddress;

    @Mock private IPowerManager mIPowerManagerMock;
    @Mock private IThermalService mIThermalServiceMock;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));

        PowerManager powerManager = new PowerManager(mContextSpy, mIPowerManagerMock,
                mIThermalServiceMock, new Handler(mTestLooper.getLooper()));
        when(mContextSpy.getSystemService(Context.POWER_SERVICE)).thenReturn(powerManager);
        when(mContextSpy.getSystemService(PowerManager.class)).thenReturn(powerManager);
        when(mIPowerManagerMock.isInteractive()).thenReturn(true);

        mHdmiControlService = new HdmiControlService(mContextSpy) {
            @Override
            AudioManager getAudioManager() {
                return new AudioManager() {
                    @Override
                    public void setWiredDeviceConnectionState(
                            int type, int state, String address, String name) {
                        // Do nothing.
                    }
                };
            }

            @Override
            void wakeUp() {
            }

            @Override
            boolean isPowerStandby() {
                return false;
            }

            @Override
            PowerManager getPowerManager() {
                return powerManager;
            }

            @Override
            void writeStringSystemProperty(String key, String value) {
                // do nothing
            }
        };

        Looper looper = mTestLooper.getLooper();
        mHdmiControlService.setIoLooper(looper);
        mNativeWrapper = new FakeNativeWrapper();
        HdmiCecController hdmiCecController = HdmiCecController.createWithNativeWrapper(
                this.mHdmiControlService, mNativeWrapper);
        mHdmiControlService.setCecController(hdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        mHdmiControlService.setMessageValidator(new HdmiCecMessageValidator(mHdmiControlService));
        mHdmiControlService.initPortInfo();
        mPhysicalAddress = 0x2000;
        mNativeWrapper.setPhysicalAddress(mPhysicalAddress);
        mTestLooper.dispatchAll();
    }

    @Test
    public void playbackDevice_sendsActiveSource_sendsMenuStatus() {
        HdmiCecLocalDevicePlayback playbackDevice = new HdmiCecLocalDevicePlayback(
                mHdmiControlService);
        playbackDevice.init();
        mLocalDevices.add(playbackDevice);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();

        HdmiCecFeatureAction action = new com.android.server.hdmi.ActiveSourceAction(
                playbackDevice, ADDR_TV);
        playbackDevice.addAndStartAction(action);
        mTestLooper.dispatchAll();

        HdmiCecMessage activeSource = HdmiCecMessageBuilder.buildActiveSource(
                playbackDevice.mAddress, mPhysicalAddress);
        HdmiCecMessage menuStatus = HdmiCecMessageBuilder.buildReportMenuStatus(
                playbackDevice.mAddress, ADDR_TV, Constants.MENU_STATE_ACTIVATED);

        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).contains(menuStatus);
    }

    @Test
    public void audioDevice_sendsActiveSource_noMenuStatus() {
        HdmiCecLocalDeviceAudioSystem audioDevice = new HdmiCecLocalDeviceAudioSystem(
                mHdmiControlService);
        audioDevice.init();
        mLocalDevices.add(audioDevice);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();

        HdmiCecFeatureAction action = new com.android.server.hdmi.ActiveSourceAction(
                audioDevice, ADDR_TV);
        audioDevice.addAndStartAction(action);
        mTestLooper.dispatchAll();

        HdmiCecMessage activeSource = HdmiCecMessageBuilder.buildActiveSource(audioDevice.mAddress,
                mPhysicalAddress);
        HdmiCecMessage menuStatus = HdmiCecMessageBuilder.buildReportMenuStatus(
                audioDevice.mAddress, ADDR_TV, Constants.MENU_STATE_ACTIVATED);

        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(menuStatus);
    }
}
