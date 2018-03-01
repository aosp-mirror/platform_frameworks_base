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
import android.media.MediaPlayerBase.PlayerEventCallback;
import android.media.session.MediaSession;
import android.media.session.MediaSession.Callback;
import android.media.session.PlaybackState;
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
 * {@link #setPlayer(MediaPlayerBase)}.
 * <p>
 * When an app is finished performing playback it must call {@link #close()} to clean up the session
 * and notify any controllers.
 * <p>
 * {@link MediaSession2} objects should be used on the thread on the looper.
 *
 * @see MediaSessionService2
 */
public class MediaSession2 implements AutoCloseable, MediaPlaylistController {
    private final MediaSession2Provider mProvider;

    // TODO(jaewan): Should we define IntDef? Currently we don't have to allow subclass to add more.
    // TODO(jaewan): Shouldn't we pull out?
    // TODO(jaewan): Should we also protect getters not related with metadata?
    //               Getters are getPlaybackState(), getSessionActivity(), getPlaylistParams()
    // Next ID: 23
    /**
     * Command code for the custom command which can be defined by string action in the
     * {@link Command}.
     */
    public static final int COMMAND_CODE_CUSTOM = 0;

    /**
     * Command code for {@link MediaController2#play()}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link SessionCallback#onCommandRequest(ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYBACK_PLAY = 1;

    /**
     * Command code for {@link MediaController2#pause()}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link SessionCallback#onCommandRequest(ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYBACK_PAUSE = 2;

    /**
     * Command code for {@link MediaController2#stop()}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link SessionCallback#onCommandRequest(ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYBACK_STOP = 3;

    /**
     * Command code for {@link MediaController2#skipToNext()} ()}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link SessionCallback#onCommandRequest(ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYBACK_SKIP_NEXT_ITEM = 4;

    /**
     * Command code for {@link MediaController2#skipToPrevious()} ()}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link SessionCallback#onCommandRequest(ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYBACK_SKIP_PREV_ITEM = 5;

    /**
     * Command code for {@link MediaController2#prepare()}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link SessionCallback#onCommandRequest(ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYBACK_PREPARE = 6;

    /**
     * Command code for {@link MediaController2#fastForward()}.
     * <p>
     * This is transport control command. Command would be sent directly to the player if the
     * session doesn't reject the request through the
     * {@link SessionCallback#onCommandRequest(ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYBACK_FAST_FORWARD = 7;

    /**
     * Command code for {@link MediaController2#rewind()}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link SessionCallback#onCommandRequest(ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYBACK_REWIND = 8;

    /**
     * Command code for {@link MediaController2#seekTo(long)}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link SessionCallback#onCommandRequest(ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYBACK_SEEK_TO = 9;
    /**
     * Command code for {@link MediaController2#skipToPlaylistItem(MediaItem2)}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link SessionCallback#onCommandRequest(ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYBACK_SET_CURRENT_PLAYLIST_ITEM = 10;

    /**
     * Command code for {@link MediaController2#setPlaylistParams(PlaylistParams)} ()}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link SessionCallback#onCommandRequest(ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYBACK_SET_PLAYLIST_PARAMS = 11;

    /**
     * Command code for {@link MediaController2#addPlaylistItem(int, MediaItem2)}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link SessionCallback#onCommandRequest(ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYLIST_ADD = 12;

    /**
     * Command code for {@link MediaController2#addPlaylistItem(int, MediaItem2)}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link SessionCallback#onCommandRequest(ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYLIST_REMOVE = 13;

    /**
     * Command code for {@link MediaController2#getPlaylist()}.
     * <p>
     * Command would be sent directly to the player if the session doesn't reject the request
     * through the {@link SessionCallback#onCommandRequest(ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_PLAYLIST_GET = 14;

    /**
     * Command code for both {@link MediaController2#setVolumeTo(int, int)} and
     * {@link MediaController2#adjustVolume(int, int)}.
     * <p>
     * Command would adjust the volume or sent to the volume provider directly if the session
     * doesn't reject the request through the
     * {@link SessionCallback#onCommandRequest(ControllerInfo, Command)}.
     */
    public static final int COMMAND_CODE_SET_VOLUME = 15;

    /**
     * Command code for {@link MediaController2#playFromMediaId(String, Bundle)}.
     */
    public static final int COMMAND_CODE_PLAY_FROM_MEDIA_ID = 16;

    /**
     * Command code for {@link MediaController2#playFromUri(Uri, Bundle)}.
     */
    public static final int COMMAND_CODE_PLAY_FROM_URI = 17;

    /**
     * Command code for {@link MediaController2#playFromSearch(String, Bundle)}.
     */
    public static final int COMMAND_CODE_PLAY_FROM_SEARCH = 18;

    /**
     * Command code for {@link MediaController2#prepareFromMediaId(String, Bundle)}.
     */
    public static final int COMMAND_CODE_PREPARE_FROM_MEDIA_ID = 19;

    /**
     * Command code for {@link MediaController2#prepareFromUri(Uri, Bundle)}.
     */
    public static final int COMMAND_CODE_PREPARE_FROM_URI = 20;

    /**
     * Command code for {@link MediaController2#prepareFromSearch(String, Bundle)}.
     */
    public static final int COMMAND_CODE_PREPARE_FROM_SEARCH = 21;

    /**
     * Command code for {@link MediaBrowser2} specific functions that allows navigation and search
     * from the {@link MediaLibraryService2}. This would be ignored if a {@link MediaSession2},
     * not {@link android.media.MediaLibraryService2.MediaLibrarySession}, specify this.
     *
     * @see MediaBrowser2
     */
    public static final int COMMAND_CODE_BROWSER = 22;

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
     * Define a command that a {@link MediaController2} can send to a {@link MediaSession2}.
     * <p>
     * If {@link #getCommandCode()} isn't {@link #COMMAND_CODE_CUSTOM}), it's predefined command.
     * If {@link #getCommandCode()} is {@link #COMMAND_CODE_CUSTOM}), it's custom command and
     * {@link #getCustomCommand()} shouldn't be {@code null}.
     */
    // TODO(jaewan): Move this into the updatable.
    public static final class Command {
        private final CommandProvider mProvider;

        public Command(@NonNull Context context, int commandCode) {
            mProvider = ApiLoader.getProvider(context)
                    .createMediaSession2Command(this, commandCode, null, null);
        }

        public Command(@NonNull Context context, @NonNull String action, @Nullable Bundle extra) {
            if (action == null) {
                throw new IllegalArgumentException("action shouldn't be null");
            }
            mProvider = ApiLoader.getProvider(context)
                    .createMediaSession2Command(this, COMMAND_CODE_CUSTOM, action, extra);
        }

        public int getCommandCode() {
            return mProvider.getCommandCode_impl();
        }

        public @Nullable String getCustomCommand() {
            return mProvider.getCustomCommand_impl();
        }

        public @Nullable Bundle getExtra() {
            return mProvider.getExtra_impl();
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
    // TODO(jaewan): Can we move this inside of the updatable for default implementation.
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
         * {@link MediaController2.ControllerCallback#onDisconnected()} and cannot be usable.
         *
         * @param controller controller information.
         * @return allowed commands. Can be {@code null} to reject coonnection.
         */
        // TODO(jaewan): Change return type. Once we do, null is for reject.
        public @Nullable CommandGroup onConnect(@NonNull ControllerInfo controller) {
            CommandGroup commands = new CommandGroup(mContext);
            commands.addAllPredefinedCommands();
            return commands;
        }

        /**
         * Called when a controller is disconnected
         *
         * @param controller controller information
         */
        public void onDisconnected(@NonNull ControllerInfo controller) { }

        /**
         * Called when a controller sent a command that will be sent directly to the player. Return
         * {@code false} here to reject the request and stop sending command to the player.
         *
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
         * @see #COMMAND_CODE_PLAYBACK_SET_CURRENT_PLAYLIST_ITEM
         * @see #COMMAND_CODE_PLAYBACK_SET_PLAYLIST_PARAMS
         * @see #COMMAND_CODE_PLAYLIST_ADD
         * @see #COMMAND_CODE_PLAYLIST_REMOVE
         * @see #COMMAND_CODE_PLAYLIST_GET
         * @see #COMMAND_CODE_SET_VOLUME
         */
        public boolean onCommandRequest(@NonNull ControllerInfo controller,
                @NonNull Command command) {
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
         * @param controller controller information
         * @param mediaId media id from the controller
         * @param rating new rating from the controller
         */
        public void onSetRating(@NonNull ControllerInfo controller, @NonNull String mediaId,
                @NonNull Rating2 rating) { }

        /**
         * Called when a controller sent a custom command through
         * {@link MediaController2#sendCustomCommand(Command, Bundle, ResultReceiver)}.
         *
         * @param controller controller information
         * @param customCommand custom command.
         * @param args optional arguments
         * @param cb optional result receiver
         */
        public void onCustomCommand(@NonNull ControllerInfo controller,
                @NonNull Command customCommand, @Nullable Bundle args,
                @Nullable ResultReceiver cb) { }

        /**
         * Called when a controller requested to play a specific mediaId through
         * {@link MediaController2#playFromMediaId(String, Bundle)}.
         *
         * @param controller controller information
         * @param mediaId media id
         * @param extras optional extra bundle
         * @see #COMMAND_CODE_PLAY_FROM_MEDIA_ID
         */
        public void onPlayFromMediaId(@NonNull ControllerInfo controller,
                @NonNull String mediaId, @Nullable Bundle extras) { }

        /**
         * Called when a controller requested to begin playback from a search query through
         * {@link MediaController2#playFromSearch(String, Bundle)}
         * <p>
         * An empty query indicates that the app may play any music. The implementation should
         * attempt to make a smart choice about what to play.
         *
         * @param controller controller information
         * @param query query string. Can be empty to indicate any suggested media
         * @param extras optional extra bundle
         * @see #COMMAND_CODE_PLAY_FROM_SEARCH
         */
        public void onPlayFromSearch(@NonNull ControllerInfo controller,
                @NonNull String query, @Nullable Bundle extras) { }

        /**
         * Called when a controller requested to play a specific media item represented by a URI
         * through {@link MediaController2#playFromUri(Uri, Bundle)}
         *
         * @param controller controller information
         * @param uri uri
         * @param extras optional extra bundle
         * @see #COMMAND_CODE_PLAY_FROM_URI
         */
        public void onPlayFromUri(@NonNull ControllerInfo controller,
                @NonNull Uri uri, @Nullable Bundle extras) { }

        /**
         * Called when a controller requested to prepare for playing a specific mediaId through
         * {@link MediaController2#prepareFromMediaId(String, Bundle)}.
         * <p>
         * During the preparation, a session should not hold audio focus in order to allow other
         * sessions play seamlessly. The state of playback should be updated to
         * {@link PlaybackState#STATE_PAUSED} after the preparation is done.
         * <p>
         * The playback of the prepared content should start in the later calls of
         * {@link MediaSession2#play()}.
         * <p>
         * Override {@link #onPlayFromMediaId} to handle requests for starting
         * playback without preparation.
         *
         * @param controller controller information
         * @param mediaId media id to prepare
         * @param extras optional extra bundle
         * @see #COMMAND_CODE_PREPARE_FROM_MEDIA_ID
         */
        public void onPrepareFromMediaId(@NonNull ControllerInfo controller,
                @NonNull String mediaId, @Nullable Bundle extras) { }

        /**
         * Called when a controller requested to prepare playback from a search query through
         * {@link MediaController2#prepareFromSearch(String, Bundle)}.
         * <p>
         * An empty query indicates that the app may prepare any music. The implementation should
         * attempt to make a smart choice about what to play.
         * <p>
         * The state of playback should be updated to {@link PlaybackState#STATE_PAUSED} after the
         * preparation is done. The playback of the prepared content should start in the later
         * calls of {@link MediaSession2#play()}.
         * <p>
         * Override {@link #onPlayFromSearch} to handle requests for starting playback without
         * preparation.
         *
         * @param controller controller information
         * @param query query string. Can be empty to indicate any suggested media
         * @param extras optional extra bundle
         * @see #COMMAND_CODE_PREPARE_FROM_SEARCH
         */
        public void onPrepareFromSearch(@NonNull ControllerInfo controller,
                @NonNull String query, @Nullable Bundle extras) { }

        /**
         * Called when a controller requested to prepare a specific media item represented by a URI
         * through {@link MediaController2#prepareFromUri(Uri, Bundle)}.
         * <p></p>
         * During the preparation, a session should not hold audio focus in order to allow
         * other sessions play seamlessly. The state of playback should be updated to
         * {@link PlaybackState#STATE_PAUSED} after the preparation is done.
         * <p>
         * The playback of the prepared content should start in the later calls of
         * {@link MediaSession2#play()}.
         * <p>
         * Override {@link #onPlayFromUri} to handle requests for starting playback without
         * preparation.
         *
         * @param controller controller information
         * @param uri uri
         * @param extras optional extra bundle
         * @see #COMMAND_CODE_PREPARE_FROM_URI
         */
        public void onPrepareFromUri(@NonNull ControllerInfo controller,
                @NonNull Uri uri, @Nullable Bundle extras) { }
    };

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
         * Set volume provider to configure this session to use remote volume handling.
         * This must be called to receive volume button events, otherwise the system
         * will adjust the appropriate stream volume for this session's player.
         * <p>
         * Set {@code null} to reset.
         *
         * @param volumeProvider The provider that will handle volume changes. Can be {@code null}.
         */
        U setVolumeProvider(@Nullable VolumeProvider2 volumeProvider) {
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
        public Builder(Context context, @NonNull MediaPlayerBase player) {
            super((instance) -> ApiLoader.getProvider(context).createMediaSession2Builder(
                    context, (Builder) instance, player));
        }

        public Builder(Context context, @NonNull MediaPlayerBase player,
                @NonNull MediaPlaylistController mplc) {
            //TODO use the MediaPlaylistController
            super((instance) -> ApiLoader.getProvider(context).createMediaSession2Builder(
                    context, (Builder) instance, player));
            if (mplc == null) {
                throw new IllegalArgumentException("Illegal null PlaylistController");
            }
        }

        @Override
        public Builder setVolumeProvider(@Nullable VolumeProvider2 volumeProvider) {
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
        // TODO(jaewan): Also accept componentName to check notificaiton listener.
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
            if (!(obj instanceof ControllerInfo)) {
                return false;
            }
            ControllerInfo other = (ControllerInfo) obj;
            return mProvider.equals_impl(other.mProvider);
        }

        @Override
        public String toString() {
            // TODO(jaewan): Move this to updatable.
            return "ControllerInfo {pkg=" + getPackageName() + ", uid=" + getUid() + ", trusted="
                    + isTrusted() + "}";
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
        public @Nullable Bundle getExtra() {
            return mProvider.getExtra_impl();
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

            public Builder setExtra(Bundle extra) {
                return mProvider.setExtra_impl(extra);
            }

            public CommandButton build() {
                return mProvider.build_impl();
            }
        }
    }

    /**
     * Parameter for the playlist.
     */
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
     *       {@link MediaSession} can be available after all of {@link MediaSession#setFlags(int)},
     *       {@link MediaSession#setCallback(Callback)}, and
     *       {@link MediaSession#setActive(boolean)}. It was common for an app to omit one, so
     *       framework had to add heuristics to figure out if an app is
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
     * Set the underlying {@link MediaPlayerBase} for this session to dispatch incoming event
     * to. Events from the {@link MediaController2} will be sent directly to the underlying
     * player on the {@link Handler} where the session is created on.
     * <p>
     * For the remote playback case which you want to handle volume by yourself, use
     * {@link #setPlayer(MediaPlayerBase, VolumeProvider2)}.
     *
     * @param player a {@link MediaPlayerBase} that handles actual media playback in your app.
     * @throws IllegalArgumentException if the player is {@code null}.
     */
    public void setPlayer(@NonNull MediaPlayerBase player) {
        mProvider.setPlayer_impl(player);
    }

    /**
     * Set the underlying {@link MediaPlayerBase} with the volume provider for remote playback.
     *
     * @param player a {@link MediaPlayerBase} that handles actual media playback in your app.
     * @param volumeProvider a volume provider
     * @see #setPlayer(MediaPlayerBase)
     * @see Builder#setVolumeProvider(VolumeProvider2)
     */
    public void setPlayer(@NonNull MediaPlayerBase player,
            @NonNull VolumeProvider2 volumeProvider) {
        mProvider.setPlayer_impl(player, volumeProvider);
    }

    @Override
    public void close() {
        mProvider.close_impl();
    }

    /**
     * @return player
     */
    public @Nullable
    MediaPlayerBase getPlayer() {
        return mProvider.getPlayer_impl();
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
        // TODO: implement this
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
     * This API can be called in the {@link SessionCallback#onConnect(ControllerInfo)}.
     *
     * @param controller controller to specify layout.
     * @param layout oredered list of layout.
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
     */
    public void play() {
        mProvider.play_impl();
    }

    /**
     * Pause playback
     */
    public void pause() {
        mProvider.pause_impl();
    }

    /**
     * Stop playback
     */
    public void stop() {
        mProvider.stop_impl();
    }

    /**
     * Rewind playback
     */
    public void skipToPrevious() {
        mProvider.skipToPrevious_impl();
    }

    /**
     * Rewind playback
     */
    public void skipToNext() {
        mProvider.skipToNext_impl();
    }

    /**
     * Request that the player prepare its playback. In other words, other sessions can continue
     * to play during the preparation of this session. This method can be used to speed up the
     * start of the playback. Once the preparation is done, the session will change its playback
     * state to {@link PlaybackState#STATE_PAUSED}. Afterwards, {@link #play} can be called to
     * start playback.
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
     * Skip to the item in the play list.
     *
     * @param item item in the play list you want to play
     * @throws IllegalArgumentException if the play list is null
     * @throws NullPointerException if index is outside play list range
     */
    public void skipToPlaylistItem(MediaItem2 item) {
        mProvider.skipToPlaylistItem_impl(item);
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
     * Set a list of {@link MediaItem2} as the current play list.
     *
     * @param playlist A list of {@link MediaItem2} objects to set as a play list.
     * @throws IllegalArgumentException if given {@param playlist} is null.
     */
    public void setPlaylist(@NonNull List<MediaItem2> playlist) {
        mProvider.setPlaylist_impl(playlist);
    }

    /**
     * Remove the media item at index in the play list.
     * <p>
     * If index is same as the current index of the playlist, current playback
     * will be stopped and playback moves to next source in the list.
     *
     * @throws IllegalArgumentException if the play list is null
     */
    // TODO(jaewan): Remove with index was previously rejected by council (b/36524925)
    // TODO(jaewan): Should we also add movePlaylistItem from index to index?
    public void removePlaylistItem(MediaItem2 item) {
        mProvider.removePlaylistItem_impl(item);
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
    @Override
    public void addPlaylistItem(int index, @NonNull MediaItem2 item) {
        mProvider.addPlaylistItem_impl(index, item);
    }

    /**
     * Edit the media item to the play list at position index. This is expected to be called when
     * the metadata information is updated.
     * <p>
     * This will not change the currently playing media item.
     *
     * @param item the media item you want to add to the play list
     */
    public void editPlaylistItem(@NonNull MediaItem2 item) {
        mProvider.editPlaylistItem_impl(item);
    }

    /**
     * Replace the media item at index in the playlist.
     * @param index the index of the item to replace
     * @param item the new item
     */
    @Override
    public void replacePlaylistItem(int index, @NonNull MediaItem2 item) {
        mProvider.replacePlaylistItem_impl(index, item);
    }

    /**
     * Return the playlist which is lastly set.
     *
     * @return playlist
     */
    @Override
    public List<MediaItem2> getPlaylist() {
        return mProvider.getPlaylist_impl();
    }

    /**
     * Return currently playing media item.
     *
     * @return currently playing media item
     */
    @Override
    public MediaItem2 getCurrentPlaylistItem() {
        return mProvider.getCurrentPlaylistItem_impl();
    }

    /**
     * Sets the {@link PlaylistParams} for the current play list. Repeat/shuffle mode and metadata
     * for the list can be set by calling this method.
     *
     * @param params A {@link PlaylistParams} object to set.
     * @throws IllegalArgumentException if given {@param param} is null.
     */
    public void setPlaylistParams(PlaylistParams params) {
        mProvider.setPlaylistParams_impl(params);
    }

    /**
     * Returns the {@link PlaylistParams} for the current play list.
     * Returns {@code null} if not set.
     */
    public PlaylistParams getPlaylistParams() {
        return mProvider.getPlaylistParams_impl();
    }

    /**
     * Notify errors to the connected controllers
     *
     * @param errorCode error code
     * @param extra extra
     */
    public void notifyError(@ErrorCode int errorCode, int extra) {
        mProvider.notifyError_impl(errorCode, extra);
    }

    /**
     * Register {@link EventCallback} to listen changes in the underlying
     * {@link MediaPlayerBase}, regardless of the change in the underlying player.
     * <p>
     * Registered callbacks will be also called when the underlying player is changed.
     *
     * @param executor a callback Executor
     * @param callback a EventCallback
     * @throws IllegalArgumentException if executor or callback is {@code null}.
     * @hide
     */
    // TODO(jaewan): Unhide or remove
    public void registerPlayerEventCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull PlayerEventCallback callback) {
        mProvider.registerPlayerEventCallback_impl(executor, callback);
    }

    /**
     * Unregister the previously registered {@link EventCallback}.
     *
     * @param callback the callback to be removed
     * @throws IllegalArgumentException if the callback is {@code null}.
     * @hide
     */
    // TODO(jaewan): Unhide or remove
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
}
