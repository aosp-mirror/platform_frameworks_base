/*
 * Copyright (C) 2023 The Android Open Source Project
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
import static com.android.server.hdmi.ResendCecCommandAction.SEND_COMMAND_RETRY_MS;

import static com.google.common.truth.Truth.assertThat;

import android.annotation.RequiresPermission;
import android.content.Context;
import android.content.Intent;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.tv.cec.V1_0.SendMessageResult;
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

/** Tests for {@link ResendCecCommandAction} */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class ResendCecCommandActionPlaybackTest {
    private static final String TAG = "SendCecCommandActionPlaybackTest";
    private HdmiControlService mHdmiControlService;
    private HdmiCecLocalDevice mPlaybackDevice;
    private FakeNativeWrapper mNativeWrapper;
    private FakePowerManagerWrapper mPowerManager;
    private Looper mMyLooper;
    private TestLooper mTestLooper = new TestLooper();
    private int mPhysicalAddress;
    private boolean mIsPowerStandby;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        FakeAudioFramework audioFramework = new FakeAudioFramework();

        mHdmiControlService = new HdmiControlService(context,
                Collections.singletonList(HdmiDeviceInfo.DEVICE_PLAYBACK),
                audioFramework.getAudioManager(), audioFramework.getAudioDeviceVolumeManager()) {

            @Override
            boolean isPowerStandby() {
                return mIsPowerStandby;
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
        mIsPowerStandby = false;

        mMyLooper = mTestLooper.getLooper();
        mHdmiControlService.setIoLooper(mMyLooper);
        mHdmiControlService.setHdmiCecConfig(new FakeHdmiCecConfig(context));
        mHdmiControlService.setDeviceConfig(new FakeDeviceConfigWrapper());
        mNativeWrapper = new FakeNativeWrapper();
        mPhysicalAddress = 0x2000;
        mNativeWrapper.setPhysicalAddress(mPhysicalAddress);
        HdmiCecController hdmiCecController = HdmiCecController.createWithNativeWrapper(
                this.mHdmiControlService, mNativeWrapper, mHdmiControlService.getAtomWriter());
        mHdmiControlService.setCecController(hdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        mHdmiControlService.initService();
        mHdmiControlService.onBootPhase(PHASE_SYSTEM_SERVICES_READY);
        mPowerManager = new FakePowerManagerWrapper(context);
        mHdmiControlService.setPowerManager(mPowerManager);
        mTestLooper.dispatchAll();
        mPlaybackDevice = mHdmiControlService.playback();

        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();
    }

    @Test
    public void sendCecCommand_activeSource_sendMessageFails_resendMessage() {
        mNativeWrapper.setMessageSendResult(Constants.MESSAGE_ACTIVE_SOURCE,
                SendMessageResult.BUSY);
        mTestLooper.dispatchAll();
        HdmiCecMessage activeSource = HdmiCecMessageBuilder.buildActiveSource(
                mPlaybackDevice.getDeviceInfo().getLogicalAddress(), mPhysicalAddress);
        mHdmiControlService.sendCecCommand(activeSource);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);

        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(SEND_COMMAND_RETRY_MS);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);

        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(SEND_COMMAND_RETRY_MS);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);

        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(SEND_COMMAND_RETRY_MS);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(activeSource);
    }

    @Test
    public void sendCecCommand_inactiveSource_sendMessageFails_resendMessage() {
        mNativeWrapper.setMessageSendResult(Constants.MESSAGE_INACTIVE_SOURCE,
                SendMessageResult.BUSY);
        mTestLooper.dispatchAll();
        HdmiCecMessage inactiveSourceMessage = HdmiCecMessageBuilder.buildInactiveSource(
                mPlaybackDevice.getDeviceInfo().getLogicalAddress(), mPhysicalAddress);
        mHdmiControlService.sendCecCommand(inactiveSourceMessage);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(inactiveSourceMessage);

        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(SEND_COMMAND_RETRY_MS);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(inactiveSourceMessage);

        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(SEND_COMMAND_RETRY_MS);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(inactiveSourceMessage);

        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(SEND_COMMAND_RETRY_MS);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(inactiveSourceMessage);
    }

    @Test
    public void sendCecCommand_inactiveSource_onStandby_powerControlModeNone_sendMessage() {
        mPlaybackDevice.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_NONE);
        mPlaybackDevice.setActiveSource(mPlaybackDevice.getDeviceInfo().getLogicalAddress(),
                mPhysicalAddress, "SendCecCommandActionPlaybackTest");
        mIsPowerStandby = true;
        mPlaybackDevice.onStandby(false, HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();
        HdmiCecMessage inactiveSourceMessage = HdmiCecMessageBuilder.buildInactiveSource(
                mPlaybackDevice.getDeviceInfo().getLogicalAddress(), mPhysicalAddress);

        assertThat(mNativeWrapper.getResultMessages()).contains(inactiveSourceMessage);
    }

    @Test
    public void sendCecCommand_inactiveSource_onStandby_initiatedByCec_sendMessage() {
        mPlaybackDevice.setActiveSource(mPlaybackDevice.getDeviceInfo().getLogicalAddress(),
                mPhysicalAddress, "SendCecCommandActionPlaybackTest");
        mIsPowerStandby = true;
        mPlaybackDevice.onStandby(true, HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();
        HdmiCecMessage inactiveSourceMessage = HdmiCecMessageBuilder.buildInactiveSource(
                mPlaybackDevice.getDeviceInfo().getLogicalAddress(), mPhysicalAddress);

        assertThat(mNativeWrapper.getResultMessages()).contains(inactiveSourceMessage);
    }

    @Test
    public void sendCecCommand_routingChange_sendMessageFails_resendMessage() {
        mNativeWrapper.setMessageSendResult(Constants.MESSAGE_ROUTING_CHANGE,
                SendMessageResult.BUSY);
        mTestLooper.dispatchAll();
        int otherPhysicalAddress = 0x3000;
        HdmiCecMessage routingChange = HdmiCecMessageBuilder.buildRoutingChange(
                mPlaybackDevice.getDeviceInfo().getLogicalAddress(), mPhysicalAddress,
                otherPhysicalAddress);
        mHdmiControlService.sendCecCommand(routingChange);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(routingChange);

        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(SEND_COMMAND_RETRY_MS);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(routingChange);

        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(SEND_COMMAND_RETRY_MS);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(routingChange);

        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(SEND_COMMAND_RETRY_MS);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(routingChange);
    }

    @Test
    public void sendCecCommand_setStreamPath_sendMessageFails_resendMessage() {
        mNativeWrapper.setMessageSendResult(Constants.MESSAGE_SET_STREAM_PATH,
                SendMessageResult.BUSY);
        mTestLooper.dispatchAll();
        int otherPhysicalAddress = 0x3000;
        HdmiCecMessage setStreamPath = HdmiCecMessageBuilder.buildSetStreamPath(
                mPlaybackDevice.getDeviceInfo().getLogicalAddress(), otherPhysicalAddress);
        mHdmiControlService.sendCecCommand(setStreamPath);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(setStreamPath);

        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(SEND_COMMAND_RETRY_MS);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(setStreamPath);

        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(SEND_COMMAND_RETRY_MS);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(setStreamPath);

        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(SEND_COMMAND_RETRY_MS);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(setStreamPath);
    }

    @Test
    public void sendCecCommand_textViewOn_sendMessageFails_resendMessage() {
        mNativeWrapper.setMessageSendResult(Constants.MESSAGE_TEXT_VIEW_ON, SendMessageResult.BUSY);
        mTestLooper.dispatchAll();
        HdmiCecMessage textViewOn = HdmiCecMessageBuilder.buildTextViewOn(
                mPlaybackDevice.getDeviceInfo().getLogicalAddress(), mPhysicalAddress);
        mHdmiControlService.sendCecCommand(textViewOn);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn);

        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(SEND_COMMAND_RETRY_MS);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn);

        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(SEND_COMMAND_RETRY_MS);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn);

        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(SEND_COMMAND_RETRY_MS);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(textViewOn);
    }

    @Test
    public void sendCecCommand_imageViewOn_sendMessageFails_resendMessage() {
        mNativeWrapper.setMessageSendResult(Constants.MESSAGE_IMAGE_VIEW_ON,
                SendMessageResult.BUSY);
        mTestLooper.dispatchAll();
        HdmiCecMessage imageViewOn = HdmiCecMessageBuilder.buildImageViewOn(
                mPlaybackDevice.getDeviceInfo().getLogicalAddress(), mPhysicalAddress);
        mHdmiControlService.sendCecCommand(imageViewOn);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(imageViewOn);

        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(SEND_COMMAND_RETRY_MS);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(imageViewOn);

        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(SEND_COMMAND_RETRY_MS);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(imageViewOn);

        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(SEND_COMMAND_RETRY_MS);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(imageViewOn);
    }

    @Test
    public void sendCecCommand_activeSource_sendMessageSuccess_noResendMessage() {
        mNativeWrapper.setMessageSendResult(Constants.MESSAGE_ACTIVE_SOURCE,
                SendMessageResult.SUCCESS);
        mTestLooper.dispatchAll();
        HdmiCecMessage activeSource = HdmiCecMessageBuilder.buildActiveSource(
                mPlaybackDevice.getDeviceInfo().getLogicalAddress(), mPhysicalAddress);
        mHdmiControlService.sendCecCommand(activeSource);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);

        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(SEND_COMMAND_RETRY_MS);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(activeSource);
    }

    @Test
    public void sendCecCommand_reportPhysicalAddress_sendMessageFails_noResendMessage() {
        mNativeWrapper.setMessageSendResult(Constants.MESSAGE_REPORT_PHYSICAL_ADDRESS,
                SendMessageResult.BUSY);
        mTestLooper.dispatchAll();
        HdmiCecMessage reportPhysicalAddress = HdmiCecMessageBuilder
                .buildReportPhysicalAddressCommand(
                        mPlaybackDevice.getDeviceInfo().getLogicalAddress(), mPhysicalAddress,
                        Constants.ALL_DEVICE_TYPES_PLAYBACK);
        mHdmiControlService.sendCecCommand(reportPhysicalAddress);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(reportPhysicalAddress);

        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(SEND_COMMAND_RETRY_MS);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(reportPhysicalAddress);
    }

    @Test
    public void sendCecCommand_inactiveSource_sendMessageFails_afterStandby_noResendMessage() {
        mNativeWrapper.setMessageSendResult(Constants.MESSAGE_INACTIVE_SOURCE,
                SendMessageResult.BUSY);
        mPlaybackDevice.setActiveSource(mPlaybackDevice.getDeviceInfo().getLogicalAddress(),
                mPhysicalAddress, "SendCecCommandActionPlaybackTest");
        mIsPowerStandby = true;
        mPlaybackDevice.onStandby(true, HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();
        HdmiCecMessage inactiveSourceMessage = HdmiCecMessageBuilder.buildInactiveSource(
                mPlaybackDevice.getDeviceInfo().getLogicalAddress(), mPhysicalAddress);
        assertThat(mNativeWrapper.getResultMessages()).contains(inactiveSourceMessage);

        mNativeWrapper.clearResultMessages();
        mHdmiControlService.onWakeUp(HdmiControlService.WAKE_UP_SCREEN_ON);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(inactiveSourceMessage);

        mTestLooper.moveTimeForward(SEND_COMMAND_RETRY_MS);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(inactiveSourceMessage);
    }

    @Test
    public void sendCecCommand_onStandby_removeAction_noResendMessage() {
        mNativeWrapper.setMessageSendResult(Constants.MESSAGE_INACTIVE_SOURCE,
                SendMessageResult.BUSY);
        mTestLooper.dispatchAll();
        HdmiCecMessage inactiveSourceMessage = HdmiCecMessageBuilder.buildInactiveSource(
                mPlaybackDevice.getDeviceInfo().getLogicalAddress(), mPhysicalAddress);
        mHdmiControlService.sendCecCommand(inactiveSourceMessage);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(inactiveSourceMessage);
        mHdmiControlService.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);

        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(SEND_COMMAND_RETRY_MS);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(inactiveSourceMessage);

        mTestLooper.dispatchAll();
        mHdmiControlService.onWakeUp(HdmiControlService.WAKE_UP_SCREEN_ON);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(inactiveSourceMessage);

        mTestLooper.moveTimeForward(SEND_COMMAND_RETRY_MS);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(inactiveSourceMessage);
    }
}
