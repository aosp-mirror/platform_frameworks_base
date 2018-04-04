/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wm;

import android.graphics.Rect;

/**
 * The target for a BoundsAnimation.
 * @see BoundsAnimationController
 */
interface BoundsAnimationTarget {

    /**
     * Callback for the target to inform it that the animation has started, so it can do some
     * necessary preparation.
     *
     * @param schedulePipModeChangedCallback whether or not to schedule the PiP mode changed
     * callbacks
     */
    void onAnimationStart(boolean schedulePipModeChangedCallback, boolean forceUpdate);

    /**
     * @return Whether the animation should be paused waiting for the windows to draw before
     *         entering PiP.
     */
    boolean shouldDeferStartOnMoveToFullscreen();

    /**
     * Sets the size of the target (without any intermediate steps, like scheduling animation)
     * but freezes the bounds of any tasks in the target at taskBounds, to allow for more
     * flexibility during resizing. Only works for the pinned stack at the moment.  This will
     * only be called between onAnimationStart() and onAnimationEnd().
     *
     * @return Whether the target should continue to be animated and this call was successful.
     * If false, the animation will be cancelled because the user has determined that the
     * animation is now invalid and not required. In such a case, the cancel will trigger the
     * animation end callback as well, but will not send any further size changes.
     */
    boolean setPinnedStackSize(Rect stackBounds, Rect taskBounds);

    /**
     * Callback for the target to inform it that the animation has ended, so it can do some
     * necessary cleanup.
     *
     * @param schedulePipModeChangedCallback whether or not to schedule the PiP mode changed
     * callbacks
     * @param finalStackSize the final stack bounds to set on the target (can be to indicate that
     * the animation was cancelled and the target does not need to update to the final stack bounds)
     * @param moveToFullscreen whether or the target should reparent itself to the fullscreen stack
     * when the animation completes
     */
    void onAnimationEnd(boolean schedulePipModeChangedCallback, Rect finalStackSize,
            boolean moveToFullscreen);
}
