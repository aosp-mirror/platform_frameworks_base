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
 *
 * @hide 
 */
public abstract class DisplayList {
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
     * Invalidates the display list, indicating that it should be repopulated
     * with new drawing commands prior to being used again. Calling this method
     * causes calls to {@link #isValid()} to return <code>false</code>.
     */
    abstract void invalidate();

    /**
     * Returns whether the display list is currently usable. If this returns false,
     * the display list should be re-recorded prior to replaying it.
     *
     * @return boolean true if the display list is able to be replayed, false otherwise.
     */
    abstract boolean isValid();
}
