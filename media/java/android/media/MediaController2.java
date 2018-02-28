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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.Context;
import android.media.MediaSession2.Command;
import android.media.MediaSession2.CommandButton;
import android.media.MediaSession2.CommandGroup;
import android.media.MediaSession2.ControllerInfo;
import android.media.MediaSession2.ErrorCode;
import android.media.MediaSession2.PlaylistParams;
import android.media.session.MediaSessionManager;
import android.media.update.ApiLoader;
import android.media.update.MediaController2Provider;
import android.media.update.MediaController2Provider.PlaybackInfoProvider;
import android.net.Uri;
import android.os.Bundle;
import android.os.ResultReceiver;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Allows an app to interact with an active {@link MediaSession2} or a
 * {@link MediaSessionService2} in any status. Media buttons and other commands can be sent to
 * the session.
 * <p>
 * When you're done, use {@link #close()} to clean up resources. This also helps session service
 * to be destroyed when there's no controller associated with it.
 * <p>
 * When controlling {@link MediaSession2}, the controller will be available immediately after
 * the creation.
 * <p>
 * When controlling {@link MediaSessionService2}, the {@link MediaController2} would be
 * available only if the session service allows this controller by
 * {@link MediaSession2.SessionCallback#onConnect(MediaSession2, ControllerInfo)} for the service.
 * Wait {@link ControllerCallback#onConnected(MediaController2, CommandGroup)} or
 * {@link ControllerCallback#onDisconnected(MediaController2)} for the result.
 * <p>
 * A controller can be created through token from {@link MediaSessionManager} if you hold the
 * signature|privileged permission "android.permission.MEDIA_CONTENT_CONTROL" permission or are
 * an enabled notification listener or by getting a {@link SessionToken2} directly the
 * the session owner.
 * <p>
 * MediaController2 objects are thread-safe.
 * <p>
 * @see MediaSession2
 * @see MediaSessionService2
 */
public class MediaController2 implements AutoCloseable, MediaPlaylistController {
    /**
     * Interface for listening to change in activeness of the {@link MediaSession2}.  It's
     * active if and only if it has set a player.
     */
    public abstract static class ControllerCallback {
        /**
         * Called when the controller is successfully connected to the session. The controller
         * becomes available afterwards.
         *
         * @param controller the controller for this event
         * @param allowedCommands commands that's allowed by the session.
         */
        public void onConnected(@NonNull MediaController2 controller,
                @NonNull CommandGroup allowedCommands) { }

        /**
         * Called when the session refuses the controller or the controller is disconnected from
         * the session. The controller becomes unavailable afterwards and the callback wouldn't
         * be called.
         * <p>
         * It will be also called after the {@link #close()}, so you can put clean up code here.
         * You don't need to call {@link #close()} after this.
         *
         * @param controller the controller for this event
         * @param controller controller for this event
         */
        public void onDisconnected(@NonNull MediaController2 controller) { }

        /**
         * Called when the session set the custom layout through the
         * {@link MediaSession2#setCustomLayout(ControllerInfo, List)}.
         * <p>
         * Can be called before {@link #onConnected(MediaController2, CommandGroup)} is called.
         *
         * @param controller the controller for this event
         * @param layout
         */
        public void onCustomLayoutChanged(@NonNull MediaController2 controller,
                @NonNull List<CommandButton> layout) { }

        /**
         * Called when the session has changed anything related with the {@link PlaybackInfo}.
         *
         * @param controller the controller for this event
         * @param info new playback info
         */
        public void onPlaybackInfoChanged(@NonNull MediaController2 controller,
                @NonNull PlaybackInfo info) { }

        /**
         * Called when the allowed commands are changed by session.
         *
         * @param controller the controller for this event
         * @param commands newly allowed commands
         */
        public void onAllowedCommandsChanged(@NonNull MediaController2 controller,
                @NonNull CommandGroup commands) { }

        /**
         * Called when the session sent a custom command.
         *
         * @param controller the controller for this event
         * @param command
         * @param args
         * @param receiver
         */
        public void onCustomCommand(@NonNull MediaController2 controller,
                @NonNull Command command, @Nullable Bundle args,
                @Nullable ResultReceiver receiver) { }

        /**
         * Called when the playlist is changed.
         * <p>
         * If the previously playing media item is gone, you should invalidate previous playback
         * information and wait for later callbacks.
         *
         * @param controller the controller for this event
         * @param playlist A new playlist set by the session.
         * @see #onPositionChanged(MediaController2, long, long)
         * @see #onBufferedPositionChanged(MediaController2, long)
         * @see #onCurrentPlaylistItemChanged(MediaController2, MediaItem2)
         */
        public void onPlaylistChanged(@NonNull MediaController2 controller,
                @NonNull List<MediaItem2> playlist) { }

        /**
         * Called when the playback state is changed.
         *
         * @param controller the controller for this event
         * @param state latest playback state
         * @hide
         */
        // TODO(jaewan): Remove (b/73971431)
        public void onPlaybackStateChanged(@NonNull MediaController2 controller,
                @NonNull PlaybackState2 state) { }

        /**
         * Called when the player state is changed.
         *
         * @param controller the controller for this event
         * @param state
         */
        public void onPlayerStateChanged(@NonNull MediaController2 controller, int state) { }

        /**
         * Called when the player's position is changed
         *
         * @param controller the controller for this event
         * @param eventTimeMs timestamp when the position information is sent from the session
         * @param positionMs position in millis
         */
        public void onPositionChanged(@NonNull MediaController2 controller,
                long eventTimeMs, long positionMs) { }

        /**
         * Called when playback speed is changed.
         *
         * @param controller the controller for this event
         * @param speed speed
         */
        public void onPlaybackSpeedChanged(@NonNull MediaController2 controller,
                float speed) { }

        /**
         * Called when the player's buffering position
         *
         * @param controller the controller for this event
         * @param positionMs buffering position in millis
         */
        public void onBufferedPositionChanged(@NonNull MediaController2 controller,
                long positionMs) { }

        /**
         * Called when a error from
         *
         * @param controller the controller for this event
         * @param errorCode error code
         * @param extras extra information
         */
        public void onError(@NonNull MediaController2 controller, @ErrorCode int errorCode,
                @Nullable Bundle extras) { }

        /**
         * Called when the player's current playing item is changed
         * <p>
         * When it's called, you should invalidate previous playback information and wait for later
         * callbacks.
         *
         * @param controller the controller for this event
         * @param item new item
         * @see #onPositionChanged(MediaController2, long, long)
         * @see #onBufferedPositionChanged(MediaController2, long)
         * @see #onCurrentPlaylistItemChanged(MediaController2, MediaItem2)
         */
        public void onCurrentPlaylistItemChanged(@NonNull MediaController2 controller,
                @NonNull MediaItem2 item) { }

        /**
         * Called when the playlist parameters are changed.
         *
         * @param controller the controller for this event
         * @param params The new play list parameters.
         */
        public void onPlaylistParamsChanged(@NonNull MediaController2 controller,
                @NonNull PlaylistParams params) { }
    }

    /**
     * Holds information about the current playback and how audio is handled for
     * this session.
     */
    // The same as MediaController.PlaybackInfo
    public static final class PlaybackInfo {
        /**
         * The session uses remote playback.
         */
        public static final int PLAYBACK_TYPE_REMOTE = 2;
        /**
         * The session uses local playback.
         */
        public static final int PLAYBACK_TYPE_LOCAL = 1;

        private final PlaybackInfoProvider mProvider;

        /**
         * @hide
         */
        public PlaybackInfo(PlaybackInfoProvider provider) {
            mProvider = provider;
        }

        /**
         * @hide
         */
        public PlaybackInfoProvider getProvider() {
            return mProvider;
        }

        /**
         * Get the type of playback which affects volume handling. One of:
         * <ul>
         * <li>{@link #PLAYBACK_TYPE_LOCAL}</li>
         * <li>{@link #PLAYBACK_TYPE_REMOTE}</li>
         * </ul>
         *
         * @return The type of playback this session is using.
         */
        public int getPlaybackType() {
            return mProvider.getPlaybackType_impl();
        }

        /**
         * Get the audio attributes for this session. The attributes will affect
         * volume handling for the session. When the volume type is
         * {@link PlaybackInfo#PLAYBACK_TYPE_REMOTE} these may be ignored by the
         * remote volume handler.
         *
         * @return The attributes for this session.
         */
        public AudioAttributes getAudioAttributes() {
            return mProvider.getAudioAttributes_impl();
        }

        /**
         * Get the type of volume control that can be used. One of:
         * <ul>
         * <li>{@link VolumeProvider2#VOLUME_CONTROL_ABSOLUTE}</li>
         * <li>{@link VolumeProvider2#VOLUME_CONTROL_RELATIVE}</li>
         * <li>{@link VolumeProvider2#VOLUME_CONTROL_FIXED}</li>
         * </ul>
         *
         * @return The type of volume control that may be used with this session.
         */
        public int getControlType() {
            return mProvider.getControlType_impl();
        }

        /**
         * Get the maximum volume that may be set for this session.
         *
         * @return The maximum allowed volume where this session is playing.
         */
        public int getMaxVolume() {
            return mProvider.getMaxVolume_impl();
        }

        /**
         * Get the current volume for this session.
         *
         * @return The current volume where this session is playing.
         */
        public int getCurrentVolume() {
            return mProvider.getCurrentVolume_impl();
        }
    }

    private final MediaController2Provider mProvider;

    /**
     * Create a {@link MediaController2} from the {@link SessionToken2}.
     * This connects to the session and may wake up the service if it's not available.
     *
     * @param context Context
     * @param token token to connect to
     * @param executor executor to run callbacks on.
     * @param callback controller callback to receive changes in
     */
    public MediaController2(@NonNull Context context, @NonNull SessionToken2 token,
            @NonNull @CallbackExecutor Executor executor, @NonNull ControllerCallback callback) {
        super();

        mProvider = createProvider(context, token, executor, callback);
        // This also connects to the token.
        // Explicit connect() isn't added on purpose because retrying connect() is impossible with
        // session whose session binder is only valid while it's active.
        // prevent a controller from reusable after the
        // session is released and recreated.
        mProvider.initialize();
    }

    MediaController2Provider createProvider(@NonNull Context context,
            @NonNull SessionToken2 token, @NonNull Executor executor,
            @NonNull ControllerCallback callback) {
        return ApiLoader.getProvider(context)
                .createMediaController2(context, this, token, executor, callback);
    }

    /**
     * Release this object, and disconnect from the session. After this, callbacks wouldn't be
     * received.
     */
    @Override
    public void close() {
        mProvider.close_impl();
    }

    /**
     * @hide
     */
    public MediaController2Provider getProvider() {
        return mProvider;
    }

    /**
     * @return token
     */
    public @NonNull SessionToken2 getSessionToken() {
        return mProvider.getSessionToken_impl();
    }

    /**
     * Returns whether this class is connected to active {@link MediaSession2} or not.
     */
    public boolean isConnected() {
        return mProvider.isConnected_impl();
    }

    public void play() {
        mProvider.play_impl();
    }

    public void pause() {
        mProvider.pause_impl();
    }

    public void stop() {
        mProvider.stop_impl();
    }

    public void skipToPrevious() {
        mProvider.skipToPrevious_impl();
    }

    public void skipToNext() {
        mProvider.skipToNext_impl();
    }

    /**
     * Request that the player prepare its playback. In other words, other sessions can continue
     * to play during the preparation of this session. This method can be used to speed up the
     * start of the playback. Once the preparation is done, the session will change its playback
     * state to {@link MediaPlayerBase#PLAYER_STATE_PAUSED}. Afterwards, {@link #play} can be called
     * to start playback.
     */
    public void prepare() {
        mProvider.prepare_impl();
    }

    /**
     * Start fast forwarding. If playback is already fast forwarding this
     * may increase the rate.
     */
    public void fastForward() {
        mProvider.fastForward_impl();
    }

    /**
     * Start rewinding. If playback is already rewinding this may increase
     * the rate.
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
     * Sets the index of current DataSourceDesc in the play list to be played.
     *
     * @param item the index of DataSourceDesc in the play list you want to play
     * @throws IllegalArgumentException if the play list is null
     * @throws NullPointerException if index is outside play list range
     */
    @Override
    public void skipToPlaylistItem(@NonNull MediaItem2 item) {
        mProvider.skipToPlaylistItem_impl(item);
    }

    /**
     * Sets the {@link PlaylistParams} for the current play list. Repeat/shuffle mode and metadata
     * for the list can be set by calling this method.
     *
     * @param params A {@link PlaylistParams} object to set.
     * @throws IllegalArgumentException if given {@param param} is null.
     */
    public void setPlaylistParams(@NonNull PlaylistParams params) {
        mProvider.setPlaylistParams_impl(params);
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
     * Request that the player start playback for a specific media id.
     *
     * @param mediaId The id of the requested media.
     * @param extras Optional extras that can include extra information about the media item
     *               to be played.
     */
    public void playFromMediaId(@NonNull String mediaId, @Nullable Bundle extras) {
        mProvider.playFromMediaId_impl(mediaId, extras);
    }

    /**
     * Request that the player start playback for a specific search query.
     *
     * @param query The search query. Should not be an empty string.
     * @param extras Optional extras that can include extra information about the query.
     */
    public void playFromSearch(@NonNull String query, @Nullable Bundle extras) {
        mProvider.playFromSearch_impl(query, extras);
    }

    /**
     * Request that the player start playback for a specific {@link Uri}.
     *
     * @param uri The URI of the requested media.
     * @param extras Optional extras that can include extra information about the media item
     *               to be played.
     */
    public void playFromUri(@NonNull Uri uri, @Nullable Bundle extras) {
        mProvider.playFromUri_impl(uri, extras);
    }

    /**
     * Request that the player prepare playback for a specific media id. In other words, other
     * sessions can continue to play during the preparation of this session. This method can be
     * used to speed up the start of the playback. Once the preparation is done, the session
     * will change its playback state to {@link MediaPlayerBase#PLAYER_STATE_PAUSED}. Afterwards,
     * {@link #play} can be called to start playback. If the preparation is not needed,
     * {@link #playFromMediaId} can be directly called without this method.
     *
     * @param mediaId The id of the requested media.
     * @param extras Optional extras that can include extra information about the media item
     *               to be prepared.
     */
    public void prepareFromMediaId(@NonNull String mediaId, @Nullable Bundle extras) {
        mProvider.prepareFromMediaId_impl(mediaId, extras);
    }

    /**
     * Request that the player prepare playback for a specific search query.
     * In other words, other sessions can continue to play during the preparation of this session.
     * This method can be used to speed up the start of the playback.
     * Once the preparation is done, the session will change its playback state to
     * {@link MediaPlayerBase#PLAYER_STATE_PAUSED}. Afterwards,
     * {@link #play} can be called to start playback. If the preparation is not needed,
     * {@link #playFromSearch} can be directly called without this method.
     *
     * @param query The search query. Should not be an empty string.
     * @param extras Optional extras that can include extra information about the query.
     */
    public void prepareFromSearch(@NonNull String query, @Nullable Bundle extras) {
        mProvider.prepareFromSearch_impl(query, extras);
    }

    /**
     * Request that the player prepare playback for a specific {@link Uri}. In other words,
     * other sessions can continue to play during the preparation of this session. This method
     * can be used to speed up the start of the playback. Once the preparation is done, the
     * session will change its playback state to {@link MediaPlayerBase#PLAYER_STATE_PAUSED}.
     * Afterwards, {@link #play} can be called to start playback. If the preparation is not needed,
     * {@link #playFromUri} can be directly called without this method.
     *
     * @param uri The URI of the requested media.
     * @param extras Optional extras that can include extra information about the media item
     *               to be prepared.
     */
    public void prepareFromUri(@NonNull Uri uri, @Nullable Bundle extras) {
        mProvider.prepareFromUri_impl(uri, extras);
    }

    /**
     * Set the volume of the output this session is playing on. The command will be ignored if it
     * does not support {@link VolumeProvider2#VOLUME_CONTROL_ABSOLUTE}.
     * <p>
     * If the session is local playback, this changes the device's volume with the stream that
     * session's player is using. Flags will be specified for the {@link AudioManager}.
     * <p>
     * If the session is remote player (i.e. session has set volume provider), its volume provider
     * will receive this request instead.
     *
     * @see #getPlaybackInfo()
     * @param value The value to set it to, between 0 and the reported max.
     * @param flags flags from {@link AudioManager} to include with the volume request for local
     *              playback
     */
    public void setVolumeTo(int value, int flags) {
        mProvider.setVolumeTo_impl(value, flags);
    }

    /**
     * Adjust the volume of the output this session is playing on. The direction
     * must be one of {@link AudioManager#ADJUST_LOWER},
     * {@link AudioManager#ADJUST_RAISE}, or {@link AudioManager#ADJUST_SAME}.
     * The command will be ignored if the session does not support
     * {@link VolumeProvider2#VOLUME_CONTROL_RELATIVE} or
     * {@link VolumeProvider2#VOLUME_CONTROL_ABSOLUTE}.
     * <p>
     * If the session is local playback, this changes the device's volume with the stream that
     * session's player is using. Flags will be specified for the {@link AudioManager}.
     * <p>
     * If the session is remote player (i.e. session has set volume provider), its volume provider
     * will receive this request instead.
     *
     * @see #getPlaybackInfo()
     * @param direction The direction to adjust the volume in.
     * @param flags flags from {@link AudioManager} to include with the volume request for local
     *              playback
     */
    public void adjustVolume(int direction, int flags) {
        mProvider.adjustVolume_impl(direction, flags);
    }

    /**
     * Get an intent for launching UI associated with this session if one exists.
     *
     * @return A {@link PendingIntent} to launch UI or null.
     */
    public @Nullable PendingIntent getSessionActivity() {
        return mProvider.getSessionActivity_impl();
    }

    /**
     * Get the lastly cached {@link PlaybackState2} from
     * {@link ControllerCallback#onPlaybackStateChanged(MediaController2, PlaybackState2)}.
     * <p>
     * It may return {@code null} before the first callback or session has sent {@code null}
     * playback state.
     *
     * @return a playback state. Can be {@code null}
     * @hide
     */
    public @Nullable PlaybackState2 getPlaybackState() {
        return mProvider.getPlaybackState_impl();
    }

    /**
     * Get the lastly cached player state from
     * {@link ControllerCallback#onPlayerStateChanged(MediaController2, int)}.
     *
     * @return player state
     */
    public int getPlayerState() {
        return mProvider.getPlayerState_impl();
    }

    /**
     * Get the lastly cached position from
     * {@link ControllerCallback#onPositionChanged(MediaController2, long, long)}.
     * <p>
     * This returns the calculated value of the position, based on the difference between the
     * update time and current time.
     *
     * @return position
     */
    public long getPosition() {
        return mProvider.getPosition_impl();
    }

    /**
     * Get the lastly cached playback speed from
     * {@link ControllerCallback#onPlaybackSpeedChanged(MediaController2, float)}.
     *
     * @return speed
     */
    public float getPlaybackSpeed() {
        return mProvider.getPlaybackSpeed_impl();
    }

    /**
     * Set the playback speed.
     */
    public void setPlaybackSpeed(float speed) {
        // TODO(jaewan): implement this (b/74093080)
    }

    /**
     * Get the lastly cached buffered position from
     * {@link ControllerCallback#onBufferedPositionChanged(MediaController2, long)}.
     *
     * @return buffering position in millis
     */
    public long getBufferedPosition() {
        return mProvider.getBufferedPosition_impl();
    }

    /**
     * Get the lastly cached current item from
     * {@link ControllerCallback#onCurrentPlaylistItemChanged(MediaController2, MediaItem2)}.
     *
     * @return index of the current item
     */
    @Override
    public MediaItem2 getCurrentPlaylistItem() {
        return mProvider.getCurrentPlaylistItem_impl();
    }

    /**
     * Get the current playback info for this session.
     *
     * @return The current playback info or null.
     */
    public @Nullable PlaybackInfo getPlaybackInfo() {
        return mProvider.getPlaybackInfo_impl();
    }

    /**
     * Rate the media. This will cause the rating to be set for the current user.
     * The rating style must follow the user rating style from the session.
     * You can get the rating style from the session through the
     * {@link MediaMetadata#getRating(String)} with the key
     * {@link MediaMetadata#METADATA_KEY_USER_RATING}.
     * <p>
     * If the user rating was {@code null}, the media item does not accept setting user rating.
     *
     * @param mediaId The id of the media
     * @param rating The rating to set
     */
    public void setRating(@NonNull String mediaId, @NonNull Rating2 rating) {
        mProvider.setRating_impl(mediaId, rating);
    }

    /**
     * Send custom command to the session
     *
     * @param command custom command
     * @param args optional argument
     * @param cb optional result receiver
     */
    public void sendCustomCommand(@NonNull Command command, @Nullable Bundle args,
            @Nullable ResultReceiver cb) {
        mProvider.sendCustomCommand_impl(command, args, cb);
    }

    /**
     * Return playlist from the session.
     *
     * @return playlist. Can be {@code null} if the controller doesn't have enough permission.
     */
    @Override
    public @Nullable List<MediaItem2> getPlaylist() {
        return mProvider.getPlaylist_impl();
    }

    /**
     * Returns the {@link PlaylistParams} for the current play list.
     * Can return {@code null} if the controller doesn't have enough permission, or if the session
     * has not set the parameters.
     */
    public @Nullable PlaylistParams getPlaylistParams() {
        return mProvider.getPlaylistParams_impl();
    }

    /**
     * Removes the media item at index in the playlist.
     *<p>
     * If the item is the currently playing item of the playlist, current playback
     * will be stopped and playback moves to next source in the list.
     */
    @Override
    public void removePlaylistItem(@NonNull MediaItem2 item) {
        mProvider.removePlaylistItem_impl(item);
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
     * Inserts the media item to the play list at position index.
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
}
