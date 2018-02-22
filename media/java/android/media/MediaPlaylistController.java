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

import java.util.List;

/**
 * Controller interfaces for playlist management for both {@link MediaSession2} and
 * {@link MediaController2} that related with metadata. This ensures that two classes share the same
 * interface.
 * <p>
 * This class only includes methods that involves {@link MediaItem2}. Because other APIs are
 * considered as the part of {@link MediaPlayerBase} (e.g. set/getPlaylistParams()}. Note that
 * setPlaylist() isn't added on purpose because it's considered as session specific.
 *
 * @hide
 */
public interface MediaPlaylistController {
    // TODO(jaewan): is Index correct here?
    void addPlaylistItem(int index, @NonNull MediaItem2 item);
    void removePlaylistItem(@NonNull MediaItem2 item);
    MediaItem2 getCurrentPlaylistItem();
    void skipToPlaylistItem(@NonNull MediaItem2 item);
    List<MediaItem2> getPlaylist();
}
