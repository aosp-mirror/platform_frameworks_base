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
import android.graphics.GraphicBuffer;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.window.PictureInPictureSurfaceTransaction;
import android.window.TaskSnapshot;
import android.window.WindowAnimationState;

import com.android.internal.os.IResultReceiver;

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
    TaskSnapshot screenshotTask(int taskId);

    /**
     * Sets the final surface transaction on a Task. This is used by Launcher to notify the system
     * that animating Activity to PiP has completed and the associated task surface should be
     * updated accordingly. This should be called before `finish`
     * @param taskId for which the leash should be updated
     * @param finishTransaction leash operations for the final transform.
     * @param overlay the surface control for an overlay being shown above the pip (can be null)
     */
     void setFinishTaskTransaction(int taskId,
             in PictureInPictureSurfaceTransaction finishTransaction, in SurfaceControl overlay);

    /**
     * Notifies to the system that the animation into Recents should end, and all leashes associated
     * with remote animation targets should be relinquished. If {@param moveHomeToTop} is true, then
     * the home activity should be moved to the top. Otherwise, the home activity is hidden and the
     * user is returned to the app.
     * @param sendUserLeaveHint If set to true, {@link Activity#onUserLeaving} will be sent to the
     *                          top resumed app, false otherwise.
     */
    @UnsupportedAppUsage
    void finish(boolean moveHomeToTop, boolean sendUserLeaveHint, in IResultReceiver finishCb);

    /**
     * Called by the handler to indicate that the recents animation input consumer should be
     * enabled. This is currently used to work around an issue where registering an input consumer
     * mid-animation causes the existing motion event chain to be canceled. Instead, the caller
     * may register the recents animation input consumer prior to starting the recents animation
     * and then enable it mid-animation to start receiving touch events.
     */
    @UnsupportedAppUsage
    void setInputConsumerEnabled(boolean enabled);

    /**
    * Informs the system whether the animation targets passed into
    * IRecentsAnimationRunner.onAnimationStart are currently behind the system bars. If they are,
    * they can control the SystemUI flags, otherwise the SystemUI flags from home activity will be
    * taken.
    */
    @UnsupportedAppUsage
    void setAnimationTargetsBehindSystemBars(boolean behindSystemBars);

    /**
     * Clean up the screenshot of previous task which was created during recents animation that
     * was cancelled by a stack order change.
     *
     * @see {@link IRecentsAnimationRunner#onAnimationCanceled}
     */
    void cleanupScreenshot();

    /**
     * Set a state for controller whether would like to cancel recents animations with deferred
     * task screenshot presentation.
     *
     * When we cancel the recents animation due to a stack order change, we can't just cancel it
     * immediately as it would lead to a flicker in Launcher if we just remove the task from the
     * leash. Instead we screenshot the previous task and replace the child of the leash with the
     * screenshot, so that Launcher can still control the leash lifecycle & make the next app
     * transition animate smoothly without flickering.
     *
     * @param defer When set {@code true}, means that the recents animation will defer canceling the
     *              animation when a stack order change is triggered until the subsequent app
     *              transition start and skip previous task's animation.
     *              When set to {@code false}, means that the recents animation will be canceled
     *              immediately when the stack order changes.
     * @param screenshot When set {@code true}, means that the system will take previous task's
     *                   screenshot and replace the contents of the leash with it when the next app
     *                   transition starting. The runner must call #cleanupScreenshot() to end the
     *                   recents animation.
     *                   When set to {@code false}, means that the system will simply wait for the
     *                   next app transition start to immediately cancel the recents animation. This
     *                   can be useful when you want an immediate transition into a state where the
     *                   task is shown in the home/recents activity (without waiting for a
     *                   screenshot).
     *
     * @see #cleanupScreenshot()
     * @see IRecentsAnimationRunner#onCancelled
     */
    void setDeferCancelUntilNextTransition(boolean defer, boolean screenshot);

    /**
     * Sets a state for controller to decide which surface is the destination when the recents
     * animation is cancelled through fail safe mechanism.
     */
    void setWillFinishToHome(boolean willFinishToHome);

    /**
     * Stops controlling a task that is currently controlled by this recents animation.
     *
     * This method should be called when a task that has been received via {@link #onAnimationStart}
     * or {@link #onTaskAppeared} is no longer needed.  After calling this method, the task will
     * either disappear from the screen, or jump to its final position in case it was the top task.
     *
     * @param taskId Id of the Task target to remove
     * @return {@code true} when target removed successfully, {@code false} otherwise.
     */
    boolean removeTask(int taskId);

    /**
     * Detach navigation bar from app.
     *
     * The system reparents the leash of navigation bar to the app when the recents animation starts
     * and Launcher should call this method to let system restore the navigation bar to its
     * original position when the quick switch gesture is finished and will run the fade-in
     * animation If {@param moveHomeToTop} is {@code true}. Otherwise, restore the navigtation bar
     * without animation.
     *
     * @param moveHomeToTop if {@code true}, the home activity should be moved to the top.
     *                      Otherwise, the home activity is hidden and the user is returned to the
     *                      app.
     */
    void detachNavigationBarFromApp(boolean moveHomeToTop);

    /**
     * Used for animating the navigation bar during app launch from recents in live tile mode.
     *
     * First fade out the navigation bar at the bottom of the display and then fade in to the app.
     *
     * @param duration the duration of the app launch animation
     */
    void animateNavigationBarToApp(long duration);

    /**
     * Hand off the ongoing animation of a set of remote targets, to be run by another handler using
     * the given starting parameters.
     *
     * Once the handoff is complete, operations on the old leashes for the given targets as well as
     * callbacks will become no-ops.
     *
     * The number of targets MUST match the number of states, and each state MUST match the target
     * at the same index.
     */
    oneway void handOffAnimation(in RemoteAnimationTarget[] targets,
                    in WindowAnimationState[] states);
}
