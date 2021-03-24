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

import static com.android.server.hdmi.Constants.ABORT_UNRECOGNIZED_OPCODE;
import static com.android.server.hdmi.Constants.ADDR_AUDIO_SYSTEM;
import static com.android.server.hdmi.Constants.ADDR_BROADCAST;
import static com.android.server.hdmi.Constants.ADDR_PLAYBACK_1;
import static com.android.server.hdmi.Constants.ADDR_RECORDER_1;
import static com.android.server.hdmi.Constants.ADDR_TV;
import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiPortInfo;
import android.hardware.tv.cec.V1_0.SendMessageResult;
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

@SmallTest
@RunWith(JUnit4.class)
/** Tests for {@link HdmiCecLocalDeviceTv} class. */
public class HdmiCecLocalDeviceTvTest {

    private HdmiControlService mHdmiControlService;
    private HdmiCecController mHdmiCecController;
    private HdmiCecLocalDeviceTv mHdmiCecLocalDeviceTv;
    private FakeNativeWrapper mNativeWrapper;
    private Looper mMyLooper;
    private TestLooper mTestLooper = new TestLooper();
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();
    private int mTvPhysicalAddress;
    private int mTvLogicalAddress;
    private boolean mWokenUp;

    @Mock
    private IPowerManager mIPowerManagerMock;
    @Mock
    private IThermalService mIThermalServiceMock;
    @Mock private AudioManager mAudioManager;

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
                    boolean isControlEnabled() {
                        return true;
                    }

                    @Override
                    boolean isTvDevice() {
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

                    @Override
                    AudioManager getAudioManager() {
                        return mAudioManager;
                    }
                };

        mHdmiCecLocalDeviceTv = new HdmiCecLocalDeviceTv(mHdmiControlService);
        mHdmiCecLocalDeviceTv.init();
        mHdmiControlService.setIoLooper(mMyLooper);
        mHdmiControlService.setHdmiCecConfig(new FakeHdmiCecConfig(context));
        mNativeWrapper = new FakeNativeWrapper();
        mHdmiCecController = HdmiCecController.createWithNativeWrapper(
                mHdmiControlService, mNativeWrapper, mHdmiControlService.getAtomWriter());
        mHdmiControlService.setCecController(mHdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        mHdmiControlService.setMessageValidator(new HdmiCecMessageValidator(mHdmiControlService));
        mLocalDevices.add(mHdmiCecLocalDeviceTv);
        HdmiPortInfo[] hdmiPortInfos = new HdmiPortInfo[2];
        hdmiPortInfos[0] =
                new HdmiPortInfo(1, HdmiPortInfo.PORT_INPUT, 0x1000, true, false, false);
        hdmiPortInfos[1] =
                new HdmiPortInfo(2, HdmiPortInfo.PORT_INPUT, 0x2000, true, false, true);
        mNativeWrapper.setPortInfo(hdmiPortInfos);
        mHdmiControlService.initService();
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTvPhysicalAddress = 0x0000;
        mNativeWrapper.setPhysicalAddress(mTvPhysicalAddress);
        mTestLooper.dispatchAll();
        mTvLogicalAddress = mHdmiCecLocalDeviceTv.getDeviceInfo().getLogicalAddress();
        mNativeWrapper.clearResultMessages();
    }

    @Test
    public void initialPowerStateIsStandby() {
        assertThat(mHdmiCecLocalDeviceTv.getPowerStatus()).isEqualTo(
                HdmiControlManager.POWER_STATUS_STANDBY);
    }

