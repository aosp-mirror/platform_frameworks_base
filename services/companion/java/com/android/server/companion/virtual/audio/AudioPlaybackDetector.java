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
import android.media.AudioPlaybackConfiguration;

import java.util.List;

/**
 * Wrapper class for other classes to listen {@link #onPlaybackConfigChanged(List)} by implementing
 * {@link AudioPlaybackCallback} instead of inheriting the
 * {@link AudioManager.AudioPlaybackCallback}.
 */
final class AudioPlaybackDetector extends AudioManager.AudioPlaybackCallback {

    /**
     * Interface to listen {@link #onPlaybackConfigChanged(List)} from
     * {@link AudioManager.AudioPlaybackCallback}.
     */
    interface AudioPlaybackCallback {
        void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs);
    }

    private final AudioManager mAudioManager;
    private AudioPlaybackCallback mAudioPlaybackCallback;

    AudioPlaybackDetector(Context context) {
        mAudioManager = context.getSystemService(AudioManager.class);
    }

    void register(@NonNull AudioPlaybackCallback callback) {
        mAudioPlaybackCallback = callback;
        mAudioManager.registerAudioPlaybackCallback(/* cb= */ this, /* handler= */ null);
    }

    void unregister() {
        if (mAudioPlaybackCallback != null) {
            mAudioPlaybackCallback = null;
            mAudioManager.unregisterAudioPlaybackCallback(/* cb= */ this);
        }
    }

    @Override
    public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
        super.onPlaybackConfigChanged(configs);
        if (mAudioPlaybackCallback != null) {
            mAudioPlaybackCallback.onPlaybackConfigChanged(configs);
        }
    }
}
