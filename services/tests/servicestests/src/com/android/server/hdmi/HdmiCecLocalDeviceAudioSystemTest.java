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

import static com.android.server.hdmi.Constants.ADDR_AUDIO_SYSTEM;
import static com.android.server.hdmi.Constants.ADDR_BROADCAST;
import static com.android.server.hdmi.Constants.ADDR_PLAYBACK_1;
import static com.android.server.hdmi.Constants.ADDR_TUNER_1;
import static com.android.server.hdmi.Constants.ADDR_TV;
import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;
import static com.android.server.hdmi.HdmiControlService.STANDBY_SCREEN_OFF;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiPortInfo;
import android.hardware.hdmi.IHdmiControlCallback;
import android.media.AudioManager;
import android.os.Looper;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.server.hdmi.HdmiCecLocalDevice.ActiveSource;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Collections;

@SmallTest
@Presubmit
@RunWith(JUnit4.class)
/** Tests for {@link HdmiCecLocalDeviceAudioSystem} class. */
public class HdmiCecLocalDeviceAudioSystemTest {

    private static final HdmiCecMessage MESSAGE_REQUEST_SAD_LCPM =
            HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                    ADDR_TV, ADDR_AUDIO_SYSTEM, new int[] {Constants.AUDIO_CODEC_LPCM});

    private HdmiControlService mHdmiControlService;
    private HdmiCecController mHdmiCecController;
    private HdmiCecLocalDeviceAudioSystem mHdmiCecLocalDeviceAudioSystem;
    private HdmiCecLocalDevicePlayback mHdmiCecLocalDevicePlayback;
    private FakeNativeWrapper mNativeWrapper;
    private FakePowerManagerWrapper mPowerManager;
    private Looper mMyLooper;
    private TestLooper mTestLooper = new TestLooper();
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();
    private int mMusicVolume;
    private int mMusicMaxVolume;
    private boolean mMusicMute;
    private static final int SELF_PHYSICAL_ADDRESS = 0x2000;
    private static final int HDMI_1_PHYSICAL_ADDRESS = 0x2100;
    private static final int HDMI_2_PHYSICAL_ADDRESS = 0x2200;
    private static final int HDMI_3_PHYSICAL_ADDRESS = 0x2300;
    private int mInvokeDeviceEventState;
    private HdmiDeviceInfo mDeviceInfo;
    private boolean mArcSupport;
    private HdmiPortInfo[] mHdmiPortInfo;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getTargetContext();
        mMyLooper = mTestLooper.getLooper();

        mHdmiControlService =
            new HdmiControlService(InstrumentationRegistry.getTargetContext(),
                    Collections.emptyList()) {
                @Override
                AudioManager getAudioManager() {
                    return new AudioManager() {
                        @Override
                        public int getStreamVolume(int streamType) {
                            switch (streamType) {
                                case STREAM_MUSIC:
                                    return mMusicVolume;
                                default:
                                    return 0;
                            }
                        }

                        @Override
                        public boolean isStreamMute(int streamType) {
                            switch (streamType) {
                                case STREAM_MUSIC:
                                    return mMusicMute;
                                default:
                                    return false;
                            }
                        }

                        @Override
                        public int getStreamMaxVolume(int streamType) {
                            switch (streamType) {
                                case STREAM_MUSIC:
                                    return mMusicMaxVolume;
                                default:
                                    return 100;
                            }
                        }

                        @Override
                        public void adjustStreamVolume(
                                int streamType, int direction, int flags) {
                            switch (streamType) {
                                case STREAM_MUSIC:
                                    if (direction == AudioManager.ADJUST_UNMUTE) {
                                        mMusicMute = false;
                                    } else if (direction == AudioManager.ADJUST_MUTE) {
                                        mMusicMute = true;
                                    }
                                    break;
                                default:
                            }
                        }

                        @Override
                        public void setWiredDeviceConnectionState(
                                int type, int state, String address, String name) {
                            // Do nothing.
                        }
                    };
                }

                @Override
                void invokeDeviceEventListeners(HdmiDeviceInfo device, int status) {
                    mDeviceInfo = device;
                    mInvokeDeviceEventState = status;
                }

                @Override
                protected void writeStringSystemProperty(String key, String value) {
                    // do nothing
                }

                @Override
                boolean readBooleanSystemProperty(String key, boolean defVal) {
                    switch (key) {
                        case Constants.PROPERTY_ARC_SUPPORT:
                            return mArcSupport;
                        default:
                            return defVal;
                    }
                }
            };

        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE,
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        mMyLooper = mTestLooper.getLooper();
        mHdmiControlService.setHdmiCecConfig(new FakeHdmiCecConfig(context));
        mHdmiCecLocalDeviceAudioSystem = new HdmiCecLocalDeviceAudioSystem(mHdmiControlService);
        mHdmiCecLocalDevicePlayback = new HdmiCecLocalDevicePlayback(mHdmiControlService) {
            @Override
            protected int getPreferredAddress() {
                return ADDR_PLAYBACK_1;
            }
        };
        mHdmiCecLocalDeviceAudioSystem.init();
        mHdmiCecLocalDevicePlayback.init();
        mHdmiControlService.setIoLooper(mMyLooper);
        mNativeWrapper = new FakeNativeWrapper();
        mNativeWrapper.setPhysicalAddress(SELF_PHYSICAL_ADDRESS);
        mHdmiCecController = HdmiCecController.createWithNativeWrapper(
                mHdmiControlService, mNativeWrapper, mHdmiControlService.getAtomWriter());
        mHdmiControlService.setCecController(mHdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        mHdmiControlService.setMessageValidator(new HdmiCecMessageValidator(mHdmiControlService));
        mLocalDevices.add(mHdmiCecLocalDeviceAudioSystem);
        mLocalDevices.add(mHdmiCecLocalDevicePlayback);
        mHdmiCecLocalDeviceAudioSystem.setRoutingControlFeatureEnabled(true);
        mHdmiPortInfo = new HdmiPortInfo[4];
        mHdmiPortInfo[0] =
            new HdmiPortInfo(
                0, HdmiPortInfo.PORT_INPUT, SELF_PHYSICAL_ADDRESS, true, false, false);
        mHdmiPortInfo[1] =
            new HdmiPortInfo(
                2, HdmiPortInfo.PORT_INPUT, HDMI_1_PHYSICAL_ADDRESS, true, false, false);
        mHdmiPortInfo[2] =
            new HdmiPortInfo(
                1, HdmiPortInfo.PORT_INPUT, HDMI_2_PHYSICAL_ADDRESS, true, false, false);
        mHdmiPortInfo[3] =
            new HdmiPortInfo(
                4, HdmiPortInfo.PORT_INPUT, HDMI_3_PHYSICAL_ADDRESS, true, false, false);
        mNativeWrapper.setPortInfo(mHdmiPortInfo);
        mHdmiControlService.initService();
        mPowerManager = new FakePowerManagerWrapper(context);
        mHdmiControlService.setPowerManager(mPowerManager);
        // No TV device interacts with AVR so system audio control won't be turned on here
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();
        mArcSupport = true;
        mInvokeDeviceEventState = 0;
        mDeviceInfo = null;
    }

    @Test
    public void handleGiveAudioStatus_volume_10_mute_true() throws Exception {
        mMusicVolume = 10;
        mMusicMute = true;
        mMusicMaxVolume = 20;
        int scaledVolume = VolumeControlAction.scaleToCecVolume(10, mMusicMaxVolume);
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildReportAudioStatus(
                        ADDR_AUDIO_SYSTEM, ADDR_TV, scaledVolume, true);
        HdmiCecMessage messageGive =
                HdmiCecMessageBuilder.buildGiveAudioStatus(ADDR_TV, ADDR_AUDIO_SYSTEM);
        assertThat(mHdmiCecLocalDeviceAudioSystem.handleGiveAudioStatus(messageGive))
            .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getOnlyResultMessage()).isEqualTo(expectedMessage);
    }

    @Test
    @Ignore("b/120845532")
    public void handleGiveSystemAudioModeStatus_originalOff() throws Exception {
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildReportSystemAudioMode(
                        ADDR_AUDIO_SYSTEM, ADDR_TV, false);
        HdmiCecMessage messageGive =
                HdmiCecMessageBuilder.buildGiveSystemAudioModeStatus(ADDR_TV, ADDR_AUDIO_SYSTEM);
        assertThat(mHdmiCecLocalDeviceAudioSystem.handleGiveSystemAudioModeStatus(messageGive))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getOnlyResultMessage()).isEqualTo(expectedMessage);
    }

    @Test
    public void handleRequestShortAudioDescriptor_featureDisabled() throws Exception {
        mHdmiCecLocalDeviceAudioSystem.setSystemAudioControlFeatureEnabled(false);
        assertThat(mHdmiCecLocalDeviceAudioSystem.handleRequestShortAudioDescriptor(
                MESSAGE_REQUEST_SAD_LCPM))
                .isEqualTo(Constants.ABORT_REFUSED);
    }

    @Test
    public void handleRequestShortAudioDescriptor_samOff() throws Exception {
        mHdmiCecLocalDeviceAudioSystem.checkSupportAndSetSystemAudioMode(false);
        assertThat(mHdmiCecLocalDeviceAudioSystem.handleRequestShortAudioDescriptor(
                MESSAGE_REQUEST_SAD_LCPM))
                .isEqualTo(Constants.ABORT_NOT_IN_CORRECT_MODE);
    }

    // Testing device has sadConfig.xml
    @Ignore("b/120845532")
    @Test
    public void handleRequestShortAudioDescriptor_noAudioDeviceInfo() throws Exception {
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildFeatureAbortCommand(
                        ADDR_AUDIO_SYSTEM,
                        ADDR_TV,
                        Constants.MESSAGE_REQUEST_SHORT_AUDIO_DESCRIPTOR,
                        Constants.ABORT_UNABLE_TO_DETERMINE);

        mHdmiCecLocalDeviceAudioSystem.checkSupportAndSetSystemAudioMode(true);
        assertThat(mHdmiCecLocalDeviceAudioSystem.handleRequestShortAudioDescriptor(
                MESSAGE_REQUEST_SAD_LCPM))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getOnlyResultMessage()).isEqualTo(expectedMessage);
    }

    @Test
    @Ignore("b/120845532")
    public void handleSetSystemAudioMode_setOn_orignalOff() throws Exception {
        mMusicMute = true;
        HdmiCecMessage messageSet =
                HdmiCecMessageBuilder.buildSetSystemAudioMode(ADDR_TV, ADDR_AUDIO_SYSTEM, true);
        HdmiCecMessage messageGive =
                HdmiCecMessageBuilder.buildGiveSystemAudioModeStatus(ADDR_TV, ADDR_AUDIO_SYSTEM);
        // Check if originally off
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildReportSystemAudioMode(ADDR_AUDIO_SYSTEM, ADDR_TV, false);
        assertThat(mHdmiCecLocalDeviceAudioSystem.handleGiveSystemAudioModeStatus(messageGive))
            .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getOnlyResultMessage()).isEqualTo(expectedMessage);
        // Check if correctly turned on
        mNativeWrapper.clearResultMessages();
        expectedMessage =
            HdmiCecMessageBuilder.buildReportSystemAudioMode(ADDR_AUDIO_SYSTEM, ADDR_TV, true);
        assertThat(mHdmiCecLocalDeviceAudioSystem.handleSetSystemAudioMode(messageSet))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mHdmiCecLocalDeviceAudioSystem.handleGiveSystemAudioModeStatus(messageGive))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getOnlyResultMessage()).isEqualTo(expectedMessage);
        assertThat(mMusicMute).isFalse();
    }

    @Test
    @Ignore("b/120845532")
    public void handleSystemAudioModeRequest_turnOffByTv() throws Exception {
        assertThat(mMusicMute).isFalse();
        // Check if feature correctly turned off
        HdmiCecMessage messageGive =
                HdmiCecMessageBuilder.buildGiveSystemAudioModeStatus(ADDR_TV, ADDR_AUDIO_SYSTEM);
        HdmiCecMessage messageRequestOff =
                HdmiCecMessageBuilder.buildSystemAudioModeRequest(
                        ADDR_TV, ADDR_AUDIO_SYSTEM, 2, false);
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildSetSystemAudioMode(
                        ADDR_AUDIO_SYSTEM, ADDR_BROADCAST, false);
        assertThat(mHdmiCecLocalDeviceAudioSystem.handleSystemAudioModeRequest(messageRequestOff))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getOnlyResultMessage()).isEqualTo(expectedMessage);

        mNativeWrapper.clearResultMessages();
        expectedMessage =
            HdmiCecMessageBuilder.buildReportSystemAudioMode(ADDR_AUDIO_SYSTEM, ADDR_TV, false);
        assertThat(mHdmiCecLocalDeviceAudioSystem.handleGiveSystemAudioModeStatus(messageGive))
            .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getOnlyResultMessage()).isEqualTo(expectedMessage);
        assertThat(mMusicMute).isTrue();
    }

    @Test
    public void onStandbyAudioSystem_currentSystemAudioControlOn() throws Exception {
        // Set system audio control on first
        mHdmiCecLocalDeviceAudioSystem.checkSupportAndSetSystemAudioMode(true);
        // Check if standby correctly turns off the feature
        mHdmiCecLocalDeviceAudioSystem.onStandby(false, STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildSetSystemAudioMode(
                        ADDR_AUDIO_SYSTEM, ADDR_BROADCAST, false);
        assertThat(mNativeWrapper.getOnlyResultMessage()).isEqualTo(expectedMessage);
        assertThat(mMusicMute).isTrue();
    }

    @Test
    public void systemAudioControlOnPowerOn_alwaysOn() throws Exception {
        mHdmiCecLocalDeviceAudioSystem.removeAction(SystemAudioInitiationActionFromAvr.class);
        mHdmiCecLocalDeviceAudioSystem.systemAudioControlOnPowerOn(
                Constants.ALWAYS_SYSTEM_AUDIO_CONTROL_ON_POWER_ON, true);
        assertThat(
            mHdmiCecLocalDeviceAudioSystem.getActions(
                SystemAudioInitiationActionFromAvr.class))
            .isNotEmpty();
    }

    @Test
    public void systemAudioControlOnPowerOn_neverOn() throws Exception {
        mHdmiCecLocalDeviceAudioSystem.removeAction(SystemAudioInitiationActionFromAvr.class);
        mHdmiCecLocalDeviceAudioSystem.systemAudioControlOnPowerOn(
                Constants.NEVER_SYSTEM_AUDIO_CONTROL_ON_POWER_ON, false);
        assertThat(
            mHdmiCecLocalDeviceAudioSystem.getActions(
                SystemAudioInitiationActionFromAvr.class))
            .isEmpty();
    }

    @Test
    public void systemAudioControlOnPowerOn_useLastState_off() throws Exception {
        mHdmiCecLocalDeviceAudioSystem.removeAction(SystemAudioInitiationActionFromAvr.class);
        mHdmiCecLocalDeviceAudioSystem.systemAudioControlOnPowerOn(
                Constants.USE_LAST_STATE_SYSTEM_AUDIO_CONTROL_ON_POWER_ON, false);
        assertThat(
            mHdmiCecLocalDeviceAudioSystem.getActions(
                SystemAudioInitiationActionFromAvr.class))
            .isEmpty();
    }

    @Test
    public void systemAudioControlOnPowerOn_useLastState_on() throws Exception {
        mHdmiCecLocalDeviceAudioSystem.removeAction(SystemAudioInitiationActionFromAvr.class);
        mHdmiCecLocalDeviceAudioSystem.systemAudioControlOnPowerOn(
                Constants.USE_LAST_STATE_SYSTEM_AUDIO_CONTROL_ON_POWER_ON, true);
        assertThat(
            mHdmiCecLocalDeviceAudioSystem.getActions(
                SystemAudioInitiationActionFromAvr.class))
            .isNotEmpty();
    }

    @Test
    public void handleActiveSource_updateActiveSource() throws Exception {
        HdmiCecMessage message = HdmiCecMessageBuilder.buildActiveSource(ADDR_TV, 0x0000);
        ActiveSource expectedActiveSource = new ActiveSource(ADDR_TV, 0x0000);
        assertThat(mHdmiCecLocalDeviceAudioSystem.handleActiveSource(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mHdmiCecLocalDeviceAudioSystem.getActiveSource().equals(expectedActiveSource))
            .isTrue();
    }

    @Test
    public void systemAudioModeMuting_enabled() {
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING,
                HdmiControlManager.SYSTEM_AUDIO_MODE_MUTING_ENABLED);
        mHdmiCecLocalDeviceAudioSystem.checkSupportAndSetSystemAudioMode(true);
        assertThat(mHdmiCecLocalDeviceAudioSystem.isSystemAudioActivated()).isTrue();
        assertThat(mHdmiControlService.getAudioManager().isStreamMute(
                AudioManager.STREAM_MUSIC)).isFalse();
        mHdmiCecLocalDeviceAudioSystem.checkSupportAndSetSystemAudioMode(false);
        assertThat(mHdmiCecLocalDeviceAudioSystem.isSystemAudioActivated()).isFalse();
        assertThat(mHdmiControlService.getAudioManager().isStreamMute(
                AudioManager.STREAM_MUSIC)).isTrue();
    }

    @Test
    public void systemAudioModeMuting_disabled() {
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING,
                HdmiControlManager.SYSTEM_AUDIO_MODE_MUTING_DISABLED);
        mHdmiCecLocalDeviceAudioSystem.checkSupportAndSetSystemAudioMode(true);
        assertThat(mHdmiCecLocalDeviceAudioSystem.isSystemAudioActivated()).isTrue();
        assertThat(mHdmiControlService.getAudioManager().isStreamMute(
                AudioManager.STREAM_MUSIC)).isFalse();
        mHdmiCecLocalDeviceAudioSystem.checkSupportAndSetSystemAudioMode(false);
        assertThat(mHdmiCecLocalDeviceAudioSystem.isSystemAudioActivated()).isFalse();
        assertThat(mHdmiControlService.getAudioManager().isStreamMute(
                AudioManager.STREAM_MUSIC)).isFalse();
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING,
                HdmiControlManager.SYSTEM_AUDIO_MODE_MUTING_ENABLED);
    }

    @Test
    public void terminateSystemAudioMode_systemAudioModeOff() throws Exception {
        mHdmiCecLocalDeviceAudioSystem.checkSupportAndSetSystemAudioMode(false);
        assertThat(mHdmiCecLocalDeviceAudioSystem.isSystemAudioActivated()).isFalse();
        mMusicMute = false;
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildSetSystemAudioMode(
                        ADDR_AUDIO_SYSTEM, ADDR_BROADCAST, false);
        mHdmiCecLocalDeviceAudioSystem.terminateSystemAudioMode();
        assertThat(mHdmiCecLocalDeviceAudioSystem.isSystemAudioActivated()).isFalse();
        assertThat(mMusicMute).isFalse();
        assertThat(mNativeWrapper.getResultMessages()).isEmpty();
    }

    @Test
    public void terminateSystemAudioMode_systemAudioModeOn() throws Exception {
        mHdmiCecLocalDeviceAudioSystem.checkSupportAndSetSystemAudioMode(true);
        assertThat(mHdmiCecLocalDeviceAudioSystem.isSystemAudioActivated()).isTrue();
        mMusicMute = false;
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildSetSystemAudioMode(
                        ADDR_AUDIO_SYSTEM, ADDR_BROADCAST, false);
        mHdmiCecLocalDeviceAudioSystem.terminateSystemAudioMode();
        assertThat(mHdmiCecLocalDeviceAudioSystem.isSystemAudioActivated()).isFalse();
        assertThat(mMusicMute).isTrue();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(expectedMessage);
    }

    @Test
    public void handleRequestArcInitiate_isNotDirectConnectedToTv() throws Exception {
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRequestArcInitiation(ADDR_TV, ADDR_AUDIO_SYSTEM);
        mNativeWrapper.setPhysicalAddress(0x1100);

        assertThat(mHdmiCecLocalDeviceAudioSystem.handleRequestArcInitiate(message))
                .isEqualTo(Constants.ABORT_NOT_IN_CORRECT_MODE);
    }

    @Test
    public void handleRequestArcInitiate_startArcInitiationActionFromAvr() throws Exception {
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRequestArcInitiation(ADDR_TV, ADDR_AUDIO_SYSTEM);
        mNativeWrapper.setPhysicalAddress(0x1000);
        mHdmiCecLocalDeviceAudioSystem.removeAction(ArcInitiationActionFromAvr.class);

        assertThat(mHdmiCecLocalDeviceAudioSystem.handleRequestArcInitiate(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mHdmiCecLocalDeviceAudioSystem.getActions(ArcInitiationActionFromAvr.class))
            .isNotEmpty();
    }

    @Test
    public void handleRequestArcTerminate_arcIsOn_startTerminationActionFromAvr() throws Exception {
        mHdmiCecLocalDeviceAudioSystem.setArcStatus(true);
        assertThat(mHdmiCecLocalDeviceAudioSystem.isArcEnabled()).isTrue();

        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRequestArcTermination(ADDR_TV, ADDR_AUDIO_SYSTEM);
        mHdmiCecLocalDeviceAudioSystem.removeAction(ArcTerminationActionFromAvr.class);

        assertThat(mHdmiCecLocalDeviceAudioSystem.handleRequestArcTermination(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mHdmiCecLocalDeviceAudioSystem.getActions(ArcTerminationActionFromAvr.class))
            .isNotEmpty();
    }

    @Test
    @Ignore("b/120845532")
    public void handleRequestArcTerminate_arcIsNotOn() throws Exception {
        assertThat(mHdmiCecLocalDeviceAudioSystem.isArcEnabled()).isFalse();
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRequestArcTermination(ADDR_TV, ADDR_AUDIO_SYSTEM);
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildFeatureAbortCommand(
                        ADDR_AUDIO_SYSTEM,
                        ADDR_TV,
                        Constants.MESSAGE_REQUEST_ARC_TERMINATION,
                        Constants.ABORT_NOT_IN_CORRECT_MODE);

        assertThat(mHdmiCecLocalDeviceAudioSystem.handleRequestArcTermination(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(expectedMessage);
    }

    @Test
    public void handleRequestArcInit_arcIsNotSupported() throws Exception {
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRequestArcInitiation(ADDR_TV, ADDR_AUDIO_SYSTEM);
        mArcSupport = false;

        assertThat(mHdmiCecLocalDeviceAudioSystem.handleRequestArcInitiate(message))
                .isEqualTo(Constants.ABORT_UNRECOGNIZED_OPCODE);
    }

    @Test
    public void setArcStatus() {
        mHdmiCecLocalDeviceAudioSystem.setArcStatus(true);
        assertThat(mHdmiCecLocalDeviceAudioSystem.isArcEnabled()).isTrue();

        mHdmiCecLocalDeviceAudioSystem.setArcStatus(false);
        assertThat(mHdmiCecLocalDeviceAudioSystem.isArcEnabled()).isFalse();
    }

    @Test
    @Ignore("b/151150320")
    public void handleSystemAudioModeRequest_fromNonTV_tVNotSupport() {
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildSystemAudioModeRequest(
                        ADDR_TUNER_1, ADDR_AUDIO_SYSTEM,
                        SELF_PHYSICAL_ADDRESS, true);
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildFeatureAbortCommand(
                        ADDR_AUDIO_SYSTEM,
                        ADDR_TUNER_1,
                        Constants.MESSAGE_SYSTEM_AUDIO_MODE_REQUEST,
                        Constants.ABORT_REFUSED);

        assertThat(mHdmiCecLocalDeviceAudioSystem.handleSystemAudioModeRequest(message))
                .isEqualTo(Constants.ABORT_UNRECOGNIZED_OPCODE);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getOnlyResultMessage()).isEqualTo(expectedMessage);
    }

    @Test
    public void handleSystemAudioModeRequest_fromNonTV_tVSupport() {
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildSystemAudioModeRequest(
                        ADDR_TUNER_1, ADDR_AUDIO_SYSTEM,
                        SELF_PHYSICAL_ADDRESS, true);
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildSetSystemAudioMode(
                        ADDR_AUDIO_SYSTEM, Constants.ADDR_BROADCAST, true);
        mHdmiCecLocalDeviceAudioSystem.setTvSystemAudioModeSupport(true);


        assertThat(mHdmiCecLocalDeviceAudioSystem.handleSystemAudioModeRequest(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getOnlyResultMessage()).isEqualTo(expectedMessage);
    }

    @Test
    public void handleActiveSource_activeSourceFromTV_swithToArc() {
        mHdmiCecLocalDeviceAudioSystem.setArcStatus(true);
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildActiveSource(ADDR_TV, 0x0000);

        ActiveSource expectedActiveSource = ActiveSource.of(ADDR_TV, 0x0000);

        assertThat(mHdmiCecLocalDeviceAudioSystem.handleActiveSource(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mHdmiCecLocalDeviceAudioSystem.getActiveSource())
            .isEqualTo(expectedActiveSource);
    }

    @Test
    @Ignore("b/151150320")
    public void handleRoutingChange_currentActivePortIsHome() {
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x3000, SELF_PHYSICAL_ADDRESS);

        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildActiveSource(ADDR_PLAYBACK_1, SELF_PHYSICAL_ADDRESS);
        ActiveSource expectedActiveSource = ActiveSource.of(ADDR_PLAYBACK_1, SELF_PHYSICAL_ADDRESS);
        int expectedLocalActivePort = Constants.CEC_SWITCH_HOME;

        assertThat(mHdmiCecLocalDeviceAudioSystem.handleRoutingChange(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mHdmiCecLocalDeviceAudioSystem.getActiveSource())
            .isEqualTo(expectedActiveSource);
        assertThat(mHdmiCecLocalDeviceAudioSystem.getRoutingPort())
            .isEqualTo(expectedLocalActivePort);
        assertThat(mNativeWrapper.getResultMessages()).contains(expectedMessage);
    }

    @Test
    public void handleRoutingInformation_currentActivePortIsHDMI1() {
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingInformation(ADDR_TV, SELF_PHYSICAL_ADDRESS);
        mHdmiCecLocalDeviceAudioSystem.setRoutingPort(mHdmiPortInfo[1].getId());
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildRoutingInformation(
                        ADDR_AUDIO_SYSTEM, HDMI_1_PHYSICAL_ADDRESS);

        assertThat(mHdmiCecLocalDeviceAudioSystem.handleRoutingInformation(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getOnlyResultMessage()).isEqualTo(expectedMessage);
    }

    @Ignore("b/120845532")
    @Test
    public void handleRoutingChange_homeIsActive_playbackSendActiveSource() {
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingChange(ADDR_TV, 0x0000, 0x2000);

        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildActiveSource(ADDR_PLAYBACK_1, 0x2000);

        assertThat(mHdmiCecLocalDeviceAudioSystem.handleRoutingChange(message))
                .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(expectedMessage);
    }

    @Test
    public void doNotWakeUpOnHotPlug_PlugIn() {
        mPowerManager.setInteractive(false);
        mHdmiCecLocalDeviceAudioSystem.onHotplug(0, true);
        assertThat(mPowerManager.isInteractive()).isFalse();
    }

    @Test
    public void doNotWakeUpOnHotPlug_PlugOut() {
        mPowerManager.setInteractive(false);
        mHdmiCecLocalDeviceAudioSystem.onHotplug(0, false);
        assertThat(mPowerManager.isInteractive()).isFalse();
    }

    @Test
    public void giveAudioStatus_volumeEnabled() {
        mMusicVolume = 50;
        mMusicMaxVolume = 100;
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        mHdmiCecLocalDeviceAudioSystem.setSystemAudioControlFeatureEnabled(true);

        int volume = mHdmiControlService.getAudioManager()
                .getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean mute = mHdmiControlService.getAudioManager()
                .isStreamMute(AudioManager.STREAM_MUSIC);
        int maxVolume = mHdmiControlService.getAudioManager()
                .getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int scaledVolume = VolumeControlAction.scaleToCecVolume(volume, maxVolume);
        HdmiCecMessage expected = HdmiCecMessageBuilder.buildReportAudioStatus(ADDR_AUDIO_SYSTEM,
                ADDR_TV, scaledVolume, mute);

        HdmiCecMessage giveAudioStatus = HdmiCecMessageBuilder.buildGiveAudioStatus(ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        mNativeWrapper.clearResultMessages();
        assertThat(mHdmiCecLocalDeviceAudioSystem.handleGiveAudioStatus(giveAudioStatus))
                        .isEqualTo(Constants.HANDLED);
        mTestLooper.dispatchAll();

        assertThat(mNativeWrapper.getResultMessages()).contains(expected);
    }

    @Test
    public void giveAudioStatus_volumeDisabled() {
        mMusicVolume = 50;
        mMusicMaxVolume = 100;
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);
        mHdmiCecLocalDeviceAudioSystem.setSystemAudioControlFeatureEnabled(true);

        int volume = mHdmiControlService.getAudioManager()
                .getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean mute = mHdmiControlService.getAudioManager()
                .isStreamMute(AudioManager.STREAM_MUSIC);
        int maxVolume = mHdmiControlService.getAudioManager()
                .getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int scaledVolume = VolumeControlAction.scaleToCecVolume(volume, maxVolume);
        HdmiCecMessage unexpected = HdmiCecMessageBuilder.buildReportAudioStatus(ADDR_AUDIO_SYSTEM,
                ADDR_TV, scaledVolume, mute);

        HdmiCecMessage giveAudioStatus = HdmiCecMessageBuilder.buildGiveAudioStatus(ADDR_TV,
                ADDR_AUDIO_SYSTEM);
        mNativeWrapper.clearResultMessages();
        assertThat(mHdmiCecLocalDeviceAudioSystem.handleGiveAudioStatus(giveAudioStatus))
                .isEqualTo(Constants.ABORT_REFUSED);
        mTestLooper.dispatchAll();

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(unexpected);
    }

    @Test
    public void reportAudioStatus_volumeEnabled() {
        mMusicVolume = 50;
        mMusicMaxVolume = 100;
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        mHdmiCecLocalDeviceAudioSystem.setSystemAudioControlFeatureEnabled(true);

        int volume = mHdmiControlService.getAudioManager()
                .getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean mute = mHdmiControlService.getAudioManager()
                .isStreamMute(AudioManager.STREAM_MUSIC);
        int maxVolume = mHdmiControlService.getAudioManager()
                .getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int scaledVolume = VolumeControlAction.scaleToCecVolume(volume, maxVolume);
        HdmiCecMessage expected = HdmiCecMessageBuilder.buildReportAudioStatus(ADDR_AUDIO_SYSTEM,
                ADDR_TV, scaledVolume, mute);

        mNativeWrapper.clearResultMessages();
        mHdmiCecLocalDeviceAudioSystem.reportAudioStatus(ADDR_TV);
        mTestLooper.dispatchAll();

        assertThat(mNativeWrapper.getResultMessages()).contains(expected);
    }

    @Test
    public void reportAudioStatus_volumeDisabled() {
        mMusicVolume = 50;
        mMusicMaxVolume = 100;
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);
        mHdmiCecLocalDeviceAudioSystem.setSystemAudioControlFeatureEnabled(true);

        int volume = mHdmiControlService.getAudioManager()
                .getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean mute = mHdmiControlService.getAudioManager()
                .isStreamMute(AudioManager.STREAM_MUSIC);
        int maxVolume = mHdmiControlService.getAudioManager()
                .getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int scaledVolume = VolumeControlAction.scaleToCecVolume(volume, maxVolume);
        HdmiCecMessage unexpected = HdmiCecMessageBuilder.buildReportAudioStatus(ADDR_AUDIO_SYSTEM,
                ADDR_TV, scaledVolume, mute);

        mNativeWrapper.clearResultMessages();
        mHdmiCecLocalDeviceAudioSystem.reportAudioStatus(ADDR_TV);
        mTestLooper.dispatchAll();

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(unexpected);
    }

    @Test
    public void setActiveSource_localDevice_playback() {
        mHdmiControlService.setActiveSource(
                mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                SELF_PHYSICAL_ADDRESS,
                "HdmiControlServiceTest");

        assertThat(mHdmiControlService.getLocalActiveSource().logicalAddress)
                .isEqualTo(mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress());
        assertThat(mHdmiControlService.getLocalActiveSource().physicalAddress).isEqualTo(
                SELF_PHYSICAL_ADDRESS);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isTrue();
        assertThat(mHdmiCecLocalDeviceAudioSystem.isActiveSource()).isFalse();
    }

    @Test
    public void setActiveSource_localDevice_audio() {
        mHdmiControlService.setActiveSource(
                mHdmiCecLocalDeviceAudioSystem.getDeviceInfo().getLogicalAddress(),
                SELF_PHYSICAL_ADDRESS,
                "HdmiControlServiceTest");

        assertThat(mHdmiControlService.getLocalActiveSource().logicalAddress)
                .isEqualTo(mHdmiCecLocalDeviceAudioSystem.getDeviceInfo().getLogicalAddress());
        assertThat(mHdmiControlService.getLocalActiveSource().physicalAddress).isEqualTo(
                SELF_PHYSICAL_ADDRESS);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mHdmiCecLocalDeviceAudioSystem.isActiveSource()).isTrue();
    }

    @Test
    public void setActiveSource_remoteDevice() {
        mHdmiControlService.setActiveSource(Constants.ADDR_TV, 0x0000, "HdmiControlServiceTest");

        assertThat(mHdmiControlService.getLocalActiveSource().logicalAddress).isEqualTo(
                Constants.ADDR_TV);
        assertThat(mHdmiControlService.getLocalActiveSource().physicalAddress).isEqualTo(0x000);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mHdmiCecLocalDeviceAudioSystem.isActiveSource()).isFalse();
    }

    @Test
    public void setActiveSource_nonCecDevice() {
        mHdmiControlService.setActiveSource(Constants.ADDR_INVALID, 0x1234,
                "HdmiControlServiceTest");

        assertThat(mHdmiControlService.getLocalActiveSource().logicalAddress).isEqualTo(
                Constants.ADDR_INVALID);
        assertThat(mHdmiControlService.getLocalActiveSource().physicalAddress).isEqualTo(0x1234);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mHdmiCecLocalDeviceAudioSystem.isActiveSource()).isFalse();
    }

    @Test
    public void setActiveSource_unknown() {
        mHdmiControlService.setActiveSource(Constants.ADDR_INVALID,
                Constants.INVALID_PHYSICAL_ADDRESS, "HdmiControlServiceTest");

        assertThat(mHdmiControlService.getLocalActiveSource().logicalAddress).isEqualTo(
                Constants.ADDR_INVALID);
        assertThat(mHdmiControlService.getLocalActiveSource().physicalAddress).isEqualTo(
                Constants.INVALID_PHYSICAL_ADDRESS);
        assertThat(mHdmiCecLocalDevicePlayback.isActiveSource()).isFalse();
        assertThat(mHdmiCecLocalDeviceAudioSystem.isActiveSource()).isFalse();
    }

    @Test
    @Ignore("b/151150320")
    public void oneTouchPlay() {
        mHdmiControlService.oneTouchPlay(new IHdmiControlCallback.Stub() {
            @Override
            public void onComplete(int result) {
            }
        });
        mTestLooper.dispatchAll();

        HdmiCecMessage textViewOn_fromPlayback = HdmiCecMessageBuilder.buildTextViewOn(
                mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        HdmiCecMessage activeSource_fromPlayback = HdmiCecMessageBuilder.buildActiveSource(
                mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                SELF_PHYSICAL_ADDRESS);
        HdmiCecMessage systemAudioModeRequest_fromPlayback =
                HdmiCecMessageBuilder.buildSystemAudioModeRequest(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        ADDR_AUDIO_SYSTEM, SELF_PHYSICAL_ADDRESS, true);
        HdmiCecMessage textViewOn_fromAudioSystem = HdmiCecMessageBuilder.buildTextViewOn(
                mHdmiCecLocalDeviceAudioSystem.getDeviceInfo().getLogicalAddress(), ADDR_TV);
        HdmiCecMessage activeSource_fromAudioSystem = HdmiCecMessageBuilder.buildActiveSource(
                mHdmiCecLocalDeviceAudioSystem.getDeviceInfo().getLogicalAddress(),
                SELF_PHYSICAL_ADDRESS);
        HdmiCecMessage systemAudioModeRequest_fromAudioSystem =
                HdmiCecMessageBuilder.buildSystemAudioModeRequest(
                        mHdmiCecLocalDeviceAudioSystem.getDeviceInfo().getLogicalAddress(),
                        ADDR_AUDIO_SYSTEM, SELF_PHYSICAL_ADDRESS, true);
        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn_fromPlayback);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource_fromPlayback);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(
                systemAudioModeRequest_fromPlayback);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(textViewOn_fromAudioSystem);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(activeSource_fromAudioSystem);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(
                systemAudioModeRequest_fromAudioSystem);
    }
}
