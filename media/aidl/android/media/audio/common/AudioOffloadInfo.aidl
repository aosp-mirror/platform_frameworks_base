/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.media.audio.common.AudioConfigBase;
import android.media.audio.common.AudioEncapsulationMode;
import android.media.audio.common.AudioStreamType;
import android.media.audio.common.AudioUsage;

/**
 * Additional information about the stream passed to hardware decoders.
 *
 * {@hide}
 */
@JavaDerive(equals=true, toString=true)
@VintfStability
parcelable AudioOffloadInfo {
    /** Base audio configuration. */
    AudioConfigBase base;
    /** Stream type. Intended for use by the system only. */
    AudioStreamType streamType = AudioStreamType.INVALID;
    /** Bit rate in bits per second. */
    int bitRatePerSecond;
    /** Duration in microseconds, -1 if unknown. */
    long durationUs;
    /** True if the stream is tied to a video stream. */
    boolean hasVideo;
    /** True if streaming, false if local playback. */
    boolean isStreaming;
    /** Sample bit width. */
    int bitWidth = 16;
    /** Offload fragment size. */
    int offloadBufferSize;
    /** See the documentation of AudioUsage. */
    AudioUsage usage = AudioUsage.INVALID;
    /** See the documentation of AudioEncapsulationMode. */
    AudioEncapsulationMode encapsulationMode = AudioEncapsulationMode.INVALID;
    /** Content id from tuner HAL (0 if none). */
    int contentId;
    /** Sync id from tuner HAL (0 if none). */
    int syncId;
}
