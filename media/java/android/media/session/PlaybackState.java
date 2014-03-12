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

    private int mState;
    private long mPosition;
    private long mBufferPosition;
    private float mSpeed;
    private long mCapabilities;
    private String mErrorMessage;

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
        this.setState(from.getState());
        this.setPosition(from.getPosition());
        this.setBufferPosition(from.getBufferPosition());
        this.setSpeed(from.getSpeed());
        this.setActions(from.getActions());
        this.setErrorMessage(from.getErrorMessage());
    }

    private PlaybackState(Parcel in) {
        this.setState(in.readInt());
        this.setPosition(in.readLong());
        this.setBufferPosition(in.readLong());
        this.setSpeed(in.readFloat());
        this.setActions(in.readLong());
        this.setErrorMessage(in.readString());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(getState());
        dest.writeLong(getPosition());
        dest.writeLong(getBufferPosition());
        dest.writeFloat(getSpeed());
        dest.writeLong(getActions());
        dest.writeString(getErrorMessage());
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
     * Set the current state of playback. One of the following:
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
    public void setState(int mState) {
        this.mState = mState;
    }

    /**
     * Get the current playback position in ms.
     */
    public long getPosition() {
        return mPosition;
    }

    /**
     * Set the current playback position in ms.
     */
    public void setPosition(long position) {
        mPosition = position;
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
     * Get the current playback speed as a multiple of normal playback. This
     * should be negative when rewinding. A value of 1 means normal playback and
     * 0 means paused.
     */
    public float getSpeed() {
        return mSpeed;
    }

    /**
     * Set the current playback speed as a multiple of normal playback. This
     * should be negative when rewinding. A value of 1 means normal playback and
     * 0 means paused.
     */
    public void setSpeed(float speed) {
        mSpeed = speed;
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
        return mCapabilities;
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
        mCapabilities = capabilities;
    }

    /**
     * Get a user readable error message. This should be set when the state is
     * {@link PlaybackState#PLAYSTATE_ERROR}.
     */
    public String getErrorMessage() {
        return mErrorMessage;
    }

    /**
     * Set a user readable error message. This should be set when the state is
     * {@link PlaybackState#PLAYSTATE_ERROR}.
     */
    public void setErrorMessage(String errorMessage) {
        mErrorMessage = errorMessage;
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
