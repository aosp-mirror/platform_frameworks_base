/*
 * Copyright (C) 2021 The Android Open Source Project
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
import static com.android.server.hdmi.Constants.ADDR_TV;
import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;
import static com.android.server.hdmi.HdmiControlService.WAKE_UP_SCREEN_ON;
import static com.android.server.hdmi.OneTouchPlayAction.STATE_WAITING_FOR_REPORT_POWER_STATUS;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.spy;

import android.annotation.RequiresPermission;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.os.Looper;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.server.hdmi.HdmiCecFeatureAction.ActionTimer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/** Tests for {@link OneTouchPlayAction} */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class OneTouchPlayActionTest {
    private static final byte[] POWER_ON = new byte[]{HdmiControlManager.POWER_STATUS_ON};
    private static final byte[] POWER_TRANSIENT_TO_ON =
            new byte[]{HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON};

    private static final int PORT_1 = 1;
    private static final HdmiDeviceInfo INFO_TV = HdmiDeviceInfo.cecDeviceBuilder()
            .setLogicalAddress(ADDR_TV)
            .setPhysicalAddress(0x0000)
            .setPortId(PORT_1)
            .setDeviceType(HdmiDeviceInfo.DEVICE_TV)
            .setVendorId(0x1234)
            .setDisplayName("TV")
            .setDevicePowerStatus(HdmiControlManager.POWER_STATUS_ON)
            .setCecVersion(HdmiControlManager.HDMI_CEC_VERSION_1_4_B)
            .build();

    private Context mContextSpy;
    private HdmiControlService mHdmiControlService;
    private HdmiCecController mHdmiCecController;
    private FakeNativeWrapper mNativeWrapper;
    private FakePowerManagerWrapper mPowerManager;
    private FakeHdmiCecConfig mHdmiCecConfig;

    private TestLooper mTestLooper = new TestLooper();
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();
    private int mPhysicalAddress;

    /**
     * Manually called before tests, because some tests require HDMI control to be disabled.
     * @param hdmiControlEnabled whether to enable the global setting hdmi_control.
     * @throws Exception
     */
    public void setUp(boolean hdmiControlEnabled) throws Exception {
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));
        mHdmiCecConfig = new FakeHdmiCecConfig(mContextSpy);

        FakeAudioFramework audioFramework = new FakeAudioFramework();

        mHdmiControlService = new HdmiControlService(mContextSpy,
                Collections.singletonList(HdmiDeviceInfo.DEVICE_PLAYBACK),
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
        mHdmiControlService.setHdmiCecConfig(mHdmiCecConfig);
        setHdmiControlEnabled(hdmiControlEnabled);
        mNativeWrapper = new FakeNativeWrapper();
        mPhysicalAddress = 0x2000;
        mNativeWrapper.setPhysicalAddress(mPhysicalAddress);
        mHdmiCecController = HdmiCecController.createWithNativeWrapper(
                this.mHdmiControlService, mNativeWrapper, mHdmiControlService.getAtomWriter());
        mHdmiControlService.setDeviceConfig(new FakeDeviceConfigWrapper());
        mHdmiControlService.setCecController(mHdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        mHdmiControlService.initService();
        mHdmiControlService.onBootPhase(PHASE_SYSTEM_SERVICES_READY);
        mPowerManager = new FakePowerManagerWrapper(mContextSpy);
        mHdmiControlService.setPowerManager(mPowerManager);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();
    }

    private OneTouchPlayAction createOneTouchPlayAction(HdmiCecLocalDevicePlayback device,
            TestActionTimer actionTimer, TestCallback callback, boolean isCec20) {
        OneTouchPlayAction action = new OneTouchPlayAction(device, ADDR_TV, callback, isCec20);
        action.setActionTimer(actionTimer);
        return action;
    }

    @Test
    public void succeedWithUnknownTvDevice() throws Exception {
        setUp(true);

        HdmiCecLocalDevicePlayback playbackDevice = mHdmiControlService.playback();
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();

        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        OneTouchPlayAction action = createOneTouchPlayAction(playbackDevice, actionTimer, callback,
                false);
        playbackDevice.addAndStartAction(action);
        mTestLooper.dispatchAll();

        HdmiCecMessage activeSource =
                HdmiCecMessageBuilder.buildActiveSource(
                        playbackDevice.getDeviceInfo().getLogicalAddress(), mPhysicalAddress);
        HdmiCecMessage textViewOn =
                HdmiCecMessageBuilder.buildTextViewOn(
                        playbackDevice.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        HdmiCecMessage giveDevicePowerStatus =
                HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                        playbackDevice.getDeviceInfo().getLogicalAddress(), ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).contains(giveDevicePowerStatus);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAITING_FOR_REPORT_POWER_STATUS);
        HdmiCecMessage reportPowerStatusOn =
                HdmiCecMessage.build(
                        ADDR_TV,
                        playbackDevice.getDeviceInfo().getLogicalAddress(),
                        Constants.MESSAGE_REPORT_POWER_STATUS,
                        POWER_ON);
        action.processCommand(reportPowerStatusOn);
        mTestLooper.dispatchAll();

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(giveDevicePowerStatus);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void succeedAfterGettingPowerStatusOn_Cec14b() throws Exception {
        setUp(true);

        HdmiCecLocalDevicePlayback playbackDevice = mHdmiControlService.playback();
        mTestLooper.dispatchAll();

        mNativeWrapper.setPollAddressResponse(ADDR_TV, SendMessageResult.SUCCESS);
        mHdmiControlService.getHdmiCecNetwork().addCecDevice(INFO_TV);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();

        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        OneTouchPlayAction action = createOneTouchPlayAction(playbackDevice, actionTimer, callback,
                false);
        playbackDevice.addAndStartAction(action);
        mTestLooper.dispatchAll();

        HdmiCecMessage activeSource =
                HdmiCecMessageBuilder.buildActiveSource(
                        playbackDevice.getDeviceInfo().getLogicalAddress(), mPhysicalAddress);
        HdmiCecMessage textViewOn =
                HdmiCecMessageBuilder.buildTextViewOn(
                        playbackDevice.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        HdmiCecMessage giveDevicePowerStatus =
                HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                        playbackDevice.getDeviceInfo().getLogicalAddress(), ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).contains(giveDevicePowerStatus);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAITING_FOR_REPORT_POWER_STATUS);
        HdmiCecMessage reportPowerStatusOn =
                HdmiCecMessage.build(
                        ADDR_TV,
                        playbackDevice.getDeviceInfo().getLogicalAddress(),
                        Constants.MESSAGE_REPORT_POWER_STATUS,
                        POWER_ON);
        action.processCommand(reportPowerStatusOn);
        mTestLooper.dispatchAll();

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(giveDevicePowerStatus);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void succeedAfterGettingTransientPowerStatus_Cec14b() throws Exception {
        setUp(true);

        HdmiCecLocalDevicePlayback playbackDevice = mHdmiControlService.playback();
        mTestLooper.dispatchAll();

        mNativeWrapper.setPollAddressResponse(ADDR_TV, SendMessageResult.SUCCESS);
        mHdmiControlService.getHdmiCecNetwork().addCecDevice(INFO_TV);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();

        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        OneTouchPlayAction action = createOneTouchPlayAction(playbackDevice, actionTimer, callback,
                false);
        playbackDevice.addAndStartAction(action);
        mTestLooper.dispatchAll();

        HdmiCecMessage activeSource =
                HdmiCecMessageBuilder.buildActiveSource(
                        playbackDevice.getDeviceInfo().getLogicalAddress(), mPhysicalAddress);
        HdmiCecMessage textViewOn =
                HdmiCecMessageBuilder.buildTextViewOn(
                        playbackDevice.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        HdmiCecMessage giveDevicePowerStatus =
                HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                        playbackDevice.getDeviceInfo().getLogicalAddress(), ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).contains(giveDevicePowerStatus);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAITING_FOR_REPORT_POWER_STATUS);
        HdmiCecMessage reportPowerStatusTransientToOn =
                HdmiCecMessage.build(
                        ADDR_TV,
                        playbackDevice.getDeviceInfo().getLogicalAddress(),
                        Constants.MESSAGE_REPORT_POWER_STATUS,
                        POWER_TRANSIENT_TO_ON);
        action.processCommand(reportPowerStatusTransientToOn);
        action.handleTimerEvent(STATE_WAITING_FOR_REPORT_POWER_STATUS);
        mTestLooper.dispatchAll();

        assertThat(mNativeWrapper.getResultMessages()).contains(giveDevicePowerStatus);
        mNativeWrapper.clearResultMessages();

        HdmiCecMessage reportPowerStatusOn =
                HdmiCecMessage.build(
                        ADDR_TV,
                        playbackDevice.getDeviceInfo().getLogicalAddress(),
                        Constants.MESSAGE_REPORT_POWER_STATUS,
                        POWER_ON);
        action.processCommand(reportPowerStatusOn);
        mTestLooper.dispatchAll();

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(giveDevicePowerStatus);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void timeOut_Cec14b() throws Exception {
        setUp(true);

        HdmiCecLocalDevicePlayback playbackDevice = mHdmiControlService.playback();
        mTestLooper.dispatchAll();

        mNativeWrapper.setPollAddressResponse(ADDR_TV, SendMessageResult.SUCCESS);
        mHdmiControlService.getHdmiCecNetwork().addCecDevice(INFO_TV);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();

        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        OneTouchPlayAction action = createOneTouchPlayAction(playbackDevice, actionTimer, callback,
                false);
        playbackDevice.addAndStartAction(action);
        mTestLooper.dispatchAll();

        HdmiCecMessage activeSource =
                HdmiCecMessageBuilder.buildActiveSource(
                        playbackDevice.getDeviceInfo().getLogicalAddress(), mPhysicalAddress);
        HdmiCecMessage textViewOn =
                HdmiCecMessageBuilder.buildTextViewOn(
                        playbackDevice.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        HdmiCecMessage giveDevicePowerStatus =
                HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                        playbackDevice.getDeviceInfo().getLogicalAddress(), ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).contains(giveDevicePowerStatus);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAITING_FOR_REPORT_POWER_STATUS);
        for (int i = 0; i < 10; ++i) {
            action.handleTimerEvent(STATE_WAITING_FOR_REPORT_POWER_STATUS);
            mTestLooper.dispatchAll();
        }

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).contains(giveDevicePowerStatus);
        action.handleTimerEvent(STATE_WAITING_FOR_REPORT_POWER_STATUS);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_TIMEOUT);
    }

    @Test
    public void succeedIfPowerStatusOn_Cec20() throws Exception {
        setUp(true);
        HdmiCecLocalDevicePlayback playbackDevice = mHdmiControlService.playback();
        mTestLooper.dispatchAll();

        mNativeWrapper.setPollAddressResponse(ADDR_TV, SendMessageResult.SUCCESS);
        mHdmiControlService.getHdmiCecNetwork().addCecDevice(INFO_TV);
        mHdmiControlService.getHdmiCecNetwork().updateDevicePowerStatus(ADDR_TV,
                HdmiControlManager.POWER_STATUS_ON);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();

        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        OneTouchPlayAction action = createOneTouchPlayAction(playbackDevice, actionTimer, callback,
                true);
        playbackDevice.addAndStartAction(action);
        mTestLooper.dispatchAll();

        HdmiCecMessage activeSource =
                HdmiCecMessageBuilder.buildActiveSource(
                        playbackDevice.getDeviceInfo().getLogicalAddress(), mPhysicalAddress);
        HdmiCecMessage textViewOn =
                HdmiCecMessageBuilder.buildTextViewOn(
                        playbackDevice.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        HdmiCecMessage giveDevicePowerStatus =
                HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                        playbackDevice.getDeviceInfo().getLogicalAddress(), ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(giveDevicePowerStatus);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void succeedIfPowerStatusUnknown_Cec20() throws Exception {
        setUp(true);

        HdmiCecLocalDevicePlayback playbackDevice = mHdmiControlService.playback();
        mTestLooper.dispatchAll();

        mNativeWrapper.setPollAddressResponse(ADDR_TV, SendMessageResult.SUCCESS);
        mHdmiControlService.getHdmiCecNetwork().addCecDevice(INFO_TV);
        mHdmiControlService.getHdmiCecNetwork().updateDevicePowerStatus(ADDR_TV,
                HdmiControlManager.POWER_STATUS_UNKNOWN);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();

        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        OneTouchPlayAction action = createOneTouchPlayAction(playbackDevice, actionTimer, callback,
                true);
        playbackDevice.addAndStartAction(action);
        mTestLooper.dispatchAll();

        HdmiCecMessage activeSource =
                HdmiCecMessageBuilder.buildActiveSource(
                        playbackDevice.getDeviceInfo().getLogicalAddress(), mPhysicalAddress);
        HdmiCecMessage textViewOn =
                HdmiCecMessageBuilder.buildTextViewOn(
                        playbackDevice.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        HdmiCecMessage giveDevicePowerStatus =
                HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                        playbackDevice.getDeviceInfo().getLogicalAddress(), ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).contains(giveDevicePowerStatus);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAITING_FOR_REPORT_POWER_STATUS);
        HdmiCecMessage reportPowerStatusOn =
                HdmiCecMessage.build(
                        ADDR_TV,
                        playbackDevice.getDeviceInfo().getLogicalAddress(),
                        Constants.MESSAGE_REPORT_POWER_STATUS,
                        POWER_ON);
        action.processCommand(reportPowerStatusOn);
        mTestLooper.dispatchAll();

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(giveDevicePowerStatus);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void succeedIfPowerStatusStandby_Cec20() throws Exception {
        setUp(true);
        HdmiCecLocalDevicePlayback playbackDevice = mHdmiControlService.playback();
        mTestLooper.dispatchAll();

        mNativeWrapper.setPollAddressResponse(ADDR_TV, SendMessageResult.SUCCESS);
        mHdmiControlService.getHdmiCecNetwork().addCecDevice(INFO_TV);
        mHdmiControlService.getHdmiCecNetwork().updateDevicePowerStatus(ADDR_TV,
                HdmiControlManager.POWER_STATUS_STANDBY);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();

        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        OneTouchPlayAction action = createOneTouchPlayAction(playbackDevice, actionTimer, callback,
                true);
        playbackDevice.addAndStartAction(action);
        mTestLooper.dispatchAll();

        HdmiCecMessage activeSource =
                HdmiCecMessageBuilder.buildActiveSource(
                        playbackDevice.getDeviceInfo().getLogicalAddress(), mPhysicalAddress);
        HdmiCecMessage textViewOn =
                HdmiCecMessageBuilder.buildTextViewOn(
                        playbackDevice.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        HdmiCecMessage giveDevicePowerStatus =
                HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                        playbackDevice.getDeviceInfo().getLogicalAddress(), ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(giveDevicePowerStatus);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAITING_FOR_REPORT_POWER_STATUS);
        HdmiCecMessage reportPowerStatusOn =
                HdmiCecMessage.build(
                        ADDR_TV,
                        playbackDevice.getDeviceInfo().getLogicalAddress(),
                        Constants.MESSAGE_REPORT_POWER_STATUS,
                        POWER_ON);
        action.processCommand(reportPowerStatusOn);
        mTestLooper.dispatchAll();

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(giveDevicePowerStatus);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void succeedWithAddressNotAllocated_Cec14b() throws Exception {
        setUp(false);

        assertThat(mHdmiControlService.isAddressAllocated()).isFalse();

        TestCallback callback = new TestCallback();

        mHdmiControlService.oneTouchPlay(callback);
        mTestLooper.dispatchAll();

        assertThat(callback.hasResult()).isFalse();
        mNativeWrapper.clearResultMessages();

        setHdmiControlEnabled(true);
        mTestLooper.dispatchAll();
        HdmiCecLocalDevicePlayback playbackDevice = mHdmiControlService.playback();

        HdmiCecMessage reportPowerStatusMessage =
                HdmiCecMessageBuilder.buildReportPowerStatus(
                        Constants.ADDR_TV,
                        playbackDevice.getDeviceInfo().getLogicalAddress(),
                        HdmiControlManager.POWER_STATUS_ON);
        mNativeWrapper.onCecMessage(reportPowerStatusMessage);

        mTestLooper.dispatchAll();

        assertThat(mHdmiControlService.isAddressAllocated()).isTrue();
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
        assertThat(playbackDevice.isActiveSource()).isTrue();
        HdmiCecMessage activeSource =
                HdmiCecMessageBuilder.buildActiveSource(
                        playbackDevice.getDeviceInfo().getLogicalAddress(), mPhysicalAddress);
        HdmiCecMessage textViewOn =
                HdmiCecMessageBuilder.buildTextViewOn(
                        playbackDevice.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn);
    }

    @Test
    public void succeedWithAddressAllocated_Cec14b() throws Exception {
        setUp(true);

        HdmiCecLocalDevicePlayback playbackDevice = mHdmiControlService.playback();
        mTestLooper.dispatchAll();
        assertThat(mHdmiControlService.isAddressAllocated()).isTrue();

        TestCallback callback = new TestCallback();
        mHdmiControlService.oneTouchPlay(callback);

        HdmiCecMessage reportPowerStatusMessage =
                HdmiCecMessageBuilder.buildReportPowerStatus(
                        Constants.ADDR_TV,
                        playbackDevice.getDeviceInfo().getLogicalAddress(),
                        HdmiControlManager.POWER_STATUS_ON);
        mNativeWrapper.onCecMessage(reportPowerStatusMessage);

        mTestLooper.dispatchAll();

        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
        assertThat(playbackDevice.isActiveSource()).isTrue();
        HdmiCecMessage activeSource =
                HdmiCecMessageBuilder.buildActiveSource(
                        playbackDevice.getDeviceInfo().getLogicalAddress(), mPhysicalAddress);
        HdmiCecMessage textViewOn =
                HdmiCecMessageBuilder.buildTextViewOn(
                        playbackDevice.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn);
    }

    @Test
    public void pendingActionDoesNotBlockSendingStandby_Cec14b() throws Exception {
        setUp(true);

        HdmiCecLocalDevicePlayback playbackDevice = new HdmiCecLocalDevicePlayback(
                mHdmiControlService);
        playbackDevice.init();
        mLocalDevices.add(playbackDevice);
        playbackDevice.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_TV);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();

        mNativeWrapper.setPollAddressResponse(ADDR_TV, SendMessageResult.SUCCESS);
        mHdmiControlService.getHdmiCecNetwork().addCecDevice(INFO_TV);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();

        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        OneTouchPlayAction action = createOneTouchPlayAction(playbackDevice, actionTimer, callback,
                false);
        playbackDevice.addAndStartAction(action);
        mTestLooper.dispatchAll();

        assertThat(actionTimer.getState()).isEqualTo(STATE_WAITING_FOR_REPORT_POWER_STATUS);
        for (int i = 0; i < 5; ++i) {
            action.handleTimerEvent(STATE_WAITING_FOR_REPORT_POWER_STATUS);
            mTestLooper.dispatchAll();
        }
        mNativeWrapper.clearResultMessages();

        mHdmiControlService.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();
        HdmiCecMessage standbyMessage =
                HdmiCecMessageBuilder.buildStandby(
                        playbackDevice.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        assertThat(mNativeWrapper.getResultMessages()).contains(standbyMessage);
    }

    @Test
    public void noWakeUpOnReportPowerStatus() throws Exception {
        setUp(true);

        HdmiCecLocalDevicePlayback playbackDevice = mHdmiControlService.playback();
        mTestLooper.dispatchAll();

        mNativeWrapper.setPollAddressResponse(ADDR_TV, SendMessageResult.SUCCESS);
        mHdmiControlService.getHdmiCecNetwork().addCecDevice(INFO_TV);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();

        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        OneTouchPlayAction action = createOneTouchPlayAction(playbackDevice, actionTimer, callback,
                false);
        playbackDevice.addAndStartAction(action);
        mTestLooper.dispatchAll();

        assertThat(mPowerManager.isInteractive()).isTrue();
        mPowerManager.setInteractive(false);
        mTestLooper.dispatchAll();

        HdmiCecMessage reportPowerStatusOn =
                HdmiCecMessage.build(
                        ADDR_TV,
                        playbackDevice.getDeviceInfo().getLogicalAddress(),
                        Constants.MESSAGE_REPORT_POWER_STATUS,
                        POWER_ON);
        action.processCommand(reportPowerStatusOn);
        mTestLooper.dispatchAll();

        assertThat(mPowerManager.isInteractive()).isFalse();
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void onWakeUp_notInteractive_startOneTouchPlay() throws Exception {
        setUp(true);

        mHdmiControlService.onWakeUp(WAKE_UP_SCREEN_ON);
        mPowerManager.setInteractive(false);
        mTestLooper.dispatchAll();

        assertThat(mPowerManager.isInteractive()).isFalse();
        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(HdmiConfig.TIMEOUT_MS);
        mTestLooper.dispatchAll();

        HdmiCecMessage textViewOn =
                HdmiCecMessageBuilder.buildTextViewOn(
                        mHdmiControlService.playback().getDeviceInfo().getLogicalAddress(),
                        ADDR_TV);
        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn);
    }


    @Test
    public void onWakeUp_interruptedByOnStandby_notInteractive_OneTouchPlayNotStarted()
            throws Exception {
        setUp(true);
        long allocationDelay = TimeUnit.SECONDS.toMillis(1);
        mHdmiCecController.setLogicalAddressAllocationDelay(allocationDelay);
        mTestLooper.dispatchAll();

        mHdmiControlService.onWakeUp(WAKE_UP_SCREEN_ON);
        mHdmiControlService.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);
        mPowerManager.setInteractive(false);
        mTestLooper.dispatchAll();

        assertThat(mPowerManager.isInteractive()).isFalse();
        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(HdmiConfig.TIMEOUT_MS);
        mTestLooper.dispatchAll();

        HdmiCecMessage textViewOn =
                HdmiCecMessageBuilder.buildTextViewOn(
                        mHdmiControlService.playback().getDeviceInfo().getLogicalAddress(),
                        ADDR_TV);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(textViewOn);

    }

    private static class TestActionTimer implements ActionTimer {
        private int mState;

        @Override
        public void sendTimerMessage(int state, long delayMillis) {
            mState = state;
        }

        @Override
        public void clearTimerMessage() {
        }

        private int getState() {
            return mState;
        }
    }

    private static class TestCallback extends IHdmiControlCallback.Stub {
        private final ArrayList<Integer> mCallbackResult = new ArrayList<Integer>();

        @Override
        public void onComplete(int result) {
            mCallbackResult.add(result);
        }

        private boolean hasResult() {
            return mCallbackResult.size() != 0;
        }

        private int getResult() {
            assertThat(mCallbackResult.size()).isEqualTo(1);
            return mCallbackResult.get(0);
        }
    }

    private void setHdmiControlEnabled(boolean enabled) {
        int value = enabled ? HdmiControlManager.HDMI_CEC_CONTROL_ENABLED :
                HdmiControlManager.HDMI_CEC_CONTROL_DISABLED;
        mHdmiCecConfig.setIntValue(HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED, value);
    }
}
