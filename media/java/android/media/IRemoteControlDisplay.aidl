/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.app.PendingIntent;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.os.Bundle;

/**
 * @hide
 * Interface registered through AudioManager of an object that displays information
 * received from a remote control client.
 * {@see AudioManager#registerRemoteControlDisplay(IRemoteControlDisplay)}.
 */
oneway interface IRemoteControlDisplay
{
    /**
     * Sets the generation counter of the current client that is displayed on the remote control.
     * @param clientGeneration the new RemoteControlClient generation
     * @param clientMediaIntent the PendingIntent associated with the client.
     *    May be null, which implies there is no registered media button event receiver.
     * @param clearing true if the new client generation value maps to a remote control update
     *    where the display should be cleared.
     */
    void setCurrentClientId(int clientGeneration, in PendingIntent clientMediaIntent,
            boolean clearing);

    /**
     * Sets whether the controls of this display are enabled
     * @param if false, the display shouldn't any commands
     */
    void setEnabled(boolean enabled);

    /**
     * Sets the playback information (state, position and speed) of a client.
     * @param generationId the current generation ID as known by this client
     * @param state the current playback state, one of the following values:
     *       {@link RemoteControlClient#PLAYSTATE_STOPPED},
     *       {@link RemoteControlClient#PLAYSTATE_PAUSED},
     *       {@link RemoteControlClient#PLAYSTATE_PLAYING},
     *       {@link RemoteControlClient#PLAYSTATE_FAST_FORWARDING},
     *       {@link RemoteControlClient#PLAYSTATE_REWINDING},
     *       {@link RemoteControlClient#PLAYSTATE_SKIPPING_FORWARDS},
     *       {@link RemoteControlClient#PLAYSTATE_SKIPPING_BACKWARDS},
     *       {@link RemoteControlClient#PLAYSTATE_BUFFERING},
     *       {@link RemoteControlClient#PLAYSTATE_ERROR}.
     * @param stateChangeTimeMs the time at which the client reported the playback information
     * @param currentPosMs a 0 or positive value for the current media position expressed in ms
     *    Strictly negative values imply that position is not known:
     *    a value of {@link RemoteControlClient#PLAYBACK_POSITION_INVALID} is intended to express
     *    that an application doesn't know the position (e.g. listening to a live stream of a radio)
     *    or that the position information is not applicable (e.g. when state
     *    is {@link RemoteControlClient#PLAYSTATE_BUFFERING} and nothing had played yet);
     *    a value of {@link RemoteControlClient#PLAYBACK_POSITION_ALWAYS_UNKNOWN} implies that the
     *    application uses {@link RemoteControlClient#setPlaybackState(int)} (legacy API) and will
     *    never pass a playback position.
     * @param speed a value expressed as a ratio of 1x playback: 1.0f is normal playback,
     *    2.0f is 2x, 0.5f is half-speed, -2.0f is rewind at 2x speed. 0.0f means nothing is
     *    playing (e.g. when state is {@link RemoteControlClient#PLAYSTATE_ERROR}).
     */
    void setPlaybackState(int generationId, int state, long stateChangeTimeMs, long currentPosMs,
            float speed);

    /**
     * Sets the transport control flags and playback position capabilities of a client.
     * @param generationId the current generation ID as known by this client
     * @param transportControlFlags bitmask of the transport controls this client supports, see
     *         {@link RemoteControlClient#setTransportControlFlags(int)}
     * @param posCapabilities a bit mask for playback position capabilities, see
     *         {@link RemoteControlClient#MEDIA_POSITION_READABLE} and
     *         {@link RemoteControlClient#MEDIA_POSITION_WRITABLE}
     */
    void setTransportControlInfo(int generationId, int transportControlFlags, int posCapabilities);

    void setMetadata(int generationId, in Bundle metadata);

    void setArtwork(int generationId, in Bitmap artwork);

    /**
     * To combine metadata text and artwork in one binder call
     */
    void setAllMetadata(int generationId, in Bundle metadata, in Bitmap artwork);
}
