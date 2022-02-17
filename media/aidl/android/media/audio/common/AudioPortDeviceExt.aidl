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

import android.media.audio.common.AudioDevice;
import android.media.audio.common.AudioFormatDescription;

/**
 * Extra parameters which are specified when the audio port is in the device role.
 *
 * {@hide}
 */
@JavaDerive(equals=true, toString=true)
@VintfStability
parcelable AudioPortDeviceExt {
    /** Audio device specification. */
    AudioDevice device;
    /** Bitmask indexed by 'FLAG_INDEX_' constants. */
    int flags;
    /**
     * List of supported encoded formats. Specified for ports that perform
     * hardware-accelerated decoding or transcoding, or connected to external
     * hardware.
     */
    AudioFormatDescription[] encodedFormats;

    /**
     * A default device port is fallback used when the preference for the device
     * to use has not been specified (AudioDeviceType.type == {IN|OUT}_DEFAULT),
     * or the specified device does not satisfy routing criteria based on audio
     * stream attributes and use cases. The device port for which the ID is
     * returned must be associated with a permanently attached device
     * (AudioDeviceDescription.connection == ''). There can be no more than one
     * default device port in a HAL module in each I/O direction.
     */
    const int FLAG_INDEX_DEFAULT_DEVICE = 0;
}
