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
     */
    void setCurrentClientGenerationId(int clientGeneration);

    void setPlaybackState(int generationId, int state);

    void setMetadata(int generationId, in Bundle metadata);

    void setTransportControlFlags(int generationId, int transportControlFlags);

    void setArtwork(int generationId, in Bitmap artwork);
}
