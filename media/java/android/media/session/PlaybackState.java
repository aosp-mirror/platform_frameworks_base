/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.media.session;

import android.media.RemoteControlClient;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;

/**
 * Playback state for a {@link MediaSession}. This includes a state like
 * {@link PlaybackState#PLAYSTATE_PLAYING}, the current playback position,
 * and the current control capabilities.
 */
public final class PlaybackState implements Parcelable {
    /**
     * Indicates this performer supports the stop command.
     *
     * @see #setActions
     */
    public static final long ACTION_STOP = 1 << 0;

    /**
     * Indicates this performer supports the pause command.
     *
     * @see #setActions
     */
    public static final long ACTION_PAUSE = 1 << 1;

    /**
     * Indicates this performer supports the play command.
     *
     * @see #setActions
     */
    public static final long ACTION_PLAY = 1 << 2;

    /**
     * Indicates this performer supports the rewind command.
     *
     * @see #setActions
     */
    public static final long ACTION_REWIND = 1 << 3;

    /**
     * Indicates this performer supports the previous command.
     *
     * @see #setActions
     */
    public static final long ACTION_PREVIOUS_ITEM = 1 << 4;

    /**
     * Indicates this performer supports the next command.
     *
     * @see #setActions
     */
    public static final long ACTION_NEXT_ITEM = 1 << 5;

    /**
     * Indicates this performer supports the fast forward command.
     *
     * @see #setActions
     */
    public static final long ACTION_FASTFORWARD = 1 << 6;

    /**
     * Indicates this performer supports the set rating command.
     *
     * @see #setActions
     */
    public static final long ACTION_RATING = 1 << 7;

    /**
     * Indicates this performer supports the seek to command.
     *
     * @see #setActions
     */
    public static final long ACTION_SEEK_TO = 1 << 8;

    /**
     * Indicates this performer supports the play/pause toggle command.
     *
     * @see #setActions
     */
    public static final long ACTION_PLAY_PAUSE = 1 << 9;

    /**
     * This is the default playback state and indicates that no media has been
     * added yet, or the performer has been reset and has no content to play.
     *
     * @see #setState
     */
    public final static int PLAYSTATE_NONE = 0;

    /**
     * State indicating this item is currently stopped.
     *
     * @see #setState
     */
    public final static int PLAYSTATE_STOPPED = 1;

    /**
     * State indicating this item is currently paused.
     *
     * @see #setState
     */
    public final static int PLAYSTATE_PAUSED = 2;

    /**
     * State indicating this item is currently playing.
     *
     * @see #setState
     */
    public final static int PLAYSTATE_PLAYING = 3;

    /**
     * State indicating this item is currently fast forwarding.
     *
     * @see #setState
     */
    public final static int PLAYSTATE_FAST_FORWARDING = 4;

    /**
     * State indicating this item is currently rewinding.
     *
     * @see #setState
     */
    public final static int PLAYSTATE_REWINDING = 5;

    /**
     * State indicating this item is currently buffering and will begin playing
     * when enough data has buffered.
     *
     * @see #setState
     */
    public final static int PLAYSTATE_BUFFERING = 6;

    /**
     * State indicating this item is currently in an error state. The error
     * message should also be set when entering this state.
     *
     * @see #setState
     */
    public final static int PLAYSTATE_ERROR = 7;

    /**
     * State indicating the class doing playback is currently connecting to a
     * route. Depending on the implementation you may return to the previous
     * state when the connection finishes or enter {@link #PLAYSTATE_NONE}. If
     * the connection failed {@link #PLAYSTATE_ERROR} should be used.
     * @hide
     */
    public final static int PLAYSTATE_CONNECTING = 8;

    /**
     * State indicating the player is currently skipping to the previous item.
     *
     * @see #setState
     */
    public final static int PLAYSTATE_SKIPPING_BACKWARDS = 9;

    /**
     * State indicating the player is currently skipping to the next item.
     *
     * @see #setState
     */
    public final static int PLAYSTATE_SKIPPING_FORWARDS = 10;

    /**
     * Use this value for the position to indicate the position is not known.
     */
    public final static long PLAYBACK_POSITION_UNKNOWN = -1;

    private int mState;
    private long mPosition;
    private long mBufferPosition;
    private float mRate;
    private long mActions;
    private String mErrorMessage;
    private long mUpdateTime;

    /**
     * Create an empty PlaybackState. At minimum a state and actions should be
     * set before publishing a PlaybackState.
     */
    public PlaybackState() {
    }

    /**
     * Create a new PlaybackState from an existing PlaybackState. All fields
     * will be copied to the new state.
     *
     * @param from The PlaybackState to duplicate
     */
    public PlaybackState(PlaybackState from) {
        mState = from.mState;
        mPosition = from.mPosition;
        mRate = from.mRate;
        mUpdateTime = from.mUpdateTime;
        mBufferPosition = from.mBufferPosition;
        mActions = from.mActions;
        mErrorMessage = from.mErrorMessage;
    }

