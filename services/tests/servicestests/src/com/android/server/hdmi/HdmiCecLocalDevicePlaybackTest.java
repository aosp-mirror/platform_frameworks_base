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

import static com.android.server.SystemService.PHASE_SYSTEM_SERVICES_READY;
import static com.android.server.hdmi.Constants.ABORT_UNRECOGNIZED_OPCODE;
import static com.android.server.hdmi.Constants.ADDR_AUDIO_SYSTEM;
import static com.android.server.hdmi.Constants.ADDR_BROADCAST;
import static com.android.server.hdmi.Constants.ADDR_INVALID;
import static com.android.server.hdmi.Constants.ADDR_TV;
import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiPortInfo;
import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.os.Looper;
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
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

@SmallTest
@Presubmit
@RunWith(JUnit4.class)
/** Tests for {@link HdmiCecLocalDevicePlayback} class. */
public class HdmiCecLocalDevicePlaybackTest {
    private static final int TIMEOUT_MS = HdmiConfig.TIMEOUT_MS + 1;
    private static final int HOTPLUG_INTERVAL =
            HotplugDetectionAction.POLLING_INTERVAL_MS_FOR_PLAYBACK;

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

    private HdmiControlService mHdmiControlService;
    private HdmiCecController mHdmiCecController;
    private HdmiCecLocalDevicePlayback mHdmiCecLocalDevicePlayback;
    private FakeNativeWrapper mNativeWrapper;
    private Looper mMyLooper;
    private TestLooper mTestLooper = new TestLooper();
    private FakePowerManagerWrapper mPowerManager;
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();
    private int mPlaybackPhysicalAddress;
    private int mPlaybackLogicalAddress;
    private boolean mWokenUp;
    private boolean mActiveMediaSessionsPaused;
    private FakePowerManagerInternalWrapper mPowerManagerInternal =
            new FakePowerManagerInternalWrapper();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        Context context = InstrumentationRegistry.getTargetContext();
        mMyLooper = mTestLooper.getLooper();

