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

/**
 * Structure that groups clock rate of the stream playback, together with the media timestamp
 * of an anchor frame and the system time when that frame was presented or is committed
 * to be presented.
 * The "present" means that audio/video produced on device is detectable by an external
 * observer off device.
 * The time is based on the implementation's best effort, using whatever knowledge
 * is available to the system, but cannot account for any delay unknown to the implementation.
 * The anchor frame could be any frame, including just-rendered frame, dependent on how
 * it's selected. When the anchor frame is the just-rendered one, the media time stands for
 * current position of the playback.
 *
 * @see MediaSync#getTimestamp
 */
public final class MediaTimestamp
{
    /**
     * Media timestamp in microseconds.
     */
    public long mediaTimeUs;

    /**
     * The {@link java.lang.System#nanoTime} corresponding to the media timestamp.
     */
    public long nanoTime;

    /**
     * Media clock rate.
     * It is 1.0 if media clock is in sync with the system clock;
     * greater than 1.0 if media clock is faster than the system clock;
     * less than 1.0 if media clock is slower than the system clock.
     */
    public float clockRate;
}
