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

import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_CUSTOM;
import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_DEFAULT;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_AUDIO;
import static android.media.AudioManager.AUDIO_SESSION_ID_GENERATE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.companion.virtual.VirtualDeviceManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.test.mock.MockContext;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.mediaframeworktest.R;

import org.junit.Test;
import org.junit.runner.RunWith;


@RunWith(AndroidJUnit4.class)
public class MediaPlayerUnitTest {

    private static final int TEST_VIRTUAL_DEVICE_ID = 42;
    private static final AudioAttributes AUDIO_ATTRIBUTES_MEDIA =
            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();

    @Test
    public void testConstructionWithContext_virtualDeviceDefaultAudioPolicy() {
        int vdmPlaybackSessionId = getContext().getSystemService(
                AudioManager.class).generateAudioSessionId();
        VirtualDeviceManager mockVdm = getMockVirtualDeviceManager(TEST_VIRTUAL_DEVICE_ID,
                vdmPlaybackSessionId, DEVICE_POLICY_DEFAULT);
        Context virtualDeviceContext = getVirtualDeviceMockContext(TEST_VIRTUAL_DEVICE_ID, mockVdm);

        MediaPlayer mediaPlayer = new MediaPlayer(virtualDeviceContext);

        assertNotEquals(vdmPlaybackSessionId, mediaPlayer.getAudioSessionId());
        assertTrue(mediaPlayer.getAudioSessionId() > 0);
    }

    @Test
    public void testConstructionWithContext_virtualDeviceCustomAudioPolicy() {
        int vdmPlaybackSessionId = getContext().getSystemService(
                AudioManager.class).generateAudioSessionId();
        VirtualDeviceManager mockVdm = getMockVirtualDeviceManager(TEST_VIRTUAL_DEVICE_ID,
                vdmPlaybackSessionId, DEVICE_POLICY_CUSTOM);
        Context virtualDeviceContext = getVirtualDeviceMockContext(TEST_VIRTUAL_DEVICE_ID, mockVdm);

        MediaPlayer mediaPlayer = new MediaPlayer(virtualDeviceContext);

        assertEquals(vdmPlaybackSessionId, mediaPlayer.getAudioSessionId());
    }

    @Test
    public void testConstructionWithContext_virtualSetSessionIdOverridesContext() {
        int vdmPlaybackSessionId = getContext().getSystemService(
                AudioManager.class).generateAudioSessionId();
        int anotherSessionId = getContext().getSystemService(
                AudioManager.class).generateAudioSessionId();
        VirtualDeviceManager mockVdm = getMockVirtualDeviceManager(TEST_VIRTUAL_DEVICE_ID,
                vdmPlaybackSessionId, DEVICE_POLICY_CUSTOM);
        Context virtualDeviceContext = getVirtualDeviceMockContext(TEST_VIRTUAL_DEVICE_ID, mockVdm);

        MediaPlayer mediaPlayer = new MediaPlayer(virtualDeviceContext);
        mediaPlayer.setAudioSessionId(anotherSessionId);

        assertEquals(anotherSessionId, mediaPlayer.getAudioSessionId());
    }

    @Test
    public void testCreateFromResource_virtualDeviceDefaultAudioPolicy() {
        int vdmPlaybackSessionId = getContext().getSystemService(
                AudioManager.class).generateAudioSessionId();
        VirtualDeviceManager mockVdm = getMockVirtualDeviceManager(TEST_VIRTUAL_DEVICE_ID,
                vdmPlaybackSessionId, DEVICE_POLICY_DEFAULT);
        Context virtualDeviceContext = getVirtualDeviceMockContext(TEST_VIRTUAL_DEVICE_ID, mockVdm);

        MediaPlayer mediaPlayer = MediaPlayer.create(virtualDeviceContext, R.raw.testmp3);

        assertNotEquals(vdmPlaybackSessionId, mediaPlayer.getAudioSessionId());
        assertTrue(mediaPlayer.getAudioSessionId() > 0);
    }

    @Test
    public void testCreateFromResource_virtualDeviceCustomAudioPolicy() {
        int vdmPlaybackSessionId = getContext().getSystemService(
                AudioManager.class).generateAudioSessionId();
        VirtualDeviceManager mockVdm = getMockVirtualDeviceManager(TEST_VIRTUAL_DEVICE_ID,
                vdmPlaybackSessionId, DEVICE_POLICY_CUSTOM);
        Context virtualDeviceContext = getVirtualDeviceMockContext(TEST_VIRTUAL_DEVICE_ID, mockVdm);

        MediaPlayer mediaPlayer = MediaPlayer.create(virtualDeviceContext, R.raw.testmp3);

        assertEquals(vdmPlaybackSessionId, mediaPlayer.getAudioSessionId());
    }

    @Test
    public void testCreateFromResource_explicitSessionIdOverridesContext() {
        int vdmPlaybackSessionId = getContext().getSystemService(
                AudioManager.class).generateAudioSessionId();
        int anotherSessionId = getContext().getSystemService(
                AudioManager.class).generateAudioSessionId();
        VirtualDeviceManager mockVdm = getMockVirtualDeviceManager(TEST_VIRTUAL_DEVICE_ID,
                vdmPlaybackSessionId, DEVICE_POLICY_CUSTOM);
        Context virtualDeviceContext = getVirtualDeviceMockContext(TEST_VIRTUAL_DEVICE_ID, mockVdm);

        MediaPlayer mediaPlayer = MediaPlayer.create(virtualDeviceContext, R.raw.testmp3,
                AUDIO_ATTRIBUTES_MEDIA, anotherSessionId);

        assertEquals(anotherSessionId, mediaPlayer.getAudioSessionId());
    }

    private Context getContext() {
        return InstrumentationRegistry.getInstrumentation().getContext();
    }

    private Context getVirtualDeviceMockContext(int deviceId, VirtualDeviceManager vdm) {
        MockContext mockContext = mock(MockContext.class);
        when(mockContext.getDeviceId()).thenReturn(deviceId);
        when(mockContext.getSystemService(VirtualDeviceManager.class)).thenReturn(vdm);
        when(mockContext.getAttributionSource()).thenReturn(getContext().getAttributionSource());
        when(mockContext.getResources()).thenReturn(getContext().getResources());
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
