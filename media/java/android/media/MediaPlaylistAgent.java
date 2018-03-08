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
import android.content.Context;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * MediaPlaylistAgent is the abstract class an application needs to derive from to pass an object
 * to a MediaSession2 that will override default playlist handling behaviors. It contains a set of
 * notify methods to signal MediaSession2 that playlist-related state has changed.
 * <p>
 * Playlists are composed of one or multiple {@link MediaItem2} instances, which combine metadata
 * and data sources (as {@link DataSourceDesc})
 * Used by {@link MediaSession2} and {@link MediaController2}.
 */
// This class only includes methods that contain {@link MediaItem2}.
public abstract class MediaPlaylistAgent {
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
     * Bluetooth AVRCP 1.6. An example of a group is the playlist.
     */
    public static final int REPEAT_MODE_GROUP = 3;

    /**
     * @hide
     */
    @IntDef({SHUFFLE_MODE_NONE, SHUFFLE_MODE_ALL, SHUFFLE_MODE_GROUP})
    @Retention(RetentionPolicy.SOURCE)
    @interface ShuffleMode {}

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
     * Bluetooth AVRCP 1.6. An example of a group is the playlist.
     */
    public static final int SHUFFLE_MODE_GROUP = 2;

    private MediaPlayerBase mPlayer;

    /**
     * A callback class to receive notifications for events on the media player. See
     * {@link MediaPlaylistAgent#registerPlaylistEventCallback(Executor, PlaylistEventCallback)}
     * to register this callback.
     */
    public static abstract class PlaylistEventCallback {
        /**
         * Called when a playlist is changed.
         *
         * @param playlistAgent playlist agent for this event
         * @param list new playlist
         * @param metadata new metadata
         */
        public void onPlaylistChanged(@NonNull MediaPlaylistAgent playlistAgent,
                @NonNull List<MediaItem2> list, @Nullable MediaMetadata2 metadata) { }

        /**
         * Called when a playlist metadata is changed.
         *
         * @param playlistAgent playlist agent for this event
         * @param metadata new metadata
         */
        public void onPlaylistMetadataChanged(@NonNull MediaPlaylistAgent playlistAgent,
                @Nullable MediaMetadata2 metadata) { }

        /**
         * Called when the shuffle mode is changed.
         *
         * @param playlistAgent playlist agent for this event
         * @param shuffleMode repeat mode
         * @see #SHUFFLE_MODE_NONE
         * @see #SHUFFLE_MODE_ALL
         * @see #SHUFFLE_MODE_GROUP
         */
        public void onShuffleModeChanged(@NonNull MediaPlaylistAgent playlistAgent,
                @ShuffleMode int shuffleMode) { }

        /**
         * Called when the repeat mode is changed.
         *
         * @param playlistAgent playlist agent for this event
         * @param repeatMode repeat mode
         * @see #REPEAT_MODE_NONE
         * @see #REPEAT_MODE_ONE
         * @see #REPEAT_MODE_ALL
         * @see #REPEAT_MODE_GROUP
         */
        public void onRepeatModeChanged(@NonNull MediaPlaylistAgent playlistAgent,
                @RepeatMode int repeatMode) { }
    }

    public MediaPlaylistAgent(@NonNull Context context) {
        // FYI, Need to have a context in the constructor for making this class be updatable
        // TODO(jaewan) : implement this (b/74090741)
    }

    /**
     * Register {@link PlaylistEventCallback} to listen changes in the underlying
     * {@link MediaPlaylistAgent}.
     *
     * @param executor a callback Executor
     * @param callback a PlaylistEventCallback
     * @throws IllegalArgumentException if executor or callback is {@code null}.
     */
    public final void registerPlaylistEventCallback(
            @NonNull @CallbackExecutor Executor executor, @NonNull PlaylistEventCallback callback) {
        // TODO(jaewan): implement this (b/74090741)
    }

    /**
     * Unregister the previously registered {@link PlaylistEventCallback}.
     *
     * @param callback the callback to be removed
     * @throws IllegalArgumentException if the callback is {@code null}.
     */
    public final void unregisterPlaylistEventCallback(
            @NonNull PlaylistEventCallback callback) {
        // TODO(jaewan): implement this (b/74090741)
    }

    public final void notifyPlaylistChanged() {
        // TODO(jaewan): implement this (b/74090741)
    }

