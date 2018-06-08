/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.app.ActivityManager;
import android.view.IRemoteAnimationFinishedCallback;
import android.graphics.GraphicBuffer;

/**
 * Passed to the {@link IRecentsAnimationRunner} in order for the runner to control to let the
 * runner control certain aspects of the recents animation, and to notify window manager when the
 * animation has completed.
 *
 * {@hide}
 */
interface IRecentsAnimationController {

    /**
     * Takes a screenshot of the task associated with the given {@param taskId}. Only valid for the
     * current set of task ids provided to the handler.
     */
    ActivityManager.TaskSnapshot screenshotTask(int taskId);

    /**
     * Notifies to the system that the animation into Recents should end, and all leashes associated
     * with remote animation targets should be relinquished. If {@param moveHomeToTop} is true, then
     * the home activity should be moved to the top. Otherwise, the home activity is hidden and the
     * user is returned to the app.
     */
    void finish(boolean moveHomeToTop);

    /**
     * Called by the handler to indicate that the recents animation input consumer should be
     * enabled. This is currently used to work around an issue where registering an input consumer
     * mid-animation causes the existing motion event chain to be canceled. Instead, the caller
     * may register the recents animation input consumer prior to starting the recents animation
     * and then enable it mid-animation to start receiving touch events.
     */
    void setInputConsumerEnabled(boolean enabled);

    /**
    * Informs the system whether the animation targets passed into
    * IRecentsAnimationRunner.onAnimationStart are currently behind the system bars. If they are,
    * they can control the SystemUI flags, otherwise the SystemUI flags from home activity will be
    * taken.
    */
    void setAnimationTargetsBehindSystemBars(boolean behindSystemBars);

    /**
     * Informs the system that the primary split-screen stack should be minimized.
     */
    void setSplitScreenMinimized(boolean minimized);

    /**
     * Hides the current input method if one is showing.
     */
    void hideCurrentInputMethod();
}
