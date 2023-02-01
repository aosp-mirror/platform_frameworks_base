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

package com.android.server.companion.virtual.audio;

import android.annotation.NonNull;
import android.content.Context;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;

import java.util.List;

/**
 * Wrapper class for other classes to listen {@link #onRecordingConfigChanged(List)} by implementing
 * {@link AudioRecordingCallback} instead of inheriting the
 * {@link AudioManager.AudioRecordingCallback}.
 */
final class AudioRecordingDetector extends AudioManager.AudioRecordingCallback {

    /**
     * Interface to listen {@link #onRecordingConfigChanged(List)} from
     * {@link AudioManager.AudioRecordingCallback}.
     */
    interface AudioRecordingCallback {
        void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs);
    }

    private final AudioManager mAudioManager;
    private AudioRecordingCallback mAudioRecordingCallback;

    AudioRecordingDetector(Context context) {
        mAudioManager = context.getSystemService(AudioManager.class);
    }

    void register(@NonNull AudioRecordingCallback callback) {
        mAudioRecordingCallback = callback;
        mAudioManager.registerAudioRecordingCallback(/* cb= */ this, /* handler= */ null);
    }

    void unregister() {
        if (mAudioRecordingCallback != null) {
            mAudioRecordingCallback = null;
            mAudioManager.unregisterAudioRecordingCallback(/* cb= */ this);
        }
    }

    @Override
    public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
        super.onRecordingConfigChanged(configs);
        if (mAudioRecordingCallback != null) {
            mAudioRecordingCallback.onRecordingConfigChanged(configs);
        }
    }
}
