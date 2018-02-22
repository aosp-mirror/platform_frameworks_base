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

import android.media.MediaItem2;
import android.media.PlaybackState2;

/**
 * @hide
 */
public interface TransportControlProvider {
    void play_impl();
    void pause_impl();
    void stop_impl();
    void skipToPrevious_impl();
    void skipToNext_impl();

    void prepare_impl();
    void fastForward_impl();
    void rewind_impl();
    void seekTo_impl(long pos);
    void skipToPlaylistItem_impl(MediaItem2 item);

    PlaybackState2 getPlaybackState_impl();
}
