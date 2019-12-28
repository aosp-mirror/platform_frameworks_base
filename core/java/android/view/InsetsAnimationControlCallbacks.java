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

/**
 * Provide an interface to let InsetsAnimationControlImpl call back into its owner.
 * @hide
 */
public interface InsetsAnimationControlCallbacks {
    /**
     * Dispatch the animation started event to all listeners.
     * @param animation
     */
    void dispatchAnimationStarted(WindowInsetsAnimationCallback.InsetsAnimation animation,
            WindowInsetsAnimationCallback.AnimationBounds bounds);

    /**
     * Schedule the apply by posting the animation callback.
     */
    void scheduleApplyChangeInsets();

    /**
     * Finish the final steps after the animation.
     * @param controller The controller used to control the animation.
     * @param shown {@code true} if the insets are shown.
     */
    void notifyFinished(InsetsAnimationControlImpl controller, boolean shown);

    /**
     * Get the description of the insets state.
     * @return {@link InsetsState} for adjusting corresponding {@link InsetsSource}.
     */
    InsetsState getState();

    /**
     * Apply the new params to the surface.
     * @param params The {@link android.view.SyncRtSurfaceTransactionApplier.SurfaceParams} to
     *               apply.
     */
    void applySurfaceParams(SyncRtSurfaceTransactionApplier.SurfaceParams... params);
}
