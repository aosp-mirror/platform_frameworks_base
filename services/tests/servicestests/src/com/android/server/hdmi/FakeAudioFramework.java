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

import static android.media.AudioDeviceVolumeManager.OnAudioDeviceVolumeChangedListener;
import static android.media.AudioDeviceVolumeManager.OnDeviceVolumeBehaviorChangedListener;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.media.VolumeInfo;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Contains a fake AudioManager and fake AudioDeviceVolumeManager.
 * Stores the shared state for these managers, simulating a fake AudioService.
 */
public class FakeAudioFramework {

    private final FakeAudioManagerWrapper mAudioManager = new FakeAudioManagerWrapper();
    private final FakeAudioDeviceVolumeManagerWrapper mAudioDeviceVolumeManager =
            new FakeAudioDeviceVolumeManagerWrapper();

    private static final int DEFAULT_DEVICE_VOLUME_BEHAVIOR =
            AudioManager.DEVICE_VOLUME_BEHAVIOR_VARIABLE;
    private final Map<AudioDeviceAttributes, Integer> mDeviceVolumeBehaviors = new HashMap<>();

    private final Set<OnDeviceVolumeBehaviorChangedListener> mVolumeBehaviorListeners =
            new HashSet<>();

    private final Map<AudioAttributes, List<AudioDeviceAttributes>> mDevicesForAttributes =
            new HashMap<>();

    private static final int DEFAULT_VOLUME = 0;
    private final Map<Integer, Integer> mStreamVolumes = new HashMap<>();

    private static final int DEFAULT_MAX_VOLUME = 100;
    private final Map<Integer, Integer> mStreamMaxVolumes = new HashMap<>();

    private static final boolean DEFAULT_MUTE_STATUS = false;
    private final Map<Integer, Boolean> mStreamMuteStatuses = new HashMap<>();

    public FakeAudioFramework() {
    }

    /**
     * Returns a fake AudioManager whose methods affect this object's internal state.
     */
    public FakeAudioManagerWrapper getAudioManager() {
        return mAudioManager;
    }

    public class FakeAudioManagerWrapper implements AudioManagerWrapper {
        @Override
        public void adjustStreamVolume(int streamType, int direction,
                @AudioManager.PublicVolumeFlags int flags) {
            switch (direction) {
                case AudioManager.ADJUST_MUTE:
                    mStreamMuteStatuses.put(streamType, true);
                    break;
                case AudioManager.ADJUST_UNMUTE:
                    mStreamMuteStatuses.put(streamType, false);
                    break;
                default:
                    // Other adjustments not implemented
            }
        }

        @Override
        public void setStreamVolume(int streamType, int index,
                @AudioManager.PublicVolumeFlags int flags) {
            mStreamVolumes.put(streamType, index);
        }

        @Override
        public int getStreamVolume(int streamType) {
            return mStreamVolumes.getOrDefault(streamType, DEFAULT_VOLUME);
        }

        @Override
        public int getStreamMinVolume(int streamType) {
            return 0;
        }

        @Override
        public int getStreamMaxVolume(int streamType) {
            return mStreamMaxVolumes.getOrDefault(streamType, DEFAULT_MAX_VOLUME);
        }

        @Override
        public boolean isStreamMute(int streamType) {
            return mStreamMuteStatuses.getOrDefault(streamType, DEFAULT_MUTE_STATUS);
        }

        @Override
        public void setStreamMute(int streamType, boolean state) {
            mStreamMuteStatuses.put(streamType, state);
        }

        @Override
        public int setHdmiSystemAudioSupported(boolean on) {
            return AudioSystem.DEVICE_NONE;
        }

        @Override
        public void setWiredDeviceConnectionState(AudioDeviceAttributes attributes, int state) {
            // Do nothing
        }

        @Override
        public void setWiredDeviceConnectionState(int device, int state, String address,
                String name) {
            // Do nothing
        }


