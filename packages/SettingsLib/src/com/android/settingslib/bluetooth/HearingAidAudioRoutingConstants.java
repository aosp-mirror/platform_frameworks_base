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

package com.android.settingslib.bluetooth;

import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Constant values used to configure hearing aid audio routing.
 *
 * {@link HearingAidAudioRoutingHelper}
 */
public final class HearingAidAudioRoutingConstants {
    public static final int[] CALL_ROUTING_ATTRIBUTES = new int[] {
            // Stands for STRATEGY_PHONE
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
    };

    public static final int[] MEDIA_ROUTING_ATTRIBUTES = new int[] {
            // Stands for STRATEGY_MEDIA, including USAGE_GAME, USAGE_ASSISTANT,
            // USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, USAGE_ASSISTANCE_SONIFICATION
            AudioAttributes.USAGE_MEDIA,
            // Stands for STRATEGY_ACCESSIBILITY
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
            // Stands for STRATEGY_DTMF
            AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
    };

    public static final int[] RINGTONE_ROUTING_ATTRIBUTES = new int[] {
            // Stands for STRATEGY_SONIFICATION, including USAGE_ALARM
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE
    };

    public static final int[] NOTIFICATION_ROUTING_ATTRIBUTES = new int[] {
            // Stands for STRATEGY_SONIFICATION_RESPECTFUL, including USAGE_NOTIFICATION_EVENT
            AudioAttributes.USAGE_NOTIFICATION,

    };

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            RoutingValue.AUTO,
            RoutingValue.HEARING_DEVICE,
            RoutingValue.DEVICE_SPEAKER,
    })

    public @interface RoutingValue {
        int AUTO = 0;
        int HEARING_DEVICE = 1;
        int DEVICE_SPEAKER = 2;
    }

    public static final AudioDeviceAttributes DEVICE_SPEAKER_OUT = new AudioDeviceAttributes(
            AudioDeviceAttributes.ROLE_OUTPUT, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "");
}
