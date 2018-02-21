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

import android.os.Bundle;

/**
 * @hide
 */
// TODO(jaewan): @SystemApi
public interface PlaybackState2Provider {
    String toString_impl();

    int getState_impl();

    long getPosition_impl();

    long getBufferedPosition_impl();

    float getPlaybackSpeed_impl();

    long getLastPositionUpdateTime_impl();

    long getCurrentPlaylistItemIndex_impl();

    Bundle toBundle_impl();
}
