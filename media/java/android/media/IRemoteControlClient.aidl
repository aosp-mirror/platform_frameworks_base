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

/**
 * @hide
 * Interface for an object that exposes information meant to be consumed by remote controls
 * capable of displaying metadata, album art and media transport control buttons.
 * Such a remote control client object is associated with a media button event receiver
 * when registered through
 * {@link AudioManager#registerRemoteControlClient(ComponentName, IRemoteControlClient)}.
 */
interface IRemoteControlClient
{
    /**
     * Called by a remote control to retrieve a String of information to display.
     * @param field the identifier for a metadata field to retrieve. Valid values are
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_ALBUM},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_ALBUMARTIST},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_TITLE},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_ARTIST},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_AUTHOR},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_CD_TRACK_NUMBER},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_COMPILATION},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_COMPOSER},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_DATE},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_DISC_NUMBER},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_DURATION},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_GENRE},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_TITLE},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_WRITER},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_YEAR}.
     * @return null if the requested field is not supported, or the String matching the
     *       metadata field.
     */
    String getMetadataString(int field);

    /**
     * Called by a remote control to retrieve the current playback state.
     * @return one of the following values:
     *       {@link android.media.AudioManager.RemoteControlParameters#PLAYSTATE_STOPPED},
     *       {@link android.media.AudioManager.RemoteControlParameters#PLAYSTATE_PAUSED},
     *       {@link android.media.AudioManager.RemoteControlParameters#PLAYSTATE_PLAYING},
     *       {@link android.media.AudioManager.RemoteControlParameters#PLAYSTATE_FAST_FORWARDING},
     *       {@link android.media.AudioManager.RemoteControlParameters#PLAYSTATE_REWINDING},
     *       {@link android.media.AudioManager.RemoteControlParameters#PLAYSTATE_SKIPPING_FORWARDS},
     *       {@link android.media.AudioManager.RemoteControlParameters#PLAYSTATE_SKIPPING_BACKWARDS},
     *       {@link android.media.AudioManager.RemoteControlParameters#PLAYSTATE_BUFFERING},
     *       {@link android.media.AudioManager.RemoteControlParameters#PLAYSTATE_ERROR}.
     */
    int getPlaybackState();

    /**
     * Called by a remote control to retrieve the flags for the media transport control buttons
     * that this client supports.
     * @see {@link android.media.AudioManager.RemoteControlParameters#FLAG_KEY_MEDIA_PREVIOUS},
     *      {@link android.media.AudioManager.RemoteControlParameters#FLAG_KEY_MEDIA_REWIND},
     *      {@link android.media.AudioManager.RemoteControlParameters#FLAG_KEY_MEDIA_PLAY},
     *      {@link android.media.AudioManager.RemoteControlParameters#FLAG_KEY_MEDIA_PLAY_PAUSE},
     *      {@link android.media.AudioManager.RemoteControlParameters#FLAG_KEY_MEDIA_PAUSE},
     *      {@link android.media.AudioManager.RemoteControlParameters#FLAG_KEY_MEDIA_STOP},
     *      {@link android.media.AudioManager.RemoteControlParameters#FLAG_KEY_MEDIA_FAST_FORWARD},
     *      {@link android.media.AudioManager.RemoteControlParameters#FLAG_KEY_MEDIA_NEXT}
     */
    int getTransportControlFlags();

    /**
     * Called by a remote control to retrieve the album art picture at the requested size.
     * Note that returning a bitmap smaller than the maximum requested dimension is accepted
     * and it will be scaled as needed, but exceeding the maximum dimensions may produce
     * unspecified results, such as the image being cropped or simply not being displayed.
     * @param maxWidth the maximum width of the requested bitmap expressed in pixels.
     * @param maxHeight the maximum height of the requested bitmap expressed in pixels.
     * @return the bitmap for the album art, or null if there isn't any.
     * @see android.graphics.Bitmap
     */
    Bitmap getAlbumArt(int maxWidth, int maxHeight);
}
