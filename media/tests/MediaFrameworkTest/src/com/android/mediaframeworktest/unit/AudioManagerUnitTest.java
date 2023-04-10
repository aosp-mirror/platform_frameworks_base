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
import static android.content.Context.DEVICE_ID_DEFAULT;
import static android.media.AudioManager.FX_KEY_CLICK;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.companion.virtual.VirtualDeviceManager;
import android.content.Context;
import android.media.AudioManager;
import android.test.mock.MockContext;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AudioManagerUnitTest {
    private static final int TEST_VIRTUAL_DEVICE_ID = 42;

    @Test
    public void testAudioManager_playSoundWithDefaultDeviceContext() {
        VirtualDeviceManager mockVdm = getMockVirtualDeviceManager(TEST_VIRTUAL_DEVICE_ID,
                DEVICE_POLICY_CUSTOM);
        Context defaultDeviceContext = getVirtualDeviceMockContext(DEVICE_ID_DEFAULT, /*vdm=*/
                mockVdm);
        AudioManager audioManager = new AudioManager(defaultDeviceContext);

        audioManager.playSoundEffect(FX_KEY_CLICK);

        // We expect no interactions with VDM when running on default device.
        verifyZeroInteractions(mockVdm);
    }

    @Test
    public void testAudioManager_playSoundWithVirtualDeviceContextDefaultPolicy() {
        VirtualDeviceManager mockVdm = getMockVirtualDeviceManager(TEST_VIRTUAL_DEVICE_ID,
                DEVICE_POLICY_DEFAULT);
        Context defaultDeviceContext = getVirtualDeviceMockContext(TEST_VIRTUAL_DEVICE_ID, /*vdm=*/
                mockVdm);
        AudioManager audioManager = new AudioManager(defaultDeviceContext);

        audioManager.playSoundEffect(FX_KEY_CLICK);

        // We expect playback not to be delegated to VDM because of default device policy for audio.
        verify(mockVdm, never()).playSoundEffect(anyInt(), anyInt());
    }

    @Test
    public void testAudioManager_playSoundWithVirtualDeviceContextCustomPolicy() {
        VirtualDeviceManager mockVdm = getMockVirtualDeviceManager(TEST_VIRTUAL_DEVICE_ID,
                DEVICE_POLICY_CUSTOM);
        Context defaultDeviceContext = getVirtualDeviceMockContext(TEST_VIRTUAL_DEVICE_ID, /*vdm=*/
                mockVdm);
        AudioManager audioManager = new AudioManager(defaultDeviceContext);

        audioManager.playSoundEffect(FX_KEY_CLICK);

        // We expect playback to be delegated to VDM because of custom device policy for audio.
        verify(mockVdm, times(1)).playSoundEffect(TEST_VIRTUAL_DEVICE_ID, FX_KEY_CLICK);
    }

    private static Context getVirtualDeviceMockContext(int deviceId, VirtualDeviceManager vdm) {
        MockContext mockContext = mock(MockContext.class);
        when(mockContext.getDeviceId()).thenReturn(deviceId);
        when(mockContext.getSystemService(VirtualDeviceManager.class)).thenReturn(vdm);
        return mockContext;
    }

    private static VirtualDeviceManager getMockVirtualDeviceManager(
            int deviceId, int audioDevicePolicy) {
        VirtualDeviceManager vdmMock = mock(VirtualDeviceManager.class);
        when(vdmMock.getDevicePolicy(anyInt(), anyInt())).thenReturn(DEVICE_POLICY_DEFAULT);
        when(vdmMock.getDevicePolicy(deviceId, POLICY_TYPE_AUDIO)).thenReturn(audioDevicePolicy);
        return vdmMock;
    }
}
