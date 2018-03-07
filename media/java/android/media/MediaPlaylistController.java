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
import android.media.MediaSession2.PlaylistParams;
import android.media.MediaSession2.PlaylistParams.RepeatMode;
import android.media.MediaSession2.PlaylistParams.ShuffleMode;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Controller interface for playlist management.
 * Playlists are composed of one or multiple {@link MediaItem2} instances, which combine metadata
 * and data sources (as {@link DataSourceDesc})
 * Used by {@link MediaSession2} and {@link MediaController2}.
 */
 // This class only includes methods that contain {@link MediaItem2}.
 // Note that setPlaylist() isn't added on purpose because it's considered session-specific.

public interface MediaPlaylistController {
    /**
     * @hide
     */
    @IntDef({REPEAT_MODE_NONE, REPEAT_MODE_ONE, REPEAT_MODE_ALL,
            REPEAT_MODE_GROUP})
    @Retention(RetentionPolicy.SOURCE)
    @interface RepeatMode {}

    /**
     * Playback will be stopped at the end of the playing media list.
     */
    int REPEAT_MODE_NONE = 0;

    /**
     * Playback of the current playing media item will be repeated.
     */
    int REPEAT_MODE_ONE = 1;

    /**
     * Playing media list will be repeated.
     */
    int REPEAT_MODE_ALL = 2;

    /**
     * Playback of the playing media group will be repeated.
     * A group is a logical block of media items which is specified in the section 5.7 of the
     * Bluetooth AVRCP 1.6. An example of a group is the playlist.
     */
    int REPEAT_MODE_GROUP = 3;

    /**
     * @hide
     */
    @IntDef({SHUFFLE_MODE_NONE, SHUFFLE_MODE_ALL, SHUFFLE_MODE_GROUP})
    @Retention(RetentionPolicy.SOURCE)
    @interface ShuffleMode {}

    /**
     * Media list will be played in order.
     */
    int SHUFFLE_MODE_NONE = 0;

    /**
     * Media list will be played in shuffled order.
     */
    int SHUFFLE_MODE_ALL = 1;

    /**
     * Media group will be played in shuffled order.
     * A group is a logical block of media items which is specified in the section 5.7 of the
     * Bluetooth AVRCP 1.6. An example of a group is the playlist.
     */
    int SHUFFLE_MODE_GROUP = 2;

    abstract class PlaylistEventCallback {
        /**
         * Called when a playlist is changed.
         *
         * @param mplc playlist controller for this event
         * @param list new playlist
         * @param metadata new metadata
         */
        public void onPlaylistChanged(@NonNull MediaPlaylistController mplc,
                @NonNull List<MediaItem2> list, @Nullable MediaMetadata2 metadata) { }

        /**
         * Called when a playlist is changed.
         *
         * @param mplc playlist controller for this event
         * @param metadata new metadata
         */
        public void onPlaylistMetadataChanged(@NonNull MediaPlaylistController mplc,
                @Nullable MediaMetadata2 metadata) { }

        /**
         * Called when a playlist is changed.
         *
         * @param mplc playlist controller for this event
         * @param shuffleMode repeat mode
         * @see #SHUFFLE_MODE_NONE
         * @see #SHUFFLE_MODE_ALL
         * @see #SHUFFLE_MODE_GROUP
         */
        public void onShuffleModeChanged(@NonNull MediaPlaylistController mplc,
                @ShuffleMode int shuffleMode) { }

        /**
         * Called when a playlist is changed.
         *
         * @param mplc playlist controller for this event
         * @param repeatMode repeat mode
         * @see #REPEAT_MODE_NONE
         * @see #REPEAT_MODE_ONE
         * @see #REPEAT_MODE_ALL
         * @see #REPEAT_MODE_GROUP
         */
        public void onRepeatModeChanged(@NonNull MediaPlaylistController mplc,
                @RepeatMode int repeatMode) { }
    }

    /**
     * Register {@link PlaylistEventCallback} to listen changes in the underlying
     * {@link MediaPlaylistController}, regardless of the change in the controller.
     *
     * @param executor a callback Executor
     * @param callback a PlaylistEventCallback
     * @throws IllegalArgumentException if executor or callback is {@code null}.
     */
    void registerPlaylistControllerCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull PlaylistEventCallback callback);

    /**
     * Unregister the previously registered {@link PlaylistEventCallback}.
     *
     * @param callback the callback to be removed
     * @throws IllegalArgumentException if the callback is {@code null}.
     */
    void unregisterPlaylistControllerCallback(@NonNull PlaylistEventCallback callback);

    /**
     * Returns the playlist
     *
     * @return playlist, or null if none is set.
     */
    @Nullable List<MediaItem2> getPlaylist();

    /**
     * Sets the playlist.
     *
     * @param list playlist
     * @param metadata metadata of the playlist
     */
    void setPlaylist(@NonNull List<MediaItem2> list, @Nullable MediaMetadata2 metadata);

    /**
     * Returns the playlist metadata
     *
     * @return metadata metadata of the playlist, or null if none is set
     */
    @Nullable MediaMetadata2 getPlaylistMetadata();

    /**
     * Updates the playlist metadata
     *
     * @param metadata metadata of the playlist
     */
    void updatePlaylistMetadata(@Nullable MediaMetadata2 metadata);

    /**
     * Adds the media item to the playlist at the index
     *
     * @param index index
     * @param item media item to add
     */
    void addPlaylistItem(int index, @NonNull MediaItem2 item);

    /**
     * Removes the media item from the playlist
     *
     * @param item media item to remove
     */
    void removePlaylistItem(@NonNull MediaItem2 item);

    /**
     * Replaces the media item with the .
     * <p>
     * This can be used to update metadata of a MediaItem.
     *
     * @param index index
     * @param item
     */
    void replacePlaylistItem(int index, @NonNull MediaItem2 item);

    /**
     * Returns the current media item.
     * @return the current media item, or null if none is set, or none available to play.
     */
    MediaItem2 getCurrentPlaylistItem();

    /**
     * Skips to the the media item, and plays from it.
     *
     * @param item media item to start playing from
     */
    void skipToPlaylistItem(@NonNull MediaItem2 item);

    /**
     * Get repeat mode
     *
     * @return repeat mode
     * @see #REPEAT_MODE_NONE
     * @see #REPEAT_MODE_ONE
     * @see #REPEAT_MODE_ALL
     * @see #REPEAT_MODE_GROUP
     */
    @RepeatMode int getRepeatMode();

    /**
     * Set repeat mode
     *
     * @param repeatMode repeat mode
     * @see #REPEAT_MODE_NONE
     * @see #REPEAT_MODE_ONE
     * @see #REPEAT_MODE_ALL
     * @see #REPEAT_MODE_GROUP
     */
    void setRepeatMode(@RepeatMode int repeatMode);

    /**
     * Get shuffle mode
     *
     * @return shuffle mode
     * @see #SHUFFLE_MODE_NONE
     * @see #SHUFFLE_MODE_ALL
     * @see #SHUFFLE_MODE_GROUP
     */
    @ShuffleMode int getShuffleMode();

    /**
     * Set shuffle mode
     *
     * @param shuffleMode shuffle mode
     * @see #SHUFFLE_MODE_NONE
     * @see #SHUFFLE_MODE_ALL
     * @see #SHUFFLE_MODE_GROUP
     */
    void setShuffleMode(@ShuffleMode int shuffleMode);
}
