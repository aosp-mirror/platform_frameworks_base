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
import android.content.Context;
import android.media.update.ApiLoader;
import android.media.update.MediaSession2Provider;
import android.media.MediaSession2.ControllerInfo;
import android.media.MediaSession2.SessionCallback;
import android.net.Uri;
import android.os.Bundle;

import java.util.List;

/**
 * @hide
 * Define a command that a {@link MediaController2} can send to a {@link MediaSession2}.
 * <p>
 * If {@link #getCommandCode()} isn't {@link #COMMAND_CODE_CUSTOM}), it's predefined command.
 * If {@link #getCommandCode()} is {@link #COMMAND_CODE_CUSTOM}), it's custom command and
 * {@link #getCustomCommand()} shouldn't be {@code null}.
 */
public final class SessionCommand2 {
    /**
     * Command code for the custom command which can be defined by string action in the
     * {@link SessionCommand2}.
     */
    public static final int COMMAND_CODE_CUSTOM = 0;

    /**
     * Command code for {@link MediaController2#play()}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo,
     * SessionCommand2)}.
     */
    public static final int COMMAND_CODE_PLAYBACK_PLAY = 1;

    /**
     * Command code for {@link MediaController2#pause()}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo,
     * SessionCommand2)}.
     */
    public static final int COMMAND_CODE_PLAYBACK_PAUSE = 2;

    /**
     * Command code for {@link MediaController2#stop()}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo,
     * SessionCommand2)}.
     */
    public static final int COMMAND_CODE_PLAYBACK_STOP = 3;

    /**
     * Command code for {@link MediaController2#skipToNextItem()}.
     * <p>
     * Command would be sent directly to the playlist agent if the session doesn't reject the
     * request through the {@link SessionCallback#onCommandRequest(
     * MediaSession2, ControllerInfo, SessionCommand2)}.
     */
    public static final int COMMAND_CODE_PLAYLIST_SKIP_NEXT_ITEM = 4;

    /**
     * Command code for {@link MediaController2#skipToPreviousItem()}.
     * <p>
     * Command would be sent directly to the playlist agent if the session doesn't reject the
     * request through the {@link SessionCallback#onCommandRequest(
     * MediaSession2, ControllerInfo, SessionCommand2)}.
     */
    public static final int COMMAND_CODE_PLAYLIST_SKIP_PREV_ITEM = 5;

    /**
     * Command code for {@link MediaController2#prepare()}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo,
     * SessionCommand2)}.
     */
    public static final int COMMAND_CODE_PLAYBACK_PREPARE = 6;

    /**
     * Command code for {@link MediaController2#fastForward()}.
     */
    public static final int COMMAND_CODE_SESSION_FAST_FORWARD = 7;

    /**
     * Command code for {@link MediaController2#rewind()}.
     */
    public static final int COMMAND_CODE_SESSION_REWIND = 8;

    /**
     * Command code for {@link MediaController2#seekTo(long)}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo,
     * SessionCommand2)}.
     */
    public static final int COMMAND_CODE_PLAYBACK_SEEK_TO = 9;

    /**
     * Command code for both {@link MediaController2#setVolumeTo(int, int)}.
     * <p>
     * Command would set the device volume or send to the volume provider directly if the session
     * doesn't reject the request through the
     * {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, SessionCommand2)}.
     */
    public static final int COMMAND_CODE_SET_VOLUME = 10;

    /**
     * Command code for both {@link MediaController2#adjustVolume(int, int)}.
     * <p>
     * Command would adjust the device volume or send to the volume provider directly if the session
     * doesn't reject the request through the
     * {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, SessionCommand2)}.
     */
    public static final int COMMAND_CODE_ADJUST_VOLUME = 11;

    /**
     * Command code for {@link MediaController2#skipToPlaylistItem(MediaItem2)}.
     * <p>
     * Command would be sent directly to the playlist agent if the session doesn't reject the
     * request through the
     * {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, SessionCommand2)}.
     */
    public static final int COMMAND_CODE_PLAYLIST_SKIP_TO_PLAYLIST_ITEM = 12;

    /**
     * Command code for {@link MediaController2#setShuffleMode(int)}.
     * <p>
     * Command would be sent directly to the playlist agent if the session doesn't reject the
     * request through the
     * {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, SessionCommand2)}.
     */
    public static final int COMMAND_CODE_PLAYLIST_SET_SHUFFLE_MODE = 13;

    /**
     * Command code for {@link MediaController2#setRepeatMode(int)}.
     * <p>
     * Command would be sent directly to the playlist agent if the session doesn't reject the
     * request through the
     * {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, SessionCommand2)}.
     */
    public static final int COMMAND_CODE_PLAYLIST_SET_REPEAT_MODE = 14;

    /**
     * Command code for {@link MediaController2#addPlaylistItem(int, MediaItem2)}.
     * <p>
     * Command would be sent directly to the playlist agent if the session doesn't reject the
     * request through the
     * {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, SessionCommand2)}.
     */
    public static final int COMMAND_CODE_PLAYLIST_ADD_ITEM = 15;

    /**
     * Command code for {@link MediaController2#addPlaylistItem(int, MediaItem2)}.
     * <p>
     * Command would be sent directly to the playlist agent if the session doesn't reject the
     * request through the
     * {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, SessionCommand2)}.
     */
    public static final int COMMAND_CODE_PLAYLIST_REMOVE_ITEM = 16;

