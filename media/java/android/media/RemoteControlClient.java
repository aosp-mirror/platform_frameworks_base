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

/**
 * @hide
 * Interface for an object that exposes information meant to be consumed by remote controls
 * capable of displaying metadata, album art and media transport control buttons.
 * Such a remote control client object is associated with a media button event receiver
 * when registered through
 * {@link AudioManager#registerRemoteControlClient(ComponentName, RemoteControlClient)}.
 */
public interface RemoteControlClient
{
    /**
     * Playback state of a RemoteControlClient which is stopped.
     *
     * @see android.media.RemoteControlClient#getPlaybackState()
     */
    public final static int PLAYSTATE_STOPPED            = 1;
    /**
     * Playback state of a RemoteControlClient which is paused.
     *
     * @see android.media.RemoteControlClient#getPlaybackState()
     */
    public final static int PLAYSTATE_PAUSED             = 2;
    /**
     * Playback state of a RemoteControlClient which is playing media.
     *
     * @see android.media.RemoteControlClient#getPlaybackState()
     */
    public final static int PLAYSTATE_PLAYING            = 3;
    /**
     * Playback state of a RemoteControlClient which is fast forwarding in the media
     *    it is currently playing.
     *
     * @see android.media.RemoteControlClient#getPlaybackState()
     */
    public final static int PLAYSTATE_FAST_FORWARDING    = 4;
    /**
     * Playback state of a RemoteControlClient which is fast rewinding in the media
     *    it is currently playing.
     *
     * @see android.media.RemoteControlClient#getPlaybackState()
     */
    public final static int PLAYSTATE_REWINDING          = 5;
    /**
     * Playback state of a RemoteControlClient which is skipping to the next
     *    logical chapter (such as a song in a playlist) in the media it is currently playing.
     *
     * @see android.media.RemoteControlClient#getPlaybackState()
     */
    public final static int PLAYSTATE_SKIPPING_FORWARDS  = 6;
    /**
     * Playback state of a RemoteControlClient which is skipping back to the previous
     *    logical chapter (such as a song in a playlist) in the media it is currently playing.
     *
     * @see android.media.RemoteControlClient#getPlaybackState()
     */
    public final static int PLAYSTATE_SKIPPING_BACKWARDS = 7;
    /**
     * Playback state of a RemoteControlClient which is buffering data to play before it can
     *    start or resume playback.
     *
     * @see android.media.RemoteControlClient#getPlaybackState()
     */
    public final static int PLAYSTATE_BUFFERING          = 8;
    /**
     * Playback state of a RemoteControlClient which cannot perform any playback related
     *    operation because of an internal error. Examples of such situations are no network
     *    connectivity when attempting to stream data from a server, or expired user credentials
     *    when trying to play subscription-based content.
     *
     * @see android.media.RemoteControlClient#getPlaybackState()
     */
    public final static int PLAYSTATE_ERROR              = 9;

