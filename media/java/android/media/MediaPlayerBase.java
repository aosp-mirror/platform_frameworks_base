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

import android.media.MediaSession2.PlaylistParam;
import android.media.session.PlaybackState;
import android.os.Handler;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Base interfaces for all media players that want media session.
 *
 * @hide
 */
public abstract class MediaPlayerBase {
    /**
     * Listens change in {@link PlaybackState2}.
     */
    public interface PlaybackListener {
        /**
         * Called when {@link PlaybackState2} for this player is changed.
         */
        void onPlaybackChanged(PlaybackState2 state);
    }

    public abstract void play();
    public abstract void prepare();
    public abstract void pause();
    public abstract void stop();
    public abstract void skipToPrevious();
    public abstract void skipToNext();
    public abstract void seekTo(long pos);
    public abstract void fastFoward();
    public abstract void rewind();

    public abstract PlaybackState2 getPlaybackState();
    public abstract AudioAttributes getAudioAttributes();

    public abstract void setPlaylist(List<MediaItem2> item, PlaylistParam param);
    public abstract void setCurrentPlaylistItem(int index);

    /**
     * Add a {@link PlaybackListener} to be invoked when the playback state is changed.
     *
     * @param executor the Handler that will receive the listener
     * @param listener the listener that will be run
     */
    public abstract void addPlaybackListener(Executor executor, PlaybackListener listener);

    /**
     * Remove previously added {@link PlaybackListener}.
     *
     * @param listener the listener to be removed
     */
    public abstract void removePlaybackListener(PlaybackListener listener);
}