    @Test
    public void onAddressAllocated_invokesDeviceDiscovery() {
        mHdmiControlService.getHdmiCecNetwork().clearLocalDevices();
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_1, SendMessageResult.SUCCESS);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);

        mTestLooper.dispatchAll();

        // Check for for <Give Physical Address> being sent to available device (ADDR_PLAYBACK_1).
        // This message is sent as part of the DeviceDiscoveryAction to available devices.
        HdmiCecMessage givePhysicalAddress = HdmiCecMessageBuilder.buildGivePhysicalAddress(ADDR_TV,
                ADDR_PLAYBACK_1);
        assertThat(mNativeWrapper.getResultMessages()).contains(givePhysicalAddress);
    }

    @Test
    public void getActiveSource_noActiveSource() {
        mHdmiControlService.setActiveSource(Constants.ADDR_UNREGISTERED,
                Constants.INVALID_PHYSICAL_ADDRESS, "HdmiControlServiceTest");
        mHdmiCecLocalDeviceTv.setActivePath(HdmiDeviceInfo.PATH_INVALID);

        assertThat(mHdmiControlService.getActiveSource()).isNull();
    }

    @Test
    public void getActiveSource_deviceInNetworkIsActiveSource() {
        HdmiDeviceInfo externalDevice = new HdmiDeviceInfo(Constants.ADDR_PLAYBACK_3, 0x1000, 0,
                Constants.ADDR_PLAYBACK_1, 0, "Test Device");
        mHdmiControlService.getHdmiCecNetwork().addCecDevice(externalDevice);
        mTestLooper.dispatchAll();

        mHdmiControlService.setActiveSource(externalDevice.getLogicalAddress(),
                externalDevice.getPhysicalAddress(), "HdmiControlServiceTest");

        assertThat(mHdmiControlService.getActiveSource()).isEqualTo(externalDevice);
    }

    @Test
    public void getActiveSource_unknownLogicalAddressInNetworkIsActiveSource() {
        HdmiDeviceInfo externalDevice = new HdmiDeviceInfo(0x1000, 1);

        mHdmiControlService.setActiveSource(Constants.ADDR_UNREGISTERED,
                externalDevice.getPhysicalAddress(), "HdmiControlServiceTest");
        mHdmiCecLocalDeviceTv.setActivePath(0x1000);

        assertThat(mHdmiControlService.getActiveSource()).isEqualTo(
                externalDevice);
    }

    @Test
    public void getActiveSource_unknownDeviceIsActiveSource() {
        HdmiDeviceInfo externalDevice = new HdmiDeviceInfo(Constants.ADDR_PLAYBACK_3, 0x1000, 0,
                Constants.ADDR_PLAYBACK_1, 0, "Test Device");

        mHdmiControlService.setActiveSource(externalDevice.getLogicalAddress(),
                externalDevice.getPhysicalAddress(), "HdmiControlServiceTest");
        mHdmiCecLocalDeviceTv.setActivePath(0x1000);

        assertThat(mHdmiControlService.getActiveSource().getPhysicalAddress()).isEqualTo(
                externalDevice.getPhysicalAddress());
    }

    @Test
    public void shouldHandleTvPowerKey_CecEnabled_PowerControlModeTv() {
        mHdmiCecLocalDeviceTv.mService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED,
                HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        mHdmiCecLocalDeviceTv.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_TV);
        assertThat(mHdmiControlService.shouldHandleTvPowerKey()).isFalse();
    }

    @Test
    public void tvWakeOnOneTouchPlay_TextViewOn_Enabled() {
        mHdmiCecLocalDeviceTv.mService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_TV_WAKE_ON_ONE_TOUCH_PLAY,
                HdmiControlManager.TV_WAKE_ON_ONE_TOUCH_PLAY_ENABLED);
        mTestLooper.dispatchAll();
        mWokenUp = false;
        HdmiCecMessage textViewOn = HdmiCecMessageBuilder.buildTextViewOn(ADDR_PLAYBACK_1,
                mTvLogicalAddress);
        assertThat(mHdmiCecLocalDeviceTv.dispatchMessage(textViewOn)).isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mWokenUp).isTrue();
    }

    @Test
    public void tvWakeOnOneTouchPlay_ImageViewOn_Enabled() {
        mHdmiCecLocalDeviceTv.mService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_TV_WAKE_ON_ONE_TOUCH_PLAY,
                HdmiControlManager.TV_WAKE_ON_ONE_TOUCH_PLAY_ENABLED);
        mTestLooper.dispatchAll();
        mWokenUp = false;
        HdmiCecMessage imageViewOn = new HdmiCecMessage(ADDR_PLAYBACK_1, mTvLogicalAddress,
                Constants.MESSAGE_IMAGE_VIEW_ON, HdmiCecMessage.EMPTY_PARAM);
        assertThat(mHdmiCecLocalDeviceTv.dispatchMessage(imageViewOn)).isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mWokenUp).isTrue();
    }

    @Test
    public void tvWakeOnOneTouchPlay_TextViewOn_Disabled() {
        mHdmiCecLocalDeviceTv.mService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_TV_WAKE_ON_ONE_TOUCH_PLAY,
                HdmiControlManager.TV_WAKE_ON_ONE_TOUCH_PLAY_DISABLED);
        mTestLooper.dispatchAll();
        mWokenUp = false;
        HdmiCecMessage textViewOn = HdmiCecMessageBuilder.buildTextViewOn(ADDR_PLAYBACK_1,
                mTvLogicalAddress);
        assertThat(mHdmiCecLocalDeviceTv.dispatchMessage(textViewOn)).isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mWokenUp).isFalse();
    }

    @Test
    public void tvWakeOnOneTouchPlay_ImageViewOn_Disabled() {
        mHdmiCecLocalDeviceTv.mService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_TV_WAKE_ON_ONE_TOUCH_PLAY,
                HdmiControlManager.TV_WAKE_ON_ONE_TOUCH_PLAY_DISABLED);
        mTestLooper.dispatchAll();
        mWokenUp = false;
        HdmiCecMessage imageViewOn = new HdmiCecMessage(ADDR_PLAYBACK_1, mTvLogicalAddress,
                Constants.MESSAGE_IMAGE_VIEW_ON, HdmiCecMessage.EMPTY_PARAM);
        assertThat(mHdmiCecLocalDeviceTv.dispatchMessage(imageViewOn)).isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mWokenUp).isFalse();
    }

    @Test
    public void tvSendStandbyOnSleep_Enabled() {
        mHdmiCecLocalDeviceTv.mService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP,
                HdmiControlManager.TV_SEND_STANDBY_ON_SLEEP_ENABLED);
        mTestLooper.dispatchAll();
        mHdmiControlService.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();
        HdmiCecMessage standby = HdmiCecMessageBuilder.buildStandby(ADDR_TV, ADDR_BROADCAST);
        assertThat(mNativeWrapper.getResultMessages()).contains(standby);
    }

    @Test
    public void tvSendStandbyOnSleep_Disabled() {
        mHdmiCecLocalDeviceTv.mService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP,
                HdmiControlManager.TV_SEND_STANDBY_ON_SLEEP_DISABLED);
        mTestLooper.dispatchAll();
        mHdmiControlService.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();
        HdmiCecMessage standby = HdmiCecMessageBuilder.buildStandby(ADDR_TV, ADDR_BROADCAST);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(standby);
    }

    @Test
    public void getRcFeatures() {
        ArrayList<Integer> features = new ArrayList<>(mHdmiCecLocalDeviceTv.getRcFeatures());
        assertThat(features.contains(Constants.RC_PROFILE_TV_NONE)).isTrue();
        assertThat(features.contains(Constants.RC_PROFILE_TV_ONE)).isFalse();
        assertThat(features.contains(Constants.RC_PROFILE_TV_TWO)).isFalse();
        assertThat(features.contains(Constants.RC_PROFILE_TV_THREE)).isFalse();
        assertThat(features.contains(Constants.RC_PROFILE_TV_FOUR)).isFalse();
    }

    @Test
    public void startArcAction_enable_noAudioDevice() {
        mHdmiCecLocalDeviceTv.startArcAction(true);

        HdmiCecMessage requestArcInitiation = HdmiCecMessageBuilder.buildRequestArcInitiation(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        HdmiCecMessage requestArcTermination = HdmiCecMessageBuilder.buildRequestArcTermination(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(requestArcInitiation);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(requestArcTermination);
    }


    @Test
    public void startArcAction_disable_noAudioDevice() {
        mHdmiCecLocalDeviceTv.startArcAction(false);

        HdmiCecMessage requestArcInitiation = HdmiCecMessageBuilder.buildRequestArcInitiation(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        HdmiCecMessage requestArcTermination = HdmiCecMessageBuilder.buildRequestArcTermination(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(requestArcInitiation);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(requestArcTermination);
    }

    @Test
    public void startArcAction_enable_portDoesNotSupportArc() {
        // Emulate Audio device on port 0x1000 (does not support ARC)
        mNativeWrapper.setPortConnectionStatus(1, true);
        HdmiCecMessage hdmiCecMessage = HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                ADDR_AUDIO_SYSTEM, 0x1000, HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
        mNativeWrapper.onCecMessage(hdmiCecMessage);

        mHdmiCecLocalDeviceTv.startArcAction(true);
        HdmiCecMessage requestArcInitiation = HdmiCecMessageBuilder.buildRequestArcInitiation(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        HdmiCecMessage requestArcTermination = HdmiCecMessageBuilder.buildRequestArcTermination(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(requestArcInitiation);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(requestArcTermination);
    }

    @Test
    public void startArcAction_disable_portDoesNotSupportArc() {
        // Emulate Audio device on port 0x1000 (does not support ARC)
        mNativeWrapper.setPortConnectionStatus(1, true);
        HdmiCecMessage hdmiCecMessage = HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                ADDR_AUDIO_SYSTEM, 0x1000, HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
        mNativeWrapper.onCecMessage(hdmiCecMessage);

        mHdmiCecLocalDeviceTv.startArcAction(false);
        HdmiCecMessage requestArcInitiation = HdmiCecMessageBuilder.buildRequestArcInitiation(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        HdmiCecMessage requestArcTermination = HdmiCecMessageBuilder.buildRequestArcTermination(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(requestArcInitiation);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(requestArcTermination);
    }

    @Test
    public void startArcAction_enable_portSupportsArc() {
        // Emulate Audio device on port 0x2000 (supports ARC)
        mNativeWrapper.setPortConnectionStatus(2, true);
        HdmiCecMessage hdmiCecMessage = HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                ADDR_AUDIO_SYSTEM, 0x2000, HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
        mNativeWrapper.onCecMessage(hdmiCecMessage);
        mTestLooper.dispatchAll();

        mHdmiCecLocalDeviceTv.startArcAction(true);
        mTestLooper.dispatchAll();
        HdmiCecMessage requestArcInitiation = HdmiCecMessageBuilder.buildRequestArcInitiation(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        HdmiCecMessage requestArcTermination = HdmiCecMessageBuilder.buildRequestArcTermination(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        assertThat(mNativeWrapper.getResultMessages()).contains(requestArcInitiation);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(requestArcTermination);
    }

    @Test
    public void startArcAction_disable_portSupportsArc() {
        // Emulate Audio device on port 0x2000 (supports ARC)
        mNativeWrapper.setPortConnectionStatus(2, true);
        HdmiCecMessage hdmiCecMessage = HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                ADDR_AUDIO_SYSTEM, 0x2000, HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
        mNativeWrapper.onCecMessage(hdmiCecMessage);
        mTestLooper.dispatchAll();

        mHdmiCecLocalDeviceTv.startArcAction(false);
        mTestLooper.dispatchAll();
        HdmiCecMessage requestArcInitiation = HdmiCecMessageBuilder.buildRequestArcInitiation(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        HdmiCecMessage requestArcTermination = HdmiCecMessageBuilder.buildRequestArcTermination(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(requestArcInitiation);
        assertThat(mNativeWrapper.getResultMessages()).contains(requestArcTermination);
    }

    @Test
    public void handleInitiateArc_noAudioDevice() {
        HdmiCecMessage requestArcInitiation = HdmiCecMessageBuilder.buildInitiateArc(
                ADDR_AUDIO_SYSTEM,
                ADDR_TV);

        mNativeWrapper.onCecMessage(requestArcInitiation);
        mTestLooper.dispatchAll();

        HdmiCecMessage reportArcInitiated = HdmiCecMessageBuilder.buildReportArcInitiated(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(reportArcInitiated);
    }

    @Test
    public void handleInitiateArc_portDoesNotSupportArc() {
        // Emulate Audio device on port 0x1000 (does not support ARC)
        mNativeWrapper.setPortConnectionStatus(1, true);
        HdmiCecMessage hdmiCecMessage = HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                ADDR_AUDIO_SYSTEM, 0x1000, HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
        mNativeWrapper.onCecMessage(hdmiCecMessage);

        HdmiCecMessage requestArcInitiation = HdmiCecMessageBuilder.buildInitiateArc(
                ADDR_AUDIO_SYSTEM,
                ADDR_TV);

        mNativeWrapper.onCecMessage(requestArcInitiation);
        mTestLooper.dispatchAll();

        HdmiCecMessage reportArcInitiated = HdmiCecMessageBuilder.buildReportArcInitiated(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(reportArcInitiated);
    }

    @Test
    public void handleInitiateArc_portSupportsArc() {
        // Emulate Audio device on port 0x2000 (supports ARC)
        mNativeWrapper.setPortConnectionStatus(2, true);
        HdmiCecMessage hdmiCecMessage = HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                ADDR_AUDIO_SYSTEM, 0x2000, HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
        mNativeWrapper.onCecMessage(hdmiCecMessage);
        mTestLooper.dispatchAll();

        HdmiCecMessage requestArcInitiation = HdmiCecMessageBuilder.buildInitiateArc(
                ADDR_AUDIO_SYSTEM,
                ADDR_TV);

        mNativeWrapper.onCecMessage(requestArcInitiation);
        mTestLooper.dispatchAll();

        HdmiCecMessage reportArcInitiated = HdmiCecMessageBuilder.buildReportArcInitiated(
                ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        assertThat(mNativeWrapper.getResultMessages()).contains(reportArcInitiated);
    }

    @Test
    public void supportsRecordTvScreen() {
        HdmiCecMessage recordTvScreen = new HdmiCecMessage(ADDR_RECORDER_1, mTvLogicalAddress,
                Constants.MESSAGE_RECORD_TV_SCREEN, HdmiCecMessage.EMPTY_PARAM);

        mNativeWrapper.onCecMessage(recordTvScreen);
        mTestLooper.dispatchAll();

        HdmiCecMessage featureAbort = HdmiCecMessageBuilder.buildFeatureAbortCommand(
                mTvLogicalAddress, ADDR_RECORDER_1, Constants.MESSAGE_RECORD_TV_SCREEN,
                ABORT_UNRECOGNIZED_OPCODE);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(featureAbort);
    }
}
