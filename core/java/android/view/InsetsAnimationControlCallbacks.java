/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowInsetsAnimation.Bounds;

/**
 * Provide an interface to let InsetsAnimationControlImpl and InsetsResizeAnimationRunner call back
 * into its owner.
 * @hide
 */
public interface InsetsAnimationControlCallbacks {

    /**
     * Executes the necessary code to start the animation in the correct order, including:
     * <ul>
     *     <li>Dispatch {@link WindowInsetsAnimation.Callback#onPrepare}</li>
     *     <li>Update insets state and run layout according to {@code layoutDuringAnimation}</li>
     *     <li>Dispatch {@link WindowInsetsAnimation.Callback#onStart}</li>
     *     <li>Dispatch {@link WindowInsetsAnimationControlListener#onReady}</li>
     * </ul>
     */
    <T extends InsetsAnimationControlRunner & InternalInsetsAnimationController>
    void startAnimation(T runner, WindowInsetsAnimationControlListener listener, int types,
            WindowInsetsAnimation animation, Bounds bounds);

    /**
     * Schedule the apply by posting the animation callback.
     *
     * @param runner The runner that requested applying insets
     */
    void scheduleApplyChangeInsets(InsetsAnimationControlRunner runner);

    /**
     * Finish the final steps after the animation.
     * @param runner The runner used to run the animation.
     * @param shown {@code true} if the insets are shown.
     */
    void notifyFinished(InsetsAnimationControlRunner runner, boolean shown);

    /**
     * Post a message to release the Surface, guaranteed to happen after all
     * previous calls to applySurfaceParams.
     */
    void releaseSurfaceControlFromRt(SurfaceControl sc);

    /**
     * Reports that the perceptibility of the given types has changed to the given value.
     *
     * A type is perceptible if it is not (almost) entirely off-screen and not (almost) entirely
     * transparent.
     *
     * @param types the (public) types whose perceptibility has changed
     * @param perceptible true, if the types are now perceptible, false if they are not perceptible
     */
    void reportPerceptible(@InsetsType int types, boolean perceptible);
}
