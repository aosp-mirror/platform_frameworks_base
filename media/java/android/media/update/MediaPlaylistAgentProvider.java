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

package android.media.update;

import android.media.DataSourceDesc;
import android.media.MediaItem2;
import android.media.MediaMetadata2;
import android.media.MediaPlaylistAgent.PlaylistEventCallback;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * @hide
 */
public interface MediaPlaylistAgentProvider {
    // final methods of MediaPlaylistAgent
    void registerPlaylistEventCallback_impl(Executor executor, PlaylistEventCallback callback);
    void unregisterPlaylistEventCallback_impl(PlaylistEventCallback callback);
    void notifyPlaylistChanged_impl();
    void notifyPlaylistMetadataChanged_impl();
    void notifyShuffleModeChanged_impl();
    void notifyRepeatModeChanged_impl();

    // public methods of MediaPlaylistAgent
    List<MediaItem2> getPlaylist_impl();
    void setPlaylist_impl(List<MediaItem2> list, MediaMetadata2 metadata);
    MediaMetadata2 getPlaylistMetadata_impl();
    void updatePlaylistMetadata_impl(MediaMetadata2 metadata);
    void addPlaylistItem_impl(int index, MediaItem2 item);
    void removePlaylistItem_impl(MediaItem2 item);
    void replacePlaylistItem_impl(int index, MediaItem2 item);
    void skipToPlaylistItem_impl(MediaItem2 item);
    void skipToPreviousItem_impl();
    void skipToNextItem_impl();
    int getRepeatMode_impl();
    void setRepeatMode_impl(int repeatMode);
    int getShuffleMode_impl();
    void setShuffleMode_impl(int shuffleMode);
    MediaItem2 getMediaItem_impl(DataSourceDesc dsd);
}