        @Override
        @AudioManager.DeviceVolumeBehavior
        public int getDeviceVolumeBehavior(@NonNull AudioDeviceAttributes device) {
            return mDeviceVolumeBehaviors.getOrDefault(device, DEFAULT_DEVICE_VOLUME_BEHAVIOR);
        }

        public void setDeviceVolumeBehavior(@NonNull AudioDeviceAttributes device,
                @AudioManager.DeviceVolumeBehavior int deviceVolumeBehavior) {
            setVolumeBehaviorHelper(device, deviceVolumeBehavior);
        }

        @Override
        @NonNull
        public List<AudioDeviceAttributes> getDevicesForAttributes(
                @NonNull AudioAttributes attributes) {
            return mDevicesForAttributes.getOrDefault(attributes, Collections.emptyList());
        }
    }

    /**
     * Returns a fake AudioDeviceVolumeManager whose methods affect this object's internal state.
     */
    public FakeAudioDeviceVolumeManagerWrapper getAudioDeviceVolumeManager() {
        return mAudioDeviceVolumeManager;
    }

    public class FakeAudioDeviceVolumeManagerWrapper implements AudioDeviceVolumeManagerWrapper {
        @Override
        public void addOnDeviceVolumeBehaviorChangedListener(
                @NonNull @CallbackExecutor Executor executor,
                @NonNull OnDeviceVolumeBehaviorChangedListener listener)
                throws SecurityException {
            mVolumeBehaviorListeners.add(listener);
        }

        @Override
        public void removeOnDeviceVolumeBehaviorChangedListener(
                @NonNull OnDeviceVolumeBehaviorChangedListener listener) {
            mVolumeBehaviorListeners.remove(listener);
        }

        @Override
        public void setDeviceAbsoluteVolumeBehavior(
                @NonNull AudioDeviceAttributes device,
                @NonNull VolumeInfo volume,
                @NonNull @CallbackExecutor Executor executor,
                @NonNull OnAudioDeviceVolumeChangedListener vclistener,
                boolean handlesVolumeAdjustment) {
            setVolumeBehaviorHelper(device, AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE);
        }

        @Override
        public void setDeviceAbsoluteVolumeAdjustOnlyBehavior(
                @NonNull AudioDeviceAttributes device,
                @NonNull VolumeInfo volume,
                @NonNull @CallbackExecutor Executor executor,
                @NonNull OnAudioDeviceVolumeChangedListener vclistener,
                boolean handlesVolumeAdjustment) {
            setVolumeBehaviorHelper(device,
                    AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY);
        }
    }

    /**
     * Allows tests to manipulate the return value of
     * {@link FakeAudioManagerWrapper#getDevicesForAttributes}
     */
    public void setDevicesForAttributes(AudioAttributes attributes,
            List<AudioDeviceAttributes> devices) {
        mDevicesForAttributes.put(attributes, devices);
    }

    /**
     * Allows tests to manipulate the return value of
     * {@link FakeAudioManagerWrapper#getStreamMaxVolume}
     */
    public void setStreamMaxVolume(int streamType, int maxVolume) {
        mStreamMaxVolumes.put(streamType, maxVolume);
    }

    /**
     * Helper method for changing an audio device's volume behavior. Notifies listeners.
     */
    private void setVolumeBehaviorHelper(AudioDeviceAttributes device,
            @AudioManager.DeviceVolumeBehavior int newVolumeBehavior) {

        int currentVolumeBehavior = mDeviceVolumeBehaviors.getOrDefault(
                device, DEFAULT_DEVICE_VOLUME_BEHAVIOR);

        mDeviceVolumeBehaviors.put(device, newVolumeBehavior);

        if (newVolumeBehavior != currentVolumeBehavior) {
            // Notify volume behavior listeners
            for (OnDeviceVolumeBehaviorChangedListener listener : mVolumeBehaviorListeners) {
                listener.onDeviceVolumeBehaviorChanged(device, newVolumeBehavior);
            }
        }
    }
}
