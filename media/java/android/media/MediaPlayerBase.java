/*
 * Copyright 2018 The Android Open Source Project
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

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.MediaSession2.PlaylistParams;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Base class for all media players that want media session.
 */
public abstract class MediaPlayerBase implements AutoCloseable {
    /**
     * @hide
     */
    @IntDef({STATE_IDLE, STATE_PAUSED, STATE_PLAYING, STATE_ERROR})
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {}

    /**
     * State when the player is idle, and needs configuration to start playback.
     */
    public static final int STATE_IDLE = 0;

    /**
     * State when the player's playback is paused
     */
    public static final int STATE_PAUSED = 0;

    /**
     * State when the player's playback is ongoing
     */
    public static final int STATE_PLAYING = 0;

    /**
     * State when the player is in error state and cannot be recovered self.
     */
    public static final int STATE_ERROR = 0;

    /**
     * Unspecified media player error.
     * @hide
     */
    public static final int MEDIA_ERROR_UNKNOWN = MediaPlayer2.MEDIA_ERROR_UNKNOWN;

    /**
     * The video is streamed and its container is not valid for progressive
     * playback i.e the video's index (e.g moov atom) is not at the start of the
     * file.
     * @hide
     */
    public static final int MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK =
            MediaPlayer2.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK;

    /**
     * File or network related operation errors.
     * @hide
     */
    public static final int MEDIA_ERROR_IO = MediaPlayer2.MEDIA_ERROR_IO;

    /**
     * Bitstream is not conforming to the related coding standard or file spec.
     * @hide
     */
    public static final int MEDIA_ERROR_MALFORMED = MediaPlayer2.MEDIA_ERROR_MALFORMED;

    /**
     * Bitstream is conforming to the related coding standard or file spec, but
     * the media framework does not support the feature.
     * @hide
     */
    public static final int MEDIA_ERROR_UNSUPPORTED = MediaPlayer2.MEDIA_ERROR_UNSUPPORTED;

    /**
     * Some operation takes too long to complete, usually more than 3-5 seconds.
     * @hide
     */
    public static final int MEDIA_ERROR_TIMED_OUT = MediaPlayer2.MEDIA_ERROR_TIMED_OUT;

    /**
     * Callbacks to listens to the changes in {@link PlaybackState2} and error.
     * @hide
     */
    public static abstract class EventCallback {
        /**
         * Called when {@link PlaybackState2} for this player is changed.
         */
        public void onPlaybackStateChanged(PlaybackState2 state) { }

        /**
         * Called to indicate an error.
         *
         * @param mediaId optional mediaId to indicate error
         * @param what what
         * @param extra
         */
        public void onError(@Nullable String mediaId, int what, int extra) { }
    }

    // Transport controls that session will send command directly to this player.
    /**
     * Start or resumes playback
     */
    public abstract void play();

    /**
     * @hide
     */
    public abstract void prepare();

    /**
     * Pause playback
     */
    public abstract void pause();

    /**
     * @hide
     */
    public abstract void stop();

    /**
     * @hide
     */
    public abstract void skipToPrevious();

    /**
     * @hide
     */
    public abstract void skipToNext();

    /**
     * @hide
     */
    public abstract void seekTo(long pos);

    /**
     * @hide
     */
    public abstract void fastForward();

    /**
     * @hide
     */
    public abstract void rewind();

    /**
     * @hide
     */
    public abstract PlaybackState2 getPlaybackState();

    /**
     * Return player state.
     *
     * @return player state
     * @see #STATE_IDLE
     * @see #STATE_PLAYING
     * @see #STATE_PAUSED
     * @see #STATE_ERROR
     */
    public abstract @State int getPlayerState();

    /**
     * Sets the {@link AudioAttributes} to be used during the playback of the media.
     *
     * @param attributes non-null <code>AudioAttributes</code>.
     */
    public abstract void setAudioAttributes(@NonNull AudioAttributes attributes);

    /**
     * Returns AudioAttributes that media player has.
     */
    public abstract @Nullable AudioAttributes getAudioAttributes();

    /**
     * @hide
     */
    public abstract void addPlaylistItem(int index, MediaItem2 item);

    /**
     * @hide
     */
    public abstract void removePlaylistItem(MediaItem2 item);

    /**
     * @hide
     */
    public abstract void setPlaylist(List<MediaItem2> playlist);

    /**
     * @hide
     */
    public abstract List<MediaItem2> getPlaylist();

    /**
     * @hide
     */
    public abstract void setCurrentPlaylistItem(MediaItem2 item);

    /**
     * @hide
     */
    public abstract void setPlaylistParams(PlaylistParams params);

    /**
     * @hide
     */
    public abstract PlaylistParams getPlaylistParams();

    /**
     * Register a {@link EventCallback}.
     *
     * @param executor a callback executor
     * @param callback a EventCallback
     * @hide
     */
    public abstract void registerEventCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull EventCallback callback);

    /**
     * Unregister previously registered {@link EventCallback}.
     *
     * @param callback a EventCallback
     * @hide
     */
    public abstract void unregisterEventCallback(@NonNull EventCallback callback);
}