    /**
     * Command code for {@link MediaController2#replacePlaylistItem(int, MediaItem2)}.
     * <p>
     * Command would be sent directly to the playlist agent if the session doesn't reject the
     * request through the
     * {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, SessionCommand2)}.
     */
    public static final int COMMAND_CODE_PLAYLIST_REPLACE_ITEM = 17;

    /**
     * Command code for {@link MediaController2#getPlaylist()}. This will expose metadata
     * information to the controller.
     * <p>
     * Command would be sent directly to the playlist agent if the session doesn't reject the
     * request through the
     * {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, SessionCommand2)}.
     */
    public static final int COMMAND_CODE_PLAYLIST_GET_LIST = 18;

    /**
     * Command code for {@link MediaController2#setPlaylist(List, MediaMetadata2)}.
     * <p>
     * Command would be sent directly to the playlist agent if the session doesn't reject the
     * request through the
     * {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, SessionCommand2)}.
     */
    public static final int COMMAND_CODE_PLAYLIST_SET_LIST = 19;

    /**
     * Command code for {@link MediaController2#getPlaylistMetadata()}. This will expose
     * metadata information to the controller.
     * <p>
     * Command would be sent directly to the playlist agent if the session doesn't reject the
     * request through the
     * {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, SessionCommand2)}.
     */
    public static final int COMMAND_CODE_PLAYLIST_GET_LIST_METADATA = 20;

    /**
     * Command code for {@link MediaController2#updatePlaylistMetadata(MediaMetadata2)}.
     * <p>
     * Command would be sent directly to the playlist agent if the session doesn't reject the
     * request through the
     * {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, SessionCommand2)}.
     */
    public static final int COMMAND_CODE_PLAYLIST_SET_LIST_METADATA = 21;

    /**
     * Command code for {@link MediaController2#playFromMediaId(String, Bundle)}.
     */
    public static final int COMMAND_CODE_SESSION_PLAY_FROM_MEDIA_ID = 22;

    /**
     * Command code for {@link MediaController2#playFromUri(Uri, Bundle)}.
     */
    public static final int COMMAND_CODE_SESSION_PLAY_FROM_URI = 23;

    /**
     * Command code for {@link MediaController2#playFromSearch(String, Bundle)}.
     */
    public static final int COMMAND_CODE_SESSION_PLAY_FROM_SEARCH = 24;

    /**
     * Command code for {@link MediaController2#prepareFromMediaId(String, Bundle)}.
     */
    public static final int COMMAND_CODE_SESSION_PREPARE_FROM_MEDIA_ID = 25;

    /**
     * Command code for {@link MediaController2#prepareFromUri(Uri, Bundle)}.
     */
    public static final int COMMAND_CODE_SESSION_PREPARE_FROM_URI = 26;

    /**
     * Command code for {@link MediaController2#prepareFromSearch(String, Bundle)}.
     */
    public static final int COMMAND_CODE_SESSION_PREPARE_FROM_SEARCH = 27;

    /**
     * Command code for {@link MediaController2#setRating(String, Rating2)}.
     */
    public static final int COMMAND_CODE_SESSION_SET_RATING = 28;

    // TODO(jaewan): Add javadoc
    public static final int COMMAND_CODE_LIBRARY_GET_CHILDREN = 29;
    public static final int COMMAND_CODE_LIBRARY_GET_ITEM = 30;
    public static final int COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT = 31;
    public static final int COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT = 32;
    public static final int COMMAND_CODE_LIBRARY_SEARCH = 33;
    public static final int COMMAND_CODE_LIBRARY_SUBSCRIBE = 34;
    public static final int COMMAND_CODE_LIBRARY_UNSUBSCRIBE = 35;

    // TODO(jaewan): Rename and move provider
    private final MediaSession2Provider.CommandProvider mProvider;

    public SessionCommand2(int commandCode) {
        mProvider = ApiLoader.getProvider().createMediaSession2Command(
                this, commandCode, null, null);
    }

    public SessionCommand2(@NonNull String action, @Nullable Bundle extras) {
        if (action == null) {
            throw new IllegalArgumentException("action shouldn't be null");
        }
        mProvider = ApiLoader.getProvider().createMediaSession2Command(
                this, COMMAND_CODE_CUSTOM, action, extras);
    }

    /**
     * @hide
     */
    public MediaSession2Provider.CommandProvider getProvider() {
        return mProvider;
    }

    public int getCommandCode() {
        return mProvider.getCommandCode_impl();
    }

    public @Nullable String getCustomCommand() {
        return mProvider.getCustomCommand_impl();
    }

    public @Nullable Bundle getExtras() {
        return mProvider.getExtras_impl();
    }

    /**
     * @return a new Bundle instance from the Command
     * @hide
     */
    public Bundle toBundle() {
        return mProvider.toBundle_impl();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof SessionCommand2)) {
            return false;
        }
        return mProvider.equals_impl(((SessionCommand2) obj).mProvider);
    }

    @Override
    public int hashCode() {
        return mProvider.hashCode_impl();
    }

    /**
     * @return a new Command instance from the Bundle
     * @hide
     */
    public static SessionCommand2 fromBundle(@NonNull Bundle command) {
        return ApiLoader.getProvider().fromBundle_MediaSession2Command(command);
    }
}
