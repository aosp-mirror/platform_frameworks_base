/**
 * Copyright (c) 2013, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.view;

import android.view.GraphicBuffer;

/**
 * Programming interface to the system assets atlas. This atlas, when
 * present, holds preloaded drawable in a single, shareable graphics
 * buffer. This allows multiple processes to share the same data to
 * save up on memory.
 *
 * @hide
 */
interface IAssetAtlas {
    /**
     * Indicates whether the atlas is compatible with the specified
     * parent process id. If the atlas' ppid does not match, this
     * method will return false.
     */
    boolean isCompatible(int ppid);

    /**
     * Returns the atlas buffer (texture) or null if the atlas is
     * not available yet.
     */
    GraphicBuffer getBuffer();

    /**
     * Returns the map of the bitmaps stored in the atlas or null
     * if the atlas is not available yet.
     *
     * Each bitmap is represented by several entries in the array:
     * long0: SkBitmap*, the native bitmap object
     * long1: x position
     * long2: y position
     * long3: rotated, 1 if the bitmap must be rotated, 0 otherwise
     */
    long[] getMap();
}
