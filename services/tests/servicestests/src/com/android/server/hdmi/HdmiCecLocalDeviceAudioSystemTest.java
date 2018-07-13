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
import static com.android.server.hdmi.Constants.ADDR_TV;
import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.assertEquals;

import android.hardware.hdmi.HdmiPortInfo;
import android.media.AudioManager;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.test.TestLooper;
import android.support.test.filters.SmallTest;
import com.android.server.hdmi.HdmiCecController.NativeWrapper;
import java.util.Arrays;
import java.util.ArrayList;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@SmallTest
@RunWith(JUnit4.class)
/**
 * Tests for {@link HdmiCecLocalDeviceAudioSystem} class.
 */
public class HdmiCecLocalDeviceAudioSystemTest {

    private static final class NativeWrapperImpl implements NativeWrapper {

        private HdmiCecMessage mResultMessage;

        @Override
        public long nativeInit(HdmiCecController handler, MessageQueue messageQueue) {
            return 1L;
        }

        @Override
        public int nativeSendCecCommand(long controllerPtr, int srcAddress, int dstAddress,
            byte[] body) {
            if (body.length != 0) {
                mResultMessage = HdmiCecMessageBuilder.of(srcAddress, dstAddress, body);
            }
            return 1;
        }

        @Override
        public int nativeAddLogicalAddress(long controllerPtr, int logicalAddress) {
            return 0;
        }

        @Override
        public void nativeClearLogicalAddress(long controllerPtr) {

        }

        @Override
        public int nativeGetPhysicalAddress(long controllerPtr) {
            return 0;
        }

        @Override
        public int nativeGetVersion(long controllerPtr) {
            return 0;
        }

        @Override
        public int nativeGetVendorId(long controllerPtr) {
            return 0;
        }

        @Override
        public HdmiPortInfo[] nativeGetPortInfos(long controllerPtr) {
            HdmiPortInfo[] hdmiPortInfo = new HdmiPortInfo[1];
            hdmiPortInfo[0] = new HdmiPortInfo(1, 1, 0x1000,true, true, true);
            return hdmiPortInfo;
        }

        @Override
        public void nativeSetOption(long controllerPtr, int flag, boolean enabled) {

        }

        @Override
        public void nativeSetLanguage(long controllerPtr, String language) {

        }

        @Override
        public void nativeEnableAudioReturnChannel(long controllerPtr, int port, boolean flag) {

        }

        @Override
        public boolean nativeIsConnected(long controllerPtr, int port) {
            return false;
        }

        public HdmiCecMessage getResultMessage() {
            return mResultMessage;
        }
    }

    private HdmiControlService mHdmiControlService;
    private HdmiCecController mHdmiCecController;
    private HdmiCecLocalDeviceAudioSystem mHdmiCecLocalDeviceAudioSystem;
    private NativeWrapperImpl mNativeWrapper;
    private Looper mMyLooper;
    private TestLooper mTestLooper = new TestLooper();
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();
    private int mMusicVolume;
    private int mMusicMaxVolume;
    private boolean mMusicMute;

