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
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioManager;

import java.util.List;

/**
 * "Default" wrapper for {@link AudioManager}, as opposed to a "Fake" wrapper for testing -
 * see {@link FakeAudioFramework.FakeAudioManagerWrapper}.
 *
 * Creates an instance of {@link AudioManager} and directly passes method calls to that instance.
 *
*/
public class DefaultAudioManagerWrapper implements AudioManagerWrapper {

    private static final String TAG = "DefaultAudioManagerWrapper";

    private final AudioManager mAudioManager;

    public DefaultAudioManagerWrapper(Context context) {
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public void adjustStreamVolume(int streamType, int direction,
            @AudioManager.PublicVolumeFlags int flags) {
        mAudioManager.adjustStreamVolume(streamType, direction, flags);
    }

    @Override
    public void setStreamVolume(int streamType, int index,
            @AudioManager.PublicVolumeFlags int flags) {
        mAudioManager.setStreamVolume(streamType, index, flags);
    }

    @Override
    public int getStreamVolume(int streamType) {
        return mAudioManager.getStreamVolume(streamType);
    }

    @Override
    public int getStreamMinVolume(int streamType) {
        return mAudioManager.getStreamMinVolume(streamType);
    }

    @Override
    public int getStreamMaxVolume(int streamType) {
        return mAudioManager.getStreamMaxVolume(streamType);
    }

    @Override
    public boolean isStreamMute(int streamType) {
        return mAudioManager.isStreamMute(streamType);
    }

    @Override
    public void setStreamMute(int streamType, boolean state) {
        mAudioManager.setStreamMute(streamType, state);
    }

    @Override
    public int setHdmiSystemAudioSupported(boolean on) {
        return mAudioManager.setHdmiSystemAudioSupported(on);
    }

    @Override
    public void setWiredDeviceConnectionState(AudioDeviceAttributes attributes, int state) {
        mAudioManager.setWiredDeviceConnectionState(attributes, state);
    }

    @Override
    public void setWiredDeviceConnectionState(int device, int state, String address, String name) {
        mAudioManager.setWiredDeviceConnectionState(device, state, address, name);
    }

    @Override
    @AudioManager.DeviceVolumeBehavior
    public int getDeviceVolumeBehavior(@NonNull AudioDeviceAttributes device) {
        return mAudioManager.getDeviceVolumeBehavior(device);
    }

    @Override
    public void setDeviceVolumeBehavior(@NonNull AudioDeviceAttributes device,
            @AudioManager.DeviceVolumeBehavior int deviceVolumeBehavior) {
        mAudioManager.setDeviceVolumeBehavior(device, deviceVolumeBehavior);
    }

    @Override
    @NonNull
    public List<AudioDeviceAttributes> getDevicesForAttributes(
            @NonNull AudioAttributes attributes) {
        return mAudioManager.getDevicesForAttributes(attributes);
    }

}
