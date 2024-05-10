/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.annotation.NonNull;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceVolumeManager;
import android.media.AudioManager;

import java.util.List;

/**
 * Interface with the methods from {@link AudioDeviceVolumeManager} used by the HDMI control
 * framework. Allows the class to be faked for tests.
 *
 * See implementations {@link DefaultAudioManagerWrapper} and
 * {@link FakeAudioFramework.FakeAudioManagerWrapper}.
 */
public interface AudioManagerWrapper {

    /**
     * Wraps {@link AudioManager#adjustStreamVolume(int, int, int)}
     */
    void adjustStreamVolume(int streamType, int direction,
            @AudioManager.PublicVolumeFlags int flags);

    /**
     * Wraps {@link AudioManager#setStreamVolume(int, int, int)}
     */
    void setStreamVolume(int streamType, int index, @AudioManager.PublicVolumeFlags int flags);

    /**
     * Wraps {@link AudioManager#getStreamVolume(int)}
     */
    int getStreamVolume(int streamType);

    /**
     * Wraps {@link AudioManager#getStreamMinVolume(int)}
     */
    int getStreamMinVolume(int streamType);

    /**
     * Wraps {@link AudioManager#getStreamMaxVolume(int)}
     */
    int getStreamMaxVolume(int streamType);

    /**
     * Wraps {@link AudioManager#isStreamMute(int)}
     */
    boolean isStreamMute(int streamType);

    /**
     * Wraps {@link AudioManager#setStreamMute(int, boolean)}
     */
    void setStreamMute(int streamType, boolean state);

    /**
     * Wraps {@link AudioManager#setHdmiSystemAudioSupported(boolean)}
     */
    int setHdmiSystemAudioSupported(boolean on);

    /**
     * Wraps {@link AudioManager#setWiredDeviceConnectionState(AudioDeviceAttributes, int)}
     */
    void setWiredDeviceConnectionState(AudioDeviceAttributes attributes, int state);

    /**
     * Wraps {@link AudioManager#setWiredDeviceConnectionState(int, int, String, String)}
     */
    void setWiredDeviceConnectionState(int device, int state, String address, String name);

    /**
     * Wraps {@link AudioManager#getDeviceVolumeBehavior(AudioDeviceAttributes)}
     */
    @AudioManager.DeviceVolumeBehavior
    int getDeviceVolumeBehavior(@NonNull AudioDeviceAttributes device);

    /**
     * Wraps {@link AudioManager#setDeviceVolumeBehavior(AudioDeviceAttributes, int)}
     */
    void setDeviceVolumeBehavior(@NonNull AudioDeviceAttributes device,
            @AudioManager.DeviceVolumeBehavior int deviceVolumeBehavior);

    /**
     * Wraps {@link AudioManager#getDevicesForAttributes(AudioAttributes)}
     */
    @NonNull
    List<AudioDeviceAttributes> getDevicesForAttributes(
            @NonNull AudioAttributes attributes);
}