    /**
     * Flag indicating a RemoteControlClient makes use of the "previous" media key.
     *
     * @see android.media.RemoteControlClient#getTransportControlFlags()
     * @see android.view.KeyEvent#KEYCODE_MEDIA_PREVIOUS
     */
    public final static int FLAG_KEY_MEDIA_PREVIOUS = 1 << 0;
    /**
     * Flag indicating a RemoteControlClient makes use of the "rewing" media key.
     *
     * @see android.media.RemoteControlClient#getTransportControlFlags()
     * @see android.view.KeyEvent#KEYCODE_MEDIA_REWIND
     */
    public final static int FLAG_KEY_MEDIA_REWIND = 1 << 1;
    /**
     * Flag indicating a RemoteControlClient makes use of the "play" media key.
     *
     * @see android.media.RemoteControlClient#getTransportControlFlags()
     * @see android.view.KeyEvent#KEYCODE_MEDIA_PLAY
     */
    public final static int FLAG_KEY_MEDIA_PLAY = 1 << 2;
    /**
     * Flag indicating a RemoteControlClient makes use of the "play/pause" media key.
     *
     * @see android.media.RemoteControlClient#getTransportControlFlags()
     * @see android.view.KeyEvent#KEYCODE_MEDIA_PLAY_PAUSE
     */
    public final static int FLAG_KEY_MEDIA_PLAY_PAUSE = 1 << 3;
    /**
     * Flag indicating a RemoteControlClient makes use of the "pause" media key.
     *
     * @see android.media.RemoteControlClient#getTransportControlFlags()
     * @see android.view.KeyEvent#KEYCODE_MEDIA_PAUSE
     */
    public final static int FLAG_KEY_MEDIA_PAUSE = 1 << 4;
    /**
     * Flag indicating a RemoteControlClient makes use of the "stop" media key.
     *
     * @see android.media.RemoteControlClient#getTransportControlFlags()
     * @see android.view.KeyEvent#KEYCODE_MEDIA_STOP
     */
    public final static int FLAG_KEY_MEDIA_STOP = 1 << 5;
    /**
     * Flag indicating a RemoteControlClient makes use of the "fast forward" media key.
     *
     * @see android.media.RemoteControlClient#getTransportControlFlags()
     * @see android.view.KeyEvent#KEYCODE_MEDIA_FAST_FORWARD
     */
    public final static int FLAG_KEY_MEDIA_FAST_FORWARD = 1 << 6;
    /**
     * Flag indicating a RemoteControlClient makes use of the "next" media key.
     *
     * @see android.media.RemoteControlClient#getTransportControlFlags()
     * @see android.view.KeyEvent#KEYCODE_MEDIA_NEXT
     */
    public final static int FLAG_KEY_MEDIA_NEXT = 1 << 7;

    /**
     * Flag used to signal that the metadata exposed by the RemoteControlClient has changed.
     *
     * @see #notifyRemoteControlInformationChanged(ComponentName, int)
     */
    public final static int FLAG_INFORMATION_CHANGED_METADATA = 1 << 0;
    /**
     * Flag used to signal that the transport control buttons supported by the
     * RemoteControlClient have changed.
     * This can for instance happen when playback is at the end of a playlist, and the "next"
     * operation is not supported anymore.
     *
     * @see #notifyRemoteControlInformationChanged(ComponentName, int)
     */
    public final static int FLAG_INFORMATION_CHANGED_KEY_MEDIA = 1 << 1;
    /**
     * Flag used to signal that the playback state of the RemoteControlClient has changed.
     *
     * @see #notifyRemoteControlInformationChanged(ComponentName, int)
     */
    public final static int FLAG_INFORMATION_CHANGED_PLAYSTATE = 1 << 2;
    /**
     * Flag used to signal that the album art for the RemoteControlClient has changed.
     *
     * @see #notifyRemoteControlInformationChanged(ComponentName, int)
     */
    public final static int FLAG_INFORMATION_CHANGED_ALBUM_ART = 1 << 3;

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
     *       {@link #PLAYSTATE_STOPPED},
     *       {@link #PLAYSTATE_PAUSED},
     *       {@link #PLAYSTATE_PLAYING},
     *       {@link #PLAYSTATE_FAST_FORWARDING},
     *       {@link #PLAYSTATE_REWINDING},
     *       {@link #PLAYSTATE_SKIPPING_FORWARDS},
     *       {@link #PLAYSTATE_SKIPPING_BACKWARDS},
     *       {@link #PLAYSTATE_BUFFERING},
     *       {@link #PLAYSTATE_ERROR}.
     */
    int getPlaybackState();

    /**
     * Called by a remote control to retrieve the flags for the media transport control buttons
     * that this client supports.
     * @see {@link #FLAG_KEY_MEDIA_PREVIOUS},
     *      {@link #FLAG_KEY_MEDIA_REWIND},
     *      {@link #FLAG_KEY_MEDIA_PLAY},
     *      {@link #FLAG_KEY_MEDIA_PLAY_PAUSE},
     *      {@link #FLAG_KEY_MEDIA_PAUSE},
     *      {@link #FLAG_KEY_MEDIA_STOP},
     *      {@link #FLAG_KEY_MEDIA_FAST_FORWARD},
     *      {@link #FLAG_KEY_MEDIA_NEXT}
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
