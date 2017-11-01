/*
 * Copyright 2017 The Android Open Source Project
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
import android.media.VolumeShaper.Configuration;

/**
 * {@code VolumeAutomation} defines an interface for automatic volume control
 * of {@link AudioTrack} and {@link MediaPlayer} objects.
 */
public interface VolumeAutomation {
    /**
     * Returns a {@link VolumeShaper} object that can be used modify the volume envelope
     * of the player or track.
     *
     * @param configuration the {@link VolumeShaper.Configuration configuration}
     *        that specifies the curve and duration to use.
     * @return a {@code VolumeShaper} object
     * @throws IllegalArgumentException if the {@code configuration} is not allowed by the player.
     * @throws IllegalStateException if too many {@code VolumeShaper}s are requested
     *         or the state of the player does not permit its creation (e.g. player is released).
     */
    public @NonNull VolumeShaper createVolumeShaper(
            @NonNull VolumeShaper.Configuration configuration);
}
