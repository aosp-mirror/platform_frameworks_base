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
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.ArrayMap;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Objects;

/**
 * Define a command that a {@link MediaController2} can send to a {@link MediaSession2}.
 * <p>
 * If {@link #getCommandCode()} isn't {@link #COMMAND_CODE_CUSTOM}), it's predefined command.
 * If {@link #getCommandCode()} is {@link #COMMAND_CODE_CUSTOM}), it's custom command and
 * {@link #getCustomCommand()} shouldn't be {@code null}.
 * <p>
 * This API is not generally intended for third party application developers.
 * Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
 * <a href="{@docRoot}reference/androidx/media2/package-summary.html">Media2 Library</a>
 * for consistent behavior across all devices.
 * </p>
 * @hide
 */
public final class Session2Command implements Parcelable {
    /**
     * The first version of session commands. This version is for commands introduced in API 29.
     * <p>
     * This would be used to specify which commands should be added by
     * {@link Session2CommandGroup.Builder#addAllPredefinedCommands(int)}
     *
     * @see Session2CommandGroup.Builder#addAllPredefinedCommands(int)
     */
    public static final int COMMAND_VERSION_1 = 1;

    /**
     * @hide
     */
    public static final int COMMAND_VERSION_CURRENT = COMMAND_VERSION_1;

    /**
     * @hide
     */
    @IntDef({COMMAND_VERSION_1})
    @Retention(RetentionPolicy.SOURCE)
    public @interface CommandVersion {}

