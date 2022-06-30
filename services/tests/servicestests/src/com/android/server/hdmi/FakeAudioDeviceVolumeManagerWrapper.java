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

package com.android.server.hdmi;

import static android.media.AudioDeviceVolumeManager.OnAudioDeviceVolumeChangedListener;
import static android.media.AudioDeviceVolumeManager.OnDeviceVolumeBehaviorChangedListener;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceVolumeManager;
import android.media.AudioManager;
import android.media.VolumeInfo;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Wrapper for {@link AudioDeviceVolumeManager} that stubs its methods. Useful for testing.
 */
public class FakeAudioDeviceVolumeManagerWrapper implements
        AudioDeviceVolumeManagerWrapperInterface {

    private final Set<OnDeviceVolumeBehaviorChangedListener> mVolumeBehaviorListeners;

    public FakeAudioDeviceVolumeManagerWrapper() {
        mVolumeBehaviorListeners = new HashSet<>();
    }

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
        // Notify all volume behavior listeners that the device adopted absolute volume behavior
        for (OnDeviceVolumeBehaviorChangedListener listener : mVolumeBehaviorListeners) {
            listener.onDeviceVolumeBehaviorChanged(device,
                    AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE);
        }
    }
}
