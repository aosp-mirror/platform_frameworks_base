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

package android.companion.virtual.audio;

import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecordingConfiguration;

/**
 * Callback to notify playback and recording state of applications running on virtual device.
 *
 * @hide
 */
oneway interface IAudioConfigChangedCallback {

    /**
     * Called whenever the playback configuration of applications running on virtual device has
     * changed.
     */
    void onPlaybackConfigChanged(in List<AudioPlaybackConfiguration> configs);

    /**
     * Called whenever the recording configuration of applications running on virtual device has
     * changed.
     */
    void onRecordingConfigChanged(in List<AudioRecordingConfiguration> configs);
}
