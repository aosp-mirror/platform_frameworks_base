/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.view;

/**
 * A display lists records a series of graphics related operation and can replay
 * them later. Display lists are usually built by recording operations on a
 * {@link android.graphics.Canvas}. Replaying the operations from a display list
 * avoids executing views drawing code on every frame, and is thus much more
 * efficient. 
 */
abstract class DisplayList {
    /**
     * Starts recording the display list. All operations performed on the
     * returned canvas are recorded and stored in this display list.
     * 
     * @return A canvas to record drawing operations.
     */
    abstract HardwareCanvas start();

    /**
     * Ends the recording for this display list. A display list cannot be
     * replayed if recording is not finished. 
     */
    abstract void end();

    /**
     * Frees resources taken by this display list. This method must be called
     * before releasing all references.
     */
    abstract void destroy();

    /**
     * Indicates whether this display list can be replayed or not.
     * 
     * @return True if the display list can be replayed, false otherwise.
     * 
     * @see android.view.HardwareCanvas#drawDisplayList(DisplayList) 
     */
    abstract boolean isReady();
}
