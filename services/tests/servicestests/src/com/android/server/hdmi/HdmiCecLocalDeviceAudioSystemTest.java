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
import static com.android.server.hdmi.Constants.ADDR_TV;
import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;
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

        @Override
        public long nativeInit(HdmiCecController handler, MessageQueue messageQueue) {
            return 1L;
        }

        @Override
        public int nativeSendCecCommand(long controllerPtr, int srcAddress, int dstAddress,
            byte[] body) {
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
    }

    private HdmiControlService mHdmiControlService;
    private HdmiCecController mHdmiCecController;
    private HdmiCecLocalDeviceAudioSystem mHdmiCecLocalDeviceAudioSystem;
    private HdmiCecMessage mResultMessage;
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
                };
            }

            @Override
            void sendCecCommand(HdmiCecMessage command) {
                mResultMessage = command;
            }
        };
        mMyLooper = mTestLooper.getLooper();
        mHdmiCecLocalDeviceAudioSystem = new HdmiCecLocalDeviceAudioSystem(mHdmiControlService);
        mHdmiCecLocalDeviceAudioSystem.init();
        mHdmiControlService.setIoLooper(mMyLooper);

        mHdmiCecController = HdmiCecController.createWithNativeWrapper(
            mHdmiControlService, new NativeWrapperImpl());
        mHdmiControlService.setCecController(mHdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));

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

        HdmiCecMessage message = HdmiCecMessageBuilder.buildGiveAudioStatus(
            ADDR_TV, ADDR_AUDIO_SYSTEM);
        assertTrue(mHdmiCecLocalDeviceAudioSystem.handleGiveAudioStatus(message));

        assertTrue(mResultMessage.equals(expectMessage));
    }

    @Test
    public void handleGiveSystemAudioModeStatus_off() {
        HdmiCecMessage expectMessage = HdmiCecMessageBuilder
            .buildReportSystemAudioMode(ADDR_AUDIO_SYSTEM, ADDR_TV, false);

        HdmiCecMessage message = HdmiCecMessageBuilder
            .buildGiveSystemAudioModeStatus(ADDR_TV, ADDR_AUDIO_SYSTEM);
        assertTrue(mHdmiCecLocalDeviceAudioSystem.handleGiveSystemAudioModeStatus(message));

        assertTrue(mResultMessage.equals(expectMessage));
    }

    @Test
    public void handleRequestArcInitiate() {
        // TODO(b/80296911): Add tests when finishing handler impl.
        HdmiCecMessage expectMessage = HdmiCecMessageBuilder
            .buildInitiateArc(ADDR_AUDIO_SYSTEM, ADDR_TV);

        HdmiCecMessage message = HdmiCecMessageBuilder
            .buildRequestArcInitiation(ADDR_TV, ADDR_AUDIO_SYSTEM);
        assertTrue(mHdmiCecLocalDeviceAudioSystem.handleRequestArcInitiate(message));

        assertTrue(mResultMessage.equals(expectMessage));
    }

    @Test
    public void handleRequestArcTermination() {
        // TODO(b/80297105): Add tests when finishing handler impl.
        HdmiCecMessage expectMessage = HdmiCecMessageBuilder
            .buildTerminateArc(ADDR_AUDIO_SYSTEM, ADDR_TV);

        HdmiCecMessage message = HdmiCecMessageBuilder
            .buildRequestArcTermination(ADDR_TV, ADDR_AUDIO_SYSTEM);
        assertTrue(mHdmiCecLocalDeviceAudioSystem.handleRequestArcTermination(message));

        assertTrue(mResultMessage.equals(expectMessage));
    }
}
