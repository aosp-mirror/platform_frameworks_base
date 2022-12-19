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

package com.android.mediaframeworktest.unit;

import static android.companion.virtual.VirtualDeviceManager.DEVICE_ID_DEFAULT;
import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_CUSTOM;
import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_DEFAULT;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_AUDIO;
import static android.media.AudioManager.AUDIO_SESSION_ID_GENERATE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.companion.virtual.VirtualDeviceManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.test.mock.MockContext;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AudioTrackUnitTest {
    private static final int TEST_SAMPLE_RATE = 44000;
    private static final int TEST_VIRTUAL_DEVICE_ID = 42;
    private static final AudioAttributes TEST_AUDIO_ATTRIBUTES =
            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();
    private static final AudioFormat TEST_AUDIO_FORMAT = new AudioFormat.Builder().setSampleRate(
            TEST_SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(
            AudioFormat.CHANNEL_OUT_STEREO).build();
    private static final int TEST_BUFFER_SIZE = AudioTrack.getMinBufferSize(
            TEST_AUDIO_FORMAT.getSampleRate(),
            TEST_AUDIO_FORMAT.getChannelMask(), TEST_AUDIO_FORMAT.getEncoding());

    @Test
    public void testBuilderConstructionWithContext_defaultDeviceExplicitSessionId() {
        Context mockDefaultDeviceContext = getVirtualDeviceMockContext(
                DEVICE_ID_DEFAULT, /*vdm=*/null);
        int sessionId = getContext().getSystemService(AudioManager.class).generateAudioSessionId();

        AudioTrack audioTrack = new AudioTrack.Builder().setContext(
                mockDefaultDeviceContext).setAudioAttributes(TEST_AUDIO_ATTRIBUTES).setAudioFormat(
                TEST_AUDIO_FORMAT).setBufferSizeInBytes(TEST_BUFFER_SIZE).setSessionId(
                sessionId).build();

        assertEquals(AudioTrack.STATE_INITIALIZED, audioTrack.getState());
        assertEquals(sessionId, audioTrack.getAudioSessionId());
    }

    @Test
    public void testBuilderConstructionWithContext_virtualDeviceDefaultAudioPolicy() {
        int vdmPlaybackSessionId = getContext().getSystemService(
                AudioManager.class).generateAudioSessionId();
        VirtualDeviceManager mockVdm = getMockVirtualDeviceManager(TEST_VIRTUAL_DEVICE_ID,
                vdmPlaybackSessionId, DEVICE_POLICY_DEFAULT);
        Context virtualDeviceContext = getVirtualDeviceMockContext(TEST_VIRTUAL_DEVICE_ID, mockVdm);

        AudioTrack audioTrack = new AudioTrack.Builder().setContext(
                virtualDeviceContext).setAudioAttributes(TEST_AUDIO_ATTRIBUTES).setAudioFormat(
                TEST_AUDIO_FORMAT).setBufferSizeInBytes(TEST_BUFFER_SIZE).build();

        assertEquals(AudioTrack.STATE_INITIALIZED, audioTrack.getState());
        assertNotEquals(vdmPlaybackSessionId, audioTrack.getAudioSessionId());
    }

    @Test
    public void testBuilderConstructionWithContext_virtualDeviceCustomAudioPolicy() {
        int vdmPlaybackSessionId = getContext().getSystemService(
                AudioManager.class).generateAudioSessionId();
        VirtualDeviceManager mockVdm = getMockVirtualDeviceManager(TEST_VIRTUAL_DEVICE_ID,
                vdmPlaybackSessionId, DEVICE_POLICY_CUSTOM);
        Context virtualDeviceContext = getVirtualDeviceMockContext(TEST_VIRTUAL_DEVICE_ID, mockVdm);

        AudioTrack audioTrack = new AudioTrack.Builder().setContext(
                virtualDeviceContext).setAudioAttributes(TEST_AUDIO_ATTRIBUTES).setAudioFormat(
                TEST_AUDIO_FORMAT).setBufferSizeInBytes(TEST_BUFFER_SIZE).build();

        assertEquals(AudioTrack.STATE_INITIALIZED, audioTrack.getState());
        assertEquals(vdmPlaybackSessionId, audioTrack.getAudioSessionId());
    }

    @Test
    public void testBuilderConstructionWithContext_virtualDeviceSetSessionIdOverridesContext() {
        int vdmPlaybackSessionId = getContext().getSystemService(
                AudioManager.class).generateAudioSessionId();
        int anotherSessionId = getContext().getSystemService(
                AudioManager.class).generateAudioSessionId();
        VirtualDeviceManager mockVdm = getMockVirtualDeviceManager(TEST_VIRTUAL_DEVICE_ID,
                vdmPlaybackSessionId, DEVICE_POLICY_CUSTOM);
        Context virtualDeviceContext = getVirtualDeviceMockContext(TEST_VIRTUAL_DEVICE_ID, mockVdm);

        AudioTrack audioTrack = new AudioTrack.Builder().setContext(
                        virtualDeviceContext).setAudioAttributes(
                        TEST_AUDIO_ATTRIBUTES).setAudioFormat(
                        TEST_AUDIO_FORMAT).setBufferSizeInBytes(TEST_BUFFER_SIZE).setSessionId(
                        anotherSessionId).build();

        assertEquals(AudioTrack.STATE_INITIALIZED, audioTrack.getState());
        assertEquals(anotherSessionId, audioTrack.getAudioSessionId());
    }

    private Context getContext() {
        return InstrumentationRegistry.getInstrumentation().getContext();
    }

    private static Context getVirtualDeviceMockContext(int deviceId, VirtualDeviceManager vdm) {
        MockContext mockContext = mock(MockContext.class);
        when(mockContext.getDeviceId()).thenReturn(deviceId);
        when(mockContext.getSystemService(VirtualDeviceManager.class)).thenReturn(vdm);
        return mockContext;
    }

    private static VirtualDeviceManager getMockVirtualDeviceManager(int deviceId,
            int playbackSessionId, int audioDevicePolicy) {
        VirtualDeviceManager vdmMock = mock(VirtualDeviceManager.class);
        when(vdmMock.getAudioPlaybackSessionId(anyInt())).thenReturn(AUDIO_SESSION_ID_GENERATE);
        when(vdmMock.getAudioPlaybackSessionId(deviceId)).thenReturn(playbackSessionId);
        when(vdmMock.getDevicePolicy(anyInt(), anyInt())).thenReturn(DEVICE_POLICY_DEFAULT);
        when(vdmMock.getDevicePolicy(deviceId, POLICY_TYPE_AUDIO)).thenReturn(audioDevicePolicy);
        return vdmMock;
    }
}
