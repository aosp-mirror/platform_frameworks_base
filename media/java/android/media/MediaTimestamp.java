/*
 * Copyright 2015 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.SystemApi;

/**
 * An immutable object that represents the linear correlation between the media time
 * and the system time. It contains the media clock rate, together with the media timestamp
 * of an anchor frame and the system time when that frame was presented or is committed
 * to be presented.
 * <p>
 * The phrase "present" means that audio/video produced on device is detectable by an external
 * observer off device.
 * The time is based on the implementation's best effort, using whatever knowledge
 * is available to the system, but cannot account for any delay unknown to the implementation.
 * The anchor frame could be any frame, including a just-rendered frame, or even a theoretical
 * or in-between frame, based on the source of the MediaTimestamp.
 * When the anchor frame is a just-rendered one, the media time stands for
 * current position of the playback or recording.
 *
 * @see MediaSync#getTimestamp
 * @see MediaPlayer#getTimestamp
 */
public final class MediaTimestamp
{
    /**
     * An unknown media timestamp value
     */
    public static final MediaTimestamp TIMESTAMP_UNKNOWN = new MediaTimestamp(-1, -1, 0.0f);

    /**
     * Get the media time of the anchor in microseconds.
     */
    public long getAnchorMediaTimeUs() {
        return mediaTimeUs;
    }

    /**
     * Get the {@link java.lang.System#nanoTime system time} corresponding to the media time
     * in nanoseconds.
     * @deprecated use {@link #getAnchorSystemNanoTime} instead.
     */
    @Deprecated
    public long getAnchorSytemNanoTime() {
        return getAnchorSystemNanoTime();
    }

    /**
     * Get the {@link java.lang.System#nanoTime system time} corresponding to the media time
     * in nanoseconds.
     */
    public long getAnchorSystemNanoTime() {
        return nanoTime;
    }

    /**
     * Get the rate of the media clock in relation to the system time.
     * <p>
     * It is 1.0 if media clock advances in sync with the system clock;
     * greater than 1.0 if media clock is faster than the system clock;
     * less than 1.0 if media clock is slower than the system clock.
     */
    public float getMediaClockRate() {
        return clockRate;
    }

    /** @hide - accessor shorthand */
    public final long mediaTimeUs;
    /** @hide - accessor shorthand */
    public final long nanoTime;
    /** @hide - accessor shorthand */
    public final float clockRate;

    /** @hide */
    MediaTimestamp(long mediaUs, long systemNs, float rate) {
        mediaTimeUs = mediaUs;
        nanoTime = systemNs;
        clockRate = rate;
    }

    /** @hide */
    MediaTimestamp() {
        mediaTimeUs = 0;
        nanoTime = 0;
        clockRate = 1.0f;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        final MediaTimestamp that = (MediaTimestamp) obj;
        return (this.mediaTimeUs == that.mediaTimeUs)
                && (this.nanoTime == that.nanoTime)
                && (this.clockRate == that.clockRate);
    }

    @Override
    public String toString() {
        return getClass().getName()
                + "{AnchorMediaTimeUs=" + mediaTimeUs
                + " AnchorSystemNanoTime=" + nanoTime
                + " clockRate=" + clockRate
                + "}";
    }

    /**
     * Builder class for {@link MediaTimestamp} objects.
     * <p> Here is an example where <code>Builder</code> is used to define the
     * {@link MediaTimestamp}:
     *
     * <pre class="prettyprint">
     * MediaTimestamp mts = new MediaTimestamp.Builder()
     *         .setMediaTimestamp(mediaTime, systemTime, rate)
     *         .build();
     * </pre>
     * @hide
     */
    @SystemApi
    public static final class Builder {
        long mMediaTimeUs;
        long mNanoTime;
        float mClockRate = 1.0f;

        /**
         * Constructs a new Builder with the defaults.
         */
        public Builder() {
        }

        /**
         * Constructs a new Builder from a given {@link MediaTimestamp} instance
         * @param mts the {@link MediaTimestamp} object whose data will be reused
         * in the new Builder.
         */
        public Builder(@NonNull MediaTimestamp mts) {
            if (mts == null) {
                throw new IllegalArgumentException("null MediaTimestamp is not allowed");
            }
            mMediaTimeUs = mts.mediaTimeUs;
            mNanoTime = mts.nanoTime;
            mClockRate = mts.clockRate;
        }

        /**
         * Combines all of the fields that have been set and return a new
         * {@link MediaTimestamp} object.
         *
         * @return a new {@link MediaTimestamp} object
         */
        public @NonNull MediaTimestamp build() {
            return new MediaTimestamp(mMediaTimeUs, mNanoTime, mClockRate);
        }

        /**
         * Sets the info of media timestamp.
         *
         * @param mediaTimeUs the media time of the anchor in microseconds
         * @param nanoTime the {@link java.lang.System#nanoTime system time} corresponding to
         *     the media time in nanoseconds.
         * @param clockRate the rate of the media clock in relation to the system time.
         * @return the same Builder instance.
         */
        public @NonNull Builder setMediaTimestamp(
                long mediaTimeUs, long nanoTime, float clockRate) {
            mMediaTimeUs = mediaTimeUs;
            mNanoTime = nanoTime;
            mClockRate = clockRate;

            return this;
        }
    }
}
