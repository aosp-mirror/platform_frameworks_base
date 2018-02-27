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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.media.update.ApiLoader;
import android.media.update.PlaybackState2Provider;
import android.os.Bundle;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Playback state for a {@link MediaPlayerBase}, to be shared between {@link MediaSession2} and
 * {@link MediaController2}. This includes a playback state {@link #STATE_PLAYING},
 * the current playback position and extra.
 * @hide
 */
// TODO(jaewan): Remove this.
public final class PlaybackState2 {
    // Similar to the PlaybackState with following changes
    //    - Not implement Parcelable and added from/toBundle()
    //    - Removed playback state that doesn't match with the MediaPlayer2
    //      Full list should be finalized when the MediaPlayer2 has getter for the playback state.
    //      Here's table for the MP2 state and PlaybackState2.State.
    //         +----------------------------------------+----------------------------------------+
    //         | MediaPlayer2 state                     | Matching PlaybackState2.State          |
    //         | (Names are from MP2' Javadoc)          |                                        |
    //         +----------------------------------------+----------------------------------------+
    //         | Idle: Just finished creating MP2       | STATE_NONE                             |
    //         |     or reset() is called               |                                        |
    //         +----------------------------------------+----------------------------------------+
    //         | Initialized: setDataSource/Playlist    | N/A (Session/Controller don't          |
    //         |                                        |     differentiate with Prepared)       |
    //         +----------------------------------------+----------------------------------------+
    //         | Prepared: Prepared after initialized   | STATE_PAUSED                           |
    //         +----------------------------------------+----------------------------------------+
    //         | Started: Started playback              | STATE_PLAYING                          |
    //         +----------------------------------------+----------------------------------------+
    //         | Paused: Paused playback                | STATE_PAUSED                           |
    //         +----------------------------------------+----------------------------------------+
    //         | PlaybackCompleted: Playback is done    | STATE_PAUSED                           |
    //         +----------------------------------------+----------------------------------------+
    //         | Stopped: MP2.stop() is called.         | STATE_STOPPED                          |
    //         |     prepare() is needed to play again  |                                        |
    //         |     (Seemingly the same as initialized |                                        |
    //         |     because cannot set data source     |                                        |
    //         |     after this)                        |                                        |
    //         +----------------------------------------+----------------------------------------+
    //         | Error: an API is called in a state     | STATE_ERROR                            |
    //         |     that the API isn't supported       |                                        |
    //         +----------------------------------------+----------------------------------------+
    //         | End: MP2.close() is called to release  | N/A (MediaSession will be gone)        |
    //         |    MP2. Cannot be reused anymore       |                                        |
    //         +----------------------------------------+----------------------------------------+
    //         | Started, but                           | STATE_BUFFERING                        |
    //         |    EventCallback.onBufferingUpdate()   |                                        |
    //         +----------------------------------------+----------------------------------------+
    //    - Removed actions and custom actions.
    //    - Removed error string
    //    - Repeat mode / shuffle mode is now in the PlaylistParams
    // TODO(jaewan): Replace states from MediaPlayer2
    /**
     * @hide
     */
    @IntDef({STATE_NONE, STATE_STOPPED, STATE_PAUSED, STATE_PLAYING, STATE_BUFFERING, STATE_ERROR})
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {}

    /**
     * This is the default playback state and indicates that no media has been
     * added yet, or the performer has been reset and has no content to play.
     */
    public final static int STATE_NONE = 0;

    /**
     * State indicating this item is currently stopped.
     */
    public final static int STATE_STOPPED = 1;

    /**
     * State indicating this item is currently paused.
     */
    public final static int STATE_PAUSED = 2;

    /**
     * State indicating this item is currently playing.
     */
    public final static int STATE_PLAYING = 3;

    /**
     * State indicating this item is currently buffering and will begin playing
     * when enough data has buffered.
     */
    public final static int STATE_BUFFERING = 4;

    /**
     * State indicating this item is currently in an error state.
     */
    public final static int STATE_ERROR = 5;

    /**
     * Use this value for the position to indicate the position is not known.
     */
    public final static long PLAYBACK_POSITION_UNKNOWN = -1;

    private final PlaybackState2Provider mProvider;

    public PlaybackState2(@NonNull Context context, int state, long position, long updateTime,
            float speed, long bufferedPosition, long activeItemId) {
        mProvider = ApiLoader.getProvider(context).createPlaybackState2(context, this, state,
                position, updateTime, speed, bufferedPosition, activeItemId);
    }

    @Override
    public String toString() {
        return mProvider.toString_impl();
    }

    /**
     * Get the current state of playback. One of the following:
     * <ul>
     * <li> {@link PlaybackState2#STATE_NONE}</li>
     * <li> {@link PlaybackState2#STATE_STOPPED}</li>
     * <li> {@link PlaybackState2#STATE_PAUSED}</li>
     * <li> {@link PlaybackState2#STATE_PLAYING}</li>
     * <li> {@link PlaybackState2#STATE_BUFFERING}</li>
     * <li> {@link PlaybackState2#STATE_ERROR}</li>
     * </ul>
     */
    @State
    public int getState() {
        return mProvider.getState_impl();
    }

    /**
     * Get the current playback position in ms.
     */
    public long getPosition() {
        return mProvider.getPosition_impl();
    }

    /**
     * Get the current buffered position in ms. This is the farthest playback
     * point that can be reached from the current position using only buffered
     * content.
     */
    public long getBufferedPosition() {
        return mProvider.getBufferedPosition_impl();
    }

    /**
     * Get the current playback speed as a multiple of normal playback. This
     * should be negative when rewinding. A value of 1 means normal playback and
     * 0 means paused.
     *
     * @return The current speed of playback.
     */
    public float getPlaybackSpeed() {
        return mProvider.getPlaybackSpeed_impl();
    }

    /**
     * Get the elapsed real time at which position was last updated. If the
     * position has never been set this will return 0;
     *
     * @return The last time the position was updated.
     */
    public long getLastPositionUpdateTime() {
        return mProvider.getLastPositionUpdateTime_impl();
    }

    /**
     * Get the id of the currently active item in the playlist.
     *
     * @return The id of the currently active item in the queue
     */
    public long getCurrentPlaylistItemIndex() {
        return mProvider.getCurrentPlaylistItemIndex_impl();
    }

    /**
     * Returns this object as a bundle to share between processes.
     */
    public @NonNull Bundle toBundle() {
        return mProvider.toBundle_impl();
    }

    /**
     * Creates an instance from a bundle which is previously created by {@link #toBundle()}.
     *
     * @param context context
     * @param bundle A bundle created by {@link #toBundle()}.
     * @return A new {@link PlaybackState2} instance. Returns {@code null} if the given
     *         {@param bundle} is null, or if the {@param bundle} has no playback state parameters.
     */
    public @Nullable static PlaybackState2 fromBundle(@NonNull Context context,
            @Nullable Bundle bundle) {
        return ApiLoader.getProvider(context).fromBundle_PlaybackState2(context, bundle);
    }
}