    private PlaybackState(Parcel in) {
        mState = in.readInt();
        mPosition = in.readLong();
        mRate = in.readFloat();
        mUpdateTime = in.readLong();
        mBufferPosition = in.readLong();
        mActions = in.readLong();
        mErrorMessage = in.readString();

    }

    @Override
    public String toString() {
        StringBuilder bob = new StringBuilder("PlaybackState {");
        bob.append("state=").append(mState);
        bob.append(", position=").append(mPosition);
        bob.append(", buffered position=").append(mBufferPosition);
        bob.append(", rate=").append(mRate);
        bob.append(", updated=").append(mUpdateTime);
        bob.append(", actions=").append(mActions);
        bob.append(", error=").append(mErrorMessage);
        bob.append("}");
        return bob.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mState);
        dest.writeLong(mPosition);
        dest.writeFloat(mRate);
        dest.writeLong(mUpdateTime);
        dest.writeLong(mBufferPosition);
        dest.writeLong(mActions);
        dest.writeString(mErrorMessage);
    }

    /**
     * Get the current state of playback. One of the following:
     * <ul>
     * <li> {@link PlaybackState#PLAYSTATE_NONE}</li>
     * <li> {@link PlaybackState#PLAYSTATE_STOPPED}</li>
     * <li> {@link PlaybackState#PLAYSTATE_PLAYING}</li>
     * <li> {@link PlaybackState#PLAYSTATE_PAUSED}</li>
     * <li> {@link PlaybackState#PLAYSTATE_FAST_FORWARDING}</li>
     * <li> {@link PlaybackState#PLAYSTATE_REWINDING}</li>
     * <li> {@link PlaybackState#PLAYSTATE_BUFFERING}</li>
     * <li> {@link PlaybackState#PLAYSTATE_ERROR}</li>
     */
    public int getState() {
        return mState;
    }

    /**
     * Set the current state of playback.
     * <p>
     * The position must be in ms and indicates the current playback position
     * within the track. If the position is unknown use
     * {@link #PLAYBACK_POSITION_UNKNOWN}.
     * <p>
     * The rate is a multiple of normal playback and should be 0 when paused and
     * negative when rewinding. Normal playback rate is 1.0.
     * <p>
     * The state must be one of the following:
     * <ul>
     * <li> {@link PlaybackState#PLAYSTATE_NONE}</li>
     * <li> {@link PlaybackState#PLAYSTATE_STOPPED}</li>
     * <li> {@link PlaybackState#PLAYSTATE_PLAYING}</li>
     * <li> {@link PlaybackState#PLAYSTATE_PAUSED}</li>
     * <li> {@link PlaybackState#PLAYSTATE_FAST_FORWARDING}</li>
     * <li> {@link PlaybackState#PLAYSTATE_REWINDING}</li>
     * <li> {@link PlaybackState#PLAYSTATE_BUFFERING}</li>
     * <li> {@link PlaybackState#PLAYSTATE_ERROR}</li>
     * </ul>
     *
     * @param state The current state of playback.
     * @param position The position in the current track in ms.
     * @param rate The current rate of playback as a multiple of normal
     *            playback.
     */
    public void setState(int state, long position, float rate) {
        this.mState = state;
        this.mPosition = position;
        this.mRate = rate;
        mUpdateTime = SystemClock.elapsedRealtime();
    }

    /**
     * Get the current playback position in ms.
     */
    public long getPosition() {
        return mPosition;
    }

    /**
     * Get the current buffer position in ms. This is the farthest playback
     * point that can be reached from the current position using only buffered
     * content.
     */
    public long getBufferPosition() {
        return mBufferPosition;
    }

    /**
     * Set the current buffer position in ms. This is the farthest playback
     * point that can be reached from the current position using only buffered
     * content.
     */
    public void setBufferPosition(long bufferPosition) {
        mBufferPosition = bufferPosition;
    }

    /**
     * Get the current playback rate as a multiple of normal playback. This
     * should be negative when rewinding. A value of 1 means normal playback and
     * 0 means paused.
     *
     * @return The current rate of playback.
     */
    public float getRate() {
        return mRate;
    }

    /**
     * Get the current actions available on this session. This should use a
     * bitmask of the available actions.
     * <ul>
     * <li> {@link PlaybackState#ACTION_PREVIOUS_ITEM}</li>
     * <li> {@link PlaybackState#ACTION_REWIND}</li>
     * <li> {@link PlaybackState#ACTION_PLAY}</li>
     * <li> {@link PlaybackState#ACTION_PAUSE}</li>
     * <li> {@link PlaybackState#ACTION_STOP}</li>
     * <li> {@link PlaybackState#ACTION_FASTFORWARD}</li>
     * <li> {@link PlaybackState#ACTION_NEXT_ITEM}</li>
     * <li> {@link PlaybackState#ACTION_SEEK_TO}</li>
     * <li> {@link PlaybackState#ACTION_RATING}</li>
     * </ul>
     */
    public long getActions() {
        return mActions;
    }

    /**
     * Set the current capabilities available on this session. This should use a
     * bitmask of the available capabilities.
     * <ul>
     * <li> {@link PlaybackState#ACTION_PREVIOUS_ITEM}</li>
     * <li> {@link PlaybackState#ACTION_REWIND}</li>
     * <li> {@link PlaybackState#ACTION_PLAY}</li>
     * <li> {@link PlaybackState#ACTION_PAUSE}</li>
     * <li> {@link PlaybackState#ACTION_STOP}</li>
     * <li> {@link PlaybackState#ACTION_FASTFORWARD}</li>
     * <li> {@link PlaybackState#ACTION_NEXT_ITEM}</li>
     * <li> {@link PlaybackState#ACTION_SEEK_TO}</li>
     * <li> {@link PlaybackState#ACTION_RATING}</li>
     * </ul>
     */
    public void setActions(long capabilities) {
        mActions = capabilities;
    }

    /**
     * Get a user readable error message. This should be set when the state is
     * {@link PlaybackState#PLAYSTATE_ERROR}.
     */
    public String getErrorMessage() {
        return mErrorMessage;
    }

    /**
     * Get the elapsed real time at which position was last updated. If the
     * position has never been set this will return 0;
     *
     * @return The last time the position was updated.
     * @hide
     */
    public long getLastPositionUpdateTime() {
        return mUpdateTime;
    }

    /**
     * Set a user readable error message. This should be set when the state is
     * {@link PlaybackState#PLAYSTATE_ERROR}.
     */
    public void setErrorMessage(String errorMessage) {
        mErrorMessage = errorMessage;
    }

    /**
     * Get the {@link PlaybackState} state for the given
     * {@link RemoteControlClient} state.
     *
     * @param rccState The state used by {@link RemoteControlClient}.
     * @return The equivalent state used by {@link PlaybackState}.
     * @hide
     */
    public static int getStateFromRccState(int rccState) {
        switch (rccState) {
            case RemoteControlClient.PLAYSTATE_BUFFERING:
                return PLAYSTATE_BUFFERING;
            case RemoteControlClient.PLAYSTATE_ERROR:
                return PLAYSTATE_ERROR;
            case RemoteControlClient.PLAYSTATE_FAST_FORWARDING:
                return PLAYSTATE_FAST_FORWARDING;
            case RemoteControlClient.PLAYSTATE_NONE:
                return PLAYSTATE_NONE;
            case RemoteControlClient.PLAYSTATE_PAUSED:
                return PLAYSTATE_PAUSED;
            case RemoteControlClient.PLAYSTATE_PLAYING:
                return PLAYSTATE_PLAYING;
            case RemoteControlClient.PLAYSTATE_REWINDING:
                return PLAYSTATE_REWINDING;
            case RemoteControlClient.PLAYSTATE_SKIPPING_BACKWARDS:
                return PLAYSTATE_SKIPPING_BACKWARDS;
            case RemoteControlClient.PLAYSTATE_STOPPED:
                return PLAYSTATE_STOPPED;
            default:
                return -1;
        }
    }

    /**
     * @hide
     */
    public static long getActionsFromRccControlFlags(int rccFlags) {
        long actions = 0;
        long flag = 1;
        while (flag <= rccFlags) {
            if ((flag & rccFlags) != 0) {
                actions |= getActionForRccFlag((int) flag);
            }
            flag = flag << 1;
        }
        return actions;
    }

    private static long getActionForRccFlag(int flag) {
        switch (flag) {
            case RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS:
                return ACTION_PREVIOUS_ITEM;
            case RemoteControlClient.FLAG_KEY_MEDIA_REWIND:
                return ACTION_REWIND;
            case RemoteControlClient.FLAG_KEY_MEDIA_PLAY:
                return ACTION_PLAY;
            case RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE:
                return ACTION_PLAY_PAUSE;
            case RemoteControlClient.FLAG_KEY_MEDIA_PAUSE:
                return ACTION_PAUSE;
            case RemoteControlClient.FLAG_KEY_MEDIA_STOP:
                return ACTION_STOP;
            case RemoteControlClient.FLAG_KEY_MEDIA_FAST_FORWARD:
                return ACTION_FASTFORWARD;
            case RemoteControlClient.FLAG_KEY_MEDIA_NEXT:
                return ACTION_NEXT_ITEM;
            case RemoteControlClient.FLAG_KEY_MEDIA_POSITION_UPDATE:
                return ACTION_SEEK_TO;
            case RemoteControlClient.FLAG_KEY_MEDIA_RATING:
                return ACTION_RATING;
        }
        return 0;
    }

    public static final Parcelable.Creator<PlaybackState> CREATOR
            = new Parcelable.Creator<PlaybackState>() {
        @Override
        public PlaybackState createFromParcel(Parcel in) {
            return new PlaybackState(in);
        }

        @Override
        public PlaybackState[] newArray(int size) {
            return new PlaybackState[size];
        }
    };
}
