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
import static com.android.server.hdmi.Constants.ADDR_UNREGISTERED;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.assertEquals;

import android.media.AudioManager;
import android.support.test.filters.SmallTest;
import junit.framework.Assert;
import java.util.Arrays;
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


    private HdmiControlService mHdmiControlService;
    private HdmiCecLocalDeviceAudioSystem mHdmiCecLocalDeviceAudioSystem;
    private HdmiCecMessage mResultMessage;
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
        mHdmiCecLocalDeviceAudioSystem = new HdmiCecLocalDeviceAudioSystem(mHdmiControlService);
    }

    @Test
    public void handleGiveAudioStatus_volume_10_mute_true() {
        mMusicVolume = 10;
        mMusicMute = true;
        mMusicMaxVolume = 20;
        int scaledVolume = VolumeControlAction.scaleToCecVolume(10, mMusicMaxVolume);
        HdmiCecMessage expectMessage = HdmiCecMessageBuilder.buildReportAudioStatus(
            ADDR_UNREGISTERED, ADDR_TV, scaledVolume, true);

        HdmiCecMessage message = HdmiCecMessageBuilder.buildGiveAudioStatus(
            ADDR_TV, ADDR_AUDIO_SYSTEM);
        assertTrue(mHdmiCecLocalDeviceAudioSystem.handleGiveAudioStatus(message));

        assertTrue(mResultMessage.equals(expectMessage));
    }

    @Test
    public void handleGiveSystemAudioModeStatus_off() {
        HdmiCecMessage expectMessage = HdmiCecMessageBuilder
            .buildReportSystemAudioMode(ADDR_UNREGISTERED, ADDR_TV, false);

        HdmiCecMessage message = HdmiCecMessageBuilder
            .buildGiveSystemAudioModeStatus(ADDR_TV, ADDR_AUDIO_SYSTEM);
        assertTrue(mHdmiCecLocalDeviceAudioSystem.handleGiveSystemAudioModeStatus(message));

        assertTrue(mResultMessage.equals(expectMessage));
    }
}
