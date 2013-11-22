/* Copyright (C) 2011 The Android Open Source Project
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
import android.media.IRemoteControlDisplay;
import android.media.Rating;

/**
 * @hide
 * Interface registered by AudioManager to notify a source of remote control information
 * that information is requested to be displayed on the remote control (through
 * IRemoteControlDisplay).
 * {@see AudioManager#registerRemoteControlClient(RemoteControlClient)}.
 */
oneway interface IRemoteControlClient
{
    /**
     * Notifies a remote control client that information for the given generation ID is
     * requested. If the flags contains
     * {@link RemoteControlClient#FLAG_INFORMATION_REQUESTED_ALBUM_ART} then the width and height
     *   parameters are valid.
     * @param generationId
     * @param infoFlags
     * FIXME: is infoFlags required? since the RCC pushes info, this might always be called
     *        with RC_INFO_ALL
     */
    void onInformationRequested(int generationId, int infoFlags);

    /**
     * Notifies a remote control client that information for the given generation ID is
     * requested for the given IRemoteControlDisplay alone.
     * @param rcd the display to which current info should be sent
     */
    void informationRequestForDisplay(IRemoteControlDisplay rcd, int w, int h);

    /**
     * Sets the generation counter of the current client that is displayed on the remote control.
     */
    void setCurrentClientGenerationId(int clientGeneration);

    void   plugRemoteControlDisplay(IRemoteControlDisplay rcd, int w, int h);
    void unplugRemoteControlDisplay(IRemoteControlDisplay rcd);
    void setBitmapSizeForDisplay(IRemoteControlDisplay rcd, int w, int h);
    void setWantsSyncForDisplay(IRemoteControlDisplay rcd, boolean wantsSync);
    void enableRemoteControlDisplay(IRemoteControlDisplay rcd, boolean enabled);
    void seekTo(int clientGeneration, long timeMs);
    void updateMetadata(int clientGeneration, int key, in Rating value);
}