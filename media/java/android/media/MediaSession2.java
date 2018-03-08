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

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayerBase.BuffState;
import android.media.MediaPlayerBase.PlayerEventCallback;
import android.media.MediaPlayerBase.PlayerState;
import android.media.MediaSession2.PlaylistParams.RepeatMode;
import android.media.MediaSession2.PlaylistParams.ShuffleMode;
import android.media.update.ApiLoader;
import android.media.update.MediaSession2Provider;
import android.media.update.MediaSession2Provider.BuilderBaseProvider;
import android.media.update.MediaSession2Provider.CommandButtonProvider;
import android.media.update.MediaSession2Provider.CommandGroupProvider;
import android.media.update.MediaSession2Provider.CommandProvider;
import android.media.update.MediaSession2Provider.ControllerInfoProvider;
import android.media.update.ProviderCreator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IInterface;
import android.os.ResultReceiver;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Allows a media app to expose its transport controls and playback information in a process to
 * other processes including the Android framework and other apps. Common use cases are as follows.
 * <ul>
 *     <li>Bluetooth/wired headset key events support</li>
 *     <li>Android Auto/Wearable support</li>
 *     <li>Separating UI process and playback process</li>
 * </ul>
 * <p>
 * A MediaSession2 should be created when an app wants to publish media playback information or
 * handle media keys. In general an app only needs one session for all playback, though multiple
 * sessions can be created to provide finer grain controls of media.
 * <p>
 * If you want to support background playback, {@link MediaSessionService2} is preferred
 * instead. With it, your playback can be revived even after you've finished playback. See
 * {@link MediaSessionService2} for details.
 * <p>
 * A session can be obtained by {@link Builder}. The owner of the session may pass its session token
 * to other processes to allow them to create a {@link MediaController2} to interact with the
 * session.
 * <p>
 * When a session receive transport control commands, the session sends the commands directly to
 * the the underlying media player set by {@link Builder} or
 * {@link #updatePlayer}.
 * <p>
 * When an app is finished performing playback it must call {@link #close()} to clean up the session
 * and notify any controllers.
 * <p>
 * {@link MediaSession2} objects should be used on the thread on the looper.
 *
 * @see MediaSessionService2
 */
public class MediaSession2 implements AutoCloseable {
    private final MediaSession2Provider mProvider;

    /**
     * Command code for the custom command which can be defined by string action in the
     * {@link Command}.
     */
    public static final int COMMAND_CODE_CUSTOM = 0;

    /**
     * Command code for {@link MediaController2#play()}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYBACK_PLAY = 1;

    /**
     * Command code for {@link MediaController2#pause()}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYBACK_PAUSE = 2;

    /**
     * Command code for {@link MediaController2#stop()}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYBACK_STOP = 3;

    /**
     * Command code for {@link MediaController2#skipToNextItem()}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYBACK_SKIP_NEXT_ITEM = 4;

    /**
     * Command code for {@link MediaController2#skipToPreviousItem()}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYBACK_SKIP_PREV_ITEM = 5;

    /**
     * Command code for {@link MediaController2#prepare()}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYBACK_PREPARE = 6;

    /**
     * Command code for {@link MediaController2#fastForward()}.
     * <p>
     * This is transport control command. Command would be sent directly to the player if the
     * session doesn't reject the request through the
     * {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYBACK_FAST_FORWARD = 7;

    /**
     * Command code for {@link MediaController2#rewind()}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYBACK_REWIND = 8;

    /**
     * Command code for {@link MediaController2#seekTo(long)}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYBACK_SEEK_TO = 9;

    /**
     * Command code for both {@link MediaController2#setVolumeTo(int, int)}.
     * <p>
     * Command would set the device volume or send to the volume provider directly if the session
     * doesn't reject the request through the
     * {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYBACK_SET_VOLUME = 10;

    /**
     * Command code for both {@link MediaController2#adjustVolume(int, int)}.
     * <p>
     * Command would adjust the device volume or send to the volume provider directly if the session
     * doesn't reject the request through the
     * {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYBACK_ADJUST_VOLUME = 11;

    /**
     * Command code for {@link MediaController2#setPlaylistParams(PlaylistParams)}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, Command)}.
     * @hide
     */
    // TODO(jaewan): Remove (b/74116823)
    public static final int COMMAND_CODE_PLAYBACK_SET_PLAYLIST_PARAMS = 12;

    /**
     * Command code for {@link MediaController2#skipToPlaylistItem(MediaItem2)}.
     * <p>
     * Command would be sent directly to the playlist agent if the session doesn't reject the
     * request through the
     * {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYLIST_SKIP_TO_PLAYLIST_ITEM = 12;

    /**
     * Command code for {@link MediaController2#setShuffleMode(int)}.
     * <p>
     * Command would be sent directly to the playlist agent if the session doesn't reject the
     * request through the
     * {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYLIST_SET_SHUFFLE_MODE = 13;

    /**
     * Command code for {@link MediaController2#setRepeatMode(int)}.
     * <p>
     * Command would be sent directly to the playlist agent if the session doesn't reject the
     * request through the
     * {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYLIST_SET_REPEAT_MODE = 14;

    /**
     * Command code for {@link MediaController2#addPlaylistItem(int, MediaItem2)}.
     * <p>
     * Command would be sent directly to the playlist agent if the session doesn't reject the
     * request through the
     * {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYLIST_ADD_ITEM = 15;

    /**
     * Command code for {@link MediaController2#addPlaylistItem(int, MediaItem2)}.
     * <p>
     * Command would be sent directly to the playlist agent if the session doesn't reject the
     * request through the
     * {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYLIST_REMOVE_ITEM = 16;

    /**
     * Command code for {@link MediaController2#replacePlaylistItem(int, MediaItem2)}.
     * <p>
     * Command would be sent directly to the playlist agent if the session doesn't reject the
     * request through the
     * {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYLIST_REPLACE_ITEM = 17;

    /**
     * Command code for {@link MediaController2#getPlaylist()}. This will expose metadata
     * information to the controller.
     * <p>
     * Command would be sent directly to the playlist agent if the session doesn't reject the
     * request through the
     * {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYLIST_GET_LIST = 18;

    /**
     * Command code for {@link MediaController2#setPlaylist(List, MediaMetadata2).
     * <p>
     * Command would be sent directly to the playlist agent if the session doesn't reject the
     * request through the
     * {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYLIST_SET_LIST = 19;

    /**
     * Command code for {@link MediaController2#getPlaylistMetadata()} ()}. This will expose
     * metadata information to the controller.
     * *
     * Command code for {@link MediaController2#setPlaylist(List, MediaMetadata2)} and
     * {@link MediaController2#updatePlaylistMetadata(MediaMetadata2)}.
     * <p>
     * Command would be sent directly to the playlist agent if the session doesn't reject the
     * request through the
     * {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYLIST_GET_LIST_METADATA = 20;

    /**
     * Command code for {@link MediaController2#updatePlaylistMetadata(MediaMetadata2)}.
     * <p>
     * Command would be sent directly to the playlist agent if the session doesn't reject the
     * request through the
     * {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYLIST_SET_LIST_METADATA = 21;

    /**
     * Command code for {@link MediaController2#playFromMediaId(String, Bundle)}.
     */
    public static final int COMMAND_CODE_PLAY_FROM_MEDIA_ID = 22;

    /**
     * Command code for {@link MediaController2#playFromUri(Uri, Bundle)}.
     */
    public static final int COMMAND_CODE_PLAY_FROM_URI = 23;

    /**
     * Command code for {@link MediaController2#playFromSearch(String, Bundle)}.
     */
    public static final int COMMAND_CODE_PLAY_FROM_SEARCH = 24;

    /**
     * Command code for {@link MediaController2#prepareFromMediaId(String, Bundle)}.
     */
    public static final int COMMAND_CODE_PREPARE_FROM_MEDIA_ID = 25;

    /**
     * Command code for {@link MediaController2#prepareFromUri(Uri, Bundle)}.
     */
    public static final int COMMAND_CODE_PREPARE_FROM_URI = 26;

    /**
     * Command code for {@link MediaController2#prepareFromSearch(String, Bundle)}.
     */
    public static final int COMMAND_CODE_PREPARE_FROM_SEARCH = 27;

    /**
     * Command code for {@link MediaBrowser2} specific functions that allows navigation and search
     * from the {@link MediaLibraryService2}. This would be ignored for a {@link MediaSession2},
     * not {@link android.media.MediaLibraryService2.MediaLibrarySession}.
     *
     * @see MediaBrowser2
     */
    public static final int COMMAND_CODE_BROWSER = 28;

    /**
     * @hide
     */
    public static final int COMMAND_CODE_MAX = 28;

    /**
     * @hide
     */
    @IntDef({ERROR_CODE_UNKNOWN_ERROR, ERROR_CODE_APP_ERROR, ERROR_CODE_NOT_SUPPORTED,
            ERROR_CODE_AUTHENTICATION_EXPIRED, ERROR_CODE_PREMIUM_ACCOUNT_REQUIRED,
            ERROR_CODE_CONCURRENT_STREAM_LIMIT, ERROR_CODE_PARENTAL_CONTROL_RESTRICTED,
            ERROR_CODE_NOT_AVAILABLE_IN_REGION, ERROR_CODE_CONTENT_ALREADY_PLAYING,
            ERROR_CODE_SKIP_LIMIT_REACHED, ERROR_CODE_ACTION_ABORTED, ERROR_CODE_END_OF_QUEUE,
            ERROR_CODE_SETUP_REQUIRED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ErrorCode {}

    /**
     * This is the default error code and indicates that none of the other error codes applies.
     */
    public static final int ERROR_CODE_UNKNOWN_ERROR = 0;

    /**
     * Error code when the application state is invalid to fulfill the request.
     */
    public static final int ERROR_CODE_APP_ERROR = 1;

    /**
     * Error code when the request is not supported by the application.
     */
    public static final int ERROR_CODE_NOT_SUPPORTED = 2;

    /**
     * Error code when the request cannot be performed because authentication has expired.
     */
    public static final int ERROR_CODE_AUTHENTICATION_EXPIRED = 3;

    /**
     * Error code when a premium account is required for the request to succeed.
     */
    public static final int ERROR_CODE_PREMIUM_ACCOUNT_REQUIRED = 4;

    /**
     * Error code when too many concurrent streams are detected.
     */
    public static final int ERROR_CODE_CONCURRENT_STREAM_LIMIT = 5;

    /**
     * Error code when the content is blocked due to parental controls.
     */
    public static final int ERROR_CODE_PARENTAL_CONTROL_RESTRICTED = 6;

    /**
     * Error code when the content is blocked due to being regionally unavailable.
     */
    public static final int ERROR_CODE_NOT_AVAILABLE_IN_REGION = 7;

    /**
     * Error code when the requested content is already playing.
     */
    public static final int ERROR_CODE_CONTENT_ALREADY_PLAYING = 8;

    /**
     * Error code when the application cannot skip any more songs because skip limit is reached.
     */
    public static final int ERROR_CODE_SKIP_LIMIT_REACHED = 9;

    /**
     * Error code when the action is interrupted due to some external event.
     */
    public static final int ERROR_CODE_ACTION_ABORTED = 10;

    /**
     * Error code when the playback navigation (previous, next) is not possible because the queue
     * was exhausted.
     */
    public static final int ERROR_CODE_END_OF_QUEUE = 11;

    /**
     * Error code when the session needs user's manual intervention.
     */
    public static final int ERROR_CODE_SETUP_REQUIRED = 12;

    /**
     * Interface definition of a callback to be invoked when a {@link MediaItem2} in the playlist
     * didn't have a {@link DataSourceDesc} but it's needed now for preparing or playing it.
     *
     * #see #setOnDataSourceMissingHelper
     */
    public interface OnDataSourceMissingHelper {
        /**
         * Called when a {@link MediaItem2} in the playlist didn't have a {@link DataSourceDesc}
         * but it's needed now for preparing or playing it. Returned data source descriptor will be
         * sent to the player directly to prepare or play the contents.
         * <p>
         * An exception may be thrown if the returned {@link DataSourceDesc} is duplicated in the
         * playlist, so items cannot be differentiated.
         *
         * @param session the session for this event
         * @param item media item from the controller
         * @return a data source descriptor if the media item. Can be {@code null} if the content
         *        isn't available.
         */
        @Nullable DataSourceDesc onDataSourceMissing(@NonNull MediaSession2 session,
                @NonNull MediaItem2 item);
    }

    /**
     * Define a command that a {@link MediaController2} can send to a {@link MediaSession2}.
     * <p>
     * If {@link #getCommandCode()} isn't {@link #COMMAND_CODE_CUSTOM}), it's predefined command.
     * If {@link #getCommandCode()} is {@link #COMMAND_CODE_CUSTOM}), it's custom command and
     * {@link #getCustomCommand()} shouldn't be {@code null}.
     */
    public static final class Command {
        private final CommandProvider mProvider;

        public Command(@NonNull Context context, int commandCode) {
            mProvider = ApiLoader.getProvider(context)
                    .createMediaSession2Command(this, commandCode, null, null);
        }

        public Command(@NonNull Context context, @NonNull String action, @Nullable Bundle extras) {
            if (action == null) {
                throw new IllegalArgumentException("action shouldn't be null");
            }
            mProvider = ApiLoader.getProvider(context)
                    .createMediaSession2Command(this, COMMAND_CODE_CUSTOM, action, extras);
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
            if (!(obj instanceof Command)) {
                return false;
            }
            return mProvider.equals_impl(((Command) obj).mProvider);
        }

        @Override
        public int hashCode() {
            return mProvider.hashCode_impl();
        }

        /**
         * @return a new Command instance from the Bundle
         * @hide
         */
        public static Command fromBundle(@NonNull Context context, Bundle command) {
            return ApiLoader.getProvider(context).fromBundle_MediaSession2Command(context, command);
        }
    }

    /**
     * Represent set of {@link Command}.
     */
    public static final class CommandGroup {
        private final CommandGroupProvider mProvider;

        public CommandGroup(Context context) {
            mProvider = ApiLoader.getProvider(context)
                    .createMediaSession2CommandGroup(context, this, null);
        }

        public CommandGroup(Context context, CommandGroup others) {
            mProvider = ApiLoader.getProvider(context)
                    .createMediaSession2CommandGroup(context, this, others);
        }

        public void addCommand(Command command) {
            mProvider.addCommand_impl(command);
        }

        public void addAllPredefinedCommands() {
            mProvider.addAllPredefinedCommands_impl();
        }

        public void removeCommand(Command command) {
            mProvider.removeCommand_impl(command);
        }

        public boolean hasCommand(Command command) {
            return mProvider.hasCommand_impl(command);
        }

        public boolean hasCommand(int code) {
            return mProvider.hasCommand_impl(code);
        }

        public List<Command> getCommands() {
            return mProvider.getCommands_impl();
        }

        /**
         * @hide
         */
        public CommandGroupProvider getProvider() {
            return mProvider;
        }

        /**
         * @return new bundle from the CommandGroup
         * @hide
         */
        public Bundle toBundle() {
            return mProvider.toBundle_impl();
        }

        /**
         * @return new instance of CommandGroup from the bundle
         * @hide
         */
        public static @Nullable CommandGroup fromBundle(Context context, Bundle commands) {
            return ApiLoader.getProvider(context)
                    .fromBundle_MediaSession2CommandGroup(context, commands);
        }
    }

    /**
     * Callback to be called for all incoming commands from {@link MediaController2}s.
     * <p>
     * If it's not set, the session will accept all controllers and all incoming commands by
     * default.
     */
    // TODO(jaewan): Move this to updatable for default implementation (b/74091963)
    public static abstract class SessionCallback {
        private final Context mContext;

        public SessionCallback(Context context) {
            mContext = context;
        }

        /**
         * Called when a controller is created for this session. Return allowed commands for
         * controller. By default it allows all connection requests and commands.
         * <p>
         * You can reject the connection by return {@code null}. In that case, controller receives
         * {@link MediaController2.ControllerCallback#onDisconnected(MediaController2)} and cannot
         * be usable.
         *
         * @param session the session for this event
         * @param controller controller information.
         * @return allowed commands. Can be {@code null} to reject coonnection.
         */
        public @Nullable CommandGroup onConnect(@NonNull MediaSession2 session,
                @NonNull ControllerInfo controller) {
            CommandGroup commands = new CommandGroup(mContext);
            commands.addAllPredefinedCommands();
            return commands;
        }

        /**
         * Called when a controller is disconnected
         *
         * @param session the session for this event
         * @param controller controller information
         */
        public void onDisconnected(@NonNull MediaSession2 session,
                @NonNull ControllerInfo controller) { }

        /**
         * Called when a controller sent a command that will be sent directly to the player. Return
         * {@code false} here to reject the request and stop sending command to the player.
         *
         * @param session the session for this event
         * @param controller controller information.
         * @param command a command. This method will be called for every single command.
         * @return {@code true} if you want to accept incoming command. {@code false} otherwise.
         * @see #COMMAND_CODE_PLAYBACK_PLAY
         * @see #COMMAND_CODE_PLAYBACK_PAUSE
         * @see #COMMAND_CODE_PLAYBACK_STOP
         * @see #COMMAND_CODE_PLAYBACK_SKIP_NEXT_ITEM
         * @see #COMMAND_CODE_PLAYBACK_SKIP_PREV_ITEM
         * @see #COMMAND_CODE_PLAYBACK_PREPARE
         * @see #COMMAND_CODE_PLAYBACK_FAST_FORWARD
         * @see #COMMAND_CODE_PLAYBACK_REWIND
         * @see #COMMAND_CODE_PLAYBACK_SEEK_TO
         * @see #COMMAND_CODE_PLAYLIST_SKIP_TO_PLAYLIST_ITEM
         * @see #COMMAND_CODE_PLAYBACK_SET_PLAYLIST_PARAMS
         * @see #COMMAND_CODE_PLAYLIST_ADD_ITEM
         * @see #COMMAND_CODE_PLAYLIST_REMOVE_ITEM
         * @see #COMMAND_CODE_PLAYLIST_GET_LIST
         * @see #COMMAND_CODE_PLAYBACK_SET_VOLUME
         */
        public boolean onCommandRequest(@NonNull MediaSession2 session,
                @NonNull ControllerInfo controller, @NonNull Command command) {
            return true;
        }

        /**
         * Called when a controller set rating of a media item through
         * {@link MediaController2#setRating(String, Rating2)}.
         * <p>
         * To allow setting user rating for a {@link MediaItem2}, the media item's metadata
         * should have {@link Rating2} with the key {@link MediaMetadata#METADATA_KEY_USER_RATING},
         * in order to provide possible rating style for controller. Controller will follow the
         * rating style.
         *
         * @param session the session for this event
         * @param controller controller information
         * @param mediaId media id from the controller
         * @param rating new rating from the controller
         */
        public void onSetRating(@NonNull MediaSession2 session, @NonNull ControllerInfo controller,
                @NonNull String mediaId, @NonNull Rating2 rating) { }

        /**
         * Called when a controller sent a custom command through
         * {@link MediaController2#sendCustomCommand(Command, Bundle, ResultReceiver)}.
         *
         * @param session the session for this event
         * @param controller controller information
         * @param customCommand custom command.
         * @param args optional arguments
         * @param cb optional result receiver
         */
        public void onCustomCommand(@NonNull MediaSession2 session,
                @NonNull ControllerInfo controller, @NonNull Command customCommand,
                @Nullable Bundle args, @Nullable ResultReceiver cb) { }

        /**
         * Called when a controller requested to play a specific mediaId through
         * {@link MediaController2#playFromMediaId(String, Bundle)}.
         *
         * @param session the session for this event
         * @param controller controller information
         * @param mediaId media id
         * @param extras optional extra bundle
         * @see #COMMAND_CODE_PLAY_FROM_MEDIA_ID
         */
        public void onPlayFromMediaId(@NonNull MediaSession2 session,
                @NonNull ControllerInfo controller, @NonNull String mediaId,
                @Nullable Bundle extras) { }

        /**
         * Called when a controller requested to begin playback from a search query through
         * {@link MediaController2#playFromSearch(String, Bundle)}
         * <p>
         * An empty query indicates that the app may play any music. The implementation should
         * attempt to make a smart choice about what to play.
         *
         * @param session the session for this event
         * @param controller controller information
         * @param query query string. Can be empty to indicate any suggested media
         * @param extras optional extra bundle
         * @see #COMMAND_CODE_PLAY_FROM_SEARCH
         */
        public void onPlayFromSearch(@NonNull MediaSession2 session,
                @NonNull ControllerInfo controller, @NonNull String query,
                @Nullable Bundle extras) { }

        /**
         * Called when a controller requested to play a specific media item represented by a URI
         * through {@link MediaController2#playFromUri(Uri, Bundle)}
         *
         * @param session the session for this event
         * @param controller controller information
         * @param uri uri
         * @param extras optional extra bundle
         * @see #COMMAND_CODE_PLAY_FROM_URI
         */
        public void onPlayFromUri(@NonNull MediaSession2 session,
                @NonNull ControllerInfo controller, @NonNull Uri uri,
                @Nullable Bundle extras) { }

        /**
         * Called when a controller requested to prepare for playing a specific mediaId through
         * {@link MediaController2#prepareFromMediaId(String, Bundle)}.
         * <p>
         * During the preparation, a session should not hold audio focus in order to allow other
         * sessions play seamlessly. The state of playback should be updated to
         * {@link MediaPlayerBase#PLAYER_STATE_PAUSED} after the preparation is done.
         * <p>
         * The playback of the prepared content should start in the later calls of
         * {@link MediaSession2#play()}.
         * <p>
         * Override {@link #onPlayFromMediaId} to handle requests for starting
         * playback without preparation.
         *
         * @param session the session for this event
         * @param controller controller information
         * @param mediaId media id to prepare
         * @param extras optional extra bundle
         * @see #COMMAND_CODE_PREPARE_FROM_MEDIA_ID
         */
        public void onPrepareFromMediaId(@NonNull MediaSession2 session,
                @NonNull ControllerInfo controller, @NonNull String mediaId,
                @Nullable Bundle extras) { }

        /**
         * Called when a controller requested to prepare playback from a search query through
         * {@link MediaController2#prepareFromSearch(String, Bundle)}.
         * <p>
         * An empty query indicates that the app may prepare any music. The implementation should
         * attempt to make a smart choice about what to play.
         * <p>
         * The state of playback should be updated to {@link MediaPlayerBase#PLAYER_STATE_PAUSED}
         * after the preparation is done. The playback of the prepared content should start in the
         * later calls of {@link MediaSession2#play()}.
         * <p>
         * Override {@link #onPlayFromSearch} to handle requests for starting playback without
         * preparation.
         *
         * @param session the session for this event
         * @param controller controller information
         * @param query query string. Can be empty to indicate any suggested media
         * @param extras optional extra bundle
         * @see #COMMAND_CODE_PREPARE_FROM_SEARCH
         */
        public void onPrepareFromSearch(@NonNull MediaSession2 session,
                @NonNull ControllerInfo controller, @NonNull String query,
                @Nullable Bundle extras) { }

        /**
         * Called when a controller requested to prepare a specific media item represented by a URI
         * through {@link MediaController2#prepareFromUri(Uri, Bundle)}.
         * <p>
         * During the preparation, a session should not hold audio focus in order to allow
         * other sessions play seamlessly. The state of playback should be updated to
         * {@link MediaPlayerBase#PLAYER_STATE_PAUSED} after the preparation is done.
         * <p>
         * The playback of the prepared content should start in the later calls of
         * {@link MediaSession2#play()}.
         * <p>
         * Override {@link #onPlayFromUri} to handle requests for starting playback without
         * preparation.
         *
         * @param session the session for this event
         * @param controller controller information
         * @param uri uri
         * @param extras optional extra bundle
         * @see #COMMAND_CODE_PREPARE_FROM_URI
         */
        public void onPrepareFromUri(@NonNull MediaSession2 session,
                @NonNull ControllerInfo controller, @NonNull Uri uri, @Nullable Bundle extras) { }

        /**
         * Called when the player's current playing item is changed
         * <p>
         * When it's called, you should invalidate previous playback information and wait for later
         * callbacks.
         *
         * @param session the controller for this event
         * @param player the player for this event
         * @param item new item
         */
        // TODO(jaewan): Use this (b/74316764)
        public void onCurrentMediaItemChanged(@NonNull MediaSession2 session,
                @NonNull MediaPlayerBase player, @NonNull MediaItem2 item) { }

        /**
         * Called when the player is <i>prepared</i>, i.e. it is ready to play the content
         * referenced by the given data source.
         * @param session the session for this event
         * @param player the player for this event
         * @param item the media item for which buffering is happening
         */
        public void onMediaPrepared(@NonNull MediaSession2 session, @NonNull MediaPlayerBase player,
                @NonNull MediaItem2 item) { }

        /**
         * Called to indicate that the state of the player has changed.
         * See {@link MediaPlayerBase#getPlayerState()} for polling the player state.
         * @param session the session for this event
         * @param player the player for this event
         * @param state the new state of the player.
         */
        public void onPlayerStateChanged(@NonNull MediaSession2 session,
                @NonNull MediaPlayerBase player, @PlayerState int state) { }

        /**
         * Called to report buffering events for a data source.
         *
         * @param session the session for this event
         * @param player the player for this event
         * @param item the media item for which buffering is happening.
         * @param state the new buffering state.
         */
        public void onBufferingStateChanged(@NonNull MediaSession2 session,
                @NonNull MediaPlayerBase player, @NonNull MediaItem2 item, @BuffState int state) { }

        /**
         * Called when a playlist is changed.
         *
         * @param session the session for this event
         * @param playlistAgent playlist agent for this event
         * @param list new playlist
         * @param metadata new metadata
         */
        public void onPlaylistChanged(@NonNull MediaSession2 session,
                @NonNull MediaPlaylistAgent playlistAgent, @NonNull List<MediaItem2> list,
                @Nullable MediaMetadata2 metadata) { }

        /**
         * Called when a playlist metadata is changed.
         *
         * @param session the session for this event
         * @param playlistAgent playlist agent for this event
         * @param metadata new metadata
         */
        public void onPlaylistMetadataChanged(@NonNull MediaSession2 session,
                @NonNull MediaPlaylistAgent playlistAgent, @Nullable MediaMetadata2 metadata) { }

        /**
         * Called when the shuffle mode is changed.
         *
         * @param session the session for this event
         * @param playlistAgent playlist agent for this event
         * @param shuffleMode repeat mode
         * @see MediaPlaylistAgent#SHUFFLE_MODE_NONE
         * @see MediaPlaylistAgent#SHUFFLE_MODE_ALL
         * @see MediaPlaylistAgent#SHUFFLE_MODE_GROUP
         */
        public void onShuffleModeChanged(@NonNull MediaSession2 session,
                @NonNull MediaPlaylistAgent playlistAgent,
                @MediaPlaylistAgent.ShuffleMode int shuffleMode) { }

        /**
         * Called when the repeat mode is changed.
         *
         * @param session the session for this event
         * @param playlistAgent playlist agent for this event
         * @param repeatMode repeat mode
         * @see MediaPlaylistAgent#REPEAT_MODE_NONE
         * @see MediaPlaylistAgent#REPEAT_MODE_ONE
         * @see MediaPlaylistAgent#REPEAT_MODE_ALL
         * @see MediaPlaylistAgent#REPEAT_MODE_GROUP
         */
        public void onRepeatModeChanged(@NonNull MediaSession2 session,
                @NonNull MediaPlaylistAgent playlistAgent,
                @MediaPlaylistAgent.RepeatMode int repeatMode) { }
    }

    /**
     * Base builder class for MediaSession2 and its subclass. Any change in this class should be
     * also applied to the subclasses {@link MediaSession2.Builder} and
     * {@link MediaLibraryService2.MediaLibrarySession.Builder}.
     * <p>
     * APIs here should be package private, but should have documentations for developers.
     * Otherwise, javadoc will generate documentation with the generic types such as follows.
     * <pre>U extends BuilderBase<T, U, C> setSessionCallback(Executor executor, C callback)</pre>
     * <p>
     * This class is hidden to prevent from generating test stub, which fails with
     * 'unexpected bound' because it tries to auto generate stub class as follows.
     * <pre>abstract static class BuilderBase<
     *      T extends android.media.MediaSession2,
     *      U extends android.media.MediaSession2.BuilderBase<
     *              T, U, C extends android.media.MediaSession2.SessionCallback>, C></pre>
     * @hide
     */
    static abstract class BuilderBase
            <T extends MediaSession2, U extends BuilderBase<T, U, C>, C extends SessionCallback> {
        private final BuilderBaseProvider<T, C> mProvider;

        BuilderBase(ProviderCreator<BuilderBase<T, U, C>, BuilderBaseProvider<T, C>> creator) {
            mProvider = creator.createProvider(this);
        }

        /**
         * Sets the underlying {@link MediaPlayerBase} for this session to dispatch incoming event
         * to.
         *
         * @param player a {@link MediaPlayerBase} that handles actual media playback in your app.
         */
        U setPlayer(@NonNull MediaPlayerBase player) {
            mProvider.setPlayer_impl(player);
            return (U) this;
        }

        /**
         * Sets the {@link MediaPlaylistAgent} for this session to manages playlist of the
         * underlying {@link MediaPlayerBase}. The playlist agent should manage
         * {@link MediaPlayerBase} for calling {@link MediaPlayerBase#setNextDataSources(List)}.
         * <p>
         * If the {@link MediaPlaylistAgent} isn't set, session will create the default playlist
         * agent.
         *
         * @param playlistAgent a {@link MediaPlaylistAgent} that manages playlist of the
         *                      {@code player}
         */
        U setPlaylistAgent(@NonNull MediaPlaylistAgent playlistAgent) {
            mProvider.setPlaylistAgent_impl(playlistAgent);
            return (U) this;
        }

        /**
         * Sets the {@link VolumeProvider2} for this session to handle volume events. If not set,
         * system will adjust the appropriate stream volume for this session's player.
         *
         * @param volumeProvider The provider that will receive volume button events.
         */
        U setVolumeProvider(@NonNull VolumeProvider2 volumeProvider) {
            mProvider.setVolumeProvider_impl(volumeProvider);
            return (U) this;
        }

        /**
         * Set an intent for launching UI for this Session. This can be used as a
         * quick link to an ongoing media screen. The intent should be for an
         * activity that may be started using {@link Context#startActivity(Intent)}.
         *
         * @param pi The intent to launch to show UI for this session.
         */
        U setSessionActivity(@Nullable PendingIntent pi) {
            mProvider.setSessionActivity_impl(pi);
            return (U) this;
        }

        /**
         * Set ID of the session. If it's not set, an empty string with used to create a session.
         * <p>
         * Use this if and only if your app supports multiple playback at the same time and also
         * wants to provide external apps to have finer controls of them.
         *
         * @param id id of the session. Must be unique per package.
         * @throws IllegalArgumentException if id is {@code null}
         * @return
         */
        U setId(@NonNull String id) {
            mProvider.setId_impl(id);
            return (U) this;
        }

        /**
         * Set callback for the session.
         *
         * @param executor callback executor
         * @param callback session callback.
         * @return
         */
        U setSessionCallback(@NonNull @CallbackExecutor Executor executor,
                @NonNull C callback) {
            mProvider.setSessionCallback_impl(executor, callback);
            return (U) this;
        }

        /**
         * Build {@link MediaSession2}.
         *
         * @return a new session
         * @throws IllegalStateException if the session with the same id is already exists for the
         *      package.
         */
        T build() {
            return mProvider.build_impl();
        }
    }

    /**
     * Builder for {@link MediaSession2}.
     * <p>
     * Any incoming event from the {@link MediaController2} will be handled on the thread
     * that created session with the {@link Builder#build()}.
     */
    // Override all methods just to show them with the type instead of generics in Javadoc.
    // This workarounds javadoc issue described in the MediaSession2.BuilderBase.
    public static final class Builder extends BuilderBase<MediaSession2, Builder, SessionCallback> {
        public Builder(Context context) {
            super((instance) -> ApiLoader.getProvider(context).createMediaSession2Builder(
                    context, (Builder) instance));
        }

        @Override
        public Builder setPlayer(@NonNull MediaPlayerBase player) {
            return super.setPlayer(player);
        }

        @Override
        public Builder setPlaylistAgent(@NonNull MediaPlaylistAgent playlistAgent) {
            return super.setPlaylistAgent(playlistAgent);
        }

        @Override
        public Builder setVolumeProvider(@NonNull VolumeProvider2 volumeProvider) {
            return super.setVolumeProvider(volumeProvider);
        }

        @Override
        public Builder setSessionActivity(@Nullable PendingIntent pi) {
            return super.setSessionActivity(pi);
        }

        @Override
        public Builder setId(@NonNull String id) {
            return super.setId(id);
        }

        @Override
        public Builder setSessionCallback(@NonNull Executor executor,
                @Nullable SessionCallback callback) {
            return super.setSessionCallback(executor, callback);
        }

        @Override
        public MediaSession2 build() {
            return super.build();
        }
    }

    /**
     * Information of a controller.
     */
    public static final class ControllerInfo {
        private final ControllerInfoProvider mProvider;

        /**
         * @hide
         */
        public ControllerInfo(Context context, int uid, int pid, String packageName,
                IInterface callback) {
            mProvider = ApiLoader.getProvider(context)
                    .createMediaSession2ControllerInfo(
                            context, this, uid, pid, packageName, callback);
        }

        /**
         * @return package name of the controller
         */
        public String getPackageName() {
            return mProvider.getPackageName_impl();
        }

        /**
         * @return uid of the controller
         */
        public int getUid() {
            return mProvider.getUid_impl();
        }

        /**
         * Return if the controller has granted {@code android.permission.MEDIA_CONTENT_CONTROL} or
         * has a enabled notification listener so can be trusted to accept connection and incoming
         * command request.
         *
         * @return {@code true} if the controller is trusted.
         */
        public boolean isTrusted() {
            return mProvider.isTrusted_impl();
        }

        /**
         * @hide
         */
        public ControllerInfoProvider getProvider() {
            return mProvider;
        }

        @Override
        public int hashCode() {
            return mProvider.hashCode_impl();
        }

        @Override
        public boolean equals(Object obj) {
            return mProvider.equals_impl(obj);
        }

        @Override
        public String toString() {
            return mProvider.toString_impl();
        }
    }

    /**
     * Button for a {@link Command} that will be shown by the controller.
     * <p>
     * It's up to the controller's decision to respect or ignore this customization request.
     */
    public static final class CommandButton {
        private final CommandButtonProvider mProvider;

        /**
         * @hide
         */
        public CommandButton(CommandButtonProvider provider) {
            mProvider = provider;
        }

        /**
         * Get command associated with this button. Can be {@code null} if the button isn't enabled
         * and only providing placeholder.
         *
         * @return command or {@code null}
         */
        public @Nullable Command getCommand() {
            return mProvider.getCommand_impl();
        }

        /**
         * Resource id of the button in this package. Can be {@code 0} if the command is predefined
         * and custom icon isn't needed.
         *
         * @return resource id of the icon. Can be {@code 0}.
         */
        public int getIconResId() {
            return mProvider.getIconResId_impl();
        }

        /**
         * Display name of the button. Can be {@code null} or empty if the command is predefined
         * and custom name isn't needed.
         *
         * @return custom display name. Can be {@code null} or empty.
         */
        public @Nullable String getDisplayName() {
            return mProvider.getDisplayName_impl();
        }

        /**
         * Extra information of the button. It's private information between session and controller.
         *
         * @return
         */
        public @Nullable Bundle getExtras() {
            return mProvider.getExtras_impl();
        }

        /**
         * Return whether it's enabled
         *
         * @return {@code true} if enabled. {@code false} otherwise.
         */
        public boolean isEnabled() {
            return mProvider.isEnabled_impl();
        }

        /**
         * @hide
         */
        public CommandButtonProvider getProvider() {
            return mProvider;
        }

        /**
         * Builder for {@link CommandButton}.
         */
        public static final class Builder {
            private final CommandButtonProvider.BuilderProvider mProvider;

            public Builder(@NonNull Context context) {
                mProvider = ApiLoader.getProvider(context)
                        .createMediaSession2CommandButtonBuilder(context, this);
            }

            public Builder setCommand(Command command) {
                return mProvider.setCommand_impl(command);
            }

            public Builder setIconResId(int resId) {
                return mProvider.setIconResId_impl(resId);
            }

            public Builder setDisplayName(String displayName) {
                return mProvider.setDisplayName_impl(displayName);
            }

            public Builder setEnabled(boolean enabled) {
                return mProvider.setEnabled_impl(enabled);
            }

            public Builder setExtras(Bundle extras) {
                return mProvider.setExtras_impl(extras);
            }

            public CommandButton build() {
                return mProvider.build_impl();
            }
        }
    }

    /**
     * Parameter for the playlist.
     * @hide
     */
    // TODO(jaewan): Remove (b/74116823)
    public final static class PlaylistParams {
        /**
         * @hide
         */
        @IntDef({REPEAT_MODE_NONE, REPEAT_MODE_ONE, REPEAT_MODE_ALL,
                REPEAT_MODE_GROUP})
        @Retention(RetentionPolicy.SOURCE)
        public @interface RepeatMode {}

        /**
         * Playback will be stopped at the end of the playing media list.
         */
        public static final int REPEAT_MODE_NONE = 0;

        /**
         * Playback of the current playing media item will be repeated.
         */
        public static final int REPEAT_MODE_ONE = 1;

        /**
         * Playing media list will be repeated.
         */
        public static final int REPEAT_MODE_ALL = 2;

        /**
         * Playback of the playing media group will be repeated.
         * A group is a logical block of media items which is specified in the section 5.7 of the
         * Bluetooth AVRCP 1.6.
         */
        public static final int REPEAT_MODE_GROUP = 3;

        /**
         * @hide
         */
        @IntDef({SHUFFLE_MODE_NONE, SHUFFLE_MODE_ALL, SHUFFLE_MODE_GROUP})
        @Retention(RetentionPolicy.SOURCE)
        public @interface ShuffleMode {}

        /**
         * Media list will be played in order.
         */
        public static final int SHUFFLE_MODE_NONE = 0;

        /**
         * Media list will be played in shuffled order.
         */
        public static final int SHUFFLE_MODE_ALL = 1;

        /**
         * Media group will be played in shuffled order.
         * A group is a logical block of media items which is specified in the section 5.7 of the
         * Bluetooth AVRCP 1.6.
         */
        public static final int SHUFFLE_MODE_GROUP = 2;


        private final MediaSession2Provider.PlaylistParamsProvider mProvider;

        /**
         * Instantiate {@link PlaylistParams}
         *
         * @param context context
         * @param repeatMode repeat mode
         * @param shuffleMode shuffle mode
         * @param playlistMetadata metadata for the list
         */
        public PlaylistParams(@NonNull Context context, @RepeatMode int repeatMode,
                @ShuffleMode int shuffleMode, @Nullable MediaMetadata2 playlistMetadata) {
            mProvider = ApiLoader.getProvider(context).createMediaSession2PlaylistParams(
                    context, this, repeatMode, shuffleMode, playlistMetadata);
        }

        /**
         * Create a new bundle for this object.
         *
         * @return
         */
        public @NonNull Bundle toBundle() {
            return mProvider.toBundle_impl();
        }

        /**
         * Create a new playlist params from the bundle that was previously returned by
         * {@link #toBundle}.
         *
         * @param context context
         * @return a new playlist params. Can be {@code null} for error.
         */
        public static @Nullable PlaylistParams fromBundle(
                @NonNull Context context, @Nullable Bundle bundle) {
            return ApiLoader.getProvider(context).fromBundle_PlaylistParams(context, bundle);
        }

        /**
         * Get repeat mode
         *
         * @return repeat mode
         * @see #REPEAT_MODE_NONE, #REPEAT_MODE_ONE, #REPEAT_MODE_ALL, #REPEAT_MODE_GROUP
         */
        public @RepeatMode int getRepeatMode() {
            return mProvider.getRepeatMode_impl();
        }

        /**
         * Get shuffle mode
         *
         * @return shuffle mode
         * @see #SHUFFLE_MODE_NONE, #SHUFFLE_MODE_ALL, #SHUFFLE_MODE_GROUP
         */
        public @ShuffleMode int getShuffleMode() {
            return mProvider.getShuffleMode_impl();
        }

        /**
         * Get metadata for the playlist
         *
         * @return metadata. Can be {@code null}
         */
        public @Nullable MediaMetadata2 getPlaylistMetadata() {
            return mProvider.getPlaylistMetadata_impl();
        }
    }

    /**
     * Constructor is hidden and apps can only instantiate indirectly through {@link Builder}.
     * <p>
     * This intended behavior and here's the reasons.
     *    1. Prevent multiple sessions with the same tag in a media app.
     *       Whenever it happens only one session was properly setup and others were all dummies.
     *       Android framework couldn't find the right session to dispatch media key event.
     *    2. Simplify session's lifecycle.
     *       {@link android.media.session.MediaSession} is available after all of
     *       {@link android.media.session.MediaSession#setFlags(int)},
     *       {@link android.media.session.MediaSession#setCallback(
     *              android.media.session.MediaSession.Callback)},
     *       and {@link android.media.session.MediaSession#setActive(boolean)}.
     *       It was common for an app to omit one, so framework had to add heuristics to figure out
     *       which should be the highest priority for handling media key event.
     * @hide
     */
    public MediaSession2(MediaSession2Provider provider) {
        super();
        mProvider = provider;
    }

    /**
     * @hide
     */
    public MediaSession2Provider getProvider() {
        return mProvider;
    }

    /**
     * Sets the underlying {@link MediaPlayerBase} and {@link MediaPlaylistAgent} for this session
     * to dispatch incoming event to.
     * <p>
     * When a {@link MediaPlaylistAgent} is specified here, the playlist agent should manage
     * {@link MediaPlayerBase} for calling {@link MediaPlayerBase#setNextDataSources(List)}.
     * <p>
     * If the {@link MediaPlaylistAgent} isn't set, session will recreate the default playlist
     * agent.
     *
     * @param player a {@link MediaPlayerBase} that handles actual media playback in your app
     * @param playlistAgent a {@link MediaPlaylistAgent} that manages playlist of the {@code player}
     * @param volumeProvider a {@link VolumeProvider2}. If {@code null}, system will adjust the
     *                       appropriate stream volume for this session's player.
     */
    public void updatePlayer(@NonNull MediaPlayerBase player,
            @Nullable MediaPlaylistAgent playlistAgent, @Nullable VolumeProvider2 volumeProvider) {
        mProvider.updatePlayer_impl(player, playlistAgent, volumeProvider);
    }

    @Override
    public void close() {
        mProvider.close_impl();
    }

    /**
     * @return player
     */
    public @NonNull MediaPlayerBase getPlayer() {
        return mProvider.getPlayer_impl();
    }

    /**
     * @return playlist agent
     */
    public @NonNull MediaPlaylistAgent getPlaylistAgent() {
        return mProvider.getPlaylistAgent_impl();
    }

    /**
     * @return volume provider
     */
    public @Nullable VolumeProvider2 getVolumeProvider() {
        return mProvider.getVolumeProvider_impl();
    }

    /**
     * Returns the {@link SessionToken2} for creating {@link MediaController2}.
     */
    public @NonNull
    SessionToken2 getToken() {
        return mProvider.getToken_impl();
    }

    public @NonNull List<ControllerInfo> getConnectedControllers() {
        return mProvider.getConnectedControllers_impl();
    }

    /**
     * Set the {@link AudioFocusRequest} to obtain the audio focus
     *
     * @param afr the full request parameters
     */
    public void setAudioFocusRequest(AudioFocusRequest afr) {
        // TODO(jaewan): implement this (b/72529899)
        // mProvider.setAudioFocusRequest_impl(focusGain);
    }

    /**
     * Sets ordered list of {@link CommandButton} for controllers to build UI with it.
     * <p>
     * It's up to controller's decision how to represent the layout in its own UI.
     * Here's the same way
     * (layout[i] means a CommandButton at index i in the given list)
     * For 5 icons row
     *      layout[3] layout[1] layout[0] layout[2] layout[4]
     * For 3 icons row
     *      layout[1] layout[0] layout[2]
     * For 5 icons row with overflow icon (can show +5 extra buttons with overflow button)
     *      expanded row:   layout[5] layout[6] layout[7] layout[8] layout[9]
     *      main row:       layout[3] layout[1] layout[0] layout[2] layout[4]
     * <p>
     * This API can be called in the {@link SessionCallback#onConnect(MediaSession2, ControllerInfo)}.
     *
     * @param controller controller to specify layout.
     * @param layout ordered list of layout.
     */
    public void setCustomLayout(@NonNull ControllerInfo controller,
            @NonNull List<CommandButton> layout) {
        mProvider.setCustomLayout_impl(controller, layout);
    }

    /**
     * Set the new allowed command group for the controller
     *
     * @param controller controller to change allowed commands
     * @param commands new allowed commands
     */
    public void setAllowedCommands(@NonNull ControllerInfo controller,
            @NonNull CommandGroup commands) {
        mProvider.setAllowedCommands_impl(controller, commands);
    }

    /**
     * Send custom command to all connected controllers.
     *
     * @param command a command
     * @param args optional argument
     */
    public void sendCustomCommand(@NonNull Command command, @Nullable Bundle args) {
        mProvider.sendCustomCommand_impl(command, args);
    }

    /**
     * Send custom command to a specific controller.
     *
     * @param command a command
     * @param args optional argument
     * @param receiver result receiver for the session
     */
    public void sendCustomCommand(@NonNull ControllerInfo controller, @NonNull Command command,
            @Nullable Bundle args, @Nullable ResultReceiver receiver) {
        // Equivalent to the MediaController.sendCustomCommand(Action action, ResultReceiver r);
        mProvider.sendCustomCommand_impl(controller, command, args, receiver);
    }

    /**
     * Play playback
     * <p>
     * This calls {@link MediaPlayerBase#play()}.
     */
    public void play() {
        mProvider.play_impl();
    }

    /**
     * Pause playback.
     * <p>
     * This calls {@link MediaPlayerBase#pause()}.
     */
    public void pause() {
        mProvider.pause_impl();
    }

    /**
     * Stop playback, and reset the player to the initial state.
     * <p>
     * This calls {@link MediaPlayerBase#reset()}.
     */
    public void stop() {
        mProvider.stop_impl();
    }

    /**
     * Request that the player prepare its playback. In other words, other sessions can continue
     * to play during the preparation of this session. This method can be used to speed up the
     * start of the playback. Once the preparation is done, the session will change its playback
     * state to {@link MediaPlayerBase#PLAYER_STATE_PAUSED}. Afterwards, {@link #play} can be called
     * to start playback.
     * <p>
     * This calls {@link MediaPlayerBase#reset()}.
     */
    public void prepare() {
        mProvider.prepare_impl();
    }

    /**
     * Start fast forwarding. If playback is already fast forwarding this may increase the rate.
     */
    public void fastForward() {
        mProvider.fastForward_impl();
    }

    /**
     * Start rewinding. If playback is already rewinding this may increase the rate.
     */
    public void rewind() {
        mProvider.rewind_impl();
    }

    /**
     * Move to a new location in the media stream.
     *
     * @param pos Position to move to, in milliseconds.
     */
    public void seekTo(long pos) {
        mProvider.seekTo_impl(pos);
    }

    /**
     * @hide
     */
    public void skipForward() {
        // To match with KEYCODE_MEDIA_SKIP_FORWARD
    }

    /**
     * @hide
     */
    public void skipBackward() {
        // To match with KEYCODE_MEDIA_SKIP_BACKWARD
    }

    /**
     * Sets the {@link PlaylistParams} for the current play list. Repeat/shuffle mode and metadata
     * for the list can be set by calling this method.
     *
     * @param params A {@link PlaylistParams} object to set.
     * @throws IllegalArgumentException if given {@param param} is null.
     * @hide
     */
    // TODO(jaewan): Remove (b/74116823)
    public void setPlaylistParams(PlaylistParams params) {
        mProvider.setPlaylistParams_impl(params);
    }

    /**
     * Returns the {@link PlaylistParams} for the current play list.
     * Returns {@code null} if not set.
     * @hide
     */
    // TODO(jaewan): Remove (b/74116823)
    public PlaylistParams getPlaylistParams() {
        return mProvider.getPlaylistParams_impl();
    }

    /**
     * Notify errors to the connected controllers
     *
     * @param errorCode error code
     * @param extras extras
     */
    public void notifyError(@ErrorCode int errorCode, @Nullable Bundle extras) {
        mProvider.notifyError_impl(errorCode, extras);
    }

    /**
     * Register {@link PlayerEventCallback} to listen changes in the underlying
     * {@link MediaPlayerBase}, regardless of the change in the underlying player.
     * <p>
     * Registered callbacks will be also called when the underlying player is changed.
     *
     * @param executor a callback Executor
     * @param callback a EventCallback
     * @throws IllegalArgumentException if executor or callback is {@code null}.
     * @hide
     */
    // TODO(jaewan): Remove (b/74157064)
    public void registerPlayerEventCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull PlayerEventCallback callback) {
        mProvider.registerPlayerEventCallback_impl(executor, callback);
    }

    /**
     * Unregister the previously registered {@link PlayerEventCallback}.
     *
     * @param callback the callback to be removed
     * @throws IllegalArgumentException if the callback is {@code null}.
     * @hide
     */
    // TODO(jaewan): Remove (b/74157064)
    public void unregisterPlayerEventCallback(@NonNull PlayerEventCallback callback) {
        mProvider.unregisterPlayerEventCallback_impl(callback);
    }

    /**
     * Return the {@link PlaybackState2} from the player.
     *
     * @return playback state
     * @hide
     */
    public PlaybackState2 getPlaybackState() {
        return mProvider.getPlaybackState_impl();
    }

    /**
     * Get the playback speed.
     *
     * @return speed
     */
    public float getPlaybackSpeed() {
        // TODO(jaewan): implement this (b/74093080)
        return -1;
    }

    /**
     * Set the playback speed.
     */
    public void setPlaybackSpeed(float speed) {
        // TODO(jaewan): implement this (b/74093080)
    }

    /**
     * Sets the data source missing helper. Helper will be used to provide default implementation of
     * {@link MediaPlaylistAgent} when it isn't set by developer.
     * <p>
     * Default implementation of the {@link MediaPlaylistAgent} will call helper when a
     * {@link MediaItem2} in the playlist doesn't have a {@link DataSourceDesc}. This may happen
     * when
     * <ul>
     *      <li>{@link MediaItem2} specified by {@link #setPlaylist(List, MediaMetadata2)} doesn't
     *          have {@link DataSourceDesc}</li>
     *      <li>{@link MediaController2#addPlaylistItem(int, MediaItem2)} is called and accepted
     *          by {@link SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, Command)}.
     *          In that case, an item would be added automatically without the data source.</li>
     * </ul>
     * <p>
     * If it's not set, playback wouldn't happen for the item without data source descriptor.
     * <p>
     * The helper will be run on the executor that you've specified by the
     * {@link Builder#setSessionCallback(Executor, SessionCallback)}.
     *
     * @param helper a data source missing helper.
     * @throws IllegalStateException when the helper is set when the playlist agent is set
     * @see #setPlaylist(List, MediaMetadata2)
     * @see SessionCallback#onCommandRequest(MediaSession2, ControllerInfo, Command)
     * @see #COMMAND_CODE_PLAYLIST_ADD_ITEM
     * @see #COMMAND_CODE_PLAYLIST_REPLACE_ITEM
     */
    public void setOnDataSourceMissingHelper(@NonNull OnDataSourceMissingHelper helper) {
        // TODO(jaewan): Implement (b/74090741).
    }

    /**
     * Clears the data source missing helper.
     *
     * @see #setOnDataSourceMissingHelper(OnDataSourceMissingHelper)
     */
    public void clearOnDataSourceMissingHelper() {
        // TODO(jaewan): Implement (b/74090741)
    }

    /**
     * Return the playlist which is lastly set.
     *
     * @return playlist
     */
    public List<MediaItem2> getPlaylist() {
        return mProvider.getPlaylist_impl();
    }

    /**
     * Set a list of {@link MediaItem2} as the current play list.
     *
     * @param playlist A list of {@link MediaItem2} objects to set as a play list.
     * @throws IllegalArgumentException if given {@param playlist} is null.
     * @hide
     */
    // TODO(jaewan): Remove
    public void setPlaylist(@NonNull List<MediaItem2> playlist) {
        mProvider.setPlaylist_impl(playlist);
    }

    /**
     * Set a list of {@link MediaItem2} as the current play list. Ensure uniqueness in the
     * {@link MediaItem2} in the playlist so session can uniquely identity individual items.
     * <p>
     * You may specify a {@link MediaItem2} without {@link DataSourceDesc}. However, in that case,
     * you should set {@link OnDataSourceMissingHelper} for player to prepare.
     *
     * @param list A list of {@link MediaItem2} objects to set as a play list.
     * @throws IllegalArgumentException if given list is {@code null}, or has duplicated media item.
     * @see #setOnDataSourceMissingHelper
     */
    public void setPlaylist(@NonNull List<MediaItem2> list, @Nullable MediaMetadata2 metadata) {
        // TODO(jaewan): Handle metadata here (b/74174649)
        // TODO(jaewan): Handle list change (b/74326040)
    }

    /**
     * Skip to the item in the play list.
     *
     * @param item item in the play list you want to play
     * @throws IllegalArgumentException if the play list is null
     * @throws NullPointerException if index is outside play list range
     */
    public void skipToPlaylistItem(MediaItem2 item) {
        mProvider.skipToPlaylistItem_impl(item);
    }

    public void skipToPreviousItem() {
        mProvider.skipToPreviousItem_impl();
    }

    public void skipToNextItem() {
        mProvider.skipToNextItem_impl();
    }

    public MediaMetadata2 getPlaylistMetadata() {
        // TODO(jaewan): Implement (b/74174649)
        return null;
    }

    /**
     * Add the media item to the play list at position index.
     * <p>
     * This will not change the currently playing media item.
     * If index is less than or equal to the current index of the play list,
     * the current index of the play list will be incremented correspondingly.
     *
     * @param index the index you want to add
     * @param item the media item you want to add
     * @throws IndexOutOfBoundsException if index is outside play list range
     */
    public void addPlaylistItem(int index, @NonNull MediaItem2 item) {
        mProvider.addPlaylistItem_impl(index, item);
    }

    /**
     * Remove the media item in the play list.
     * <p>
     * If the item is the currently playing item of the playlist, current playback
     * will be stopped and playback moves to next source in the list.
     *
     * @throws IllegalArgumentException if the play list is null
     */
    public void removePlaylistItem(MediaItem2 item) {
        mProvider.removePlaylistItem_impl(item);
    }

    /**
     * Replace the media item at index in the playlist. This can be also used to update metadata of
     * an item.
     *
     * @param index the index of the item to replace
     * @param item the new item
     */
    public void replacePlaylistItem(int index, @NonNull MediaItem2 item) {
        mProvider.replacePlaylistItem_impl(index, item);
    }

    /**
     * Return currently playing media item.
     *
     * @return currently playing media item
     */
    public MediaItem2 getCurrentMediaItem() {
        // TODO(jaewan): Rename provider, and implement (b/74316764)
        return mProvider.getCurrentPlaylistItem_impl();
    }

    public void updatePlaylistMetadata(@Nullable MediaMetadata2 metadata) {
        // TODO(jaewan): Implement (b/74174649)
    }

    public @RepeatMode int getRepeatMode() {
        // TODO(jaewan): Implement (b/74118768)
        return 0;
    }

    public void setRepeatMode(@RepeatMode int repeatMode) {
        // TODO(jaewan): Implement (b/74118768)
    }

    public @ShuffleMode int getShuffleMode() {
        // TODO(jaewan): Implement (b/74118768)
        return 0;
    }

    public void setShuffleMode(@ShuffleMode int shuffleMode) {
        // TODO(jaewan): Implement (b/74118768)
    }
}
