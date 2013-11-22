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

/**
 * Structure that groups a position in frame units relative to an assumed audio stream,
 * together with the estimated time when that frame was presented or is committed to be
 * presented.
 * In the case of audio output, "present" means that audio produced on device
 * is detectable by an external observer off device.
 * The time is based on the implementation's best effort, using whatever knowledge
 * is available to the system, but cannot account for any delay unknown to the implementation.
 *
 * @see AudioTrack#getTimestamp
 */
public final class AudioTimestamp
{
    /**
     * Position in frames relative to start of an assumed audio stream.
     * The low-order 32 bits of position is in wrapping frame units similar to
     * {@link AudioTrack#getPlaybackHeadPosition}.
     */
    public long framePosition;

    /**
     * The estimated time when frame was presented or is committed to be presented,
     * in the same units and timebase as {@link java.lang.System#nanoTime}.
     */
    public long nanoTime;
}
