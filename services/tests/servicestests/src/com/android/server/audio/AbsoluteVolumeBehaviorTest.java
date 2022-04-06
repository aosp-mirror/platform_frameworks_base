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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioDeviceVolumeManager;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.media.IAudioDeviceVolumeDispatcher;
import android.media.VolumeInfo;
import android.os.RemoteException;
import android.os.test.TestLooper;

import androidx.test.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

public class AbsoluteVolumeBehaviorTest {
    private static final String TAG = "AbsoluteVolumeBehaviorTest";

    private static final AudioDeviceAttributes DEVICE_SPEAKER_OUT = new AudioDeviceAttributes(
            AudioDeviceAttributes.ROLE_OUTPUT, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "");

    private Context mContext;
    private String mPackageName;
    private AudioSystemAdapter mSpyAudioSystem;
    private SystemServerAdapter mSystemServer;
    private SettingsAdapter mSettingsAdapter;
    private TestLooper mTestLooper;

    private AudioService mAudioService;

    private IAudioDeviceVolumeDispatcher.Stub mMockDispatcher =
            mock(IAudioDeviceVolumeDispatcher.Stub.class);

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
    public void registerDispatcher_setsVolumeBehaviorToAbsolute() {
        List<VolumeInfo> volumes = Collections.singletonList(
                new VolumeInfo.Builder(AudioManager.STREAM_MUSIC).build());

        mAudioService.registerDeviceVolumeDispatcherForAbsoluteVolume(true,
                mMockDispatcher, mPackageName, DEVICE_SPEAKER_OUT, volumes, true);
        mTestLooper.dispatchAll();

        assertThat(mAudioService.getDeviceVolumeBehavior(DEVICE_SPEAKER_OUT))
                .isEqualTo(AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE);
    }

    @Test
    public void registerDispatcher_setsVolume() {
        List<VolumeInfo> volumes = Collections.singletonList(
                new VolumeInfo.Builder(AudioManager.STREAM_MUSIC)
                        .setMinVolumeIndex(0)
                        .setMaxVolumeIndex(250) // Max index is 10 times that of STREAM_MUSIC
                        .setVolumeIndex(50)
                        .build());

        mAudioService.registerDeviceVolumeDispatcherForAbsoluteVolume(true,
                mMockDispatcher, mPackageName, DEVICE_SPEAKER_OUT, volumes, true);
        mTestLooper.dispatchAll();

        assertThat(mAudioService.getStreamVolume(AudioManager.STREAM_MUSIC))
                .isEqualTo(5);
    }

    @Test
    public void unregisterDispatcher_deviceBecomesVariableVolume_listenerNoLongerTriggered()
            throws RemoteException {

        List<VolumeInfo> volumes = Collections.singletonList(
                new VolumeInfo.Builder(AudioManager.STREAM_MUSIC).build());

        mAudioService.registerDeviceVolumeDispatcherForAbsoluteVolume(true,
                mMockDispatcher, mPackageName, DEVICE_SPEAKER_OUT, volumes, true);
        mTestLooper.dispatchAll();

        mAudioService.registerDeviceVolumeDispatcherForAbsoluteVolume(false,
                mMockDispatcher, mPackageName, DEVICE_SPEAKER_OUT, volumes, true);
        mTestLooper.dispatchAll();

        assertThat(mAudioService.getDeviceVolumeBehavior(DEVICE_SPEAKER_OUT))
                .isEqualTo(AudioManager.DEVICE_VOLUME_BEHAVIOR_VARIABLE);

        mAudioService.setStreamVolume(AudioManager.STREAM_MUSIC, 15, 0, mPackageName);
        mTestLooper.dispatchAll();

        verify(mMockDispatcher, never()).dispatchDeviceVolumeChanged(
                eq(DEVICE_SPEAKER_OUT), any());
    }

    @Test
    public void setDeviceVolumeBehavior_unregistersDispatcher() throws RemoteException {
        List<VolumeInfo> volumes = Collections.singletonList(
                new VolumeInfo.Builder(AudioManager.STREAM_MUSIC).build());

        mAudioService.registerDeviceVolumeDispatcherForAbsoluteVolume(true,
                mMockDispatcher, mPackageName, DEVICE_SPEAKER_OUT, volumes, true);
        mTestLooper.dispatchAll();

        mAudioService.setDeviceVolumeBehavior(DEVICE_SPEAKER_OUT,
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL, mPackageName);
        mTestLooper.dispatchAll();

        mAudioService.setStreamVolume(AudioManager.STREAM_MUSIC, 15, 0, mPackageName);
        mTestLooper.dispatchAll();

        verify(mMockDispatcher, never()).dispatchDeviceVolumeChanged(
                eq(DEVICE_SPEAKER_OUT), any());
    }

    @Test
    public void setStreamVolume_noAbsVolFlag_dispatchesVolumeChanged() throws RemoteException {
        VolumeInfo volumeInfo = new VolumeInfo.Builder(AudioManager.STREAM_MUSIC)
                .setMinVolumeIndex(0)
                .setMaxVolumeIndex(250) // Max index is 10 times that of STREAM_MUSIC
                .setVolumeIndex(50)
                .build();

        mAudioService.registerDeviceVolumeDispatcherForAbsoluteVolume(true,
                mMockDispatcher, mPackageName, DEVICE_SPEAKER_OUT,
                Collections.singletonList(volumeInfo), true);
        mTestLooper.dispatchAll();

        // Set stream volume without FLAG_ABSOLUTE_VOLUME
        mAudioService.setStreamVolume(AudioManager.STREAM_MUSIC, 15, 0, mPackageName);
        mTestLooper.dispatchAll();

        // Dispatched volume index is scaled to the range in the initial VolumeInfo
        verify(mMockDispatcher).dispatchDeviceVolumeChanged(DEVICE_SPEAKER_OUT,
                new VolumeInfo.Builder(volumeInfo).setVolumeIndex(150).build());
    }

