/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.audio;

import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.media.VolumeInfo;
import android.os.test.TestLooper;

import androidx.test.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;

public class AudioDeviceVolumeManagerTest {
    private static final String TAG = "AudioDeviceVolumeManagerTest";

    private static final AudioDeviceAttributes DEVICE_SPEAKER_OUT = new AudioDeviceAttributes(
            AudioDeviceAttributes.ROLE_OUTPUT, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "");

    private Context mContext;
    private String mPackageName;
    private AudioSystemAdapter mSpyAudioSystem;
    private SystemServerAdapter mSystemServer;
    private SettingsAdapter mSettingsAdapter;
    private TestLooper mTestLooper;

    private AudioService mAudioService;


    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();
        mContext = InstrumentationRegistry.getTargetContext();
        mPackageName = mContext.getOpPackageName();
        mSpyAudioSystem = spy(new NoOpAudioSystemAdapter());

        mSystemServer = new NoOpSystemServerAdapter();
        mSettingsAdapter = new NoOpSettingsAdapter();
        mAudioService = new AudioService(mContext, mSpyAudioSystem, mSystemServer,
                mSettingsAdapter, mTestLooper.getLooper()) {
            @Override
            public int getDeviceForStream(int stream) {
                return AudioSystem.DEVICE_OUT_SPEAKER;
            }
        };

        mTestLooper.dispatchAll();
    }

    @Test
    public void testSetDeviceVolume() {
        AudioManager am = mContext.getSystemService(AudioManager.class);
        final int minIndex = am.getStreamMinVolume(AudioManager.STREAM_MUSIC);
        final int maxIndex = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        final int midIndex = (minIndex + maxIndex) / 2;
        final VolumeInfo volMedia = new VolumeInfo.Builder(AudioManager.STREAM_MUSIC)
                .setMinVolumeIndex(minIndex)
                .setMaxVolumeIndex(maxIndex)
                .build();
        final VolumeInfo volMin = new VolumeInfo.Builder(volMedia).setVolumeIndex(minIndex).build();
        final VolumeInfo volMid = new VolumeInfo.Builder(volMedia).setVolumeIndex(midIndex).build();
        final AudioDeviceAttributes usbDevice = new AudioDeviceAttributes(
                /*native type*/ AudioSystem.DEVICE_OUT_USB_DEVICE, /*address*/ "bla");

        mAudioService.setDeviceVolume(volMin, usbDevice, mPackageName, TAG);
        mTestLooper.dispatchAll();
        verify(mSpyAudioSystem, atLeast(1)).setStreamVolumeIndexAS(
                        AudioManager.STREAM_MUSIC, minIndex, AudioSystem.DEVICE_OUT_USB_DEVICE);

        mAudioService.setDeviceVolume(volMid, usbDevice, mPackageName, TAG);
        mTestLooper.dispatchAll();
        verify(mSpyAudioSystem, atLeast(1)).setStreamVolumeIndexAS(
                AudioManager.STREAM_MUSIC, midIndex, AudioSystem.DEVICE_OUT_USB_DEVICE);
    }
}
