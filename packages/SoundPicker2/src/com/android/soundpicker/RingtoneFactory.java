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

package com.android.soundpicker;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.net.Uri;

import dagger.hilt.android.qualifiers.ApplicationContext;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A factory class used to create {@link Ringtone}.
 */
@Singleton
public class RingtoneFactory {

    private final Context mApplicationContext;

    @Inject
    RingtoneFactory(@ApplicationContext Context applicationContext) {
        mApplicationContext = applicationContext;
    }

    /**
     * Returns a {@link Ringtone} built from the provided URI and audio attributes flags.
     *
     * @param uri The URI used to build the {@link Ringtone}.
     * @param audioAttributesFlags A combination of audio attribute flags that affect the volume
     *                             and settings when playing the ringtone.
     * @return the built {@link Ringtone}.
     */
    public Ringtone create(Uri uri, int audioAttributesFlags) {
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setFlags(audioAttributesFlags)
                .build();
        // TODO: We are currently only using MEDIA_SOUND for enabledMedia. This will change once we
        //  start playing sound and/or vibration.
        return new Ringtone.Builder(mApplicationContext, Ringtone.MEDIA_SOUND, audioAttributes)
                .setUri(uri)
                .build();
    }
}
