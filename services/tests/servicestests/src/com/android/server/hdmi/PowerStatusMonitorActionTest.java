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

import static com.android.server.hdmi.Constants.ADDR_BROADCAST;
import static com.android.server.hdmi.Constants.ADDR_PLAYBACK_1;
import static com.android.server.hdmi.Constants.ADDR_PLAYBACK_2;
import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiPortInfo;
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
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/** Tests for {@link ActiveSourceAction} */
@SmallTest
@RunWith(JUnit4.class)
public class PowerStatusMonitorActionTest {

    private Context mContextSpy;
    private HdmiControlService mHdmiControlService;
    private FakeNativeWrapper mNativeWrapper;

    private TestLooper mTestLooper = new TestLooper();
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();
    private int mPhysicalAddress;
    private HdmiCecLocalDeviceTv mTvDevice;

    @Mock
    private IPowerManager mIPowerManagerMock;
    @Mock
    private IThermalService mIThermalServiceMock;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));

        when(mContextSpy.getSystemService(Context.POWER_SERVICE)).thenAnswer(i ->
                new PowerManager(mContextSpy, mIPowerManagerMock,
                mIThermalServiceMock, new Handler(mTestLooper.getLooper())));
        when(mContextSpy.getSystemService(PowerManager.class)).thenAnswer(i ->
                new PowerManager(mContextSpy, mIPowerManagerMock,
                mIThermalServiceMock, new Handler(mTestLooper.getLooper())));
        when(mIPowerManagerMock.isInteractive()).thenReturn(true);

        mHdmiControlService = new HdmiControlService(mContextSpy,
                Collections.singletonList(HdmiDeviceInfo.DEVICE_TV)) {
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
            protected PowerManager getPowerManager() {
                return new PowerManager(mContextSpy, mIPowerManagerMock,
                        mIThermalServiceMock, new Handler(mTestLooper.getLooper()));
            }

            @Override
            protected void writeStringSystemProperty(String key, String value) {
                // do nothing
            }
        };

        Looper looper = mTestLooper.getLooper();
        mHdmiControlService.setIoLooper(looper);
        mHdmiControlService.setHdmiCecConfig(new FakeHdmiCecConfig(mContextSpy));
        mNativeWrapper = new FakeNativeWrapper();
        HdmiCecController hdmiCecController = HdmiCecController.createWithNativeWrapper(
                this.mHdmiControlService, mNativeWrapper, mHdmiControlService.getAtomWriter());
        mHdmiControlService.setCecController(hdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        mHdmiControlService.setMessageValidator(new HdmiCecMessageValidator(mHdmiControlService));
        mTvDevice = new HdmiCecLocalDeviceTv(mHdmiControlService);
        mTvDevice.init();
        mLocalDevices.add(mTvDevice);
        mTestLooper.dispatchAll();
        HdmiPortInfo[] hdmiPortInfo = new HdmiPortInfo[2];
        hdmiPortInfo[0] =
                new HdmiPortInfo(1, HdmiPortInfo.PORT_INPUT, 0x1000, true, false, false);
        hdmiPortInfo[1] =
                new HdmiPortInfo(2, HdmiPortInfo.PORT_INPUT, 0x2000, true, false, false);
        mNativeWrapper.setPortInfo(hdmiPortInfo);
        mHdmiControlService.initService();
        mPhysicalAddress = 0x0000;
        mNativeWrapper.setPhysicalAddress(mPhysicalAddress);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();
    }

    @Test
    public void sourceDevice_1_4_updatesPowerState() {
        sendMessageFromPlaybackDevice(ADDR_PLAYBACK_1, 0x1000);

        PowerStatusMonitorAction action = new PowerStatusMonitorAction(mTvDevice);
        action.start();
        assertPowerStatus(ADDR_PLAYBACK_1, HdmiControlManager.POWER_STATUS_UNKNOWN);
        mTestLooper.dispatchAll();

        HdmiCecMessage giveDevicePowerStatus = HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                mTvDevice.mAddress,
                ADDR_PLAYBACK_1);
        assertThat(mNativeWrapper.getResultMessages()).contains(giveDevicePowerStatus);

        reportPowerStatus(ADDR_PLAYBACK_1, false, HdmiControlManager.POWER_STATUS_ON);
        assertPowerStatus(ADDR_PLAYBACK_1, HdmiControlManager.POWER_STATUS_ON);

        mTestLooper.moveTimeForward(TimeUnit.SECONDS.toMillis(60));
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(giveDevicePowerStatus);

        reportPowerStatus(ADDR_PLAYBACK_1, false, HdmiControlManager.POWER_STATUS_STANDBY);
        assertPowerStatus(ADDR_PLAYBACK_1, HdmiControlManager.POWER_STATUS_STANDBY);
    }

    private void assertPowerStatus(int logicalAddress, int powerStatus) {
        HdmiDeviceInfo deviceInfo = mHdmiControlService.getHdmiCecNetwork().getCecDeviceInfo(
                logicalAddress);
        assertThat(deviceInfo).isNotNull();
        assertThat(deviceInfo.getDevicePowerStatus()).isEqualTo(powerStatus);
    }

    @Test
    public void sourceDevice_2_0_doesNotUpdatePowerState() {
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
        mTestLooper.dispatchAll();
        sendMessageFromPlaybackDevice(ADDR_PLAYBACK_1, 0x1000);
        reportPowerStatus(ADDR_PLAYBACK_1, true, HdmiControlManager.POWER_STATUS_ON);
        mTestLooper.dispatchAll();

        PowerStatusMonitorAction action = new PowerStatusMonitorAction(mTvDevice);
        action.start();

        assertPowerStatus(ADDR_PLAYBACK_1, HdmiControlManager.POWER_STATUS_ON);
        mTestLooper.dispatchAll();

        HdmiCecMessage giveDevicePowerStatus = HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                mTvDevice.mAddress,
                ADDR_PLAYBACK_1);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(giveDevicePowerStatus);

        mTestLooper.moveTimeForward(TimeUnit.SECONDS.toMillis(60));
        mTestLooper.dispatchAll();

        assertPowerStatus(ADDR_PLAYBACK_1, HdmiControlManager.POWER_STATUS_ON);
    }

    @Test
    public void mixedSourceDevices_localDevice_1_4_updatesAll() {
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
        mTestLooper.dispatchAll();
        sendMessageFromPlaybackDevice(ADDR_PLAYBACK_1, 0x1000);
        sendMessageFromPlaybackDevice(ADDR_PLAYBACK_2, 0x2000);
        reportPowerStatus(ADDR_PLAYBACK_2, true, HdmiControlManager.POWER_STATUS_ON);

        assertPowerStatus(ADDR_PLAYBACK_1, HdmiControlManager.POWER_STATUS_UNKNOWN);
        assertPowerStatus(ADDR_PLAYBACK_2, HdmiControlManager.POWER_STATUS_ON);

        PowerStatusMonitorAction action = new PowerStatusMonitorAction(mTvDevice);
        action.start();
        mTestLooper.dispatchAll();

        HdmiCecMessage giveDevicePowerStatus = HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                mTvDevice.mAddress,
                ADDR_PLAYBACK_1);
        assertThat(mNativeWrapper.getResultMessages()).contains(giveDevicePowerStatus);

        HdmiCecMessage giveDevicePowerStatus2 = HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                mTvDevice.mAddress,
                ADDR_PLAYBACK_2);
        assertThat(mNativeWrapper.getResultMessages()).contains(giveDevicePowerStatus2);
    }

    @Test
    public void mixedSourceDevices_localDevice_2_0_onlyUpdates_1_4() {
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
        mTestLooper.dispatchAll();
        sendMessageFromPlaybackDevice(ADDR_PLAYBACK_1, 0x1000);
        sendMessageFromPlaybackDevice(ADDR_PLAYBACK_2, 0x2000);
        reportPowerStatus(ADDR_PLAYBACK_2, true, HdmiControlManager.POWER_STATUS_ON);

        PowerStatusMonitorAction action = new PowerStatusMonitorAction(mTvDevice);
        action.start();
        mTestLooper.dispatchAll();

        HdmiCecMessage giveDevicePowerStatus = HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                mTvDevice.mAddress,
                ADDR_PLAYBACK_1);

        assertThat(mNativeWrapper.getResultMessages()).contains(giveDevicePowerStatus);

        HdmiCecMessage giveDevicePowerStatus2 = HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                mTvDevice.mAddress,
                ADDR_PLAYBACK_2);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(giveDevicePowerStatus2);
    }

    private void sendMessageFromPlaybackDevice(int logicalAddress, int physicalAddress) {
        HdmiCecMessage playbackDevice = HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                logicalAddress, physicalAddress, HdmiDeviceInfo.DEVICE_PLAYBACK);
        mNativeWrapper.onCecMessage(playbackDevice);
        mTestLooper.dispatchAll();
    }

    private void reportPowerStatus(int logicalAddress, boolean broadcast, int powerStatus) {
        int destination = broadcast ? ADDR_BROADCAST : mTvDevice.mAddress;
        HdmiCecMessage reportPowerStatus = HdmiCecMessageBuilder.buildReportPowerStatus(
                logicalAddress, destination,
                powerStatus);
        mNativeWrapper.onCecMessage(reportPowerStatus);
        mTestLooper.dispatchAll();
    }
}