    public final void notifyPlaylistMetadataChanged() {
        // TODO(jaewan): implement this (b/74090741)
    }

    public final void notifyShuffleModeChanged() {
        // TODO(jaewan): implement this (b/74090741)
    }

    public final void notifyRepeatModeChanged() {
        // TODO(jaewan): implement this (b/74090741)
    }

    /**
     * Returns the playlist
     *
     * @return playlist, or null if none is set.
     */
    public @Nullable List<MediaItem2> getPlaylist() {
        // TODO(jaewan): implement this (b/74090741)
        return null;
    }

    /**
     * Sets the playlist.
     *
     * @param list playlist
     * @param metadata metadata of the playlist
     */
    public void setPlaylist(@NonNull List<MediaItem2> list, @Nullable MediaMetadata2 metadata) {
        // TODO(jaewan): implement this (b/74090741)
    }

    /**
     * Returns the playlist metadata
     *
     * @return metadata metadata of the playlist, or null if none is set
     */
    public @Nullable MediaMetadata2 getPlaylistMetadata() {
        // TODO(jaewan): implement this (b/74090741)
        return null;
    }

    /**
     * Updates the playlist metadata
     *
     * @param metadata metadata of the playlist
     */
    public void updatePlaylistMetadata(@Nullable MediaMetadata2 metadata) {
        // TODO(jaewan): implement this (b/74090741)
    }

    /**
     * Adds the media item to the playlist at the index
     *
     * @param index index
     * @param item media item to add
     */
    public void addPlaylistItem(int index, @NonNull MediaItem2 item) {
        // TODO(jaewan): implement this (b/74090741)
    }

    /**
     * Removes the media item from the playlist
     *
     * @param item media item to remove
     */
    public void removePlaylistItem(@NonNull MediaItem2 item) {
        // TODO(jaewan): implement this (b/74090741)
    }

    /**
     * Replace the media item at index in the playlist. This can be also used to update metadata of
     * an item.
     *
     * @param index the index of the item to replace
     * @param item the new item
     */
    public void replacePlaylistItem(int index, @NonNull MediaItem2 item) {
        // TODO(jaewan): implement this (b/74090741)
    }

    /**
     * Skips to the the media item, and plays from it.
     *
     * @param item media item to start playing from
     */
    public void skipToPlaylistItem(@NonNull MediaItem2 item) {
        // TODO(jaewan): implement this (b/74090741)
    }

    public void skipToPreviousItem() {
        // TODO(jaewan): implement this (b/74090741)
    }

    public void skipToNextItem() {
        // TODO(jaewan): implement this (b/74090741)
    }

    /**
     * Get repeat mode
     *
     * @return repeat mode
     * @see #REPEAT_MODE_NONE
     * @see #REPEAT_MODE_ONE
     * @see #REPEAT_MODE_ALL
     * @see #REPEAT_MODE_GROUP
     */
    public @RepeatMode int getRepeatMode() {
        // TODO(jaewan): implement this (b/74090741)
        return REPEAT_MODE_NONE;
    }

    /**
     * Set repeat mode
     *
     * @param repeatMode repeat mode
     * @see #REPEAT_MODE_NONE
     * @see #REPEAT_MODE_ONE
     * @see #REPEAT_MODE_ALL
     * @see #REPEAT_MODE_GROUP
     */
    public void setRepeatMode(@RepeatMode int repeatMode) {
        // TODO(jaewan): implement this (b/74090741)
    }

    /**
     * Get shuffle mode
     *
     * @return shuffle mode
     * @see #SHUFFLE_MODE_NONE
     * @see #SHUFFLE_MODE_ALL
     * @see #SHUFFLE_MODE_GROUP
     */
    public @ShuffleMode int getShuffleMode() {
        // TODO(jaewan): implement this (b/74090741)
        return SHUFFLE_MODE_NONE;
    }

    /**
     * Set shuffle mode
     *
     * @param shuffleMode shuffle mode
     * @see #SHUFFLE_MODE_NONE
     * @see #SHUFFLE_MODE_ALL
     * @see #SHUFFLE_MODE_GROUP
     */
    public void setShuffleMode(@ShuffleMode int shuffleMode) {
        // TODO(jaewan): implement this (b/74090741)
    }
}