    @Test
    public void setStreamVolume_absVolFlagSet_doesNotDispatchVolumeChanged()
            throws RemoteException {
        VolumeInfo volumeInfo = new VolumeInfo.Builder(AudioManager.STREAM_MUSIC)
                .setMinVolumeIndex(0)
                .setMaxVolumeIndex(250)
                .setVolumeIndex(50)
                .build();

        mAudioService.registerDeviceVolumeDispatcherForAbsoluteVolume(true,
                mMockDispatcher, mPackageName, DEVICE_SPEAKER_OUT,
                Collections.singletonList(volumeInfo), true);
        mTestLooper.dispatchAll();

        // Set stream volume with FLAG_ABSOLUTE_VOLUME
        mAudioService.setStreamVolume(AudioManager.STREAM_MUSIC, 15,
                AudioManager.FLAG_ABSOLUTE_VOLUME, mPackageName);
        mTestLooper.dispatchAll();

        verify(mMockDispatcher, never()).dispatchDeviceVolumeChanged(eq(DEVICE_SPEAKER_OUT),
                any());
    }

    @Test
    public void adjustStreamVolume_handlesAdjust_noAbsVolFlag_noVolChange_dispatchesVolumeAdjusted()
            throws RemoteException {
        VolumeInfo volumeInfo = new VolumeInfo.Builder(AudioManager.STREAM_MUSIC)
                .setMinVolumeIndex(0)
                .setMaxVolumeIndex(250)
                .setVolumeIndex(0)
                .build();

        // Register dispatcher with handlesVolumeAdjustment = true
        mAudioService.registerDeviceVolumeDispatcherForAbsoluteVolume(true,
                mMockDispatcher, mPackageName, DEVICE_SPEAKER_OUT,
                Collections.singletonList(volumeInfo), true);
        mTestLooper.dispatchAll();

        // Adjust stream volume without FLAG_ABSOLUTE_VOLUME
        mAudioService.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE,
                0, mPackageName);
        mTestLooper.dispatchAll();

        // Stream volume does not change
        assertThat(mAudioService.getStreamVolume(AudioManager.STREAM_MUSIC)).isEqualTo(0);
        // Listener is notified via dispatchDeviceVolumeAdjusted
        verify(mMockDispatcher, never()).dispatchDeviceVolumeChanged(eq(DEVICE_SPEAKER_OUT), any());
        verify(mMockDispatcher).dispatchDeviceVolumeAdjusted(eq(DEVICE_SPEAKER_OUT),
                argThat((VolumeInfo v) -> v.getStreamType() == AudioManager.STREAM_MUSIC),
                eq(AudioManager.ADJUST_RAISE), eq(AudioDeviceVolumeManager.ADJUST_MODE_NORMAL));
    }

    @Test
    public void adjustStreamVolume_noHandleAdjust_noAbsVolFlag_volChanges_dispatchesVolumeChanged()
            throws RemoteException {
        VolumeInfo volumeInfo = new VolumeInfo.Builder(AudioManager.STREAM_MUSIC)
                .setMinVolumeIndex(0)
                .setMaxVolumeIndex(250)
                .setVolumeIndex(0)
                .build();

        // Register dispatcher with handlesVolumeAdjustment = false
        mAudioService.registerDeviceVolumeDispatcherForAbsoluteVolume(true,
                mMockDispatcher, mPackageName, DEVICE_SPEAKER_OUT,
                Collections.singletonList(volumeInfo), false);
        mTestLooper.dispatchAll();

        // Adjust stream volume without FLAG_ABSOLUTE_VOLUME
        mAudioService.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE,
                0, mPackageName);
        mTestLooper.dispatchAll();

        // Stream volume changes
        assertThat(mAudioService.getStreamVolume(AudioManager.STREAM_MUSIC)).isNotEqualTo(0);
        // Listener is notified via dispatchDeviceVolumeChanged
        verify(mMockDispatcher).dispatchDeviceVolumeChanged(eq(DEVICE_SPEAKER_OUT), any());
        verify(mMockDispatcher, never()).dispatchDeviceVolumeAdjusted(eq(DEVICE_SPEAKER_OUT), any(),
                anyInt(), anyInt());
    }

    @Test
    public void adjustStreamVolume_absVolFlagSet_streamVolumeChanges_nothingDispatched()
            throws RemoteException {
        VolumeInfo volumeInfo = new VolumeInfo.Builder(AudioManager.STREAM_MUSIC)
                .setMinVolumeIndex(0)
                .setMaxVolumeIndex(250)
                .setVolumeIndex(0)
                .build();

        mAudioService.registerDeviceVolumeDispatcherForAbsoluteVolume(true,
                mMockDispatcher, mPackageName, DEVICE_SPEAKER_OUT,
                Collections.singletonList(volumeInfo), true);
        mTestLooper.dispatchAll();

        // Adjust stream volume with FLAG_ABSOLUTE_VOLUME set
        mAudioService.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE,
                AudioManager.FLAG_ABSOLUTE_VOLUME, mPackageName);
        mTestLooper.dispatchAll();

        // Stream volume changes
        assertThat(mAudioService.getStreamVolume(AudioManager.STREAM_MUSIC)).isNotEqualTo(0);
        // Nothing is dispatched
        verify(mMockDispatcher, never()).dispatchDeviceVolumeChanged(eq(DEVICE_SPEAKER_OUT), any());
        verify(mMockDispatcher, never()).dispatchDeviceVolumeAdjusted(eq(DEVICE_SPEAKER_OUT), any(),
                anyInt(), anyInt());
    }
}