        FakeAudioFramework audioFramework = new FakeAudioFramework();
        mHdmiControlService =
                new HdmiControlService(InstrumentationRegistry.getTargetContext(),
                        Collections.singletonList(HdmiDeviceInfo.DEVICE_PLAYBACK),
                        audioFramework.getAudioManager(),
                        audioFramework.getAudioDeviceVolumeManager()) {

                    @Override
                    void wakeUp() {
                        mWokenUp = true;
                        super.wakeUp();
                    }

                    @Override
                    void pauseActiveMediaSessions() {
                        mActiveMediaSessionsPaused = true;
                    }

                    @Override
                    boolean isCecControlEnabled() {
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
                    boolean canGoToStandby() {
                        return true;
                    }
                };

        mHdmiControlService.setHdmiCecConfig(new FakeHdmiCecConfig(context));
        mHdmiControlService.setDeviceConfig(new FakeDeviceConfigWrapper());
        mHdmiControlService.setIoLooper(mMyLooper);
        mNativeWrapper = new FakeNativeWrapper();
        mHdmiCecController = HdmiCecController.createWithNativeWrapper(
                mHdmiControlService, mNativeWrapper, mHdmiControlService.getAtomWriter());
        mHdmiControlService.setCecController(mHdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        HdmiPortInfo[] hdmiPortInfos = new HdmiPortInfo[1];
        hdmiPortInfos[0] =
                new HdmiPortInfo.Builder(1, HdmiPortInfo.PORT_OUTPUT, 0x0000)
                        .setCecSupported(true)
                        .setMhlSupported(false)
                        .setArcSupported(false)
                        .build();
        mNativeWrapper.setPortInfo(hdmiPortInfos);
        mNativeWrapper.setPortConnectionStatus(1, true);
        mHdmiControlService.initService();
        mHdmiControlService.onBootPhase(PHASE_SYSTEM_SERVICES_READY);
        mPowerManager = new FakePowerManagerWrapper(context);
        mHdmiControlService.setPowerManager(mPowerManager);
        mHdmiControlService.setPowerManagerInternal(mPowerManagerInternal);
        mPlaybackPhysicalAddress = 0x2000;
        mNativeWrapper.setPhysicalAddress(mPlaybackPhysicalAddress);
        mTestLooper.dispatchAll();
        mHdmiCecLocalDevicePlayback = mHdmiControlService.playback();
        mLocalDevices.add(mHdmiCecLocalDevicePlayback);
        mPlaybackLogicalAddress = mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress();
        mHdmiControlService.getHdmiCecNetwork().addCecDevice(INFO_TV);
        mNativeWrapper.clearResultMessages();
        mHdmiCecLocalDevicePlayback.mPlaybackDeviceActionOnRoutingControl =
                HdmiProperties.playback_device_action_on_routing_control_values.NONE;
        mHdmiControlService.setPowerStatus(HdmiControlManager.POWER_STATUS_ON);
    }

    @Test
    public void handleRoutingChange_None() {
        mHdmiCecLocalDevicePlayback.mPlaybackDeviceActionOnRoutingControl =
                HdmiProperties.playback_device_action_on_routing_control_values.NONE;

        mPowerManager.setInteractive(false);

        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000,
                        mPlaybackPhysicalAddress);

        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                        mPlaybackPhysicalAddress);

        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingChange(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mPowerManager.isInteractive()).isFalse();
        assertThat(mNativeWrapper.getResultMessages().contains(expectedMessage)).isFalse();
    }

    @Test
    public void handleRoutingInformation_None() {
        mHdmiCecLocalDevicePlayback.mPlaybackDeviceActionOnRoutingControl =
                HdmiProperties.playback_device_action_on_routing_control_values.NONE;

        mPowerManager.setInteractive(false);

        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV,
                        mPlaybackPhysicalAddress);

        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                        mPlaybackPhysicalAddress);

        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingInformation(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mPowerManager.isInteractive()).isFalse();
        assertThat(mNativeWrapper.getResultMessages().contains(expectedMessage)).isFalse();
    }

    @Test
    public void handleRoutingChange_WakeUpOnly() {
        mHdmiCecLocalDevicePlayback.mPlaybackDeviceActionOnRoutingControl =
                HdmiProperties.playback_device_action_on_routing_control_values.WAKE_UP_ONLY;

        mPowerManager.setInteractive(false);

        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000,
                        mPlaybackPhysicalAddress);

        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                        mPlaybackPhysicalAddress);

        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingChange(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mPowerManager.isInteractive()).isTrue();
        assertThat(mNativeWrapper.getResultMessages().contains(expectedMessage)).isFalse();
    }

    @Test
    public void handleRoutingInformation_WakeUpOnly() {
        mHdmiCecLocalDevicePlayback.mPlaybackDeviceActionOnRoutingControl =
                HdmiProperties.playback_device_action_on_routing_control_values.WAKE_UP_ONLY;

        mPowerManager.setInteractive(false);

        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV,
                        mPlaybackPhysicalAddress);

        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                        mPlaybackPhysicalAddress);

        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingInformation(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mPowerManager.isInteractive()).isTrue();
        assertThat(mNativeWrapper.getResultMessages().contains(expectedMessage)).isFalse();
    }

    @Test
    public void handleRoutingChange_WakeUpAndSendActiveSource() {
        mHdmiCecLocalDevicePlayback.mPlaybackDeviceActionOnRoutingControl =
                HdmiProperties
                        .playback_device_action_on_routing_control_values
                        .WAKE_UP_AND_SEND_ACTIVE_SOURCE;

        mPowerManager.setInteractive(false);

        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000,
                        mPlaybackPhysicalAddress);

        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                        mPlaybackPhysicalAddress);

        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingChange(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mPowerManager.isInteractive()).isTrue();
        assertThat(mNativeWrapper.getResultMessages()).contains(expectedMessage);
    }

    @Test
    public void handleRoutingInformation_WakeUpAndSendActiveSource() {
        mHdmiCecLocalDevicePlayback.mPlaybackDeviceActionOnRoutingControl =
                HdmiProperties
                        .playback_device_action_on_routing_control_values
                        .WAKE_UP_AND_SEND_ACTIVE_SOURCE;

        mPowerManager.setInteractive(false);

        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV,
                        mPlaybackPhysicalAddress);

        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                        mPlaybackPhysicalAddress);

        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingInformation(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mPowerManager.isInteractive()).isTrue();
        assertThat(mNativeWrapper.getResultMessages()).contains(expectedMessage);
    }

    @Test
    public void handleRoutingChange_otherDevice_None() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_NONE);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mPowerManager.setInteractive(true);
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000, 0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingChange(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().physicalAddress).isEqualTo(
                0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().logicalAddress).isEqualTo(
                ADDR_INVALID);
        assertThat(mPowerManager.isInteractive()).isTrue();
    }

    @Test
    public void handleRoutingChange_sameDevice_None_ActiveSource() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_NONE);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mPowerManager.setInteractive(true);
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
        assertThat(mPowerManager.isInteractive()).isTrue();
    }

    @Test
    public void handleRoutingChange_sameDevice_None_InactiveSource() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_NONE);
        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");
        mPowerManager.setInteractive(true);
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
        assertThat(mPowerManager.isInteractive()).isTrue();
    }

    @Test
    public void handleRoutingChange_otherDevice_StandbyNow() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mPowerManager.setInteractive(true);
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000, 0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingChange(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mPowerManager.isInteractive()).isFalse();
    }

    @Test
    public void handleRoutingChange_toSwitchInActivePath_noStandby() {
        int newPlaybackPhysicalAddress = 0x2100;
        int switchPhysicalAddress = 0x2000;
        mNativeWrapper.setPhysicalAddress(newPlaybackPhysicalAddress);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);

        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                newPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mTestLooper.dispatchAll();

        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, newPlaybackPhysicalAddress,
                        switchPhysicalAddress);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingChange(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isTrue();
        assertThat(mPowerManager.isInteractive()).isTrue();
    }

    @Test
    public void handleRoutingChange_toTv_StandbyNow() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mTestLooper.dispatchAll();

        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, mPlaybackPhysicalAddress,
                        Constants.TV_PHYSICAL_ADDRESS);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingChange(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mPowerManager.isInteractive()).isFalse();
    }

    @Test
    public void handleRoutingChange_otherDevice_StandbyNow_InactiveSource() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");
        mPowerManager.setInteractive(true);
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000, 0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingChange(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mPowerManager.isInteractive()).isTrue();
    }

    @Test
    public void handleRoutingChange_sameDevice_StandbyNow_ActiveSource() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mPowerManager.setInteractive(true);
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000,
                        mPlaybackPhysicalAddress);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingChange(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isTrue();
        assertThat(mPowerManager.isInteractive()).isTrue();
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
        mPowerManager.setInteractive(true);
        HdmiCecMessage message = HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV, 0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingInformation(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().physicalAddress).isEqualTo(
                0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().logicalAddress).isEqualTo(
                ADDR_INVALID);
        assertThat(mPowerManager.isInteractive()).isTrue();
    }

    @Test
    public void handleRoutingInformation_sameDevice_None_ActiveSource() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_NONE);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mPowerManager.setInteractive(true);
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
        assertThat(mPowerManager.isInteractive()).isTrue();
    }

    @Test
    public void handleRoutingInformation_sameDevice_None_InactiveSource() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_NONE);
        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");
        mPowerManager.setInteractive(true);
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
        assertThat(mPowerManager.isInteractive()).isTrue();
    }

    @Test
    public void handleRoutingInformation_otherDevice_StandbyNow() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mPowerManager.setInteractive(true);
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV, 0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingInformation(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mPowerManager.isInteractive()).isFalse();
    }

    @Test
    public void handleRoutingInformation_otherDevice_StandbyNow_InactiveSource() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");
        mPowerManager.setInteractive(true);
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV, 0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingInformation(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mPowerManager.isInteractive()).isTrue();
    }

    @Test
    public void handleRoutingInformation_sameDevice_StandbyNow_ActiveSource() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mPowerManager.setInteractive(true);
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV,
                        mPlaybackPhysicalAddress);
        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingInformation(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isTrue();
        assertThat(mPowerManager.isInteractive()).isTrue();
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
    public void handleRoutingInformation_physicalAddressOfSender_Tv_activeSourceChange() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mPowerManager.setInteractive(true);
        // Physical address reported in this message is the same as message sender's (TV) physical
        // address.
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingInformation(Constants.ADDR_TV, 0x0000);

        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingInformation(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().physicalAddress).isEqualTo(
                0x0000);
        // Active source's logical address is invalidated.
        // See {@link HdmiCecLocalDevicePlayback#handleRoutingChangeAndInformation}.
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().logicalAddress).isEqualTo(
                ADDR_INVALID);
        assertThat(mPowerManager.isInteractive()).isFalse();
    }

    @Test
    public void handleRoutingInformation_physicalAddressOfSender_notTv_noActiveSourceChange() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        // Add a device to the network and assert that this device is included in the list of
        // devices.
        HdmiDeviceInfo infoPlayback = HdmiDeviceInfo.cecDeviceBuilder()
                .setLogicalAddress(Constants.ADDR_PLAYBACK_3)
                .setPhysicalAddress(0x1000)
                .setPortId(PORT_1)
                .setDeviceType(HdmiDeviceInfo.DEVICE_PLAYBACK)
                .setVendorId(0x1000)
                .setDisplayName("Playback 3")
                .build();
        mHdmiControlService.getHdmiCecNetwork().addCecDevice(infoPlayback);
        mPowerManager.setInteractive(true);
        // Physical address reported in this message is the same as message sender's (Playback_3)
        // physical address.
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingInformation(Constants.ADDR_PLAYBACK_3, 0x1000);

        assertThat(mHdmiCecLocalDevicePlayback.handleRoutingInformation(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isTrue();
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().physicalAddress).isEqualTo(
                mPlaybackPhysicalAddress);
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().logicalAddress).isEqualTo(
                mPlaybackLogicalAddress);
        assertThat(mPowerManager.isInteractive()).isTrue();
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
                        Constants.ADDR_AUDIO_SYSTEM,
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        false);
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
                        Constants.ADDR_AUDIO_SYSTEM,
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        true);
        assertThat(mHdmiCecLocalDevicePlayback.handleSystemAudioModeStatus(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.mService.isSystemAudioActivated()).isTrue();
    }

    @Test
    public void doNotWakeUpOnHotPlug_PlugIn() {
        mPowerManager.setInteractive(false);
        mHdmiCecLocalDevicePlayback.onHotplug(0, true);
        assertThat(mPowerManager.isInteractive()).isFalse();
    }

    @Test
    public void doNotWakeUpOnHotPlug_PlugOut() {
        mPowerManager.setInteractive(false);
        mHdmiCecLocalDevicePlayback.onHotplug(0, false);
        assertThat(mPowerManager.isInteractive()).isFalse();
    }

    @Test
    public void handleOnStandby_ScreenOff_NotActiveSource_ToTv() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_TV);
        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");
        mHdmiCecLocalDevicePlayback.onStandby(false, HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();

        HdmiCecMessage standbyMessageToTv =
                HdmiCecMessageBuilder.buildStandby(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        HdmiCecMessage standbyMessageToAudioSystem =
                HdmiCecMessageBuilder.buildStandby(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        ADDR_AUDIO_SYSTEM);
        HdmiCecMessage standbyMessageBroadcast =
                HdmiCecMessageBuilder.buildStandby(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        ADDR_BROADCAST);
        HdmiCecMessage inactiveSource = HdmiCecMessageBuilder.buildInactiveSource(
                mPlaybackLogicalAddress, mPlaybackPhysicalAddress);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageToTv);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageToAudioSystem);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageBroadcast);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(inactiveSource);
    }

    @Test
    public void handleOnStandby_ScreenOff_NotActiveSource_ToTvAndAudioSystem() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_TV_AND_AUDIO_SYSTEM);
        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");
        mHdmiCecLocalDevicePlayback.onStandby(false, HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();

        HdmiCecMessage standbyMessageToTv =
                HdmiCecMessageBuilder.buildStandby(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        HdmiCecMessage standbyMessageToAudioSystem =
                HdmiCecMessageBuilder.buildStandby(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        ADDR_AUDIO_SYSTEM);
        HdmiCecMessage standbyMessageBroadcast =
                HdmiCecMessageBuilder.buildStandby(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        ADDR_BROADCAST);
        HdmiCecMessage inactiveSource = HdmiCecMessageBuilder.buildInactiveSource(
                mPlaybackLogicalAddress, mPlaybackPhysicalAddress);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageToTv);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageToAudioSystem);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageBroadcast);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(inactiveSource);
    }

    @Test
    public void handleOnStandby_ScreenOff_NotActiveSource_Broadcast() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_BROADCAST);
        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");
        mHdmiCecLocalDevicePlayback.onStandby(false, HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();

        HdmiCecMessage standbyMessageToTv =
                HdmiCecMessageBuilder.buildStandby(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        HdmiCecMessage standbyMessageToAudioSystem =
                HdmiCecMessageBuilder.buildStandby(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        ADDR_AUDIO_SYSTEM);
        HdmiCecMessage standbyMessageBroadcast =
                HdmiCecMessageBuilder.buildStandby(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        ADDR_BROADCAST);
        HdmiCecMessage inactiveSource = HdmiCecMessageBuilder.buildInactiveSource(
                mPlaybackLogicalAddress, mPlaybackPhysicalAddress);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageToTv);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageToAudioSystem);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageBroadcast);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(inactiveSource);
    }

    @Test
    public void handleOnStandby_ScreenOff_NotActiveSource_None() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_NONE);
        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");
        mHdmiCecLocalDevicePlayback.onStandby(false, HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();

        HdmiCecMessage standbyMessageToTv =
                HdmiCecMessageBuilder.buildStandby(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        HdmiCecMessage standbyMessageToAudioSystem =
                HdmiCecMessageBuilder.buildStandby(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        ADDR_AUDIO_SYSTEM);
        HdmiCecMessage standbyMessageBroadcast =
                HdmiCecMessageBuilder.buildStandby(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        ADDR_BROADCAST);
        HdmiCecMessage inactiveSource = HdmiCecMessageBuilder.buildInactiveSource(
                mPlaybackLogicalAddress, mPlaybackPhysicalAddress);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageToTv);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageToAudioSystem);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageBroadcast);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(inactiveSource);
    }

    @Test
    public void handleOnStandby_ScreenOff_ActiveSource_ToTv() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_TV);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mHdmiCecLocalDevicePlayback.onStandby(false, HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();

        HdmiCecMessage standbyMessageToTv =
                HdmiCecMessageBuilder.buildStandby(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        HdmiCecMessage standbyMessageToAudioSystem =
                HdmiCecMessageBuilder.buildStandby(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        ADDR_AUDIO_SYSTEM);
        HdmiCecMessage standbyMessageBroadcast =
                HdmiCecMessageBuilder.buildStandby(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        ADDR_BROADCAST);
        HdmiCecMessage inactiveSource = HdmiCecMessageBuilder.buildInactiveSource(
                mPlaybackLogicalAddress, mPlaybackPhysicalAddress);

        assertThat(mNativeWrapper.getResultMessages()).contains(standbyMessageToTv);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageToAudioSystem);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageBroadcast);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(inactiveSource);
    }

    @Test
    public void handleOnStandby_ScreenOff_ActiveSource_ToTvAndAudioSystem() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_TV_AND_AUDIO_SYSTEM);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mHdmiCecLocalDevicePlayback.onStandby(false, HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();

        HdmiCecMessage standbyMessageToTv =
                HdmiCecMessageBuilder.buildStandby(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        HdmiCecMessage standbyMessageToAudioSystem =
                HdmiCecMessageBuilder.buildStandby(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        ADDR_AUDIO_SYSTEM);
        HdmiCecMessage standbyMessageBroadcast =
                HdmiCecMessageBuilder.buildStandby(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        ADDR_BROADCAST);
        HdmiCecMessage inactiveSource = HdmiCecMessageBuilder.buildInactiveSource(
                mPlaybackLogicalAddress, mPlaybackPhysicalAddress);

        assertThat(mNativeWrapper.getResultMessages()).contains(standbyMessageToTv);
        assertThat(mNativeWrapper.getResultMessages()).contains(standbyMessageToAudioSystem);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageBroadcast);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(inactiveSource);
    }

    @Test
    public void handleOnStandby_ScreenOff_ActiveSource_Broadcast() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_BROADCAST);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mHdmiCecLocalDevicePlayback.onStandby(false, HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();

        HdmiCecMessage standbyMessageToTv =
                HdmiCecMessageBuilder.buildStandby(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        HdmiCecMessage standbyMessageToAudioSystem =
                HdmiCecMessageBuilder.buildStandby(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        ADDR_AUDIO_SYSTEM);
        HdmiCecMessage standbyMessageBroadcast =
                HdmiCecMessageBuilder.buildStandby(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        ADDR_BROADCAST);
        HdmiCecMessage inactiveSource = HdmiCecMessageBuilder.buildInactiveSource(
                mPlaybackLogicalAddress, mPlaybackPhysicalAddress);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageToTv);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageToAudioSystem);
        assertThat(mNativeWrapper.getResultMessages()).contains(standbyMessageBroadcast);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(inactiveSource);
    }

    @Test
    public void handleOnStandby_ScreenOff_ActiveSource_None() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_NONE);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mHdmiCecLocalDevicePlayback.onStandby(false, HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();

        HdmiCecMessage standbyMessageToTv =
                HdmiCecMessageBuilder.buildStandby(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        HdmiCecMessage standbyMessageToAudioSystem =
                HdmiCecMessageBuilder.buildStandby(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        ADDR_AUDIO_SYSTEM);
        HdmiCecMessage standbyMessageBroadcast =
                HdmiCecMessageBuilder.buildStandby(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        ADDR_BROADCAST);
        HdmiCecMessage inactiveSource = HdmiCecMessageBuilder.buildInactiveSource(
                mPlaybackLogicalAddress, mPlaybackPhysicalAddress);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageToTv);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageToAudioSystem);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageBroadcast);
        assertThat(mNativeWrapper.getResultMessages()).contains(inactiveSource);
    }

    @Test
    public void handleOnStandby_CecMessageReceived() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_TV);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mHdmiCecLocalDevicePlayback.onStandby(true, HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();

        HdmiCecMessage standbyMessageToTv =
                HdmiCecMessageBuilder.buildStandby(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        HdmiCecMessage standbyMessageBroadcast =
                HdmiCecMessageBuilder.buildStandby(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        ADDR_BROADCAST);
        HdmiCecMessage inactiveSource = HdmiCecMessageBuilder.buildInactiveSource(
                mPlaybackLogicalAddress, mPlaybackPhysicalAddress);

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageToTv);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standbyMessageBroadcast);
        assertThat(mNativeWrapper.getResultMessages()).contains(inactiveSource);
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
    public void handleOnInitializeCecComplete_ByScreenOn_PowerControlModeTv() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_TV);

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
    public void handleOnInitializeCecComplete_ByScreenOn_PowerControlModeNone() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_NONE);

        mHdmiCecLocalDevicePlayback.onInitializeCecComplete(
                mHdmiControlService.INITIATED_BY_SCREEN_ON);
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
        mPowerManager.setInteractive(true);
        HdmiCecMessage message = HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress);
        assertThat(mHdmiCecLocalDevicePlayback.handleActiveSource(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mPowerManager.isInteractive()).isTrue();
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
        mPowerManager.setInteractive(true);
        HdmiCecMessage message = HdmiCecMessageBuilder.buildActiveSource(ADDR_TV, 0x0000);
        assertThat(mHdmiCecLocalDevicePlayback.handleActiveSource(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mPowerManager.isInteractive()).isTrue();
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
        mPowerManager.setInteractive(true);
        HdmiCecMessage message = HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress);
        assertThat(mHdmiCecLocalDevicePlayback.handleActiveSource(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mPowerManager.isInteractive()).isTrue();
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isTrue();
    }

    @Test
    public void handleActiveSource_notActiveSource_StandbyNow() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
        mPowerManager.setInteractive(true);
        HdmiCecMessage message = HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress);
        assertThat(mHdmiCecLocalDevicePlayback.handleActiveSource(message))
                .isEqualTo(Constants.HANDLED);
        message = HdmiCecMessageBuilder.buildActiveSource(ADDR_TV, 0x0000);
        assertThat(mHdmiCecLocalDevicePlayback.handleActiveSource(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mPowerManager.isInteractive()).isFalse();
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
        mPowerManager.setInteractive(true);
        // 1. DUT is <AS>.
        HdmiCecMessage message1 =
                HdmiCecMessageBuilder.buildActiveSource(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        mPlaybackPhysicalAddress);
        assertThat(mHdmiCecLocalDevicePlayback.handleActiveSource(message1))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isTrue();
        assertThat(mPowerManager.isInteractive()).isTrue();
        // 2. DUT loses <AS> and goes to sleep.
        HdmiCecMessage message2 = HdmiCecMessageBuilder.buildActiveSource(ADDR_TV, 0x0000);
        assertThat(mHdmiCecLocalDevicePlayback.handleActiveSource(message2))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mPowerManager.isInteractive()).isFalse();
        mHdmiControlService.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);
        // 3. DUT becomes <AS> again.
        HdmiCecMessage setStreamPath = HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV,
                mPlaybackPhysicalAddress);
        mHdmiCecLocalDevicePlayback.dispatchMessage(setStreamPath);
        // The ActiveSourceAction created from the message above is deferred until the device wakes
        // up.
        mHdmiControlService.onWakeUp(HdmiControlService.WAKE_UP_SCREEN_ON);
        mTestLooper.dispatchAll();
        HdmiCecMessage activeSource =
                HdmiCecMessageBuilder.buildActiveSource(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        mPlaybackPhysicalAddress);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mPowerManager.isInteractive()).isTrue();
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isTrue();
        // 4. DUT turned off.
        mHdmiControlService.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();
        HdmiCecMessage standbyMessageBroadcast =
                HdmiCecMessageBuilder.buildStandby(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        ADDR_BROADCAST);
        assertThat(mNativeWrapper.getResultMessages()).contains(standbyMessageBroadcast);
    }

    @Test
    public void sendVolumeKeyEvent_up_volumeEnabled() {
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, true);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, false);
        mTestLooper.dispatchAll();

        HdmiCecMessage keyPressed =
                HdmiCecMessageBuilder.buildUserControlPressed(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        ADDR_TV,
                        HdmiCecKeycode.CEC_KEYCODE_VOLUME_UP);
        HdmiCecMessage keyReleased =
                HdmiCecMessageBuilder.buildUserControlReleased(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(), ADDR_TV);

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

        HdmiCecMessage keyPressed =
                HdmiCecMessageBuilder.buildUserControlPressed(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        ADDR_TV,
                        HdmiCecKeycode.CEC_KEYCODE_VOLUME_DOWN);
        HdmiCecMessage keyReleased =
                HdmiCecMessageBuilder.buildUserControlReleased(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(), ADDR_TV);

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

        HdmiCecMessage keyPressed =
                HdmiCecMessageBuilder.buildUserControlPressed(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        ADDR_TV,
                        HdmiCecKeycode.CEC_KEYCODE_MUTE);
        HdmiCecMessage keyReleased =
                HdmiCecMessageBuilder.buildUserControlReleased(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(), ADDR_TV);

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

        HdmiCecMessage keyPressed =
                HdmiCecMessageBuilder.buildUserControlPressed(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        ADDR_TV,
                        HdmiCecKeycode.CEC_KEYCODE_VOLUME_UP);
        HdmiCecMessage keyReleased =
                HdmiCecMessageBuilder.buildUserControlReleased(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(), ADDR_TV);

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

        HdmiCecMessage keyPressed =
                HdmiCecMessageBuilder.buildUserControlPressed(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        ADDR_TV,
                        HdmiCecKeycode.CEC_KEYCODE_VOLUME_UP);
        HdmiCecMessage keyReleased =
                HdmiCecMessageBuilder.buildUserControlReleased(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(), ADDR_TV);

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

        HdmiCecMessage keyPressed =
                HdmiCecMessageBuilder.buildUserControlPressed(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        ADDR_TV,
                        HdmiCecKeycode.CEC_KEYCODE_VOLUME_UP);
        HdmiCecMessage keyReleased =
                HdmiCecMessageBuilder.buildUserControlReleased(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(), ADDR_TV);

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
    public void sendVolumeKeyEvent_toLocalDevice_discardMessage() {
        HdmiCecLocalDeviceAudioSystem audioSystem =
                new HdmiCecLocalDeviceAudioSystem(mHdmiControlService);
        audioSystem.init();
        mLocalDevices.add(audioSystem);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();

        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        mHdmiControlService.setSystemAudioActivated(true);

        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, true);
        mHdmiCecLocalDevicePlayback.sendVolumeKeyEvent(KeyEvent.KEYCODE_VOLUME_UP, false);

        HdmiCecMessage keyPressed = HdmiCecMessageBuilder.buildUserControlPressed(
                mPlaybackLogicalAddress, ADDR_AUDIO_SYSTEM, HdmiCecKeycode.CEC_KEYCODE_VOLUME_UP);
        HdmiCecMessage keyReleased = HdmiCecMessageBuilder.buildUserControlReleased(
                mPlaybackLogicalAddress, ADDR_AUDIO_SYSTEM);
        mTestLooper.dispatchAll();

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(keyPressed);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(keyReleased);
    }

    @Test
    public void handleSetStreamPath_broadcastsActiveSource() {
        HdmiCecMessage setStreamPath = HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV,
                mPlaybackPhysicalAddress);
        mHdmiCecLocalDevicePlayback.dispatchMessage(setStreamPath);
        mTestLooper.dispatchAll();

        HdmiCecMessage activeSource =
                HdmiCecMessageBuilder.buildActiveSource(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        mPlaybackPhysicalAddress);

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
    public void handleSetStreamPath_Dreaming() throws RemoteException {
        mPowerManager.setInteractive(true);
        mWokenUp = false;

        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV,
                        mPlaybackPhysicalAddress);

        assertThat(mHdmiCecLocalDevicePlayback.handleSetStreamPath(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mPowerManager.isInteractive()).isTrue();
        assertThat(mWokenUp).isTrue();
    }

    @Test
    public void handleSetStreamPath_otherDevice_None() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_NONE);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mPowerManager.setInteractive(true);
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV, 0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.handleSetStreamPath(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().physicalAddress).isEqualTo(
                0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.getActiveSource().logicalAddress).isEqualTo(
                ADDR_INVALID);
        assertThat(mPowerManager.isInteractive()).isTrue();
    }

    @Test
    public void handleSetStreamPath_otherDevice_StandbyNow() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
        mHdmiCecLocalDevicePlayback.setActiveSource(mPlaybackLogicalAddress,
                mPlaybackPhysicalAddress, "HdmiCecLocalDevicePlaybackTest");
        mPowerManager.setInteractive(true);
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV, 0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.handleSetStreamPath(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mPowerManager.isInteractive()).isFalse();
    }

    @Test
    public void handleSetStreamPath_otherDevice_StandbyNow_InactiveSource() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
                HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW);
        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");
        mPowerManager.setInteractive(true);
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV, 0x5000);
        assertThat(mHdmiCecLocalDevicePlayback.handleSetStreamPath(message))
                .isEqualTo(Constants.HANDLED);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mPowerManager.isInteractive()).isTrue();
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
    public void oneTouchPlay_PowerControlModeToTv() {
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
    public void oneTouchPlay_PowerControlModeToTvAndAudioSystem() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_TV_AND_AUDIO_SYSTEM);
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
    public void oneTouchPlay_PowerControlModeBroadcast() {
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
    public void oneTouchPlay_PowerControlModeNone() {
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
    public void onAddressAllocated_invokesDeviceDiscovery() {
        mNativeWrapper.setPollAddressResponse(Constants.ADDR_PLAYBACK_2, SendMessageResult.SUCCESS);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);

        mTestLooper.dispatchAll();

        // Check for <Give Physical Address> being sent to available device (ADDR_PLAYBACK_2).
        // This message is sent as part of the DeviceDiscoveryAction to available devices.
        HdmiCecMessage givePhysicalAddress = HdmiCecMessageBuilder.buildGivePhysicalAddress(
                Constants.ADDR_PLAYBACK_1,
                Constants.ADDR_PLAYBACK_2);
        assertThat(mNativeWrapper.getResultMessages()).contains(givePhysicalAddress);
    }

    @Test
    public void wakeUp_hotPlugIn_invokesDeviceDiscoveryOnce() {
        mNativeWrapper.setPollAddressResponse(Constants.ADDR_PLAYBACK_2, SendMessageResult.SUCCESS);
        mHdmiControlService.onWakeUp(HdmiControlService.WAKE_UP_SCREEN_ON);
        mTestLooper.dispatchAll();

        mNativeWrapper.onHotplugEvent(1, true);
        mTestLooper.dispatchAll();

        assertThat(mHdmiCecLocalDevicePlayback.getActions(DeviceDiscoveryAction.class)).hasSize(1);
    }

    @Test
    public void hotplugDetectionAction_addDevice() {
        int otherPlaybackLogicalAddress = mPlaybackLogicalAddress == Constants.ADDR_PLAYBACK_2
                ? Constants.ADDR_PLAYBACK_1 : Constants.ADDR_PLAYBACK_2;
        mNativeWrapper.setPollAddressResponse(otherPlaybackLogicalAddress,
                SendMessageResult.NACK);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();

        mNativeWrapper.setPollAddressResponse(otherPlaybackLogicalAddress,
                SendMessageResult.SUCCESS);
        mTestLooper.moveTimeForward(HOTPLUG_INTERVAL);
        mTestLooper.dispatchAll();

        // Check for <Give Physical Address> being sent to the newly discovered device.
        // This message is sent as part of the HotplugDetectionAction to available devices.
        HdmiCecMessage givePhysicalAddress = HdmiCecMessageBuilder.buildGivePhysicalAddress(
                mPlaybackLogicalAddress, otherPlaybackLogicalAddress);
        assertThat(mNativeWrapper.getResultMessages()).contains(givePhysicalAddress);
    }

    @Test
    public void hotplugDetectionAction_removeDevice() {
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mHdmiControlService.getHdmiCecNetwork().clearDeviceList();
        HdmiDeviceInfo infoPlayback = HdmiDeviceInfo.cecDeviceBuilder()
                .setLogicalAddress(Constants.ADDR_PLAYBACK_2)
                .setPhysicalAddress(0x1234)
                .setPortId(PORT_1)
                .setDeviceType(HdmiDeviceInfo.DEVICE_PLAYBACK)
                .setVendorId(0x1234)
                .setDisplayName("Playback 2")
                .setDevicePowerStatus(HdmiControlManager.POWER_STATUS_ON)
                .setCecVersion(HdmiControlManager.HDMI_CEC_VERSION_1_4_B)
                .build();
        mHdmiControlService.getHdmiCecNetwork().addCecDevice(infoPlayback);
        // This logical address (ADDR_PLAYBACK_2) won't acknowledge the poll message sent by the
        // HotplugDetectionAction so it shall be removed.
        mNativeWrapper.setPollAddressResponse(Constants.ADDR_PLAYBACK_2, SendMessageResult.NACK);
        mTestLooper.moveTimeForward(HOTPLUG_INTERVAL);
        mTestLooper.dispatchAll();

        assertThat(mHdmiControlService.getHdmiCecNetwork().getDeviceInfoList(false)).isEmpty();
    }

    @Test
    public void getActiveSource_noActiveSource() {
        mHdmiControlService.setActiveSource(Constants.ADDR_UNREGISTERED,
                Constants.INVALID_PHYSICAL_ADDRESS, "HdmiControlServiceTest");

        assertThat(mHdmiControlService.getActiveSource()).isNull();
    }

    @Test
    public void getActiveSource_localPlaybackIsActiveSource() {
        mHdmiControlService.setActiveSource(
                mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                mHdmiControlService.getPhysicalAddress(),
                "HdmiControlServiceTest");

        assertThat(mHdmiControlService.getActiveSource()).isEqualTo(
                mHdmiCecLocalDevicePlayback.getDeviceInfo());
    }

    @Test
    public void getActiveSource_deviceInNetworkIsActiveSource() {
        HdmiDeviceInfo externalDevice = HdmiDeviceInfo.cecDeviceBuilder()
                .setLogicalAddress(Constants.ADDR_PLAYBACK_3)
                .setPhysicalAddress(0x3000)
                .setPortId(0)
                .setDeviceType(Constants.ADDR_PLAYBACK_1)
                .setVendorId(0)
                .setDisplayName("Test Device")
                .build();

        mHdmiControlService.getHdmiCecNetwork().addCecDevice(externalDevice);
        mTestLooper.dispatchAll();

        mHdmiControlService.setActiveSource(externalDevice.getLogicalAddress(),
                externalDevice.getPhysicalAddress(), "HdmiControlServiceTest");

        assertThat(mHdmiControlService.getActiveSource()).isEqualTo(externalDevice);
    }

    @Test
    public void getActiveSource_unknownDeviceIsActiveSource() {
        HdmiDeviceInfo externalDevice = HdmiDeviceInfo.cecDeviceBuilder()
                .setLogicalAddress(Constants.ADDR_PLAYBACK_3)
                .setPhysicalAddress(0x3000)
                .setPortId(0)
                .setDeviceType(Constants.ADDR_PLAYBACK_1)
                .setVendorId(0)
                .setDisplayName("Test Device")
                .build();

        mHdmiControlService.setActiveSource(externalDevice.getLogicalAddress(),
                externalDevice.getPhysicalAddress(), "HdmiControlServiceTest");

        assertThat(mHdmiControlService.getActiveSource().getPhysicalAddress()).isEqualTo(
                externalDevice.getPhysicalAddress());
    }

    @Test
    public void queryDisplayStatus() {
        mTestLooper.moveTimeForward(TIMEOUT_MS);
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
        mPowerManager.setInteractive(true);
        mHdmiControlService.toggleAndFollowTvPower();
        HdmiCecMessage tvPowerStatus = HdmiCecMessageBuilder.buildReportPowerStatus(ADDR_TV,
                mPlaybackLogicalAddress, HdmiControlManager.POWER_STATUS_ON);
        assertThat(mHdmiCecLocalDevicePlayback.dispatchMessage(tvPowerStatus))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();

        HdmiCecMessage expectedMessage = HdmiCecMessageBuilder.buildStandby(
                mPlaybackLogicalAddress, ADDR_TV);
        assertThat(mNativeWrapper.getResultMessages()).contains(expectedMessage);
        assertThat(mPowerManager.isInteractive()).isFalse();
    }

    @Test
    public void toggleAndFollowTvPower_Broadcast_TvStatusOn() {
        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_BROADCAST);
        mPowerManager.setInteractive(true);
        mHdmiControlService.toggleAndFollowTvPower();
        HdmiCecMessage tvPowerStatus = HdmiCecMessageBuilder.buildReportPowerStatus(ADDR_TV,
                mPlaybackLogicalAddress, HdmiControlManager.POWER_STATUS_ON);
        assertThat(mHdmiCecLocalDevicePlayback.dispatchMessage(tvPowerStatus))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();

        HdmiCecMessage expectedMessage = HdmiCecMessageBuilder.buildStandby(
                mPlaybackLogicalAddress, ADDR_BROADCAST);
        assertThat(mNativeWrapper.getResultMessages()).contains(expectedMessage);
        assertThat(mPowerManager.isInteractive()).isFalse();
    }

    @Test
    public void toggleAndFollowTvPower_TvStatusStandby() {
        mPowerManager.setInteractive(true);
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
        assertThat(mPowerManager.isInteractive()).isTrue();
    }

    @Test
    public void toggleAndFollowTvPower_TvStatusUnknown() {
        mPowerManager.setInteractive(true);
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
        assertThat(mPowerManager.isInteractive()).isTrue();
    }

    @Test
    public void toggleAndFollowTvPower_isInteractive() throws RemoteException {
        mPowerManager.setInteractive(true);
        mActiveMediaSessionsPaused = false;
        mWokenUp = false;

        mHdmiControlService.toggleAndFollowTvPower();

        assertThat(mActiveMediaSessionsPaused).isTrue();
        assertThat(mPowerManager.isInteractive()).isTrue();
        assertThat(mWokenUp).isFalse();
    }

    @Test
    public void toggleAndFollowTvPower_isNotInteractive() throws RemoteException {
        mPowerManager.setInteractive(false);
        mActiveMediaSessionsPaused = false;

        mHdmiControlService.toggleAndFollowTvPower();

        assertThat(mActiveMediaSessionsPaused).isFalse();
        assertThat(mPowerManager.isInteractive()).isTrue();
    }


    @Test
    public void shouldHandleTvPowerKey_CecDisabled() {
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        HdmiCecMessage reportPowerStatusMessage = HdmiCecMessageBuilder.buildReportPowerStatus(
                Constants.ADDR_TV, mPlaybackLogicalAddress, HdmiControlManager.POWER_STATUS_ON);
        mNativeWrapper.onCecMessage(reportPowerStatusMessage);
        mTestLooper.dispatchAll();

        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED,
                HdmiControlManager.HDMI_CEC_CONTROL_DISABLED);
        assertThat(mHdmiControlService.shouldHandleTvPowerKey()).isFalse();
    }

    @Test
    public void shouldHandleTvPowerKey_PowerControlModeNone() {
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        HdmiCecMessage reportPowerStatusMessage = HdmiCecMessageBuilder.buildReportPowerStatus(
                Constants.ADDR_TV, mPlaybackLogicalAddress, HdmiControlManager.POWER_STATUS_ON);
        mNativeWrapper.onCecMessage(reportPowerStatusMessage);
        mTestLooper.dispatchAll();

        mHdmiCecLocalDevicePlayback.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_NONE);
        assertThat(mHdmiControlService.shouldHandleTvPowerKey()).isFalse();
    }

    @Test
    public void shouldHandleTvPowerKey_CecNotAvailable() {
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        // TV doesn't report its power status
        mTestLooper.moveTimeForward(TIMEOUT_MS);
        mTestLooper.dispatchAll();
        assertThat(mHdmiControlService.shouldHandleTvPowerKey()).isFalse();
    }

    @Test
    public void shouldHandleTvPowerKey_CecEnabled_PowerControlModeTv() {
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        HdmiCecMessage reportPowerStatusMessage = HdmiCecMessageBuilder.buildReportPowerStatus(
                Constants.ADDR_TV, mPlaybackLogicalAddress, HdmiControlManager.POWER_STATUS_ON);
        mNativeWrapper.onCecMessage(reportPowerStatusMessage);
        mTestLooper.dispatchAll();

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
        HdmiCecMessage recordTvScreen = HdmiCecMessage.build(ADDR_TV, mPlaybackLogicalAddress,
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

    @Test
    public void onHotplugInAfterHotplugOut_noStandbyAfterDelay() {
        mPowerManager.setInteractive(true);
        mNativeWrapper.onHotplugEvent(1, false);
        mTestLooper.dispatchAll();

        mTestLooper.moveTimeForward(
                HdmiCecLocalDevicePlayback.STANDBY_AFTER_HOTPLUG_OUT_DELAY_MS / 2);
        mNativeWrapper.onHotplugEvent(1, true);
        mTestLooper.dispatchAll();

        mPowerManagerInternal.setIdleDuration(
                HdmiCecLocalDevicePlayback.STANDBY_AFTER_HOTPLUG_OUT_DELAY_MS);
        mTestLooper.moveTimeForward(HdmiCecLocalDevicePlayback.STANDBY_AFTER_HOTPLUG_OUT_DELAY_MS);
        mTestLooper.dispatchAll();

        assertThat(mPowerManager.isInteractive()).isTrue();
    }

    @Test
    public void onHotplugOut_standbyAfterDelay_onlyAfterDeviceIdle() {
        mPowerManager.setInteractive(true);
        mNativeWrapper.onHotplugEvent(1, false);
        mTestLooper.dispatchAll();

        mPowerManagerInternal.setIdleDuration(0);
        mTestLooper.moveTimeForward(HdmiCecLocalDevicePlayback.STANDBY_AFTER_HOTPLUG_OUT_DELAY_MS);
        mTestLooper.dispatchAll();
        assertThat(mPowerManager.isInteractive()).isTrue();

        mPowerManagerInternal.setIdleDuration(
                HdmiCecLocalDevicePlayback.STANDBY_AFTER_HOTPLUG_OUT_DELAY_MS);
        mTestLooper.moveTimeForward(HdmiCecLocalDevicePlayback.STANDBY_AFTER_HOTPLUG_OUT_DELAY_MS);
        mTestLooper.dispatchAll();
        assertThat(mPowerManager.isInteractive()).isFalse();
    }

    @Test
    public void onHotplugClearsDevices() {
        mHdmiControlService.getHdmiCecNetwork().clearDeviceList();
        assertThat(mHdmiControlService.getHdmiCecNetwork().getDeviceInfoList(false))
                .isEmpty();
        // Add a device to the network and assert that this device is included in the list of
        // devices.
        HdmiDeviceInfo infoPlayback = HdmiDeviceInfo.cecDeviceBuilder()
                .setLogicalAddress(Constants.ADDR_PLAYBACK_3)
                .setPhysicalAddress(0x1000)
                .setPortId(PORT_1)
                .setDeviceType(HdmiDeviceInfo.DEVICE_PLAYBACK)
                .setVendorId(0x1000)
                .setDisplayName("Playback 3")
                .build();
        mHdmiControlService.getHdmiCecNetwork().addCecDevice(infoPlayback);
        mTestLooper.dispatchAll();
        assertThat(mHdmiControlService.getHdmiCecNetwork().getDeviceInfoList(false))
                .hasSize(1);

        // HAL detects a hotplug out. Assert that this device gets removed from the list of devices.
        mHdmiControlService.onHotplug(PORT_1, false);
        mTestLooper.dispatchAll();

        assertThat(mHdmiControlService.getHdmiCecNetwork().getDeviceInfoList(false))
                .isEmpty();
    }

    @Test
    public void handleRoutingChange_addressNotAllocated_removeActiveSourceAction() {
        long allocationDelay = TimeUnit.SECONDS.toMillis(60);
        mHdmiCecLocalDevicePlayback.mPlaybackDeviceActionOnRoutingControl =
                HdmiProperties
                        .playback_device_action_on_routing_control_values
                        .WAKE_UP_AND_SEND_ACTIVE_SOURCE;
        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");
        HdmiCecMessage routingChangeToPlayback =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000,
                        mPlaybackPhysicalAddress);
        HdmiCecMessage routingChangeToTv =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, mPlaybackPhysicalAddress,
                        0x0000);
        HdmiCecMessage unexpectedMessage =
                HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                        mPlaybackPhysicalAddress);
        // 1. DUT goes to sleep.
        mHdmiControlService.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);
        // Delay allocate logical address in order to trigger message buffering.
        mHdmiCecController.setLogicalAddressAllocationDelay(allocationDelay);
        mNativeWrapper.onCecMessage(routingChangeToPlayback);
        mTestLooper.dispatchAll();
        // 2. DUT wakes up and defer ActiveSourceAction.
        mHdmiControlService.onWakeUp(HdmiControlService.WAKE_UP_SCREEN_ON);
        mTestLooper.dispatchAll();
        assertThat(mHdmiCecLocalDevicePlayback.getActions(ActiveSourceAction.class)).isNotEmpty();
        // 3. DUT buffers <Routing Change> message to TV.
        mNativeWrapper.onCecMessage(routingChangeToTv);
        mTestLooper.dispatchAll();
        // 4. Allocation is finished and the ActiveSourceAction is removed from the queue.
        // No <Active Source> message is sent by the DUT.
        mTestLooper.moveTimeForward(allocationDelay);
        mTestLooper.dispatchAll();
        assertThat(mHdmiCecLocalDevicePlayback.getActions(ActiveSourceAction.class)).isEmpty();
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(unexpectedMessage);
    }

    @Test
    public void handleSetStreamPath_addressNotAllocated_removeActiveSourceAction() {
        long allocationDelay = TimeUnit.SECONDS.toMillis(60);
        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");
        HdmiCecMessage setStreamPathToPlayback =
                HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV, mPlaybackPhysicalAddress);
        HdmiCecMessage setStreamPathToTv =
                HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV, 0x0000);
        HdmiCecMessage unexpectedMessage =
                HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                        mPlaybackPhysicalAddress);
        // 1. DUT goes to sleep.
        mHdmiControlService.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);
        // Delay allocate logical address in order to trigger message buffering.
        mHdmiCecController.setLogicalAddressAllocationDelay(allocationDelay);
        mNativeWrapper.onCecMessage(setStreamPathToPlayback);
        mTestLooper.dispatchAll();
        // 2. DUT wakes up and defer ActiveSourceAction.
        mHdmiControlService.onWakeUp(HdmiControlService.WAKE_UP_SCREEN_ON);
        mTestLooper.dispatchAll();
        assertThat(mHdmiCecLocalDevicePlayback.getActions(ActiveSourceAction.class)).isNotEmpty();
        // 3. DUT buffers <Set Stream Path> message to TV.
        mNativeWrapper.onCecMessage(setStreamPathToTv);
        mTestLooper.dispatchAll();
        // 4. Allocation is finished and the ActiveSourceAction is removed from the queue.
        // No <Active Source> message is sent by the DUT.
        mTestLooper.moveTimeForward(allocationDelay);
        mTestLooper.dispatchAll();
        assertThat(mHdmiCecLocalDevicePlayback.getActions(ActiveSourceAction.class)).isEmpty();
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(unexpectedMessage);
    }

    @Test
    public void handleActiveSource_addressNotAllocated_removeActiveSourceAction() {
        long allocationDelay = TimeUnit.SECONDS.toMillis(60);

        mHdmiCecLocalDevicePlayback.setActiveSource(ADDR_TV, 0x0000,
                "HdmiCecLocalDevicePlaybackTest");

        HdmiCecMessage setStreamPathToPlayback =
                HdmiCecMessageBuilder.buildSetStreamPath(ADDR_TV, mPlaybackPhysicalAddress);
        HdmiCecMessage activeSourceFromTv =
                HdmiCecMessageBuilder.buildActiveSource(ADDR_TV, 0x0000);
        HdmiCecMessage unexpectedMessage =
                HdmiCecMessageBuilder.buildActiveSource(mPlaybackLogicalAddress,
                        mPlaybackPhysicalAddress);
        // 1. DUT goes to sleep.
        mHdmiControlService.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);
        // Delay allocate logical address in order to trigger message buffering.
        mHdmiCecController.setLogicalAddressAllocationDelay(allocationDelay);
        mNativeWrapper.onCecMessage(setStreamPathToPlayback);
        mTestLooper.dispatchAll();
        // 2. DUT wakes up and defer ActiveSourceAction.
        mHdmiControlService.onWakeUp(HdmiControlService.WAKE_UP_SCREEN_ON);
        mTestLooper.dispatchAll();
        assertThat(mHdmiCecLocalDevicePlayback.getActions(ActiveSourceAction.class)).isNotEmpty();
        // 3. DUT buffers <Active Source> message from TV.
        mNativeWrapper.onCecMessage(activeSourceFromTv);
        mTestLooper.dispatchAll();
        // 4. Allocation is finished and the ActiveSourceAction is removed from the queue.
        // No <Active Source> message is sent by the DUT.
        mTestLooper.moveTimeForward(allocationDelay);
        mTestLooper.dispatchAll();
        assertThat(mHdmiCecLocalDevicePlayback.getActions(ActiveSourceAction.class)).isEmpty();
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(unexpectedMessage);
    }
}
