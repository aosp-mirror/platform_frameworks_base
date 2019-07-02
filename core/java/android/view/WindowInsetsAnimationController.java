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

import android.annotation.NonNull;
import android.graphics.Insets;
import android.view.WindowInsets.Type.InsetType;
import android.view.WindowInsetsAnimationListener.InsetsAnimation;

/**
 * Interface to control a window inset animation frame-by-frame.
 * @hide pending unhide
 */
public interface WindowInsetsAnimationController {

    /**
     * Retrieves the {@link Insets} when the windows this animation is controlling are fully hidden.
     * <p>
     * If there are any animation listeners registered, this value is the same as
     * {@link InsetsAnimation#getLowerBound()} that will be passed into the callbacks.
     *
     * @return Insets when the windows this animation is controlling are fully hidden.
     *
     * @see InsetsAnimation#getLowerBound()
     */
    @NonNull Insets getHiddenStateInsets();

    /**
     * Retrieves the {@link Insets} when the windows this animation is controlling are fully shown.
     * <p>
     * In case the size of a window causing insets is changing in the middle of the animation, we
     * execute that height change after this animation has finished.
     * <p>
     * If there are any animation listeners registered, this value is the same as
     * {@link InsetsAnimation#getUpperBound()} that will be passed into the callbacks.
     *
     * @return Insets when the windows this animation is controlling are fully shown.
     *
     * @see InsetsAnimation#getUpperBound()
     */
    @NonNull Insets getShownStateInsets();

    /**
     * @return The current insets on the window. These will follow any animation changes.
     */
    @NonNull Insets getCurrentInsets();

    /**
     * @return The {@link InsetType}s this object is currently controlling.
     */
    @InsetType int getTypes();

    /**
     * Modifies the insets by indirectly moving the windows around in the system that are causing
     * window insets.
     * <p>
     * Note that this will <b>not</b> inform the view system of a full inset change via
     * {@link View#dispatchApplyWindowInsets} in order to avoid a full layout pass during the
     * animation. If you'd like to animate views during a window inset animation, register a
     * {@link WindowInsetsAnimationListener} by calling
     * {@link View#setWindowInsetsAnimationListener(WindowInsetsAnimationListener)} that will be
     * notified about any insets change via {@link WindowInsetsAnimationListener#onProgress} during
     * the animation.
     * <p>
     * {@link View#dispatchApplyWindowInsets} will instead be called once the animation has
     * finished, i.e. once {@link #finish} has been called.
     *
     * @param insets The new insets to apply. Based on the requested insets, the system will
     *               calculate the positions of the windows in the system causing insets such that
     *               the resulting insets of that configuration will match the passed in parameter.
     *               Note that these insets are being clamped to the range from
     *               {@link #getHiddenStateInsets} to {@link #getShownStateInsets}
     *
     * @see WindowInsetsAnimationListener
     * @see View#setWindowInsetsAnimationListener(WindowInsetsAnimationListener)
     */
    void changeInsets(@NonNull Insets insets);

    /**
     * @param shownTypes The list of windows causing insets that should remain shown after finishing
     *                   the animation.
     */
    void finish(@InsetType int shownTypes);
}
