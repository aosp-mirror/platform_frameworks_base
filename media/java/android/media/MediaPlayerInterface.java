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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.MediaSession2.PlaylistParams;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Base interfaces for all media players that want media session.
 * @hide
 */
public interface MediaPlayerInterface {
    /**
     * Unspecified media player error.
     */
    int MEDIA_ERROR_UNKNOWN = MediaPlayer2.MEDIA_ERROR_UNKNOWN;

    /**
     * The video is streamed and its container is not valid for progressive
     * playback i.e the video's index (e.g moov atom) is not at the start of the
     * file.
     */
    int MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK =
            MediaPlayer2.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK;

    /**
     * File or network related operation errors.
     */
    int MEDIA_ERROR_IO = MediaPlayer2.MEDIA_ERROR_IO;

    /**
     * Bitstream is not conforming to the related coding standard or file spec.
     */
    int MEDIA_ERROR_MALFORMED = MediaPlayer2.MEDIA_ERROR_MALFORMED;

    /**
     * Bitstream is conforming to the related coding standard or file spec, but
     * the media framework does not support the feature.
     */
    int MEDIA_ERROR_UNSUPPORTED = MediaPlayer2.MEDIA_ERROR_UNSUPPORTED;

    /**
     * Some operation takes too long to complete, usually more than 3-5 seconds.
     */
    int MEDIA_ERROR_TIMED_OUT = MediaPlayer2.MEDIA_ERROR_TIMED_OUT;

    /**
     * Callbacks to listens to the changes in {@link PlaybackState2} and error.
     */
    interface EventCallback {
        /**
         * Called when {@link PlaybackState2} for this player is changed.
         */
        default void onPlaybackStateChanged(PlaybackState2 state) { }

        /**
         * Called to indicate an error.
         *
         * @param mediaId optional mediaId to indicate error
         * @param what what
         * @param extra
         */
        default void onError(@Nullable String mediaId, int what, int extra) { }
    }

    // Transport controls that session will send command directly to this player.
    void play();
    void prepare();
    void pause();
    void stop();
    void skipToPrevious();
    void skipToNext();
    void seekTo(long pos);
    void fastForward();
    void rewind();

    PlaybackState2 getPlaybackState();

    /**
     * Sets the {@link AudioAttributes} to be used during the playback of the media.
     *
     * @param attributes non-null <code>AudioAttributes</code>.
     */
    void setAudioAttributes(@NonNull AudioAttributes attributes);

    /**
     * Returns AudioAttributes that media player has.
     */
    @Nullable
    AudioAttributes getAudioAttributes();

    void addPlaylistItem(int index, MediaItem2 item);
    void removePlaylistItem(MediaItem2 item);

    void setPlaylist(List<MediaItem2> playlist);
    List<MediaItem2> getPlaylist();

    void setCurrentPlaylistItem(int index);
    void setPlaylistParams(PlaylistParams params);
    PlaylistParams getPlaylistParams();

    /**
     * Register a {@link EventCallback}.
     *
     * @param executor a callback executor
     * @param callback a EventCallback
     */
    void registerEventCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull EventCallback callback);

    /**
     * Unregister previously registered {@link EventCallback}.
     *
     * @param callback a EventCallback
     */
    void unregisterEventCallback(@NonNull EventCallback callback);
}
