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
     * Listens change in {@link PlaybackState2}.
     */
    interface PlaybackListener {
        /**
         * Called when {@link PlaybackState2} for this player is changed.
         */
        void onPlaybackChanged(PlaybackState2 state);
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
     * Add a {@link PlaybackListener} to be invoked when the playback state is changed.
     *
     * @param executor the Handler that will receive the listener
     * @param listener the listener that will be run
     */
    void addPlaybackListener(Executor executor, PlaybackListener listener);

    /**
     * Remove previously added {@link PlaybackListener}.
     *
     * @param listener the listener to be removed
     */
    void removePlaybackListener(PlaybackListener listener);
}
