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

import static com.android.media.audio.Flags.FLAG_DISABLE_PRESCALE_ABSOLUTE_VOLUME;

import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.AppOpsManager;
import android.content.Context;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.media.VolumeInfo;
import android.os.PermissionEnforcer;
import android.os.test.TestLooper;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AudioDeviceVolumeManagerTest {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    private static final String TAG = "AudioDeviceVolumeManagerTest";

    private static final AudioDeviceAttributes DEVICE_SPEAKER_OUT = new AudioDeviceAttributes(
            AudioDeviceAttributes.ROLE_OUTPUT, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "");

    private Context mContext;
    private String mPackageName;
    private AudioSystemAdapter mSpyAudioSystem;
    private SystemServerAdapter mSystemServer;
    private SettingsAdapter mSettingsAdapter;
    private AudioVolumeGroupHelperBase mAudioVolumeGroupHelper;
    private TestLooper mTestLooper;
    private AudioPolicyFacade mAudioPolicyMock = mock(AudioPolicyFacade.class);

    private AudioService mAudioService;


    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();
        mContext = InstrumentationRegistry.getTargetContext();
        mPackageName = mContext.getOpPackageName();
        mSpyAudioSystem = spy(new NoOpAudioSystemAdapter());

        mSystemServer = new NoOpSystemServerAdapter();
        mSettingsAdapter = new NoOpSettingsAdapter();
        mAudioVolumeGroupHelper = new AudioVolumeGroupHelperBase();
        mAudioService = new AudioService(mContext, mSpyAudioSystem, mSystemServer,
                mSettingsAdapter, mAudioVolumeGroupHelper, mAudioPolicyMock,
                mTestLooper.getLooper(), mock(AppOpsManager.class), mock(PermissionEnforcer.class),
                mock(AudioServerPermissionProvider.class), r -> r.run()) {
            @Override
            public int getDeviceForStream(int stream) {
                return AudioSystem.DEVICE_OUT_SPEAKER;
            }
        };

        mTestLooper.dispatchAll();
    }

    // ------------ AudioDeviceVolumeManager related tests ------------
    @Test
    public void setDeviceVolume_checkIndex() {
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

        mAudioService.setDeviceVolume(volMin, usbDevice, mPackageName);
        mTestLooper.dispatchAll();
        verify(mSpyAudioSystem, atLeast(1)).setStreamVolumeIndexAS(
                        AudioManager.STREAM_MUSIC, minIndex, AudioSystem.DEVICE_OUT_USB_DEVICE);

        mAudioService.setDeviceVolume(volMid, usbDevice, mPackageName);
        mTestLooper.dispatchAll();
        verify(mSpyAudioSystem, atLeast(1)).setStreamVolumeIndexAS(
                AudioManager.STREAM_MUSIC, midIndex, AudioSystem.DEVICE_OUT_USB_DEVICE);
    }

    @Test
    @RequiresFlagsDisabled(FLAG_DISABLE_PRESCALE_ABSOLUTE_VOLUME)
    public void configurablePreScaleAbsoluteVolume_checkIndex() throws Exception {
        AudioManager am = mContext.getSystemService(AudioManager.class);
        final int minIndex = am.getStreamMinVolume(AudioManager.STREAM_MUSIC);
        final int maxIndex = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        final VolumeInfo volMedia = new VolumeInfo.Builder(AudioManager.STREAM_MUSIC)
                .setMinVolumeIndex(minIndex)
                .setMaxVolumeIndex(maxIndex)
                .build();
        final AudioDeviceAttributes bleDevice = new AudioDeviceAttributes(
                /*native type*/ AudioSystem.DEVICE_OUT_BLE_HEADSET, /*address*/ "fake_ble");
        final int maxPreScaleIndex = 3;
        final float[] preScale = new float[3];
        preScale[0] = mContext.getResources().getFraction(
                com.android.internal.R.fraction.config_prescaleAbsoluteVolume_index1,
                1, 1);
        preScale[1] = mContext.getResources().getFraction(
                com.android.internal.R.fraction.config_prescaleAbsoluteVolume_index2,
                1, 1);
        preScale[2] = mContext.getResources().getFraction(
                com.android.internal.R.fraction.config_prescaleAbsoluteVolume_index3,
                1, 1);

        for (int i = 0; i < maxPreScaleIndex; i++) {
            final int targetIndex = (int) (preScale[i] * maxIndex);
            final VolumeInfo volCur = new VolumeInfo.Builder(volMedia)
                    .setVolumeIndex(i + 1).build();
            // Adjust stream volume with FLAG_ABSOLUTE_VOLUME set (index:1~3)
            mAudioService.setDeviceVolume(volCur, bleDevice, mPackageName);
            mTestLooper.dispatchAll();

            // Stream volume changes
            verify(mSpyAudioSystem, atLeast(1)).setStreamVolumeIndexAS(
                            AudioManager.STREAM_MUSIC, targetIndex,
                            AudioSystem.DEVICE_OUT_BLE_HEADSET);
        }

        // Adjust stream volume with FLAG_ABSOLUTE_VOLUME set (index:4)
        final VolumeInfo volIndex4 = new VolumeInfo.Builder(volMedia)
                .setVolumeIndex(4).build();
        mAudioService.setDeviceVolume(volIndex4, bleDevice, mPackageName);
        mTestLooper.dispatchAll();

        verify(mSpyAudioSystem, atLeast(1)).setStreamVolumeIndexAS(
                        AudioManager.STREAM_MUSIC, maxIndex,
                        AudioSystem.DEVICE_OUT_BLE_HEADSET);
    }

    @Test
    @RequiresFlagsEnabled(FLAG_DISABLE_PRESCALE_ABSOLUTE_VOLUME)
    public void disablePreScaleAbsoluteVolume_checkIndex() throws Exception {
        AudioManager am = mContext.getSystemService(AudioManager.class);
        final int minIndex = am.getStreamMinVolume(AudioManager.STREAM_MUSIC);
        final int maxIndex = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        final VolumeInfo volMedia = new VolumeInfo.Builder(AudioManager.STREAM_MUSIC)
                .setMinVolumeIndex(minIndex)
                .setMaxVolumeIndex(maxIndex)
                .build();
        final AudioDeviceAttributes bleDevice = new AudioDeviceAttributes(
                /*native type*/ AudioSystem.DEVICE_OUT_BLE_HEADSET, /*address*/ "bla");
        final int maxPreScaleIndex = 3;

        for (int i = 0; i < maxPreScaleIndex; i++) {
            final VolumeInfo volCur = new VolumeInfo.Builder(volMedia)
                    .setVolumeIndex(i + 1).build();
            // Adjust stream volume with FLAG_ABSOLUTE_VOLUME set (index:1~3)
            mAudioService.setDeviceVolume(volCur, bleDevice, mPackageName);
            mTestLooper.dispatchAll();

            // Stream volume changes
            verify(mSpyAudioSystem, atLeast(1)).setStreamVolumeIndexAS(
                            AudioManager.STREAM_MUSIC, maxIndex,
                            AudioSystem.DEVICE_OUT_BLE_HEADSET);
        }

        // Adjust stream volume with FLAG_ABSOLUTE_VOLUME set (index:4)
        final VolumeInfo volIndex4 = new VolumeInfo.Builder(volMedia)
                .setVolumeIndex(4).build();
        mAudioService.setDeviceVolume(volIndex4, bleDevice, mPackageName);
        mTestLooper.dispatchAll();

        verify(mSpyAudioSystem, atLeast(1)).setStreamVolumeIndexAS(
                        AudioManager.STREAM_MUSIC, maxIndex,
                        AudioSystem.DEVICE_OUT_BLE_HEADSET);
    }
}
