/*
 * Copyright (C) 2013 The Android Open Source Project
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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import android.annotation.IntDef;

/**
 * Structure that groups a position in frame units relative to an assumed audio stream,
 * together with the estimated time when that frame enters or leaves the audio
 * processing pipeline on that device. This can be used to coordinate events
 * and interactions with the external environment.
 * <p>
 * The time is based on the implementation's best effort, using whatever knowledge
 * is available to the system, but cannot account for any delay unknown to the implementation.
 *
 * @see AudioTrack#getTimestamp AudioTrack.getTimestamp(AudioTimestamp)
 * @see AudioRecord#getTimestamp AudioRecord.getTimestamp(AudioTimestamp, int)
 */
public final class AudioTimestamp
{
    /**
     * Clock monotonic or its equivalent on the system,
     * in the same units and timebase as {@link java.lang.System#nanoTime}.
     */
    public static final int TIMEBASE_MONOTONIC = 0;

    /**
     * Clock monotonic including suspend time or its equivalent on the system,
     * in the same units and timebase as {@link android.os.SystemClock#elapsedRealtimeNanos}.
     */
    public static final int TIMEBASE_BOOTTIME = 1;

    /** @hide */
    @IntDef({
        TIMEBASE_MONOTONIC,
        TIMEBASE_BOOTTIME,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Timebase {}

    /**
     * Position in frames relative to start of an assumed audio stream.
     * <p>
     * When obtained through
     * {@link AudioRecord#getTimestamp AudioRecord.getTimestamp(AudioTimestamp, int)},
     * all 64 bits of position are valid.
     * <p>
     * When obtained through
     * {@link AudioTrack#getTimestamp AudioTrack.getTimestamp(AudioTimestamp)},
     * the low-order 32 bits of position is in wrapping frame units similar to
     * {@link AudioTrack#getPlaybackHeadPosition AudioTrack.getPlaybackHeadPosition()}.
     */
    public long framePosition;

    /**
     * Time associated with the frame in the audio pipeline.
     * <p>
     * When obtained through
     * {@link AudioRecord#getTimestamp AudioRecord.getTimestamp(AudioTimestamp, int)},
     * this is the estimated time in nanoseconds when the frame referred to by
     * {@link #framePosition} was captured. The timebase is either
     * {@link #TIMEBASE_MONOTONIC} or {@link #TIMEBASE_BOOTTIME}, depending
     * on the timebase parameter used in
     * {@link AudioRecord#getTimestamp AudioRecord.getTimestamp(AudioTimestamp, int)}.
     * <p>
     * When obtained through
     * {@link AudioTrack#getTimestamp AudioTrack.getTimestamp(AudioTimestamp)},
     * this is the estimated time when the frame was presented or is committed to be presented,
     * with a timebase of {@link #TIMEBASE_MONOTONIC}.
     */
    public long nanoTime;
}
