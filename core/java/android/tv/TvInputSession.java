/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.tv;

import android.net.Uri;
import android.view.Surface;

/**
 * The TvInputSession provides the per-session functionality of TvInputService.
 *
 * @hide
 */
public abstract class TvInputSession {
    /**
     * This method is called when the application would like to stop using the current input
     * session.
     */
    public void release() { }

    /**
     * Sets the {@link Surface} for the current input session on which the TV input renders video.
     *
     * @param surface {@link Surface} to be used for the video playback of this session.
     */
    public void setSurface(Surface surface) { }

    /**
     * This method is called when the application needs to handle the change of audio focus by
     * setting the relative volume of the current TV input service session.
     *
     * @param volume Volume scale from 0.0 to 1.0.
     */
    // TODO: Remove this once it becomes irrelevant for applications to handle audio focus. The plan
    // is to introduce some new concepts that will solve a number of problems in audio policy today.
    public void setVolume(float volume) { }

    /**
     * Tunes to a given channel.
     *
     * @param channelUri The URI of the channel.
     */
    public void tune(Uri channelUri) { }
}
