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
import android.media.VolumeInfo;

import java.util.concurrent.Executor;

/**
 * Interface with the methods from {@link AudioDeviceVolumeManager} used by the HDMI framework.
 * Allows the class to be faked for tests.
 */
public interface AudioDeviceVolumeManagerWrapperInterface {

    /**
     * Wrapper for {@link AudioDeviceVolumeManager#addOnDeviceVolumeBehaviorChangedListener(
     * Executor, OnDeviceVolumeBehaviorChangedListener)}
     */
    void addOnDeviceVolumeBehaviorChangedListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull AudioDeviceVolumeManager.OnDeviceVolumeBehaviorChangedListener listener);

    /**
     * Wrapper for {@link AudioDeviceVolumeManager#removeOnDeviceVolumeBehaviorChangedListener(
     * OnDeviceVolumeBehaviorChangedListener)}
     */
    void removeOnDeviceVolumeBehaviorChangedListener(
            @NonNull AudioDeviceVolumeManager.OnDeviceVolumeBehaviorChangedListener listener);

    /**
     * Wrapper for {@link AudioDeviceVolumeManager#setDeviceAbsoluteVolumeBehavior(
     * AudioDeviceAttributes, VolumeInfo, Executor, OnAudioDeviceVolumeChangedListener, boolean)}
     */
    void setDeviceAbsoluteVolumeBehavior(
            @NonNull AudioDeviceAttributes device,
            @NonNull VolumeInfo volume,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull AudioDeviceVolumeManager.OnAudioDeviceVolumeChangedListener vclistener,
            boolean handlesVolumeAdjustment);
}
