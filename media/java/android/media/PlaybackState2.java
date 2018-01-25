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
import android.os.Bundle;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Playback state for a {@link MediaPlayerBase}, to be shared between {@link MediaSession2} and
 * {@link MediaController2}. This includes a playback state {@link #STATE_PLAYING},
 * the current playback position and extra.
 * @hide
 */
public final class PlaybackState2 {
    private static final String TAG = "PlaybackState2";

    private static final String KEY_STATE = "android.media.playbackstate2.state";

    // TODO(jaewan): Replace states from MediaPlayer2
    /**
     * @hide
     */
    @IntDef({STATE_NONE, STATE_STOPPED, STATE_PREPARED, STATE_PAUSED, STATE_PLAYING,
            STATE_FINISH, STATE_BUFFERING, STATE_ERROR})
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
     * State indicating this item is currently prepared
     */
    public final static int STATE_PREPARED = 2;

    /**
     * State indicating this item is currently paused.
     */
    public final static int STATE_PAUSED = 3;

    /**
     * State indicating this item is currently playing.
     */
    public final static int STATE_PLAYING = 4;

    /**
     * State indicating the playback reaches the end of the item.
     */
    public final static int STATE_FINISH = 5;

    /**
     * State indicating this item is currently buffering and will begin playing
     * when enough data has buffered.
     */
    public final static int STATE_BUFFERING = 6;

    /**
     * State indicating this item is currently in an error state. The error
     * message should also be set when entering this state.
     */
    public final static int STATE_ERROR = 7;

    /**
     * Use this value for the position to indicate the position is not known.
     */
    public final static long PLAYBACK_POSITION_UNKNOWN = -1;

    private final int mState;
    private final long mPosition;
    private final long mBufferedPosition;
    private final float mSpeed;
    private final CharSequence mErrorMessage;
    private final long mUpdateTime;
    private final long mActiveItemId;

    public PlaybackState2(int state, long position, long updateTime, float speed,
            long bufferedPosition, long activeItemId, CharSequence error) {
        mState = state;
        mPosition = position;
        mSpeed = speed;
        mUpdateTime = updateTime;
        mBufferedPosition = bufferedPosition;
        mActiveItemId = activeItemId;
        mErrorMessage = error;
    }

    @Override
    public String toString() {
        StringBuilder bob = new StringBuilder("PlaybackState {");
        bob.append("state=").append(mState);
        bob.append(", position=").append(mPosition);
        bob.append(", buffered position=").append(mBufferedPosition);
        bob.append(", speed=").append(mSpeed);
        bob.append(", updated=").append(mUpdateTime);
        bob.append(", active item id=").append(mActiveItemId);
        bob.append(", error=").append(mErrorMessage);
        bob.append("}");
        return bob.toString();
    }

    /**
     * Get the current state of playback. One of the following:
     * <ul>
     * <li> {@link PlaybackState2#STATE_NONE}</li>
     * <li> {@link PlaybackState2#STATE_STOPPED}</li>
     * <li> {@link PlaybackState2#STATE_PLAYING}</li>
     * <li> {@link PlaybackState2#STATE_PAUSED}</li>
     * <li> {@link PlaybackState2#STATE_BUFFERING}</li>
     * <li> {@link PlaybackState2#STATE_ERROR}</li>
     * </ul>
     */
    @State
    public int getState() {
        return mState;
    }

    /**
     * Get the current playback position in ms.
     */
    public long getPosition() {
        return mPosition;
    }

    /**
     * Get the current buffered position in ms. This is the farthest playback
     * point that can be reached from the current position using only buffered
     * content.
     */
    public long getBufferedPosition() {
        return mBufferedPosition;
    }

    /**
     * Get the current playback speed as a multiple of normal playback. This
     * should be negative when rewinding. A value of 1 means normal playback and
     * 0 means paused.
     *
     * @return The current speed of playback.
     */
    public float getPlaybackSpeed() {
        return mSpeed;
    }

    /**
     * Get a user readable error message. This should be set when the state is
     * {@link PlaybackState2#STATE_ERROR}.
     */
    public CharSequence getErrorMessage() {
        return mErrorMessage;
    }

    /**
     * Get the elapsed real time at which position was last updated. If the
     * position has never been set this will return 0;
     *
     * @return The last time the position was updated.
     */
    public long getLastPositionUpdateTime() {
        return mUpdateTime;
    }

    /**
     * Get the id of the currently active item in the playlist.
     *
     * @return The id of the currently active item in the queue
     */
    public long getCurrentPlaylistItemIndex() {
        return mActiveItemId;
    }

    /**
     * @return Bundle object for this to share between processes.
     */
    public Bundle toBundle() {
        // TODO(jaewan): Include other variables.
        Bundle bundle = new Bundle();
        bundle.putInt(KEY_STATE, mState);
        return bundle;
    }

    /**
     * @param bundle input
     * @return
     */
    public static PlaybackState2 fromBundle(Bundle bundle) {
        // TODO(jaewan): Include other variables.
        final int state = bundle.getInt(KEY_STATE);
        return new PlaybackState2(state, 0, 0, 0, 0, 0, null);
    }
}