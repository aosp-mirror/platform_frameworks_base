/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.media;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;

import java.util.concurrent.Executor;

/**
 * AudioRecordingMonitor defines an interface implemented by {@link AudioRecord} and
 * {@link MediaRecorder} allowing applications to install a callback and be notified of changes
 * in the capture path while recoding is active.
 */
public interface AudioRecordingMonitor {
    /**
     * Register a callback to be notified of audio capture changes via a
     * {@link AudioManager.AudioRecordingCallback}. A callback is received when the capture path
     * configuration changes (pre-processing, format, sampling rate...) or capture is
     * silenced/unsilenced by the system.
     * @param executor {@link Executor} to handle the callbacks.
     * @param cb non-null callback to register
     */
    void registerAudioRecordingCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull AudioManager.AudioRecordingCallback cb);

    /**
     * Unregister an audio recording callback previously registered with
     * {@link #registerAudioRecordingCallback(Executor, AudioManager.AudioRecordingCallback)}.
     * @param cb non-null callback to unregister
     */
    void unregisterAudioRecordingCallback(@NonNull AudioManager.AudioRecordingCallback cb);

    /**
     * Returns the current active audio recording for this audio recorder.
     * @return a valid {@link AudioRecordingConfiguration} if this recorder is active
     * or null otherwise.
     * @see AudioRecordingConfiguration
     */
    @Nullable AudioRecordingConfiguration getActiveRecordingConfiguration();
}
