/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.media.AudioAttributes;
import android.media.VolumeShaper;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.os.VibrationEffect;

/**
 * @hide
 */
interface IRingtonePlayer {
    /** Used for Ringtone.java playback */
    @UnsupportedAppUsage
    oneway void play(IBinder token, in Uri uri, in AudioAttributes aa, float volume, boolean looping);
    oneway void stop(IBinder token);
    boolean isPlaying(IBinder token);

    // RingtoneV1
    oneway void playWithVolumeShaping(IBinder token, in Uri uri, in AudioAttributes aa,
            float volume, boolean looping, in @nullable VolumeShaper.Configuration volumeShaperConfig);
    oneway void setPlaybackProperties(IBinder token, float volume, boolean looping,
            boolean hapticGeneratorEnabled);

    // RingtoneV2
    oneway void playRemoteRingtone(IBinder token, in Uri uri, in AudioAttributes aa,
        boolean useExactAudioAttributes, int enabledMedia, in @nullable VibrationEffect ve,
        float volume, boolean looping, boolean hapticGeneratorEnabled,
        in @nullable VolumeShaper.Configuration volumeShaperConfig);
    oneway void setLooping(IBinder token, boolean looping);
    oneway void setVolume(IBinder token, float volume);
    oneway void setHapticGeneratorEnabled(IBinder token, boolean hapticGeneratorEnabled);

    /** Used for Notification sound playback. */
    oneway void playAsync(in Uri uri, in UserHandle user, boolean looping, in AudioAttributes aa, float volume);
    oneway void stopAsync();

    /** Return the title of the media. */
    String getTitle(in Uri uri);

    ParcelFileDescriptor openRingtone(in Uri uri);
}
