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

import static com.android.server.hdmi.Constants.ABORT_UNRECOGNIZED_OPCODE;
import static com.android.server.hdmi.Constants.ADDR_AUDIO_SYSTEM;
import static com.android.server.hdmi.Constants.ADDR_BROADCAST;
import static com.android.server.hdmi.Constants.ADDR_INVALID;
import static com.android.server.hdmi.Constants.ADDR_TV;
import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiPortInfo;
import android.hardware.hdmi.IHdmiControlCallback;
import android.os.Handler;
import android.os.IPowerManager;
import android.os.IThermalService;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;
import android.sysprop.HdmiProperties;
import android.view.KeyEvent;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

@SmallTest
@Presubmit
@RunWith(JUnit4.class)
/** Tests for {@link HdmiCecLocalDevicePlayback} class. */
public class HdmiCecLocalDevicePlaybackTest {

    private static final int PORT_1 = 1;
    private static final HdmiDeviceInfo INFO_TV = new HdmiDeviceInfo(
            ADDR_TV, 0x0000, PORT_1, HdmiDeviceInfo.DEVICE_TV,
            0x1234, "TV",
            HdmiControlManager.POWER_STATUS_ON, HdmiControlManager.HDMI_CEC_VERSION_1_4_B);

    private HdmiControlService mHdmiControlService;
    private HdmiCecController mHdmiCecController;
    private HdmiCecLocalDevicePlayback mHdmiCecLocalDevicePlayback;
    private FakeNativeWrapper mNativeWrapper;
    private Looper mMyLooper;
    private TestLooper mTestLooper = new TestLooper();
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();
    private int mPlaybackPhysicalAddress;
    private int mPlaybackLogicalAddress;
    private boolean mWokenUp;
    private boolean mStandby;
    private boolean mActiveMediaSessionsPaused;

    @Mock
    private IPowerManager mIPowerManagerMock;
    @Mock
    private IThermalService mIThermalServiceMock;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        Context context = InstrumentationRegistry.getTargetContext();
        mMyLooper = mTestLooper.getLooper();
        PowerManager powerManager = new PowerManager(context, mIPowerManagerMock,
                mIThermalServiceMock, new Handler(mMyLooper));

        mHdmiControlService =
                new HdmiControlService(InstrumentationRegistry.getTargetContext()) {
                    @Override
                    void wakeUp() {
                        mWokenUp = true;
                    }

                    @Override
                    void standby() {
                        mStandby = true;
                        mHdmiControlService.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);
                    }

                    @Override
                    void pauseActiveMediaSessions() {
                        mActiveMediaSessionsPaused = true;
                    }

                    @Override
                    protected boolean isStandbyMessageReceived() {
                        return mStandby;
                    }

                    @Override
                    boolean isControlEnabled() {
                        return true;
                    }

                    @Override
                    boolean isPlaybackDevice() {
                        return true;
                    }

                    @Override
                    protected void writeStringSystemProperty(String key, String value) {
                        // do nothing
                    }

                    @Override
                    boolean isPowerStandby() {
                        return false;
                    }

