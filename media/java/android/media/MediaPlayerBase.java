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

import android.media.session.PlaybackState;
import android.os.Handler;

/**
 * Tentative interface for all media players that want media session.
 * APIs are named to avoid conflicts with MediaPlayer APIs.
 * All calls should be asynchrounous.
 *
 * @hide
 */
// TODO(wjia) Finalize the list of MediaPlayer2, which MediaPlayerBase's APIs will be come from.
public abstract class MediaPlayerBase {
    /**
     * Listens change in {@link PlaybackState}.
     */
    public interface PlaybackListener {
        /**
         * Called when {@link PlaybackState} for this player is changed.
         */
        void onPlaybackChanged(PlaybackState state);
    }

    // TODO(jaewan): setDataSources()?
    // TODO(jaewan): Add release() or do that in stop()?

    // TODO(jaewan): Add set/getSupportedActions().
    public abstract void play();
    public abstract void pause();
    public abstract void stop();
    public abstract void skipToPrevious();
    public abstract void skipToNext();

    // Currently PlaybackState's error message is the content title (for testing only)
    // TODO(jaewan): Add metadata support
    public abstract PlaybackState getPlaybackState();

    /**
     * Add a {@link PlaybackListener} to be invoked when the playback state is changed.
     *
     * @param listener the listener that will be run
     * @param handler the Handler that will receive the listener
     */
    public abstract void addPlaybackListener(PlaybackListener listener, Handler handler);

    /**
     * Remove previously added {@link PlaybackListener}.
     *
     * @param listener the listener to be removed
     */
    public abstract void removePlaybackListener(PlaybackListener listener);
}