    @Before
    public void SetUp() {
        mHdmiControlService = new HdmiControlService(null) {
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
                    public void adjustStreamVolume(int streamType, int direction, int flags) {
                        switch (streamType) {
                            case STREAM_MUSIC:
                                if (direction == AudioManager.ADJUST_UNMUTE) {
                                    mMusicMute = false;
                                } else if (direction == AudioManager.ADJUST_MUTE) {
                                    mMusicMute = true;
                                }
                            default:
                        }
                    }
                };
            }
        };
        mMyLooper = mTestLooper.getLooper();
        mHdmiCecLocalDeviceAudioSystem = new HdmiCecLocalDeviceAudioSystem(mHdmiControlService);
        mHdmiCecLocalDeviceAudioSystem.init();
        mHdmiControlService.setIoLooper(mMyLooper);

        mNativeWrapper = new NativeWrapperImpl();
        mHdmiCecController = HdmiCecController.createWithNativeWrapper(
            mHdmiControlService, mNativeWrapper);
        mHdmiControlService.setCecController(mHdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        mHdmiControlService.setMessageValidator(new HdmiCecMessageValidator(mHdmiControlService));

        mLocalDevices.add(mHdmiCecLocalDeviceAudioSystem);
        mHdmiControlService.initPortInfo();
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);

        mTestLooper.dispatchAll();
    }

    @Test
    public void handleGiveAudioStatus_volume_10_mute_true() {
        mMusicVolume = 10;
        mMusicMute = true;
        mMusicMaxVolume = 20;
        int scaledVolume = VolumeControlAction.scaleToCecVolume(10, mMusicMaxVolume);
        HdmiCecMessage expectMessage = HdmiCecMessageBuilder.buildReportAudioStatus(
            ADDR_AUDIO_SYSTEM, ADDR_TV, scaledVolume, true);
        HdmiCecMessage messageGive = HdmiCecMessageBuilder.buildGiveAudioStatus(
            ADDR_TV, ADDR_AUDIO_SYSTEM);

        assertTrue(mHdmiCecLocalDeviceAudioSystem.handleGiveAudioStatus(messageGive));
        mTestLooper.dispatchAll();
        assertEquals(expectMessage, mNativeWrapper.getResultMessage());
    }

    @Test
    public void handleGiveSystemAudioModeStatus_off() {
        HdmiCecMessage expectMessage = HdmiCecMessageBuilder
            .buildReportSystemAudioMode(ADDR_AUDIO_SYSTEM, ADDR_TV, false);
        HdmiCecMessage messageGive = HdmiCecMessageBuilder
            .buildGiveSystemAudioModeStatus(ADDR_TV, ADDR_AUDIO_SYSTEM);

        assertTrue(mHdmiCecLocalDeviceAudioSystem.handleGiveSystemAudioModeStatus(messageGive));
        mTestLooper.dispatchAll();
        assertEquals(expectMessage, mNativeWrapper.getResultMessage());
    }

    @Test
    public void handleRequestArcInitiate() {
        // TODO(b/80296911): Add tests when finishing handler impl.
        HdmiCecMessage expectMessage = HdmiCecMessageBuilder
            .buildInitiateArc(ADDR_AUDIO_SYSTEM, ADDR_TV);
        HdmiCecMessage message = HdmiCecMessageBuilder
            .buildRequestArcInitiation(ADDR_TV, ADDR_AUDIO_SYSTEM);

        assertTrue(mHdmiCecLocalDeviceAudioSystem.handleRequestArcInitiate(message));
        mTestLooper.dispatchAll();
        assertEquals(expectMessage, mNativeWrapper.getResultMessage());
    }

    @Test
    public void handleRequestArcTermination() {
        // TODO(b/80297105): Add tests when finishing handler impl.
        HdmiCecMessage expectMessage = HdmiCecMessageBuilder
            .buildTerminateArc(ADDR_AUDIO_SYSTEM, ADDR_TV);
        HdmiCecMessage messageRequestOff = HdmiCecMessageBuilder
            .buildRequestArcTermination(ADDR_TV, ADDR_AUDIO_SYSTEM);

        assertTrue(mHdmiCecLocalDeviceAudioSystem.handleRequestArcTermination(messageRequestOff));
        mTestLooper.dispatchAll();
        assertEquals(expectMessage, mNativeWrapper.getResultMessage());
    }

    @Test
    public void handleSystemAudioModeRequest_turnOffByTv_originalOff() {
        HdmiCecMessage messageRequest = HdmiCecMessageBuilder
            .buildSystemAudioModeRequest(ADDR_TV, ADDR_AUDIO_SYSTEM, 2, false);
        HdmiCecMessage expectMessage = HdmiCecMessageBuilder
            .buildSetSystemAudioMode(ADDR_AUDIO_SYSTEM, ADDR_BROADCAST, false);

        assertTrue(mHdmiCecLocalDeviceAudioSystem.handleSystemAudioModeRequest(messageRequest));
        mTestLooper.dispatchAll();
        assertEquals(expectMessage, mNativeWrapper.getResultMessage());
    }

    @Test
    public void handleSetSystemAudioMode_setOn() {
        HdmiCecMessage messageSet = HdmiCecMessageBuilder
            .buildSetSystemAudioMode(ADDR_TV, ADDR_AUDIO_SYSTEM, true);
        HdmiCecMessage messageGive = HdmiCecMessageBuilder
            .buildGiveSystemAudioModeStatus(ADDR_TV, ADDR_AUDIO_SYSTEM);

        // Check if originally off
        HdmiCecMessage expectMessage = HdmiCecMessageBuilder
            .buildReportSystemAudioMode(ADDR_AUDIO_SYSTEM, ADDR_TV, false);

        assertTrue(mHdmiCecLocalDeviceAudioSystem.handleGiveSystemAudioModeStatus(messageGive));
        mTestLooper.dispatchAll();
        assertEquals(expectMessage, mNativeWrapper.getResultMessage());

        // Check if correctly turned on
        expectMessage = HdmiCecMessageBuilder
            .buildReportSystemAudioMode(ADDR_AUDIO_SYSTEM, ADDR_TV, true);

        assertTrue(mHdmiCecLocalDeviceAudioSystem.handleSetSystemAudioMode(messageSet));
        mTestLooper.dispatchAll();
        assertTrue(mHdmiCecLocalDeviceAudioSystem.handleGiveSystemAudioModeStatus(messageGive));
        mTestLooper.dispatchAll();
        assertEquals(expectMessage, mNativeWrapper.getResultMessage());
    }

    @Test
    public void handleSystemAudioModeRequest_turnOnByTv_thenTurnOffByTv() {
        mMusicMute = true;
        HdmiCecMessage messageRequestOn = HdmiCecMessageBuilder
            .buildSystemAudioModeRequest(ADDR_TV, ADDR_AUDIO_SYSTEM, 2, true);
        HdmiCecMessage messageGive = HdmiCecMessageBuilder
            .buildGiveSystemAudioModeStatus(ADDR_TV, ADDR_AUDIO_SYSTEM);
        // Turn the feature on
        HdmiCecMessage expectMessage = HdmiCecMessageBuilder
            .buildReportSystemAudioMode(ADDR_AUDIO_SYSTEM, ADDR_TV, true);

        assertTrue(mHdmiCecLocalDeviceAudioSystem.handleSystemAudioModeRequest(messageRequestOn));
        mTestLooper.dispatchAll();
        assertTrue(mHdmiCecLocalDeviceAudioSystem.handleGiveSystemAudioModeStatus(messageGive));
        mTestLooper.dispatchAll();
        assertEquals(expectMessage, mNativeWrapper.getResultMessage());
        assertFalse(mMusicMute);

        // Check if feature correctly turned off
        HdmiCecMessage messageRequestOff = HdmiCecMessageBuilder
            .buildSystemAudioModeRequest(ADDR_TV, ADDR_AUDIO_SYSTEM, 2, false);
        expectMessage = HdmiCecMessageBuilder
            .buildReportSystemAudioMode(ADDR_AUDIO_SYSTEM, ADDR_TV, false);

        assertTrue(mHdmiCecLocalDeviceAudioSystem.handleSystemAudioModeRequest(messageRequestOff));
        mTestLooper.dispatchAll();
        assertTrue(mHdmiCecLocalDeviceAudioSystem.handleGiveSystemAudioModeStatus(messageGive));
        mTestLooper.dispatchAll();
        assertEquals(expectMessage, mNativeWrapper.getResultMessage());
        assertTrue(mMusicMute);
    }
}
