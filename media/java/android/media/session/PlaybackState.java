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

import android.annotation.DrawableRes;
import android.annotation.Nullable;
import android.media.RemoteControlClient;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Playback state for a {@link MediaSession}. This includes a state like
 * {@link PlaybackState#STATE_PLAYING}, the current playback position,
 * and the current control capabilities.
 */
public final class PlaybackState implements Parcelable {
    private static final String TAG = "PlaybackState";

    /**
     * Indicates this session supports the stop command.
     *
     * @see Builder#setActions(long)
     */
    public static final long ACTION_STOP = 1 << 0;

    /**
     * Indicates this session supports the pause command.
     *
     * @see Builder#setActions(long)
     */
    public static final long ACTION_PAUSE = 1 << 1;

    /**
     * Indicates this session supports the play command.
     *
     * @see Builder#setActions(long)
     */
    public static final long ACTION_PLAY = 1 << 2;

    /**
     * Indicates this session supports the rewind command.
     *
     * @see Builder#setActions(long)
     */
    public static final long ACTION_REWIND = 1 << 3;

    /**
     * Indicates this session supports the previous command.
     *
     * @see Builder#setActions(long)
     */
    public static final long ACTION_SKIP_TO_PREVIOUS = 1 << 4;

    /**
     * Indicates this session supports the next command.
     *
     * @see Builder#setActions(long)
     */
    public static final long ACTION_SKIP_TO_NEXT = 1 << 5;

    /**
     * Indicates this session supports the fast forward command.
     *
     * @see Builder#setActions(long)
     */
    public static final long ACTION_FAST_FORWARD = 1 << 6;

    /**
     * Indicates this session supports the set rating command.
     *
     * @see Builder#setActions(long)
     */
    public static final long ACTION_SET_RATING = 1 << 7;

    /**
     * Indicates this session supports the seek to command.
     *
     * @see Builder#setActions(long)
     */
    public static final long ACTION_SEEK_TO = 1 << 8;

    /**
     * Indicates this session supports the play/pause toggle command.
     *
     * @see Builder#setActions(long)
     */
    public static final long ACTION_PLAY_PAUSE = 1 << 9;

    /**
     * Indicates this session supports the play from media id command.
     *
     * @see Builder#setActions(long)
     */
    public static final long ACTION_PLAY_FROM_MEDIA_ID = 1 << 10;

    /**
     * Indicates this session supports the play from search command.
     *
     * @see Builder#setActions(long)
     */
    public static final long ACTION_PLAY_FROM_SEARCH = 1 << 11;

    /**
     * Indicates this session supports the skip to queue item command.
     *
     * @see Builder#setActions(long)
     */
    public static final long ACTION_SKIP_TO_QUEUE_ITEM = 1 << 12;

    /**
     * This is the default playback state and indicates that no media has been
     * added yet, or the performer has been reset and has no content to play.
     *
     * @see Builder#setState(int, long, float)
     * @see Builder#setState(int, long, float, long)
     */
    public final static int STATE_NONE = 0;

    /**
     * State indicating this item is currently stopped.
     *
     * @see Builder#setState
     */
    public final static int STATE_STOPPED = 1;

    /**
     * State indicating this item is currently paused.
     *
     * @see Builder#setState
     */
    public final static int STATE_PAUSED = 2;

    /**
     * State indicating this item is currently playing.
     *
     * @see Builder#setState
     */
    public final static int STATE_PLAYING = 3;

    /**
     * State indicating this item is currently fast forwarding.
     *
     * @see Builder#setState
     */
    public final static int STATE_FAST_FORWARDING = 4;

    /**
     * State indicating this item is currently rewinding.
     *
     * @see Builder#setState
     */
    public final static int STATE_REWINDING = 5;

    /**
     * State indicating this item is currently buffering and will begin playing
     * when enough data has buffered.
     *
     * @see Builder#setState
     */
    public final static int STATE_BUFFERING = 6;

    /**
     * State indicating this item is currently in an error state. The error
     * message should also be set when entering this state.
     *
     * @see Builder#setState
     */
    public final static int STATE_ERROR = 7;

    /**
     * State indicating the class doing playback is currently connecting to a
     * new destination.  Depending on the implementation you may return to the previous
     * state when the connection finishes or enter {@link #STATE_NONE}.
     * If the connection failed {@link #STATE_ERROR} should be used.
     *
     * @see Builder#setState
     */
    public final static int STATE_CONNECTING = 8;

    /**
     * State indicating the player is currently skipping to the previous item.
     *
     * @see Builder#setState
     */
    public final static int STATE_SKIPPING_TO_PREVIOUS = 9;

    /**
     * State indicating the player is currently skipping to the next item.
     *
     * @see Builder#setState
     */
    public final static int STATE_SKIPPING_TO_NEXT = 10;

    /**
     * State indicating the player is currently skipping to a specific item in
     * the queue.
     *
     * @see Builder#setState
     */
    public final static int STATE_SKIPPING_TO_QUEUE_ITEM = 11;

    /**
     * Use this value for the position to indicate the position is not known.
     */
    public final static long PLAYBACK_POSITION_UNKNOWN = -1;

    private final int mState;
    private final long mPosition;
    private final long mBufferedPosition;
    private final float mSpeed;
    private final long mActions;
    private List<PlaybackState.CustomAction> mCustomActions;
    private final CharSequence mErrorMessage;
    private final long mUpdateTime;
    private final long mActiveItemId;
    private final Bundle mExtras;

    private PlaybackState(int state, long position, long updateTime, float speed,
            long bufferedPosition, long transportControls,
            List<PlaybackState.CustomAction> customActions, long activeItemId,
            CharSequence error, Bundle extras) {
        mState = state;
        mPosition = position;
        mSpeed = speed;
        mUpdateTime = updateTime;
        mBufferedPosition = bufferedPosition;
        mActions = transportControls;
        mCustomActions = new ArrayList<>(customActions);
        mActiveItemId = activeItemId;
        mErrorMessage = error;
        mExtras = extras;
    }

    private PlaybackState(Parcel in) {
        mState = in.readInt();
        mPosition = in.readLong();
        mSpeed = in.readFloat();
        mUpdateTime = in.readLong();
        mBufferedPosition = in.readLong();
        mActions = in.readLong();
        mCustomActions = in.createTypedArrayList(CustomAction.CREATOR);
        mActiveItemId = in.readLong();
        mErrorMessage = in.readCharSequence();
        mExtras = in.readBundle();
    }

    @Override
    public String toString() {
        StringBuilder bob = new StringBuilder("PlaybackState {");
        bob.append("state=").append(mState);
        bob.append(", position=").append(mPosition);
        bob.append(", buffered position=").append(mBufferedPosition);
        bob.append(", speed=").append(mSpeed);
        bob.append(", updated=").append(mUpdateTime);
        bob.append(", actions=").append(mActions);
        bob.append(", custom actions=").append(mCustomActions);
        bob.append(", active item id=").append(mActiveItemId);
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
        dest.writeFloat(mSpeed);
        dest.writeLong(mUpdateTime);
        dest.writeLong(mBufferedPosition);
        dest.writeLong(mActions);
        dest.writeTypedList(mCustomActions);
        dest.writeLong(mActiveItemId);
        dest.writeCharSequence(mErrorMessage);
        dest.writeBundle(mExtras);
    }

    /**
     * Get the current state of playback. One of the following:
     * <ul>
     * <li> {@link PlaybackState#STATE_NONE}</li>
     * <li> {@link PlaybackState#STATE_STOPPED}</li>
     * <li> {@link PlaybackState#STATE_PLAYING}</li>
     * <li> {@link PlaybackState#STATE_PAUSED}</li>
     * <li> {@link PlaybackState#STATE_FAST_FORWARDING}</li>
     * <li> {@link PlaybackState#STATE_REWINDING}</li>
     * <li> {@link PlaybackState#STATE_BUFFERING}</li>
     * <li> {@link PlaybackState#STATE_ERROR}</li>
     * </ul>
     */
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
     * Get the current actions available on this session. This should use a
     * bitmask of the available actions.
     * <ul>
     * <li> {@link PlaybackState#ACTION_SKIP_TO_PREVIOUS}</li>
     * <li> {@link PlaybackState#ACTION_REWIND}</li>
     * <li> {@link PlaybackState#ACTION_PLAY}</li>
     * <li> {@link PlaybackState#ACTION_PAUSE}</li>
     * <li> {@link PlaybackState#ACTION_STOP}</li>
     * <li> {@link PlaybackState#ACTION_FAST_FORWARD}</li>
     * <li> {@link PlaybackState#ACTION_SKIP_TO_NEXT}</li>
     * <li> {@link PlaybackState#ACTION_SEEK_TO}</li>
     * <li> {@link PlaybackState#ACTION_SET_RATING}</li>
     * </ul>
     */
    public long getActions() {
        return mActions;
    }

    /**
     * Get the list of custom actions.
     */
    public List<PlaybackState.CustomAction> getCustomActions() {
        return mCustomActions;
    }

    /**
     * Get a user readable error message. This should be set when the state is
     * {@link PlaybackState#STATE_ERROR}.
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
     * Get the id of the currently active item in the queue. If there is no
     * queue or a queue is not supported by the session this will be
     * {@link MediaSession.QueueItem#UNKNOWN_ID}.
     *
     * @return The id of the currently active item in the queue or
     *         {@link MediaSession.QueueItem#UNKNOWN_ID}.
     */
    public long getActiveQueueItemId() {
        return mActiveItemId;
    }

    /**
     * Get any custom extras that were set on this playback state.
     *
     * @return The extras for this state or null.
     */
    public @Nullable Bundle getExtras() {
        return mExtras;
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
                return STATE_BUFFERING;
            case RemoteControlClient.PLAYSTATE_ERROR:
                return STATE_ERROR;
            case RemoteControlClient.PLAYSTATE_FAST_FORWARDING:
                return STATE_FAST_FORWARDING;
            case RemoteControlClient.PLAYSTATE_NONE:
                return STATE_NONE;
            case RemoteControlClient.PLAYSTATE_PAUSED:
                return STATE_PAUSED;
            case RemoteControlClient.PLAYSTATE_PLAYING:
                return STATE_PLAYING;
            case RemoteControlClient.PLAYSTATE_REWINDING:
                return STATE_REWINDING;
            case RemoteControlClient.PLAYSTATE_SKIPPING_BACKWARDS:
                return STATE_SKIPPING_TO_PREVIOUS;
            case RemoteControlClient.PLAYSTATE_SKIPPING_FORWARDS:
                return STATE_SKIPPING_TO_NEXT;
            case RemoteControlClient.PLAYSTATE_STOPPED:
                return STATE_STOPPED;
            default:
                return -1;
        }
    }

    /**
     * Get the {@link RemoteControlClient} state for the given
     * {@link PlaybackState} state.
     *
     * @param state The state used by {@link PlaybackState}.
     * @return The equivalent state used by {@link RemoteControlClient}.
     * @hide
     */
    public static int getRccStateFromState(int state) {
        switch (state) {
            case STATE_BUFFERING:
                return RemoteControlClient.PLAYSTATE_BUFFERING;
            case STATE_ERROR:
                return RemoteControlClient.PLAYSTATE_ERROR;
            case STATE_FAST_FORWARDING:
                return RemoteControlClient.PLAYSTATE_FAST_FORWARDING;
            case STATE_NONE:
                return RemoteControlClient.PLAYSTATE_NONE;
            case STATE_PAUSED:
                return RemoteControlClient.PLAYSTATE_PAUSED;
            case STATE_PLAYING:
                return RemoteControlClient.PLAYSTATE_PLAYING;
            case STATE_REWINDING:
                return RemoteControlClient.PLAYSTATE_REWINDING;
            case STATE_SKIPPING_TO_PREVIOUS:
                return RemoteControlClient.PLAYSTATE_SKIPPING_BACKWARDS;
            case STATE_SKIPPING_TO_NEXT:
                return RemoteControlClient.PLAYSTATE_SKIPPING_FORWARDS;
            case STATE_STOPPED:
                return RemoteControlClient.PLAYSTATE_STOPPED;
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

    /**
     * @hide
     */
    public static int getRccControlFlagsFromActions(long actions) {
        int rccFlags = 0;
        long action = 1;
        while (action <= actions && action < Integer.MAX_VALUE) {
            if ((action & actions) != 0) {
                rccFlags |= getRccFlagForAction(action);
            }
            action = action << 1;
        }
        return rccFlags;
    }

    private static long getActionForRccFlag(int flag) {
        switch (flag) {
            case RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS:
                return ACTION_SKIP_TO_PREVIOUS;
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
                return ACTION_FAST_FORWARD;
            case RemoteControlClient.FLAG_KEY_MEDIA_NEXT:
                return ACTION_SKIP_TO_NEXT;
            case RemoteControlClient.FLAG_KEY_MEDIA_POSITION_UPDATE:
                return ACTION_SEEK_TO;
            case RemoteControlClient.FLAG_KEY_MEDIA_RATING:
                return ACTION_SET_RATING;
        }
        return 0;
    }

    private static int getRccFlagForAction(long action) {
        // We only care about the lower set of actions that can map to rcc
        // flags.
        int testAction = action < Integer.MAX_VALUE ? (int) action : 0;
        switch (testAction) {
            case (int) ACTION_SKIP_TO_PREVIOUS:
                return RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS;
            case (int) ACTION_REWIND:
                return RemoteControlClient.FLAG_KEY_MEDIA_REWIND;
            case (int) ACTION_PLAY:
                return RemoteControlClient.FLAG_KEY_MEDIA_PLAY;
            case (int) ACTION_PLAY_PAUSE:
                return RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE;
            case (int) ACTION_PAUSE:
                return RemoteControlClient.FLAG_KEY_MEDIA_PAUSE;
            case (int) ACTION_STOP:
                return RemoteControlClient.FLAG_KEY_MEDIA_STOP;
            case (int) ACTION_FAST_FORWARD:
                return RemoteControlClient.FLAG_KEY_MEDIA_FAST_FORWARD;
            case (int) ACTION_SKIP_TO_NEXT:
                return RemoteControlClient.FLAG_KEY_MEDIA_NEXT;
            case (int) ACTION_SEEK_TO:
                return RemoteControlClient.FLAG_KEY_MEDIA_POSITION_UPDATE;
            case (int) ACTION_SET_RATING:
                return RemoteControlClient.FLAG_KEY_MEDIA_RATING;
        }
        return 0;
    }

    public static final Parcelable.Creator<PlaybackState> CREATOR =
            new Parcelable.Creator<PlaybackState>() {
        @Override
        public PlaybackState createFromParcel(Parcel in) {
            return new PlaybackState(in);
        }

        @Override
        public PlaybackState[] newArray(int size) {
            return new PlaybackState[size];
        }
    };

    /**
     * {@link PlaybackState.CustomAction CustomActions} can be used to extend the capabilities of
     * the standard transport controls by exposing app specific actions to
     * {@link MediaController MediaControllers}.
     */
    public static final class CustomAction implements Parcelable {
        private final String mAction;
        private final CharSequence mName;
        private final int mIcon;
        private final Bundle mExtras;

        /**
         * Use {@link PlaybackState.CustomAction.Builder#build()}.
         */
        private CustomAction(String action, CharSequence name, int icon, Bundle extras) {
            mAction = action;
            mName = name;
            mIcon = icon;
            mExtras = extras;
        }

        private CustomAction(Parcel in) {
            mAction = in.readString();
            mName = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
            mIcon = in.readInt();
            mExtras = in.readBundle();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(mAction);
            TextUtils.writeToParcel(mName, dest, flags);
            dest.writeInt(mIcon);
            dest.writeBundle(mExtras);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Parcelable.Creator<PlaybackState.CustomAction> CREATOR
                = new Parcelable.Creator<PlaybackState.CustomAction>() {

            @Override
            public PlaybackState.CustomAction createFromParcel(Parcel p) {
                return new PlaybackState.CustomAction(p);
            }

            @Override
            public PlaybackState.CustomAction[] newArray(int size) {
                return new PlaybackState.CustomAction[size];
            }
        };

        /**
         * Returns the action of the {@link CustomAction}.
         *
         * @return The action of the {@link CustomAction}.
         */
        public String getAction() {
            return mAction;
        }

        /**
         * Returns the display name of this action. e.g. "Favorite"
         *
         * @return The display name of this {@link CustomAction}.
         */
        public CharSequence getName() {
            return mName;
        }

        /**
         * Returns the resource id of the icon in the {@link MediaSession MediaSession's} package.
         *
         * @return The resource id of the icon in the {@link MediaSession MediaSession's} package.
         */
        public int getIcon() {
            return mIcon;
        }

        /**
         * Returns extras which provide additional application-specific information about the
         * action, or null if none. These arguments are meant to be consumed by a
         * {@link MediaController} if it knows how to handle them.
         *
         * @return Optional arguments for the {@link CustomAction}.
         */
        public Bundle getExtras() {
            return mExtras;
        }

        @Override
        public String toString() {
            return "Action:" +
                    "mName='" + mName +
                    ", mIcon=" + mIcon +
                    ", mExtras=" + mExtras;
        }

        /**
         * Builder for {@link CustomAction} objects.
         */
        public static final class Builder {
            private final String mAction;
            private final CharSequence mName;
            private final int mIcon;
            private Bundle mExtras;

            /**
             * Creates a {@link CustomAction} builder with the id, name, and icon set.
             *
             * @param action The action of the {@link CustomAction}.
             * @param name The display name of the {@link CustomAction}. This name will be displayed
             *             along side the action if the UI supports it.
             * @param icon The icon resource id of the {@link CustomAction}. This resource id
             *             must be in the same package as the {@link MediaSession}. It will be
             *             displayed with the custom action if the UI supports it.
             */
            public Builder(String action, CharSequence name, @DrawableRes int icon) {
                if (TextUtils.isEmpty(action)) {
                    throw new IllegalArgumentException(
                            "You must specify an action to build a CustomAction.");
                }
                if (TextUtils.isEmpty(name)) {
                    throw new IllegalArgumentException(
                            "You must specify a name to build a CustomAction.");
                }
                if (icon == 0) {
                    throw new IllegalArgumentException(
                            "You must specify an icon resource id to build a CustomAction.");
                }
                mAction = action;
                mName = name;
                mIcon = icon;
            }

            /**
             * Set optional extras for the {@link CustomAction}. These extras are meant to be
             * consumed by a {@link MediaController} if it knows how to handle them.
             * Keys should be fully qualified (e.g. "com.example.MY_ARG") to avoid collisions.
             *
             * @param extras Optional extras for the {@link CustomAction}.
             * @return this.
             */
            public Builder setExtras(Bundle extras) {
                mExtras = extras;
                return this;
            }

            /**
             * Build and return the {@link CustomAction} instance with the specified values.
             *
             * @return A new {@link CustomAction} instance.
             */
            public CustomAction build() {
                return new CustomAction(mAction, mName, mIcon, mExtras);
            }
        }
    }

    /**
     * Builder for {@link PlaybackState} objects.
     */
    public static final class Builder {
        private final List<PlaybackState.CustomAction> mCustomActions = new ArrayList<>();

        private int mState;
        private long mPosition;
        private long mBufferedPosition;
        private float mSpeed;
        private long mActions;
        private CharSequence mErrorMessage;
        private long mUpdateTime;
        private long mActiveItemId = MediaSession.QueueItem.UNKNOWN_ID;
        private Bundle mExtras;

        /**
         * Creates an initially empty state builder.
         */
        public Builder() {
        }

        /**
         * Creates a builder with the same initial values as those in the from
         * state.
         *
         * @param from The state to use for initializing the builder.
         */
        public Builder(PlaybackState from) {
            if (from == null) {
                return;
            }
            mState = from.mState;
            mPosition = from.mPosition;
            mBufferedPosition = from.mBufferedPosition;
            mSpeed = from.mSpeed;
            mActions = from.mActions;
            if (from.mCustomActions != null) {
                mCustomActions.addAll(from.mCustomActions);
            }
            mErrorMessage = from.mErrorMessage;
            mUpdateTime = from.mUpdateTime;
            mActiveItemId = from.mActiveItemId;
            mExtras = from.mExtras;
        }

        /**
         * Set the current state of playback.
         * <p>
         * The position must be in ms and indicates the current playback
         * position within the item. If the position is unknown use
         * {@link #PLAYBACK_POSITION_UNKNOWN}. When not using an unknown
         * position the time at which the position was updated must be provided.
         * It is okay to use {@link SystemClock#elapsedRealtime()} if the
         * current position was just retrieved.
         * <p>
         * The speed is a multiple of normal playback and should be 0 when
         * paused and negative when rewinding. Normal playback speed is 1.0.
         * <p>
         * The state must be one of the following:
         * <ul>
         * <li> {@link PlaybackState#STATE_NONE}</li>
         * <li> {@link PlaybackState#STATE_STOPPED}</li>
         * <li> {@link PlaybackState#STATE_PLAYING}</li>
         * <li> {@link PlaybackState#STATE_PAUSED}</li>
         * <li> {@link PlaybackState#STATE_FAST_FORWARDING}</li>
         * <li> {@link PlaybackState#STATE_REWINDING}</li>
         * <li> {@link PlaybackState#STATE_BUFFERING}</li>
         * <li> {@link PlaybackState#STATE_ERROR}</li>
         * </ul>
         *
         * @param state The current state of playback.
         * @param position The position in the current item in ms.
         * @param playbackSpeed The current speed of playback as a multiple of
         *            normal playback.
         * @param updateTime The time in the {@link SystemClock#elapsedRealtime}
         *            timebase that the position was updated at.
         * @return this
         */
        public Builder setState(int state, long position, float playbackSpeed, long updateTime) {
            mState = state;
            mPosition = position;
            mUpdateTime = updateTime;
            mSpeed = playbackSpeed;
            return this;
        }

        /**
         * Set the current state of playback.
         * <p>
         * The position must be in ms and indicates the current playback
         * position within the item. If the position is unknown use
         * {@link #PLAYBACK_POSITION_UNKNOWN}. The update time will be set to
         * the current {@link SystemClock#elapsedRealtime()}.
         * <p>
         * The speed is a multiple of normal playback and should be 0 when
         * paused and negative when rewinding. Normal playback speed is 1.0.
         * <p>
         * The state must be one of the following:
         * <ul>
         * <li> {@link PlaybackState#STATE_NONE}</li>
         * <li> {@link PlaybackState#STATE_STOPPED}</li>
         * <li> {@link PlaybackState#STATE_PLAYING}</li>
         * <li> {@link PlaybackState#STATE_PAUSED}</li>
         * <li> {@link PlaybackState#STATE_FAST_FORWARDING}</li>
         * <li> {@link PlaybackState#STATE_REWINDING}</li>
         * <li> {@link PlaybackState#STATE_BUFFERING}</li>
         * <li> {@link PlaybackState#STATE_ERROR}</li>
         * </ul>
         *
         * @param state The current state of playback.
         * @param position The position in the current item in ms.
         * @param playbackSpeed The current speed of playback as a multiple of
         *            normal playback.
         * @return this
         */
        public Builder setState(int state, long position, float playbackSpeed) {
            return setState(state, position, playbackSpeed, SystemClock.elapsedRealtime());
        }

        /**
         * Set the current actions available on this session. This should use a
         * bitmask of possible actions.
         * <ul>
         * <li> {@link PlaybackState#ACTION_SKIP_TO_PREVIOUS}</li>
         * <li> {@link PlaybackState#ACTION_REWIND}</li>
         * <li> {@link PlaybackState#ACTION_PLAY}</li>
         * <li> {@link PlaybackState#ACTION_PAUSE}</li>
         * <li> {@link PlaybackState#ACTION_STOP}</li>
         * <li> {@link PlaybackState#ACTION_FAST_FORWARD}</li>
         * <li> {@link PlaybackState#ACTION_SKIP_TO_NEXT}</li>
         * <li> {@link PlaybackState#ACTION_SEEK_TO}</li>
         * <li> {@link PlaybackState#ACTION_SET_RATING}</li>
         * </ul>
         *
         * @param actions The set of actions allowed.
         * @return this
         */
        public Builder setActions(long actions) {
            mActions = actions;
            return this;
        }

        /**
         * Add a custom action to the playback state. Actions can be used to
         * expose additional functionality to {@link MediaController
         * MediaControllers} beyond what is offered by the standard transport
         * controls.
         * <p>
         * e.g. start a radio station based on the current item or skip ahead by
         * 30 seconds.
         *
         * @param action An identifier for this action. It can be sent back to
         *            the {@link MediaSession} through
         *            {@link MediaController.TransportControls#sendCustomAction(String, Bundle)}.
         * @param name The display name for the action. If text is shown with
         *            the action or used for accessibility, this is what should
         *            be used.
         * @param icon The resource action of the icon that should be displayed
         *            for the action. The resource should be in the package of
         *            the {@link MediaSession}.
         * @return this
         */
        public Builder addCustomAction(String action, String name, int icon) {
            return addCustomAction(new PlaybackState.CustomAction(action, name, icon, null));
        }

        /**
         * Add a custom action to the playback state. Actions can be used to expose additional
         * functionality to {@link MediaController MediaControllers} beyond what is offered by the
         * standard transport controls.
         * <p>
         * An example of an action would be to start a radio station based on the current item
         * or to skip ahead by 30 seconds.
         *
         * @param customAction The custom action to add to the {@link PlaybackState}.
         * @return this
         */
        public Builder addCustomAction(PlaybackState.CustomAction customAction) {
            if (customAction == null) {
                throw new IllegalArgumentException(
                        "You may not add a null CustomAction to PlaybackState.");
            }
            mCustomActions.add(customAction);
            return this;
        }

        /**
         * Set the current buffered position in ms. This is the farthest
         * playback point that can be reached from the current position using
         * only buffered content.
         *
         * @param bufferedPosition The position in ms that playback is buffered
         *            to.
         * @return this
         */
        public Builder setBufferedPosition(long bufferedPosition) {
            mBufferedPosition = bufferedPosition;
            return this;
        }

        /**
         * Set the active item in the play queue by specifying its id. The
         * default value is {@link MediaSession.QueueItem#UNKNOWN_ID}
         *
         * @param id The id of the active item.
         * @return this
         */
        public Builder setActiveQueueItemId(long id) {
            mActiveItemId = id;
            return this;
        }

        /**
         * Set a user readable error message. This should be set when the state
         * is {@link PlaybackState#STATE_ERROR}.
         *
         * @param error The error message for display to the user.
         * @return this
         */
        public Builder setErrorMessage(CharSequence error) {
            mErrorMessage = error;
            return this;
        }

        /**
         * Set any custom extras to be included with the playback state.
         *
         * @param extras The extras to include.
         * @return this
         */
        public Builder setExtras(Bundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * Build and return the {@link PlaybackState} instance with these
         * values.
         *
         * @return A new state instance.
         */
        public PlaybackState build() {
            return new PlaybackState(mState, mPosition, mUpdateTime, mSpeed, mBufferedPosition,
                    mActions, mCustomActions, mActiveItemId, mErrorMessage, mExtras);
        }
    }
}
