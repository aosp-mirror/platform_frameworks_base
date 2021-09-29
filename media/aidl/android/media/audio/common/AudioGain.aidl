/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.media.audio.common;

import android.media.audio.common.AudioChannelLayout;

/**
 * This structure represents a gain stage. A gain stage is always attached
 * to an AudioPort.
 *
 * {@hide}
 */
@JavaDerive(equals=true, toString=true)
@VintfStability
parcelable AudioGain {
    /** Bitmask, indexed by AudioGainMode. */
    int mode;
    /** For AudioGainMode.CHANNELS, specifies controlled channels. */
    AudioChannelLayout channelMask;
    /** Minimum gain value in millibels. */
    int minValue;
    /** Maximum gain value in millibels. */
    int maxValue;
    /** Default gain value in millibels. */
    int defaultValue;
    /** Gain step in millibels. */
    int stepValue;
    /** Minimum ramp duration in milliseconds. */
    int minRampMs;
    /** Maximum ramp duration in milliseconds. */
    int maxRampMs;
}
