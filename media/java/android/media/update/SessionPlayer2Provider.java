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

import android.media.AudioAttributes;
import android.media.MediaItem2;
import android.media.MediaPlayer2;
import android.media.MediaPlayerInterface.PlaybackListener;
import android.media.MediaSession2.PlaylistParams;
import android.media.PlaybackState2;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * @hide
 */
public interface SessionPlayer2Provider {
    void play_impl();
    void prepare_impl();
    void pause_impl();
    void stop_impl();
    void skipToPrevious_impl();
    void skipToNext_impl();
    void seekTo_impl(long pos);
    void fastForward_impl();
    void rewind_impl();
    PlaybackState2 getPlaybackState_impl();
    void setAudioAttributes_impl(AudioAttributes attributes);
    AudioAttributes getAudioAttributes_impl();
    void addPlaylistItem_impl(int index, MediaItem2 item);
    void removePlaylistItem_impl(MediaItem2 item);
    void setPlaylist_impl(List<MediaItem2> playlist);
    List<MediaItem2> getPlaylist_impl();
    void setCurrentPlaylistItem_impl(int index);
    void setPlaylistParams_impl(PlaylistParams params);
    PlaylistParams getPlaylistParams_impl();
    void addPlaybackListener_impl(Executor executor, PlaybackListener listener);
    void removePlaybackListener_impl(PlaybackListener listener);
    MediaPlayer2 getPlayer_impl();
}
