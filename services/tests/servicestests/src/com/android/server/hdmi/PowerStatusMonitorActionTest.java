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

import static com.android.server.SystemService.PHASE_SYSTEM_SERVICES_READY;
import static com.android.server.hdmi.Constants.ADDR_BROADCAST;
import static com.android.server.hdmi.Constants.ADDR_PLAYBACK_1;
import static com.android.server.hdmi.Constants.ADDR_PLAYBACK_2;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.spy;

import android.annotation.RequiresPermission;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiPortInfo;
import android.os.Looper;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/** Tests for {@link PowerStatusMonitorAction} */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class PowerStatusMonitorActionTest {

    private Context mContextSpy;
    private HdmiControlService mHdmiControlService;
    private FakeNativeWrapper mNativeWrapper;
    private FakePowerManagerWrapper mPowerManager;

    private TestLooper mTestLooper = new TestLooper();
    private int mPhysicalAddress;
    private HdmiCecLocalDeviceTv mTvDevice;

    @Before
    public void setUp() throws Exception {
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));

        FakeAudioFramework audioFramework = new FakeAudioFramework();

        mHdmiControlService = new HdmiControlService(mContextSpy,
                Collections.singletonList(HdmiDeviceInfo.DEVICE_TV),
                audioFramework.getAudioManager(), audioFramework.getAudioDeviceVolumeManager()) {
            @Override
            boolean isPowerStandby() {
                return false;
            }

            @Override
            protected void writeStringSystemProperty(String key, String value) {
                // do nothing
            }

            @Override
            protected void sendBroadcastAsUser(@RequiresPermission Intent intent) {
                // do nothing
            }
        };

        Looper looper = mTestLooper.getLooper();
        mHdmiControlService.setIoLooper(looper);
        mHdmiControlService.setHdmiCecConfig(new FakeHdmiCecConfig(mContextSpy));
        mHdmiControlService.setDeviceConfig(new FakeDeviceConfigWrapper());
        mNativeWrapper = new FakeNativeWrapper();
        HdmiCecController hdmiCecController = HdmiCecController.createWithNativeWrapper(
                this.mHdmiControlService, mNativeWrapper, mHdmiControlService.getAtomWriter());
        mHdmiControlService.setCecController(hdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        mTestLooper.dispatchAll();
        HdmiPortInfo[] hdmiPortInfo = new HdmiPortInfo[2];
        hdmiPortInfo[0] =
                new HdmiPortInfo.Builder(1, HdmiPortInfo.PORT_INPUT, 0x1000)
                        .setCecSupported(true)
                        .setMhlSupported(false)
                        .setArcSupported(false)
                        .build();
        hdmiPortInfo[1] =
                new HdmiPortInfo.Builder(2, HdmiPortInfo.PORT_INPUT, 0x2000)
                        .setCecSupported(true)
                        .setMhlSupported(false)
                        .setArcSupported(false)
                        .build();
        mNativeWrapper.setPortInfo(hdmiPortInfo);
        mPhysicalAddress = 0x0000;
        mNativeWrapper.setPhysicalAddress(mPhysicalAddress);
        mHdmiControlService.initService();
        mHdmiControlService.onBootPhase(PHASE_SYSTEM_SERVICES_READY);
        mPowerManager = new FakePowerManagerWrapper(mContextSpy);
        mHdmiControlService.setPowerManager(mPowerManager);
        mTestLooper.dispatchAll();
        mTvDevice = mHdmiControlService.tv();
        mNativeWrapper.clearResultMessages();
    }

    @Test
    public void sourceDevice_1_4_updatesPowerState() {
        sendMessageFromPlaybackDevice(ADDR_PLAYBACK_1, 0x1000);

        PowerStatusMonitorAction action = new PowerStatusMonitorAction(mTvDevice);
        action.start();
        assertPowerStatus(ADDR_PLAYBACK_1, HdmiControlManager.POWER_STATUS_UNKNOWN);
        mTestLooper.dispatchAll();

        HdmiCecMessage giveDevicePowerStatus =
                HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                        mTvDevice.getDeviceInfo().getLogicalAddress(), ADDR_PLAYBACK_1);
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

        HdmiCecMessage giveDevicePowerStatus =
                HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                        mTvDevice.getDeviceInfo().getLogicalAddress(), ADDR_PLAYBACK_1);

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

        HdmiCecMessage giveDevicePowerStatus =
                HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                        mTvDevice.getDeviceInfo().getLogicalAddress(), ADDR_PLAYBACK_1);
        assertThat(mNativeWrapper.getResultMessages()).contains(giveDevicePowerStatus);

        HdmiCecMessage giveDevicePowerStatus2 =
                HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                        mTvDevice.getDeviceInfo().getLogicalAddress(), ADDR_PLAYBACK_2);
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

        HdmiCecMessage giveDevicePowerStatus =
                HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                        mTvDevice.getDeviceInfo().getLogicalAddress(), ADDR_PLAYBACK_1);

        assertThat(mNativeWrapper.getResultMessages()).contains(giveDevicePowerStatus);

        HdmiCecMessage giveDevicePowerStatus2 =
                HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                        mTvDevice.getDeviceInfo().getLogicalAddress(), ADDR_PLAYBACK_2);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(giveDevicePowerStatus2);
    }

    private void sendMessageFromPlaybackDevice(int logicalAddress, int physicalAddress) {
        HdmiCecMessage playbackDevice = HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                logicalAddress, physicalAddress, HdmiDeviceInfo.DEVICE_PLAYBACK);
        mNativeWrapper.onCecMessage(playbackDevice);
        mTestLooper.dispatchAll();
    }

    private void reportPowerStatus(int logicalAddress, boolean broadcast, int powerStatus) {
        int destination =
                broadcast ? ADDR_BROADCAST : mTvDevice.getDeviceInfo().getLogicalAddress();
        HdmiCecMessage reportPowerStatus = HdmiCecMessageBuilder.buildReportPowerStatus(
                logicalAddress, destination,
                powerStatus);
        mNativeWrapper.onCecMessage(reportPowerStatus);
        mTestLooper.dispatchAll();
    }
}
