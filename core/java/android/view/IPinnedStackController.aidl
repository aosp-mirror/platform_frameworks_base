/**
 * Copyright (c) 2016, The Android Open Source Project
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

import android.graphics.Rect;

/**
 * An interface to the PinnedStackController to update it of state changes, and to query
 * information based on the current state.
 *
 * @hide
 */
interface IPinnedStackController {

    /**
     * Notifies the controller that the PiP is currently minimized.
     */
    oneway void setIsMinimized(boolean isMinimized);

    /**
     * @return what WM considers to be the current device rotation.
     */
    int getDisplayRotation();

    /**
     * Notifies the controller to actually start the PiP animation.
     * The bounds would be calculated based on the last save reentry fraction internally.
     * {@param destinationBounds} is the stack bounds of the final PiP window
     * and {@param sourceRectHint} is the source bounds hint used when entering picture-in-picture,
     * expect the same bound passed via IPinnedStackListener#onPrepareAnimation.
     * {@param animationDuration} suggests the animation duration transitioning to PiP window.
     */
    void startAnimation(in Rect destinationBounds, in Rect sourceRectHint, int animationDuration);
}