                    @Override
                    protected PowerManager getPowerManager() {
                        return powerManager;
                    }
                };

        mHdmiCecLocalDevicePlayback = new HdmiCecLocalDevicePlayback(mHdmiControlService);
        mHdmiCecLocalDevicePlayback.init();
        mHdmiControlService.setHdmiCecConfig(new FakeHdmiCecConfig(context));
        mHdmiControlService.setIoLooper(mMyLooper);
        mNativeWrapper = new FakeNativeWrapper();
        mHdmiCecController = HdmiCecController.createWithNativeWrapper(
                mHdmiControlService, mNativeWrapper, mHdmiControlService.getAtomWriter());
        mHdmiControlService.setCecController(mHdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        mHdmiControlService.setMessageValidator(new HdmiCecMessageValidator(mHdmiControlService));
        mLocalDevices.add(mHdmiCecLocalDevicePlayback);
        HdmiPortInfo[] hdmiPortInfos = new HdmiPortInfo[1];
        hdmiPortInfos[0] =
                new HdmiPortInfo(1, HdmiPortInfo.PORT_OUTPUT, 0x0000, true, false, false);
        mNativeWrapper.setPortInfo(hdmiPortInfos);
        mNativeWrapper.setPortConnectionStatus(1, true);
        mHdmiControlService.initService();
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mPlaybackPhysicalAddress = 0x2000;
        mNativeWrapper.setPhysicalAddress(mPlaybackPhysicalAddress);
        mTestLooper.dispatchAll();
        mPlaybackLogicalAddress = mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress();
        mHdmiControlService.getHdmiCecNetwork().addCecDevice(INFO_TV);
        mNativeWrapper.clearResultMessages();
    }

    @Test
    public void handleRoutingChange_None() {
        mHdmiCecLocalDevicePlayback.mPlaybackDeviceActionOnRoutingControl =
                HdmiProperties.playback_device_action_on_routing_control_values.NONE;

        mWokenUp = false;

        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000,
                        mPlaybackPhysicalAddress);

        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                        mPlaybackPhysicalAddress);

        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingChange(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mWokenUp).isFalse();
        assertThat(mNativeWrapper.getResultMessages().contains(expectedMessage)).isFalse();
    }

    @Test
    public void handleRoutingInformation_None() {
        mHdmiCecLocalDevicePlayback.mPlaybackDeviceActionOnRoutingControl =
                HdmiProperties.playback_device_action_on_routing_control_values.NONE;

        mWokenUp = false;

        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV,
                        mPlaybackPhysicalAddress);

        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                        mPlaybackPhysicalAddress);

        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingInformation(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mWokenUp).isFalse();
        assertThat(mNativeWrapper.getResultMessages().contains(expectedMessage)).isFalse();
    }

    @Test
    public void handleRoutingChange_WakeUpOnly() {
        mHdmiCecLocalDevicePlayback.mPlaybackDeviceActionOnRoutingControl =
                HdmiProperties.playback_device_action_on_routing_control_values.WAKE_UP_ONLY;

        mWokenUp = false;

        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000,
                        mPlaybackPhysicalAddress);

        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                        mPlaybackPhysicalAddress);

        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingChange(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mWokenUp).isTrue();
        assertThat(mNativeWrapper.getResultMessages().contains(expectedMessage)).isFalse();
    }

    @Test
    public void handleRoutingInformation_WakeUpOnly() {
        mHdmiCecLocalDevicePlayback.mPlaybackDeviceActionOnRoutingControl =
                HdmiProperties.playback_device_action_on_routing_control_values.WAKE_UP_ONLY;

        mWokenUp = false;

        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV,
                        mPlaybackPhysicalAddress);

        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                        mPlaybackPhysicalAddress);

        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingInformation(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mWokenUp).isTrue();
        assertThat(mNativeWrapper.getResultMessages().contains(expectedMessage)).isFalse();
    }

    @Test
    public void handleRoutingChange_WakeUpAndSendActiveSource() {
        mHdmiCecLocalDevicePlayback.mPlaybackDeviceActionOnRoutingControl =
                HdmiProperties
                        .playback_device_action_on_routing_control_values
                        .WAKE_UP_AND_SEND_ACTIVE_SOURCE;

        mWokenUp = false;

        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000,
                        mPlaybackPhysicalAddress);

        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                        mPlaybackPhysicalAddress);

        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingChange(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mWokenUp).isTrue();
        assertThat(mNativeWrapper.getResultMessages()).contains(expectedMessage);
    }

    @Test
    public void handleRoutingInformation_WakeUpAndSendActiveSource() {
        mHdmiCecLocalDevicePlayback.mPlaybackDeviceActionOnRoutingControl =
                HdmiProperties
                        .playback_device_action_on_routing_control_values
                        .WAKE_UP_AND_SEND_ACTIVE_SOURCE;

        mWokenUp = false;

        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV,
                        mPlaybackPhysicalAddress);

        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                        mPlaybackPhysicalAddress);

        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingInformation(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mWokenUp).isTrue();
        assertThat(mNativeWrapper.getResultMessages()).contains(expectedMessage);
    }

    @Test
    public void handleRoutingChange_otherDevice_None() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_NONE);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mStandby = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000, 0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingChange(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().physicalAddress).isEqualTo(
                0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().logicalAddress).isEqualTo(
                ADDR_INVALID);
        assertThat(mStandby).isFalse();
    }

    @Test
    public void handleRoutingChange_sameDevice_None_ActiveSource() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_NONE);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mStandby = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000,
                        mPlaybackPhysicalAddress);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingChange(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isTrue();
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().physicalAddress).isEqualTo(
                mPlaybackPhysicalAddress);
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().logicalAddress).isEqualTo(
                mPlaybackLogicalAddress);
        assertThat(mStandby).isFalse();
    }

    @Test
    public void handleRoutingChange_sameDevice_None_InactiveSource() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_NONE);
        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");
        mStandby = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000,
                        mPlaybackPhysicalAddress);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingChange(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().physicalAddress).isEqualTo(
                mPlaybackPhysicalAddress);
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().logicalAddress).isEqualTo(
                ADDR_INVALID);
        assertThat(mStandby).isFalse();
    }

    @Test
    public void handleRoutingChange_otherDevice_StandbyNow() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mStandby = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000, 0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingChange(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mStandby).isTrue();
    }

    @Test
    public void handleRoutingChange_otherDevice_StandbyNow_InactiveSource() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");
        mStandby = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000, 0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingChange(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mStandby).isFalse();
    }

    @Test
    public void handleRoutingChange_sameDevice_StandbyNow_ActiveSource() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mStandby = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000,
                        mPlaybackPhysicalAddress);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingChange(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isTrue();
        assertThat(mStandby).isFalse();
    }

    @Test
    public void handleRoutingChange_otherDevice_ActiveSource_mediaSessionsPaused() {
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mActiveMediaSessionsPaused = false;
        HdmiCecMessage message = HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000,
                0x5000);
        mHdmiCecLocalDevicePlayback.dispatchMessage(message);
        mTestLooper.dispatchAll();
        assertThat(mActiveMediaSessionsPaused).isTrue();
    }

    @Test
    public void handleRoutingChange_otherDevice_InactiveSource_mediaSessionsNotPaused() {
        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");
        mActiveMediaSessionsPaused = false;
        HdmiCecMessage message = HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000,
                0x5000);
        mHdmiCecLocalDevicePlayback.dispatchMessage(message);
        mTestLooper.dispatchAll();
        assertThat(mActiveMediaSessionsPaused).isFalse();
    }

    @Test
    public void handleRoutingChange_sameDevice_ActiveSource_mediaSessionsNotPaused() {
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mActiveMediaSessionsPaused = false;
        HdmiCecMessage message = HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000,
                mPlaybackPhysicalAddress);
        mHdmiCecLocalDevicePlayback.dispatchMessage(message);
        mTestLooper.dispatchAll();
        assertThat(mActiveMediaSessionsPaused).isFalse();
    }

    @Test
    public void handleRoutingChange_sameDevice_InactiveSource_mediaSessionsNotPaused() {
        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");
        mActiveMediaSessionsPaused = false;
        HdmiCecMessage message = HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000,
                mPlaybackPhysicalAddress);
        mHdmiCecLocalDevicePlayback.dispatchMessage(message);
        mTestLooper.dispatchAll();
        assertThat(mActiveMediaSessionsPaused).isFalse();
    }

    @Test
    public void handleRoutingInformation_otherDevice_None() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_NONE);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mStandby = false;
        HdmiCecMessage message = HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV, 0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingInformation(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().physicalAddress).isEqualTo(
                0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().logicalAddress).isEqualTo(
                ADDR_INVALID);
        assertThat(mStandby).isFalse();
    }

    @Test
    public void handleRoutingInformation_sameDevice_None_ActiveSource() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_NONE);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mStandby = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV,
                        mPlaybackPhysicalAddress);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingInformation(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isTrue();
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().physicalAddress).isEqualTo(
                mPlaybackPhysicalAddress);
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().logicalAddress).isEqualTo(
                mPlaybackLogicalAddress);
        assertThat(mStandby).isFalse();
    }

    @Test
    public void handleRoutingInformation_sameDevice_None_InactiveSource() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_NONE);
        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");
        mStandby = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV,
                        mPlaybackPhysicalAddress);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingInformation(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().physicalAddress).isEqualTo(
                mPlaybackPhysicalAddress);
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().logicalAddress).isEqualTo(
                ADDR_INVALID);
        assertThat(mStandby).isFalse();
    }

    @Test
    public void handleRoutingInformation_otherDevice_StandbyNow() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mStandby = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV, 0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingInformation(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mStandby).isTrue();
    }

    @Test
    public void handleRoutingInformation_otherDevice_StandbyNow_InactiveSource() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");
        mStandby = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV, 0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingInformation(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mStandby).isFalse();
    }

    @Test
    public void handleRoutingInformation_sameDevice_StandbyNow_ActiveSource() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mStandby = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV,
                        mPlaybackPhysicalAddress);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingInformation(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isTrue();
        assertThat(mStandby).isFalse();
    }

    @Test
    public void handleRoutingInformation_otherDevice_ActiveSource_mediaSessionsPaused() {
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mActiveMediaSessionsPaused = false;
        HdmiCecMessage message = HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV, 0x5000);
        mHdmiCecLocalDevicePlayback.dispatchMessage(message);
        mTestLooper.dispatchAll();
        assertThat(mActiveMediaSessionsPaused).isTrue();
    }

    @Test
    public void handleRoutingInformation_otherDevice_InactiveSource_mediaSessionsNotPaused() {
        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");
        mActiveMediaSessionsPaused = false;
        HdmiCecMessage message = HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV, 0x5000);
        mHdmiCecLocalDevicePlayback.dispatchMessage(message);
        mTestLooper.dispatchAll();
        assertThat(mActiveMediaSessionsPaused).isFalse();
    }

    @Test
    public void handleRoutingInformation_sameDevice_ActiveSource_mediaSessionsNotPaused() {
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mActiveMediaSessionsPaused = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV, mPlaybackPhysicalAddress);
        mHdmiCecLocalDevicePlayback.dispatchMessage(message);
        mTestLooper.dispatchAll();
        assertThat(mActiveMediaSessionsPaused).isFalse();
    }

    @Test
    public void handleRoutingInformation_sameDevice_InactiveSource_mediaSessionsNotPaused() {
        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");
        mActiveMediaSessionsPaused = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV, mPlaybackPhysicalAddress);
        mHdmiCecLocalDevicePlayback.dispatchMessage(message);
        mTestLooper.dispatchAll();
        assertThat(mActiveMediaSessionsPaused).isFalse();
    }

    @Test
    public void handleSetStreamPath() {
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV, 0x2100);
        assertThat(mHdmiCecLocalDevicePlayback.handleSetStreamPath(message))
                .isEqualTo(Constants.HANDLED);
    }

    @Test
    public void handleSetSystemAudioModeOn_audioSystemBroadcast() {
        mHdmiControlService.setSystemAudioActivated(false);
        assertThat(mHdmiCecLocalDevicePlayback.mService.isSystemAudioActivated()).isFalse();
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildSetSystemAudioMode(
                        Constants.ADDR_AUDIO_SYSTEM, Constants.ADDR_BROADCAST, true);
        assertThat(mHdmiCecLocalDevicePlayback.handleSetSystemAudioMode(message))
                .isEqualTo(Constants.HANDLED);
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
        assertThat(mHdmiCecLocalDevicePlayback.handleSetSystemAudioMode(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.mService.isSystemAudioActivated()).isTrue();
    }

    @Test
    public void handleSystemAudioModeStatusOn_DirectlyToLocalDeviceFromAudioSystem() {
        mHdmiControlService.setSystemAudioActivated(false);
        assertThat(mHdmiCecLocalDevicePlayback.mService.isSystemAudioActivated()).isFalse();
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildReportSystemAudioMode(
                        Constants.ADDR_AUDIO_SYSTEM, mHdmiCecLocalDevicePlayback.mAddress, true);
        assertThat(mHdmiCecLocalDevicePlayback.handleSystemAudioModeStatus(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.mService.isSystemAudioActivated()).isTrue();
    }

    @Test
    public void doNotWakeUpOnHotPlug_PlugIn() {
        mWokenUp = false;
        mHdmiCecLocalDevicePlayback.onHotplug(0, true);
        assertThat(mWokenUp).isFalse();
    }

    @Test
    public void doNotWakeUpOnHotPlug_PlugOut() {
        mWokenUp = false;
        mHdmiCecLocalDevicePlayback.onHotplug(0, false);
        assertThat(mWokenUp).isFalse();
    }

    @Test
    public void handleOnStandby_ScreenOff_NotActiveSource_ToTv() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_TV);
        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP,
                HdmiControlManager.TV_SEND_STANDBY_ON_SLEEP_ENABLED);
        mHdmiCecLocalDevicePlayback.onStandby(false, HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();

        HdmiCecMessage standbyMessageToTv = HdmiCecMessageBuilder.buildStandby(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV);
        HdmiCecMessage standbyMessageBroadcast = HdmiCecMessageBuilder.buildStandby(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_BROADCAST);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageToTv);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageBroadcast);
    }

    @Test
    public void handleOnStandby_ScreenOff_NotActiveSource_Broadcast() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_BROADCAST);
        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP,
                HdmiControlManager.TV_SEND_STANDBY_ON_SLEEP_ENABLED);
        mHdmiCecLocalDevicePlayback.onStandby(false, HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();

        HdmiCecMessage standbyMessageToTv = HdmiCecMessageBuilder.buildStandby(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV);
        HdmiCecMessage standbyMessageBroadcast = HdmiCecMessageBuilder.buildStandby(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_BROADCAST);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageToTv);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageBroadcast);
    }

    @Test
    public void handleOnStandby_ScreenOff_NotActiveSource_None() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_NONE);
        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP,
                HdmiControlManager.TV_SEND_STANDBY_ON_SLEEP_ENABLED);
        mHdmiCecLocalDevicePlayback.onStandby(false, HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();

        HdmiCecMessage standbyMessageToTv = HdmiCecMessageBuilder.buildStandby(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV);
        HdmiCecMessage standbyMessageBroadcast = HdmiCecMessageBuilder.buildStandby(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_BROADCAST);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageToTv);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageBroadcast);
    }

    @Test
    public void handleOnStandby_ScreenOff_ActiveSource_ToTv() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_TV);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP,
                HdmiControlManager.TV_SEND_STANDBY_ON_SLEEP_ENABLED);
        mHdmiCecLocalDevicePlayback.onStandby(false, HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();

        HdmiCecMessage standbyMessageToTv = HdmiCecMessageBuilder.buildStandby(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV);
        HdmiCecMessage standbyMessageBroadcast = HdmiCecMessageBuilder.buildStandby(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_BROADCAST);

        assertThat(mNativeWrapper.getResultMessages()).contains(standbyMessageToTv);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageBroadcast);
    }

    @Test
    public void handleOnStandby_ScreenOff_ActiveSource_Broadcast() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_BROADCAST);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP,
                HdmiControlManager.TV_SEND_STANDBY_ON_SLEEP_ENABLED);
        mHdmiCecLocalDevicePlayback.onStandby(false, HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();

        HdmiCecMessage standbyMessageToTv = HdmiCecMessageBuilder.buildStandby(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV);
        HdmiCecMessage standbyMessageBroadcast = HdmiCecMessageBuilder.buildStandby(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_BROADCAST);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageToTv);
        assertThat(mNativeWrapper.getResultMessages()).contains(standbyMessageBroadcast);
    }

    @Test
    public void handleOnStandby_ScreenOff_ActiveSource_None() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_NONE);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP,
                HdmiControlManager.TV_SEND_STANDBY_ON_SLEEP_ENABLED);
        mHdmiCecLocalDevicePlayback.onStandby(false, HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();

        HdmiCecMessage standbyMessageToTv = HdmiCecMessageBuilder.buildStandby(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV);
        HdmiCecMessage standbyMessageBroadcast = HdmiCecMessageBuilder.buildStandby(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_BROADCAST);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageToTv);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageBroadcast);
    }

    @Test
    public void handleOnInitializeCecComplete_ByEnableCec() {
        mHdmiCecLocalDevicePlayback.onInitializeCecComplete(
                mHdmiControlService.INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();

        HdmiCecMessage activeSource =
                HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                        mPlaybackPhysicalAddress);
        HdmiCecMessage textViewOn =
                HdmiCecMessageBuilder.buildTextViewOn(mPlaybackLogicalAddress,
                        ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(textViewOn);
    }

    @Test
    public void handleOnInitializeCecComplete_ByBootUp() {
        mHdmiCecLocalDevicePlayback.onInitializeCecComplete(
                mHdmiControlService.INITIATED_BY_BOOT_UP);
        mTestLooper.dispatchAll();

        HdmiCecMessage activeSource =
                HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                        mPlaybackPhysicalAddress);
        HdmiCecMessage textViewOn =
                HdmiCecMessageBuilder.buildTextViewOn(mPlaybackLogicalAddress,
                        ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(textViewOn);
    }

    @Test
    public void handleOnInitializeCecComplete_ByScreenOn() {
        mHdmiCecLocalDevicePlayback.onInitializeCecComplete(
                mHdmiControlService.INITIATED_BY_SCREEN_ON);
        mTestLooper.dispatchAll();

        HdmiCecMessage activeSource =
                HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                        mPlaybackPhysicalAddress);
        HdmiCecMessage textViewOn =
                HdmiCecMessageBuilder.buildTextViewOn(mPlaybackLogicalAddress,
                        ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn);
    }

    @Test
    public void handleOnInitializeCecComplete_ByWakeUpMessage() {
        mHdmiCecLocalDevicePlayback.onInitializeCecComplete(
                mHdmiControlService.INITIATED_BY_WAKE_UP_MESSAGE);
        mTestLooper.dispatchAll();

        HdmiCecMessage activeSource =
                HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                        mPlaybackPhysicalAddress);
        HdmiCecMessage textViewOn =
                HdmiCecMessageBuilder.buildTextViewOn(mPlaybackLogicalAddress,
                        ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(textViewOn);
    }

    @Test
    public void handleOnInitializeCecComplete_ByHotplug() {
        mHdmiCecLocalDevicePlayback.onInitializeCecComplete(
                mHdmiControlService.INITIATED_BY_HOTPLUG);
        mTestLooper.dispatchAll();

        HdmiCecMessage activeSource =
                HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                        mPlaybackPhysicalAddress);
        HdmiCecMessage textViewOn =
                HdmiCecMessageBuilder.buildTextViewOn(mPlaybackLogicalAddress,
                        ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(textViewOn);
    }

    @Test
    public void handleActiveSource_ActiveSource_None() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_NONE);
        mStandby = false;
        HdmiCecMessage message = HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress);
        assertThat(mHdmiCecLocalDevicePlayback.handleActiveSource(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mStandby).isFalse();
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isTrue();
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().physicalAddress).isEqualTo(
                mPlaybackPhysicalAddress);
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().logicalAddress).isEqualTo(
                mPlaybackLogicalAddress);
    }

    @Test
    public void handleActiveSource_notActiveSource_None() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_NONE);
        mStandby = false;
        HdmiCecMessage message = HdmiCecMessageBuilder.buildActiveSource(ADDR_TV, 0x0000);
        assertThat(mHdmiCecLocalDevicePlayback.handleActiveSource(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mStandby).isFalse();
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().physicalAddress).isEqualTo(
                0x0000);
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().logicalAddress).isEqualTo(
                ADDR_TV);
    }

    @Test
    public void handleActiveSource_ActiveSource_StandbyNow() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
        mStandby = false;
        HdmiCecMessage message = HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress);
        assertThat(mHdmiCecLocalDevicePlayback.handleActiveSource(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mStandby).isFalse();
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isTrue();
    }

    @Test
    public void handleActiveSource_notActiveSource_StandbyNow() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
        mStandby = false;
        HdmiCecMessage message = HdmiCecMessageBuilder.buildActiveSource(ADDR_TV, 0x0000);
        assertThat(mHdmiCecLocalDevicePlayback.handleActiveSource(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mStandby).isTrue();
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
    }

    @Test
    public void handleActiveSource_otherDevice_ActiveSource_mediaSessionsPaused() {
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mActiveMediaSessionsPaused = false;
        HdmiCecMessage message = HdmiCecMessageBuilder.buildActiveSource(ADDR_TV, 0x0000);
        mHdmiCecLocalDevicePlayback.dispatchMessage(message);
        mTestLooper.dispatchAll();
        assertThat(mActiveMediaSessionsPaused).isTrue();
    }

    @Test
    public void handleActiveSource_otherDevice_InactiveSource_mediaSessionsNotPaused() {
        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");
        mActiveMediaSessionsPaused = false;
        HdmiCecMessage message = HdmiCecMessageBuilder.buildActiveSource(ADDR_TV, 0x0000);
        mHdmiCecLocalDevicePlayback.dispatchMessage(message);
        mTestLooper.dispatchAll();
        assertThat(mActiveMediaSessionsPaused).isFalse();
    }

    @Test
    public void handleActiveSource_sameDevice_ActiveSource_mediaSessionsNotPaused() {
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mActiveMediaSessionsPaused = false;
        HdmiCecMessage message = HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress);
        mHdmiCecLocalDevicePlayback.dispatchMessage(message);
        mTestLooper.dispatchAll();
        assertThat(mActiveMediaSessionsPaused).isFalse();
    }

    @Test
    public void handleActiveSource_sameDevice_InactiveSource_mediaSessionsNotPaused() {
        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");
        mActiveMediaSessionsPaused = false;
        HdmiCecMessage message = HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress);
        mHdmiCecLocalDevicePlayback.dispatchMessage(message);
        mTestLooper.dispatchAll();
        assertThat(mActiveMediaSessionsPaused).isFalse();
    }

    @Test
    public void losingActiveSource_standbyNow_verifyStandbyMessageIsSentOnNextStandby() {
        // As described in b/161097846.
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_BROADCAST);
        mStandby = false;
        // 1. DUT is <AS>.
        HdmiCecMessage message1 = HdmiCecMessageBuilder.buildActiveSource(
                mHdmiCecLocalDevicePlayback.mAddress, mPlaybackPhysicalAddress);
        assertThat(mHdmiCecLocalDevicePlayback.handleActiveSource(message1))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isTrue();
        assertThat(mStandby).isFalse();
        // 2. DUT loses <AS> and goes to sleep.
        HdmiCecMessage message2 = HdmiCecMessageBuilder.buildActiveSource(ADDR_TV, 0x0000);
        assertThat(mHdmiCecLocalDevicePlayback.handleActiveSource(message2))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mStandby).isTrue();
        // 3. DUT becomes <AS> again.
        mWokenUp = false;
        HdmiCecMessage setStreamPath = HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV,
                mPlaybackPhysicalAddress);
        mHdmiCecLocalDevicePlayback.dispatchMessage(setStreamPath);
        mTestLooper.dispatchAll();
        HdmiCecMessage activeSource = HdmiCecMessageBuilder.buildActiveSource(
                mHdmiCecLocalDevicePlayback.mAddress, mPlaybackPhysicalAddress);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mWokenUp).isTrue();
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isTrue();
        // 4. DUT turned off.
        mHdmiControlService.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();
        HdmiCecMessage standbyMessageBroadcast = HdmiCecMessageBuilder.buildStandby(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_BROADCAST);
        assertThat(mNativeWrapper.getResultMessages()).contains(standbyMessageBroadcast);
    }

    @Test
    public void sendVolumeKeyEvent_up_volumeEnabled() {
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, true);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, false);
        mTestLooper.dispatchAll();

        HdmiCecMessage keyPressed = HdmiCecMessageBuilder.buildUserControlPressed(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV,
                HdmiCecKeycode.CEC_KEYCODE_VOLUME_UP);
        HdmiCecMessage keyReleased = HdmiCecMessageBuilder.buildUserControlReleased(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(keyPressed);
        assertThat(mNativeWrapper.getResultMessages()).contains(keyReleased);
    }

    @Test
    public void sendVolumeKeyEvent_down_volumeEnabled() {
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN, true);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN, false);
        mTestLooper.dispatchAll();

        HdmiCecMessage keyPressed = HdmiCecMessageBuilder.buildUserControlPressed(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV,
                HdmiCecKeycode.CEC_KEYCODE_VOLUME_DOWN);
        HdmiCecMessage keyReleased = HdmiCecMessageBuilder.buildUserControlReleased(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(keyPressed);
        assertThat(mNativeWrapper.getResultMessages()).contains(keyReleased);
    }

    @Test
    public void sendVolumeKeyEvent_mute_volumeEnabled() {
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_MUTE, true);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_MUTE, false);
        mTestLooper.dispatchAll();

        HdmiCecMessage keyPressed = HdmiCecMessageBuilder.buildUserControlPressed(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV,
                HdmiCecKeycode.CEC_KEYCODE_MUTE);
        HdmiCecMessage keyReleased = HdmiCecMessageBuilder.buildUserControlReleased(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(keyPressed);
        assertThat(mNativeWrapper.getResultMessages()).contains(keyReleased);
    }

    @Test
    public void sendVolumeKeyEvent_up_volumeDisabled() {
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, true);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, false);
        mTestLooper.dispatchAll();

        HdmiCecMessage keyPressed = HdmiCecMessageBuilder.buildUserControlPressed(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV,
                HdmiCecKeycode.CEC_KEYCODE_VOLUME_UP);
        HdmiCecMessage keyReleased = HdmiCecMessageBuilder.buildUserControlReleased(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(keyPressed);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(keyReleased);
    }

    @Test
    public void sendVolumeKeyEvent_down_volumeDisabled() {
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN, true);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN, false);
        mTestLooper.dispatchAll();

        HdmiCecMessage keyPressed = HdmiCecMessageBuilder.buildUserControlPressed(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV,
                HdmiCecKeycode.CEC_KEYCODE_VOLUME_UP);
        HdmiCecMessage keyReleased = HdmiCecMessageBuilder.buildUserControlReleased(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(keyPressed);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(keyReleased);
    }

    @Test
    public void sendVolumeKeyEvent_mute_volumeDisabled() {
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_MUTE, true);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_MUTE, false);
        mTestLooper.dispatchAll();

        HdmiCecMessage keyPressed = HdmiCecMessageBuilder.buildUserControlPressed(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV,
                HdmiCecKeycode.CEC_KEYCODE_VOLUME_UP);
        HdmiCecMessage keyReleased = HdmiCecMessageBuilder.buildUserControlReleased(
                mHdmiCecLocalDevicePlayback.mAddress, ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(keyPressed);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(keyReleased);
    }

    @Test
    public void sendVolumeKeyEvent_toTv_activeSource() {
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        mHdmiControlService.setSystemAudioActivated(false);
        mHdmiControlService.setActiveSource(mPlaybackLogicalAddress, mPlaybackPhysicalAddress,
                "HdmiCecLocalDevicePlaybackTest");

        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, true);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, false);

        HdmiCecMessage pressed = HdmiCecMessageBuilder.buildUserControlPressed(
                mPlaybackLogicalAddress, ADDR_TV, HdmiCecKeycode.CEC_KEYCODE_VOLUME_UP);
        HdmiCecMessage released = HdmiCecMessageBuilder.buildUserControlReleased(
                mPlaybackLogicalAddress, ADDR_TV);
        mTestLooper.dispatchAll();

        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isTrue();
        assertThat(mNativeWrapper.getResultMessages()).containsAtLeast(pressed, released);
    }

    @Test
    public void sendVolumeKeyEvent_toAudio_activeSource() {
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        mHdmiControlService.setSystemAudioActivated(true);
        mHdmiControlService.setActiveSource(mPlaybackLogicalAddress, mPlaybackPhysicalAddress,
                "HdmiCecLocalDevicePlaybackTest");

        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, true);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, false);

        HdmiCecMessage pressed = HdmiCecMessageBuilder.buildUserControlPressed(
                mPlaybackLogicalAddress, ADDR_AUDIO_SYSTEM, HdmiCecKeycode.CEC_KEYCODE_VOLUME_UP);
        HdmiCecMessage released = HdmiCecMessageBuilder.buildUserControlReleased(
                mPlaybackLogicalAddress, ADDR_AUDIO_SYSTEM);
        mTestLooper.dispatchAll();

        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isTrue();
        assertThat(mNativeWrapper.getResultMessages()).containsAtLeast(pressed, released);
    }

    @Test
    public void sendVolumeKeyEvent_toTv_inactiveSource() {
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        mHdmiControlService.setSystemAudioActivated(false);
        mHdmiControlService.setActiveSource(ADDR_TV, 0x0000, "HdmiCecLocalDevicePlaybackTest");

        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, true);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, false);

        HdmiCecMessage pressed = HdmiCecMessageBuilder.buildUserControlPressed(
                mPlaybackLogicalAddress, ADDR_TV, HdmiCecKeycode.CEC_KEYCODE_VOLUME_UP);
        HdmiCecMessage released = HdmiCecMessageBuilder.buildUserControlReleased(
                mPlaybackLogicalAddress, ADDR_TV);
        mTestLooper.dispatchAll();

        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mNativeWrapper.getResultMessages()).containsAtLeast(pressed, released);
    }

    @Test
    public void sendVolumeKeyEvent_toAudio_inactiveSource() {
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        mHdmiControlService.setSystemAudioActivated(true);
        mHdmiControlService.setActiveSource(ADDR_TV, 0x0000, "HdmiCecLocalDevicePlaybackTest");

        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, true);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, false);

        HdmiCecMessage pressed = HdmiCecMessageBuilder.buildUserControlPressed(
                mPlaybackLogicalAddress, ADDR_AUDIO_SYSTEM, HdmiCecKeycode.CEC_KEYCODE_VOLUME_UP);
        HdmiCecMessage released = HdmiCecMessageBuilder.buildUserControlReleased(
                mPlaybackLogicalAddress, ADDR_AUDIO_SYSTEM);
        mTestLooper.dispatchAll();

        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mNativeWrapper.getResultMessages()).containsAtLeast(pressed, released);
    }

    @Test
    public void handleSetStreamPath_broadcastsActiveSource() {
        HdmiCecMessage setStreamPath = HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV,
                mPlaybackPhysicalAddress);
        mHdmiCecLocalDevicePlayback.dispatchMessage(setStreamPath);
        mTestLooper.dispatchAll();

        HdmiCecMessage activeSource = HdmiCecMessageBuilder.buildActiveSource(
                mHdmiCecLocalDevicePlayback.mAddress, mPlaybackPhysicalAddress);

        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
    }

    @Test
    public void handleSetStreamPath_afterHotplug_broadcastsActiveSource() {
        mNativeWrapper.onHotplugEvent(1, false);
        mNativeWrapper.onHotplugEvent(1, true);
        mTestLooper.dispatchAll();

        HdmiCecMessage setStreamPath = HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV,
                mPlaybackPhysicalAddress);
        mHdmiCecLocalDevicePlayback.dispatchMessage(setStreamPath);
        mTestLooper.dispatchAll();

        HdmiCecMessage activeSource = HdmiCecMessageBuilder.buildActiveSource(
                mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                mPlaybackPhysicalAddress);

        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
    }

    @Test
    public void handleSetStreamPath_afterHotplug_hasCorrectActiveSource() {
        mNativeWrapper.onHotplugEvent(1, false);
        mNativeWrapper.onHotplugEvent(1, true);

        HdmiCecMessage setStreamPath = HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV,
                mPlaybackPhysicalAddress);
        mHdmiCecLocalDevicePlayback.dispatchMessage(setStreamPath);
        mTestLooper.dispatchAll();

        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().physicalAddress).isEqualTo(
                mPlaybackPhysicalAddress);
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().logicalAddress).isEqualTo(
                mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress());
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isTrue();
    }

    @Test
    public void handleSetStreamPath_otherDevice_None() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_NONE);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mStandby = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV, 0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.handleSetStreamPath(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().physicalAddress).isEqualTo(
                0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().logicalAddress).isEqualTo(
                ADDR_INVALID);
        assertThat(mStandby).isFalse();
    }

    @Test
    public void handleSetStreamPath_otherDevice_StandbyNow() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mStandby = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV, 0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.handleSetStreamPath(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mStandby).isTrue();
    }

    @Test
    public void handleSetStreamPath_otherDevice_StandbyNow_InactiveSource() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");
        mStandby = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV, 0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.handleSetStreamPath(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mStandby).isFalse();
    }

    @Test
    public void handleSetStreamPath_otherDevice_ActiveSource_mediaSessionsPaused() {
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mActiveMediaSessionsPaused = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV, 0x5000);
        mHdmiCecLocalDevicePlayback.dispatchMessage(message);
        mTestLooper.dispatchAll();
        assertThat(mActiveMediaSessionsPaused).isTrue();
    }

    @Test
    public void handleSetStreamPath_otherDevice_InactiveSource_mediaSessionsNotPaused() {
        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");
        mActiveMediaSessionsPaused = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV, 0x5000);
        mHdmiCecLocalDevicePlayback.dispatchMessage(message);
        mTestLooper.dispatchAll();
        assertThat(mActiveMediaSessionsPaused).isFalse();
    }

    @Test
    public void handleSetStreamPath_sameDevice_ActiveSource_mediaSessionsNotPaused() {
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mActiveMediaSessionsPaused = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV, mPlaybackPhysicalAddress);
        mHdmiCecLocalDevicePlayback.dispatchMessage(message);
        mTestLooper.dispatchAll();
        assertThat(mActiveMediaSessionsPaused).isFalse();
    }

    @Test
    public void handleSetStreamPath_sameDevice_InactiveSource_mediaSessionsNotPaused() {
        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");
        mActiveMediaSessionsPaused = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV, mPlaybackPhysicalAddress);
        mHdmiCecLocalDevicePlayback.dispatchMessage(message);
        mTestLooper.dispatchAll();
        assertThat(mActiveMediaSessionsPaused).isFalse();
    }

    @Test
    public void oneTouchPlay_SendStandbyOnSleepToTv() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_TV);
        mHdmiControlService.oneTouchPlay(new IHdmiControlCallback.Stub() {
            @Override
            public void onComplete(int result) {
            }
        });
        mTestLooper.dispatchAll();

        HdmiCecMessage textViewOn = HdmiCecMessageBuilder.buildTextViewOn(mPlaybackLogicalAddress,
                ADDR_TV);
        HdmiCecMessage activeSource = HdmiCecMessageBuilder.buildActiveSource(
                mPlaybackLogicalAddress, mPlaybackPhysicalAddress);
        HdmiCecMessage systemAudioModeRequest = HdmiCecMessageBuilder.buildSystemAudioModeRequest(
                mPlaybackLogicalAddress, ADDR_AUDIO_SYSTEM, mPlaybackPhysicalAddress, true);
        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(systemAudioModeRequest);
    }

    @Test
    public void oneTouchPlay_SendStandbyOnSleepBroadcast() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_BROADCAST);
        mHdmiControlService.oneTouchPlay(new IHdmiControlCallback.Stub() {
            @Override
            public void onComplete(int result) {
            }
        });
        mTestLooper.dispatchAll();

        HdmiCecMessage textViewOn = HdmiCecMessageBuilder.buildTextViewOn(mPlaybackLogicalAddress,
                ADDR_TV);
        HdmiCecMessage activeSource = HdmiCecMessageBuilder.buildActiveSource(
                mPlaybackLogicalAddress, mPlaybackPhysicalAddress);
        HdmiCecMessage systemAudioModeRequest = HdmiCecMessageBuilder.buildSystemAudioModeRequest(
                mPlaybackLogicalAddress, ADDR_AUDIO_SYSTEM, mPlaybackPhysicalAddress, true);
        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).contains(systemAudioModeRequest);
    }

    @Test
    public void oneTouchPlay_SendStandbyOnSleepNone() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_NONE);
        mHdmiControlService.oneTouchPlay(new IHdmiControlCallback.Stub() {
            @Override
            public void onComplete(int result) {
            }
        });
        mTestLooper.dispatchAll();

        HdmiCecMessage textViewOn = HdmiCecMessageBuilder.buildTextViewOn(mPlaybackLogicalAddress,
                ADDR_TV);
        HdmiCecMessage activeSource = HdmiCecMessageBuilder.buildActiveSource(
                mPlaybackLogicalAddress, mPlaybackPhysicalAddress);
        HdmiCecMessage systemAudioModeRequest = HdmiCecMessageBuilder.buildSystemAudioModeRequest(
                mPlaybackLogicalAddress, ADDR_AUDIO_SYSTEM, mPlaybackPhysicalAddress, true);
        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(systemAudioModeRequest);
    }

    @Test
    public void getActiveSource_noActiveSource() {
        mHdmiControlService.setActiveSource(Constants.ADDR_UNREGISTERED,
                Constants.INVALID_PHYSICAL_ADDRESS, "HdmiControlServiceTest");

        assertThat(mHdmiControlService.getActiveSource()).isNull();
    }

    @Test
    public void getActiveSource_localPlaybackIsActiveSource() {
        mHdmiControlService.setActiveSource(mHdmiCecLocalDevicePlayback.mAddress,
                mHdmiControlService.getPhysicalAddress(), "HdmiControlServiceTest");

        assertThat(mHdmiControlService.getActiveSource()).isEqualTo(
                mHdmiCecLocalDevicePlayback.getDeviceInfo());
    }

    @Test
    public void getActiveSource_deviceInNetworkIsActiveSource() {
        HdmiDeviceInfo externalDevice = new HdmiDeviceInfo(Constants.ADDR_PLAYBACK_3, 0x3000, 0,
                Constants.ADDR_PLAYBACK_1, 0, "Test Device");
        mHdmiControlService.getHdmiCecNetwork().addCecDevice(externalDevice);
        mTestLooper.dispatchAll();

        mHdmiControlService.setActiveSource(externalDevice.getLogicalAddress(),
                externalDevice.getPhysicalAddress(), "HdmiControlServiceTest");

        assertThat(mHdmiControlService.getActiveSource()).isEqualTo(externalDevice);
    }

    @Test
    public void getActiveSource_unknownDeviceIsActiveSource() {
        HdmiDeviceInfo externalDevice = new HdmiDeviceInfo(Constants.ADDR_PLAYBACK_3, 0x3000, 0,
                Constants.ADDR_PLAYBACK_1, 0, "Test Device");

        mHdmiControlService.setActiveSource(externalDevice.getLogicalAddress(),
                externalDevice.getPhysicalAddress(), "HdmiControlServiceTest");

        assertThat(mHdmiControlService.getActiveSource().getPhysicalAddress()).isEqualTo(
                externalDevice.getPhysicalAddress());
    }

    @Test
    public void queryDisplayStatus() {
        mHdmiControlService.queryDisplayStatus(new IHdmiControlCallback.Stub() {
            @Override
            public void onComplete(int result) {
            }
        });
        mTestLooper.dispatchAll();

        HdmiCecMessage expectedMessage = HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                mPlaybackLogicalAddress, Constants.ADDR_TV);
        assertThat(mNativeWrapper.getResultMessages()).contains(expectedMessage);
    }

    @Test
    public void toggleAndFollowTvPower_ToTv_TvStatusOn() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_TV);
        mStandby = false;
        mHdmiControlService.toggleAndFollowTvPower();
        HdmiCecMessage tvPowerStatus = HdmiCecMessageBuilder.buildReportPowerStatus(ADDR_TV,
                mPlaybackLogicalAddress, HdmiControlManager.POWER_STATUS_ON);
        assertThat(mHdmiCecLocalDevicePlayback.dispatchMessage(tvPowerStatus))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();

        HdmiCecMessage expectedMessage = HdmiCecMessageBuilder.buildStandby(
                mPlaybackLogicalAddress, ADDR_TV);
        assertThat(mNativeWrapper.getResultMessages()).contains(expectedMessage);
        assertThat(mStandby).isTrue();
    }

    @Test
    public void toggleAndFollowTvPower_Broadcast_TvStatusOn() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_BROADCAST);
        mStandby = false;
        mHdmiControlService.toggleAndFollowTvPower();
        HdmiCecMessage tvPowerStatus = HdmiCecMessageBuilder.buildReportPowerStatus(ADDR_TV,
                mPlaybackLogicalAddress, HdmiControlManager.POWER_STATUS_ON);
        assertThat(mHdmiCecLocalDevicePlayback.dispatchMessage(tvPowerStatus))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();

        HdmiCecMessage expectedMessage = HdmiCecMessageBuilder.buildStandby(
                mPlaybackLogicalAddress, ADDR_BROADCAST);
        assertThat(mNativeWrapper.getResultMessages()).contains(expectedMessage);
        assertThat(mStandby).isTrue();
    }

    @Test
    public void toggleAndFollowTvPower_TvStatusStandby() {
        mStandby = false;
        mHdmiControlService.toggleAndFollowTvPower();
        HdmiCecMessage tvPowerStatus = HdmiCecMessageBuilder.buildReportPowerStatus(ADDR_TV,
                mPlaybackLogicalAddress, HdmiControlManager.POWER_STATUS_STANDBY);
        assertThat(mHdmiCecLocalDevicePlayback.dispatchMessage(tvPowerStatus))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();

        HdmiCecMessage textViewOn = HdmiCecMessageBuilder.buildTextViewOn(mPlaybackLogicalAddress,
                ADDR_TV);
        HdmiCecMessage activeSource = HdmiCecMessageBuilder.buildActiveSource(
                mPlaybackLogicalAddress, mPlaybackPhysicalAddress);
        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mStandby).isFalse();
    }

    @Test
    public void toggleAndFollowTvPower_TvStatusUnknown() {
        mStandby = false;
        mHdmiControlService.toggleAndFollowTvPower();
        HdmiCecMessage tvPowerStatus = HdmiCecMessageBuilder.buildReportPowerStatus(ADDR_TV,
                mPlaybackLogicalAddress, HdmiControlManager.POWER_STATUS_UNKNOWN);
        assertThat(mHdmiCecLocalDevicePlayback.dispatchMessage(tvPowerStatus))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();

        HdmiCecMessage userControlPressed = HdmiCecMessageBuilder.buildUserControlPressed(
                mPlaybackLogicalAddress, Constants.ADDR_TV,
                HdmiCecKeycode.CEC_KEYCODE_POWER_TOGGLE_FUNCTION);
        HdmiCecMessage userControlReleased = HdmiCecMessageBuilder.buildUserControlReleased(
                mPlaybackLogicalAddress, Constants.ADDR_TV);
        assertThat(mNativeWrapper.getResultMessages()).contains(userControlPressed);
        assertThat(mNativeWrapper.getResultMessages()).contains(userControlReleased);
        assertThat(mStandby).isFalse();
    }

    @Test
    public void toggleAndFollowTvPower_isInteractive() throws RemoteException {
        when(mIPowerManagerMock.isInteractive()).thenReturn(true);
        mActiveMediaSessionsPaused = false;
        mWokenUp = false;

        mHdmiControlService.toggleAndFollowTvPower();

        assertThat(mActiveMediaSessionsPaused).isTrue();
        assertThat(mWokenUp).isFalse();
    }

    @Test
    public void toggleAndFollowTvPower_isNotInteractive() throws RemoteException {
        when(mIPowerManagerMock.isInteractive()).thenReturn(false);
        mActiveMediaSessionsPaused = false;
        mWokenUp = false;

        mHdmiControlService.toggleAndFollowTvPower();

        assertThat(mActiveMediaSessionsPaused).isFalse();
        assertThat(mWokenUp).isTrue();
    }


    @Test
    public void shouldHandleTvPowerKey_CecDisabled() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED,
                HdmiControlManager.HDMI_CEC_CONTROL_DISABLED);
        assertThat(mHdmiControlService.shouldHandleTvPowerKey()).isFalse();
    }

    @Test
    public void shouldHandleTvPowerKey_PowerControlModeNone() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_NONE);
        assertThat(mHdmiControlService.shouldHandleTvPowerKey()).isFalse();
    }

    @Test
    public void shouldHandleTvPowerKey_CecEnabled_PowerControlModeTv() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED,
                HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_TV);
        assertThat(mHdmiControlService.shouldHandleTvPowerKey()).isTrue();
    }

    @Test
    public void getRcFeatures() {
        ArrayList<Integer> features = new ArrayList<>(mHdmiCecLocalDevicePlayback.getRcFeatures());
        assertThat(features.contains(Constants.RC_PROFILE_SOURCE_HANDLES_ROOT_MENU)).isTrue();
        assertThat(features.contains(Constants.RC_PROFILE_SOURCE_HANDLES_SETUP_MENU)).isTrue();
        assertThat(features.contains(Constants.RC_PROFILE_SOURCE_HANDLES_CONTENTS_MENU)).isFalse();
        assertThat(features.contains(Constants.RC_PROFILE_SOURCE_HANDLES_TOP_MENU)).isFalse();
        assertThat(features.contains(
                Constants.RC_PROFILE_SOURCE_HANDLES_MEDIA_CONTEXT_SENSITIVE_MENU)).isFalse();
    }

    @Test
    public void doesNotSupportRecordTvScreen() {
        HdmiCecMessage recordTvScreen = new HdmiCecMessage(ADDR_TV, mPlaybackLogicalAddress,
                Constants.MESSAGE_RECORD_TV_SCREEN, HdmiCecMessage.EMPTY_PARAM);

        mNativeWrapper.onCecMessage(recordTvScreen);
        mTestLooper.dispatchAll();

        HdmiCecMessage featureAbort = HdmiCecMessageBuilder.buildFeatureAbortCommand(
                mPlaybackLogicalAddress, ADDR_TV, Constants.MESSAGE_RECORD_TV_SCREEN,
                ABORT_UNRECOGNIZED_OPCODE);
        assertThat(mNativeWrapper.getResultMessages()).contains(featureAbort);
    }

    @Test
    public void shouldHandleUserControlPressedAndReleased() {
        HdmiCecMessage userControlPressed = HdmiCecMessageBuilder.buildUserControlPressed(
                ADDR_TV, mPlaybackLogicalAddress,
                HdmiCecKeycode.CEC_KEYCODE_VOLUME_UP);
        HdmiCecMessage userControlReleased = HdmiCecMessageBuilder.buildUserControlReleased(
                ADDR_TV, mPlaybackLogicalAddress);

        mNativeWrapper.onCecMessage(userControlPressed);
        mTestLooper.dispatchAll();

        // Move past the follower safety timeout
        mTestLooper.moveTimeForward(TimeUnit.SECONDS.toMillis(2));
        mTestLooper.dispatchAll();

        mNativeWrapper.onCecMessage(userControlReleased);
        mTestLooper.dispatchAll();

        HdmiCecMessage featureAbortPressed = HdmiCecMessageBuilder.buildFeatureAbortCommand(
                mPlaybackLogicalAddress, ADDR_TV, Constants.MESSAGE_USER_CONTROL_PRESSED,
                ABORT_UNRECOGNIZED_OPCODE);
        HdmiCecMessage featureAbortReleased = HdmiCecMessageBuilder.buildFeatureAbortCommand(
                mPlaybackLogicalAddress, ADDR_TV, Constants.MESSAGE_USER_CONTROL_RELEASED,
                ABORT_UNRECOGNIZED_OPCODE);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(featureAbortPressed);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(featureAbortReleased);
    }
}
