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
     * @param clientEventReceiver the media button event receiver associated with the client.
     *    May be null, which implies there is no registered media button event receiver. This
     *    parameter is supplied as an optimization so a display can directly target media button
     *    events to the client.
     * @param clearing true if the new client generation value maps to a remote control update
     *    where the display should be cleared.
     */
    void setCurrentClientId(int clientGeneration, in ComponentName clientEventReceiver,
            boolean clearing);

    void setPlaybackState(int generationId, int state);

    void setTransportControlFlags(int generationId, int transportControlFlags);

    void setMetadata(int generationId, in Bundle metadata);

    void setArtwork(int generationId, in Bitmap artwork);

    /**
     * To combine metadata text and artwork in one binder call
     */
    void setAllMetadata(int generationId, in Bundle metadata, in Bitmap artwork);
}
