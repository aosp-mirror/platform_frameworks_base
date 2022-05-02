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
import static com.android.server.hdmi.Constants.ADDR_TV;
import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.media.AudioManager;
import android.os.Looper;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

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

/** Tests for {@link DevicePowerStatusAction} */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class DevicePowerStatusActionTest {

    private static final int TIMEOUT_MS = HdmiConfig.TIMEOUT_MS + 1;

    private Context mContextSpy;
    private HdmiControlService mHdmiControlService;
    private HdmiCecLocalDevice mPlaybackDevice;
    private FakeNativeWrapper mNativeWrapper;
    private FakePowerManagerWrapper mPowerManager;

    private TestLooper mTestLooper = new TestLooper();
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();
    private int mPhysicalAddress;

    private DevicePowerStatusAction mDevicePowerStatusAction;

    @Mock
    private IHdmiControlCallback mCallbackMock;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));

        mHdmiControlService = new HdmiControlService(mContextSpy, Collections.emptyList(),
                new FakeAudioDeviceVolumeManagerWrapper()) {
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
            boolean isPowerStandby() {
                return false;
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
        mHdmiControlService.initService();
        mHdmiControlService.onBootPhase(PHASE_SYSTEM_SERVICES_READY);
        mPowerManager = new FakePowerManagerWrapper(mContextSpy);
        mHdmiControlService.setPowerManager(mPowerManager);
        mPhysicalAddress = 0x2000;
        mNativeWrapper.setPhysicalAddress(mPhysicalAddress);
        mPlaybackDevice = new HdmiCecLocalDevicePlayback(
                mHdmiControlService);
        mPlaybackDevice.init();
        mLocalDevices.add(mPlaybackDevice);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mDevicePowerStatusAction = DevicePowerStatusAction.create(mPlaybackDevice, ADDR_TV,
                mCallbackMock);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();
    }

    @Test
    public void queryDisplayStatus_sendsRequestAndHandlesResponse() throws Exception {
        mPlaybackDevice.addAndStartAction(mDevicePowerStatusAction);
        mTestLooper.dispatchAll();

        HdmiCecMessage expected =
                HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                        mPlaybackDevice.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        assertThat(mNativeWrapper.getResultMessages()).contains(expected);

        HdmiCecMessage response =
                HdmiCecMessageBuilder.buildReportPowerStatus(
                        ADDR_TV,
                        mPlaybackDevice.getDeviceInfo().getLogicalAddress(),
                        HdmiControlManager.POWER_STATUS_STANDBY);
        mNativeWrapper.onCecMessage(response);
        mTestLooper.dispatchAll();

        verify(mCallbackMock).onComplete(HdmiControlManager.POWER_STATUS_STANDBY);
    }

    @Test
    public void queryDisplayStatus_sendsRequest_nack() throws Exception {
        mNativeWrapper.setMessageSendResult(Constants.MESSAGE_GIVE_DEVICE_POWER_STATUS,
                SendMessageResult.NACK);

        mPlaybackDevice.addAndStartAction(mDevicePowerStatusAction);
        mTestLooper.dispatchAll();

        verify(mCallbackMock).onComplete(HdmiControlManager.POWER_STATUS_UNKNOWN);
    }

    @Test
    public void queryDisplayStatus_sendsRequest_timeout_retriesSuccessfully() throws Exception {
        mPlaybackDevice.addAndStartAction(mDevicePowerStatusAction);

        HdmiCecMessage expected =
                HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                        mPlaybackDevice.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        mTestLooper.dispatchAll();

        assertThat(mNativeWrapper.getResultMessages()).contains(expected);
        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(TIMEOUT_MS);
        mTestLooper.dispatchAll();

        assertThat(mNativeWrapper.getResultMessages()).contains(expected);

        HdmiCecMessage response =
                HdmiCecMessageBuilder.buildReportPowerStatus(
                        ADDR_TV,
                        mPlaybackDevice.getDeviceInfo().getLogicalAddress(),
                        HdmiControlManager.POWER_STATUS_STANDBY);
        mNativeWrapper.onCecMessage(response);
        mTestLooper.dispatchAll();

        verify(mCallbackMock).onComplete(HdmiControlManager.POWER_STATUS_STANDBY);
    }

    @Test
    public void queryDisplayStatus_sendsRequest_timeout_retriesFailure() throws Exception {
        mPlaybackDevice.addAndStartAction(mDevicePowerStatusAction);
        mTestLooper.dispatchAll();

        HdmiCecMessage expected =
                HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                        mPlaybackDevice.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        assertThat(mNativeWrapper.getResultMessages()).contains(expected);
        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(TIMEOUT_MS);
        mTestLooper.dispatchAll();

        assertThat(mNativeWrapper.getResultMessages()).contains(expected);
        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(TIMEOUT_MS);
        mTestLooper.dispatchAll();

        verify(mCallbackMock).onComplete(HdmiControlManager.POWER_STATUS_UNKNOWN);
    }

    @Test
    public void queryDisplayStatus_localDevice_2_0_targetDevice_1_4() throws Exception {
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mPlaybackDevice.addAndStartAction(mDevicePowerStatusAction);
        mTestLooper.dispatchAll();

        HdmiCecMessage giveDevicePowerStatus =
                HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                        mPlaybackDevice.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        assertThat(mNativeWrapper.getResultMessages()).contains(giveDevicePowerStatus);

        HdmiCecMessage response =
                HdmiCecMessageBuilder.buildReportPowerStatus(
                        ADDR_TV,
                        mPlaybackDevice.getDeviceInfo().getLogicalAddress(),
                        HdmiControlManager.POWER_STATUS_STANDBY);
        mNativeWrapper.onCecMessage(response);
        mTestLooper.dispatchAll();

        verify(mCallbackMock).onComplete(HdmiControlManager.POWER_STATUS_STANDBY);
    }

    @Test
    public void queryDisplayStatus_localDevice_2_0_targetDevice_2_0() throws Exception {
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        HdmiCecMessage reportPhysicalAddress = HdmiCecMessageBuilder
                .buildReportPhysicalAddressCommand(ADDR_TV, 0x0000, HdmiDeviceInfo.DEVICE_TV);
        mNativeWrapper.onCecMessage(reportPhysicalAddress);
        mTestLooper.dispatchAll();
        HdmiCecMessage reportPowerStatusBroadcast = HdmiCecMessageBuilder.buildReportPowerStatus(
                ADDR_TV, ADDR_BROADCAST, HdmiControlManager.POWER_STATUS_STANDBY);
        mNativeWrapper.onCecMessage(reportPowerStatusBroadcast);
        mTestLooper.dispatchAll();
        mPlaybackDevice.addAndStartAction(mDevicePowerStatusAction);
        mTestLooper.dispatchAll();

        HdmiCecMessage giveDevicePowerStatus =
                HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                        mPlaybackDevice.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(giveDevicePowerStatus);

        verify(mCallbackMock).onComplete(HdmiControlManager.POWER_STATUS_STANDBY);
    }

    @Test
    public void queryDisplayStatus_localDevice_2_0_targetDevice_2_0_unknown() throws Exception {
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        HdmiCecMessage reportPhysicalAddress = HdmiCecMessageBuilder
                .buildReportPhysicalAddressCommand(ADDR_TV, 0x0000, HdmiDeviceInfo.DEVICE_TV);
        mNativeWrapper.onCecMessage(reportPhysicalAddress);
        mTestLooper.dispatchAll();
        HdmiCecMessage reportPowerStatusBroadcast = HdmiCecMessageBuilder.buildReportPowerStatus(
                ADDR_TV, ADDR_BROADCAST, HdmiControlManager.POWER_STATUS_UNKNOWN);
        mNativeWrapper.onCecMessage(reportPowerStatusBroadcast);
        mTestLooper.dispatchAll();
        mPlaybackDevice.addAndStartAction(mDevicePowerStatusAction);
        mTestLooper.dispatchAll();

        HdmiCecMessage giveDevicePowerStatus =
                HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                        mPlaybackDevice.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        assertThat(mNativeWrapper.getResultMessages()).contains(giveDevicePowerStatus);

        HdmiCecMessage response =
                HdmiCecMessageBuilder.buildReportPowerStatus(
                        ADDR_TV,
                        mPlaybackDevice.getDeviceInfo().getLogicalAddress(),
                        HdmiControlManager.POWER_STATUS_STANDBY);
        mNativeWrapper.onCecMessage(response);
        mTestLooper.dispatchAll();

        verify(mCallbackMock).onComplete(HdmiControlManager.POWER_STATUS_STANDBY);
    }

    @Test
    public void pendingActionDoesNotBlockSendingStandby() throws Exception {
        mPlaybackDevice.addAndStartAction(mDevicePowerStatusAction);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();

        mHdmiControlService.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();
        HdmiCecMessage standbyMessage =
                HdmiCecMessageBuilder.buildStandby(
                        mPlaybackDevice.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        assertThat(mNativeWrapper.getResultMessages()).contains(standbyMessage);
    }
}