    /**
     * @hide
     */
    @IntDef({COMMAND_CODE_CUSTOM,
            COMMAND_CODE_PLAYER_PLAY,
            COMMAND_CODE_PLAYER_PAUSE,
            COMMAND_CODE_PLAYER_PREPARE,
            COMMAND_CODE_PLAYER_SEEK_TO,
            COMMAND_CODE_PLAYER_SET_SPEED,
            COMMAND_CODE_PLAYER_GET_PLAYLIST,
            COMMAND_CODE_PLAYER_SET_PLAYLIST,
            COMMAND_CODE_PLAYER_SKIP_TO_PLAYLIST_ITEM,
            COMMAND_CODE_PLAYER_SKIP_TO_PREVIOUS_PLAYLIST_ITEM,
            COMMAND_CODE_PLAYER_SKIP_TO_NEXT_PLAYLIST_ITEM,
            COMMAND_CODE_PLAYER_SET_SHUFFLE_MODE,
            COMMAND_CODE_PLAYER_SET_REPEAT_MODE,
            COMMAND_CODE_PLAYER_GET_PLAYLIST_METADATA,
            COMMAND_CODE_PLAYER_ADD_PLAYLIST_ITEM,
            COMMAND_CODE_PLAYER_REMOVE_PLAYLIST_ITEM,
            COMMAND_CODE_PLAYER_REPLACE_PLAYLIST_ITEM,
            COMMAND_CODE_PLAYER_GET_CURRENT_MEDIA_ITEM,
            COMMAND_CODE_PLAYER_UPDATE_LIST_METADATA,
            COMMAND_CODE_PLAYER_SET_MEDIA_ITEM,
            COMMAND_CODE_VOLUME_SET_VOLUME,
            COMMAND_CODE_VOLUME_ADJUST_VOLUME,
            COMMAND_CODE_SESSION_FAST_FORWARD,
            COMMAND_CODE_SESSION_REWIND,
            COMMAND_CODE_SESSION_SKIP_FORWARD,
            COMMAND_CODE_SESSION_SKIP_BACKWARD,
            COMMAND_CODE_SESSION_SET_RATING,
            COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT,
            COMMAND_CODE_LIBRARY_SUBSCRIBE,
            COMMAND_CODE_LIBRARY_UNSUBSCRIBE,
            COMMAND_CODE_LIBRARY_GET_CHILDREN,
            COMMAND_CODE_LIBRARY_GET_ITEM,
            COMMAND_CODE_LIBRARY_SEARCH,
            COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CommandCode {}

    /**
     * Command code for the custom command which can be defined by string action in the
     * {@link Session2Command}.
     */
    public static final int COMMAND_CODE_CUSTOM = 0;

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Player commands (i.e. commands to {@link Session2Player})
    ////////////////////////////////////////////////////////////////////////////////////////////////
    static final ArrayMap<Integer, Range> VERSION_PLAYER_COMMANDS_MAP = new ArrayMap<>();
    static final ArrayMap<Integer, Range> VERSION_PLAYER_PLAYLIST_COMMANDS_MAP = new ArrayMap<>();

    // TODO: check the link tag, and reassign int values properly.
    /**
     * Command code for {@link MediaController2#play()}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link Session2Callback#onCommandRequest(MediaSession2, Controller2Info,
     * Session2Command)}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_PLAYER_PLAY = 10000;

    /**
     * Command code for {@link MediaController2#pause()}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link Session2Callback#onCommandRequest(MediaSession2, Controller2Info,
     * Session2Command)}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_PLAYER_PAUSE = 10001;

    /**
     * Command code for {@link MediaController2#prepare()}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link Session2Callback#onCommandRequest(MediaSession2, Controller2Info,
     * Session2Command)}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_PLAYER_PREPARE = 10002;

    /**
     * Command code for {@link MediaController2#seekTo(long)}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link Session2Callback#onCommandRequest(MediaSession2, Controller2Info,
     * Session2Command)}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_PLAYER_SEEK_TO = 10003;

    /**
     * Command code for {@link MediaController2#setPlaybackSpeed(float)}}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link Session22Callback#onCommandRequest(MediaSession2, Controller2Info,
     * Session2Command)}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_PLAYER_SET_SPEED = 10004;

    /**
     * Command code for {@link MediaController2#getPlaylist()}. This will expose metadata
     * information to the controller.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_PLAYER_GET_PLAYLIST = 10005;

    /**
     * Command code for {@link MediaController2#setPlaylist(List, MediaMetadata)}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the
     * {@link Session2Callback#onCommandRequest(MediaSession2, Controller2Info, Session2Command)}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_PLAYER_SET_PLAYLIST = 10006;

    /**
     * Command code for {@link MediaController2#skipToPlaylistItem(int)}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the
     * {@link Session2Callback#onCommandRequest(MediaSession2, Controller2Info, Session2Command)}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_PLAYER_SKIP_TO_PLAYLIST_ITEM = 10007;

    /**
     * Command code for {@link MediaController2#skipToPreviousPlaylistItem()}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link Session2Callback#onCommandRequest(
     * MediaSession2, Controller2Info, Session2Command)}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_PLAYER_SKIP_TO_PREVIOUS_PLAYLIST_ITEM = 10008;

    /**
     * Command code for {@link MediaController2#skipToNextPlaylistItem()}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link Session2Callback#onCommandRequest(
     * MediaSession2, Controller2Info, Session2Command)}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */

    public static final int COMMAND_CODE_PLAYER_SKIP_TO_NEXT_PLAYLIST_ITEM = 10009;

    /**
     * Command code for {@link MediaController2#setShuffleMode(int)}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the
     * {@link Session2Callback#onCommandRequest(MediaSession2, Controller2Info, Session2Command)}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_PLAYER_SET_SHUFFLE_MODE = 10010;

    /**
     * Command code for {@link MediaController2#setRepeatMode(int)}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the
     * {@link Session2Callback#onCommandRequest(MediaSession2, Controller2Info, Session2Command)}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_PLAYER_SET_REPEAT_MODE = 10011;

    /**
     * Command code for {@link MediaController2#getPlaylistMetadata()}. This will expose metadata
     * information to the controller.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_PLAYER_GET_PLAYLIST_METADATA = 10012;

    /**
     * Command code for {@link MediaController2#addPlaylistItem(int, String)}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the
     * {@link Session2Callback#onCommandRequest(MediaSession2, Controller2Info, Session2Command)}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_PLAYER_ADD_PLAYLIST_ITEM = 10013;

    /**
     * Command code for {@link MediaController2#removePlaylistItem(int, String)}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the
     * {@link Session2Callback#onCommandRequest(MediaSession2, Controller2Info, Session2Command)}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_PLAYER_REMOVE_PLAYLIST_ITEM = 10014;

    /**
     * Command code for {@link MediaController2#replacePlaylistItem(int, String)}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the
     * {@link Session2Callback#onCommandRequest(MediaSession2, Controller2Info, Session2Command)}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_PLAYER_REPLACE_PLAYLIST_ITEM = 10015;

    /**
     * Command code for {@link MediaController2#getCurrentMediaItem()}. This will expose metadata
     * information to the controller.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_PLAYER_GET_CURRENT_MEDIA_ITEM = 10016;

    /**
     * Command code for {@link MediaController2#updatePlaylistMetadata(MediaMetadata)}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the
     * {@link Session2Callback#onCommandRequest(MediaSession2, Controller2Info, Session2Command)}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_PLAYER_UPDATE_LIST_METADATA = 10017;

    /**
     * Command code for {@link MediaController2#setMediaItem(String)}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the
     * {@link Session2Callback#onCommandRequest(MediaSession2, Controller2Info, Session2Command)}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_PLAYER_SET_MEDIA_ITEM = 10018;

    static {
        VERSION_PLAYER_COMMANDS_MAP.put(COMMAND_VERSION_1,
                new Range(COMMAND_CODE_PLAYER_PLAY, COMMAND_CODE_PLAYER_SET_MEDIA_ITEM));
    }

    static {
        VERSION_PLAYER_PLAYLIST_COMMANDS_MAP.put(COMMAND_VERSION_1,
                new Range(COMMAND_CODE_PLAYER_GET_PLAYLIST,
                        COMMAND_CODE_PLAYER_SET_MEDIA_ITEM));
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Volume commands (i.e. commands to {@link AudioManager} or {@link RouteMediaPlayer})
    ////////////////////////////////////////////////////////////////////////////////////////////////
    static final ArrayMap<Integer, Range> VERSION_VOLUME_COMMANDS_MAP = new ArrayMap<>();

    /**
     * Command code for {@link MediaController2#setVolumeTo(int, int)}.
     * <p>
     * <p>
     * If the session doesn't reject the request through the
     * {@link Session2Callback#onCommandRequest(MediaSession2, Controller2Info, Session2Command)},
     * command would adjust the device volume. It would send to the player directly only if it's
     * remote player. See RouteMediaPlayer for a remote player.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_VOLUME_SET_VOLUME = 30000;

    /**
     * Command code for {@link MediaController2#adjustVolume(int, int)}.
     * <p>
     * If the session doesn't reject the request through the
     * {@link Session2Callback#onCommandRequest(MediaSession2, Controller2Info, Session2Command)},
     * command would adjust the device volume. It would send to the player directly only if it's
     * remote player. See RouteMediaPlayer for a remote player.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_VOLUME_ADJUST_VOLUME = 30001;

    static {
        VERSION_VOLUME_COMMANDS_MAP.put(COMMAND_VERSION_1,
                new Range(COMMAND_CODE_VOLUME_SET_VOLUME,
                        COMMAND_CODE_VOLUME_ADJUST_VOLUME));
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Session commands (i.e. commands to {@link MediaSession2#Session2Callback})
    ////////////////////////////////////////////////////////////////////////////////////////////////
    static final ArrayMap<Integer, Range> VERSION_SESSION_COMMANDS_MAP = new ArrayMap<>();

    /**
     * Command code for {@link MediaController2#fastForward()}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_SESSION_FAST_FORWARD = 40000;

    /**
     * Command code for {@link MediaController2#rewind()}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_SESSION_REWIND = 40001;

    /**
     * Command code for {@link MediaController2#skipForward()}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_SESSION_SKIP_FORWARD = 40002;

    /**
     * Command code for {@link MediaController2#skipBackward()}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_SESSION_SKIP_BACKWARD = 40003;

    /**
     * Command code for {@link MediaController2#setRating(String, Rating)}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_SESSION_SET_RATING = 40010;

    public static final Parcelable.Creator<Session2Command> CREATOR =
            new Parcelable.Creator<Session2Command>() {
                @Override
                public Session2Command createFromParcel(Parcel in) {
                    return new Session2Command(in);
                }

                @Override
                public Session2Command[] newArray(int size) {
                    return new Session2Command[size];
                }
            };

    static {
        VERSION_SESSION_COMMANDS_MAP.put(COMMAND_VERSION_1,
                new Range(COMMAND_CODE_SESSION_FAST_FORWARD, COMMAND_CODE_SESSION_SET_RATING));
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Session commands (i.e. commands to {@link MediaLibrarySession#MediaLibrarySessionCallback})
    ////////////////////////////////////////////////////////////////////////////////////////////////
    static final ArrayMap<Integer, Range> VERSION_LIBRARY_COMMANDS_MAP = new ArrayMap<>();

    /**
     * Command code for {@link MediaBrowser2#getLibraryRoot(Library2Params)}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT = 50000;

    /**
     * Command code for {@link MediaBrowser2#subscribe(String, Library2Params)}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_LIBRARY_SUBSCRIBE = 50001;

    /**
     * Command code for {@link MediaBrowser2#unsubscribe(String)}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_LIBRARY_UNSUBSCRIBE = 50002;

    /**
     * Command code for {@link MediaBrowser2#getChildren(String, int, int, Library2Params)}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_LIBRARY_GET_CHILDREN = 50003;

    /**
     * Command code for {@link MediaBrowser2#getItem(String)}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_LIBRARY_GET_ITEM = 50004;

    /**
     * Command code for {@link MediaBrowser2#search(String, LibraryParams)}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_LIBRARY_SEARCH = 50005;

    /**
     * Command code for {@link MediaBrowser2#getSearchResult(String, int, int, Library2Params)}.
     * <p>
     * Code version is {@link #COMMAND_VERSION_1}.
     */
    public static final int COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT = 50006;

    static {
        VERSION_LIBRARY_COMMANDS_MAP.put(COMMAND_VERSION_1,
                new Range(COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT,
                        COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT));
    }

    @CommandCode private final int mCommandCode;
    // Nonnull if it's custom command
    private final String mCustomCommand;
    private final Bundle mExtras;

    /**
     * Constructor for creating a predefined command.
     *
     * @param commandCode A command code for predefined command.
     */
    public Session2Command(@CommandCode int commandCode) {
        if (commandCode == COMMAND_CODE_CUSTOM) {
            throw new IllegalArgumentException("commandCode shouldn't be COMMAND_CODE_CUSTOM");
        }
        mCommandCode = commandCode;
        mCustomCommand = null;
        mExtras = null;
    }

    /**
     * Constructor for creating a custom command.
     *
     * @param action The action of this custom command.
     * @param extras An extra bundle for this custom command.
     */
    public Session2Command(@NonNull String action, @Nullable Bundle extras) {
        if (action == null) {
            throw new IllegalArgumentException("action shouldn't be null");
        }
        mCommandCode = COMMAND_CODE_CUSTOM;
        mCustomCommand = action;
        mExtras = extras;
    }

    /**
     * Used by parcelable creator.
     */
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    Session2Command(Parcel in) {
        mCommandCode = in.readInt();
        mCustomCommand = in.readString();
        mExtras = in.readBundle();
    }

    /**
     * Gets the command code of a predefined command.
     * This will return {@link #COMMAND_CODE_CUSTOM} for a custom command.
     */
    public @CommandCode int getCommandCode() {
        return mCommandCode;
    }

    /**
     * Gets the action of a custom command.
     * This will return {@code null} for a predefined command.
     */
    public @Nullable String getCustomCommand() {
        return mCustomCommand;
    }

    /**
     * Gets the extra bundle of a custom command.
     * This will return {@code null} for a predefined command.
     */
    public @Nullable Bundle getExtras() {
        return mExtras;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mCommandCode);
        dest.writeString(mCustomCommand);
        dest.writeBundle(mExtras);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Session2Command)) {
            return false;
        }
        Session2Command other = (Session2Command) obj;
        return mCommandCode == other.mCommandCode
                && TextUtils.equals(mCustomCommand, other.mCustomCommand);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mCustomCommand, mCommandCode);
    }

    @SuppressWarnings("WeakerAccess") /* synthetic access */
    static final class Range {
        public final int lower;
        public final int upper;

        Range(int lower, int upper) {
            this.lower = lower;
            this.upper = upper;
        }
    }
}

