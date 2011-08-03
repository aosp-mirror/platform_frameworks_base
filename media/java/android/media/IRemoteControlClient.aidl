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
 * {@hide}
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
     * @return null if the given field is not supported, or the String matching the metadata field.
     */
    String getMetadataString(int field);

    /**
     * Returns the current playback state.
     * @return one of the following values:
     *       {@link android.media.AudioManager.RemoteControl#PLAYSTATE_STOPPED},
     *       {@link android.media.AudioManager.RemoteControl#PLAYSTATE_PAUSED},
     *       {@link android.media.AudioManager.RemoteControl#PLAYSTATE_PLAYING},
     *       {@link android.media.AudioManager.RemoteControl#PLAYSTATE_FAST_FORWARDING},
     *       {@link android.media.AudioManager.RemoteControl#PLAYSTATE_REWINDING},
     *       {@link android.media.AudioManager.RemoteControl#PLAYSTATE_SKIPPING_FORWARDS},
     *       {@link android.media.AudioManager.RemoteControl#PLAYSTATE_SKIPPING_BACKWARDS},
     *       {@link android.media.AudioManager.RemoteControl#PLAYSTATE_BUFFERING}.
     */
    int getPlaybackState();

    /**
     * Returns the flags for the media transport control buttons this client supports.
     * @see {@link android.media.AudioManager.RemoteControl#FLAG_KEY_MEDIA_PREVIOUS},
     *      {@link android.media.AudioManager.RemoteControl#FLAG_KEY_MEDIA_REWIND},
     *      {@link android.media.AudioManager.RemoteControl#FLAG_KEY_MEDIA_PLAY},
     *      {@link android.media.AudioManager.RemoteControl#FLAG_KEY_MEDIA_PLAY_PAUSE},
     *      {@link android.media.AudioManager.RemoteControl#FLAG_KEY_MEDIA_PAUSE},
     *      {@link android.media.AudioManager.RemoteControl#FLAG_KEY_MEDIA_STOP},
     *      {@link android.media.AudioManager.RemoteControl#FLAG_KEY_MEDIA_FAST_FORWARD},
     *      {@link android.media.AudioManager.RemoteControl#FLAG_KEY_MEDIA_NEXT}
     */
    int getTransportControlFlags();

    Bitmap getAlbumArt(int width, int height);
}
